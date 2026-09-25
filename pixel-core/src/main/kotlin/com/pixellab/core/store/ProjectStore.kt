package com.pixellab.core.store

import com.pixellab.core.export.PngCodec
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.project.ProjectCodec
import java.io.File
import java.io.IOException

/**
 * File-based persistence for [SpriteProject]s — pure JVM, `java.io` only.
 *
 * ## Path layout
 *
 * ```
 * <root>/
 *   .probe-<nano>              (transient write-access probe, deleted at init)
 *   projects/
 *     <project-id>/
 *       <name>.pixellab.json            the ProjectCodec (version 2) document
 *       <name>.pixellab.json.tmp        transient atomic-write staging file
 *       <name>.pixellab.json.meta.json  sidecar metadata (schema 1)
 *       <name>.pixellab.json.thumb.png  64px PNG preview (best effort)
 * ```
 *
 * The project **id** names the directory; the project **name** (sanitized
 * to `[A-Za-z0-9._-]`) names the files inside. Saving under a new name
 * removes the previous `*.pixellab.json` (and its sidecars) so exactly one
 * document per directory remains — [list] relies on that invariant.
 *
 * ## Atomicity
 *
 * Every file write is *atomic-by-rename*: bytes go to `<file>.tmp`, then
 * the target is deleted (rename cannot overwrite on all platforms) and the
 * staging file renamed into place. A crash mid-write therefore leaves the
 * previous document intact and at most a stale `.tmp` file behind —
 * never a truncated `.json`. The three artifacts (document, meta, thumb)
 * are written in that order; a crash between them is resolved by
 * [migrateAll]/[list] meta fallbacks (the document is always the source of
 * truth, the sidecars are accelerators).
 *
 * ## Cache
 *
 * [load] keeps the [maxCache] most recently *used* projects in an
 * access-ordered [LinkedHashMap] guarded by an internal lock (all public
 * methods synchronize on the same lock, making the store safe for
 * concurrent hosts). [save] refreshes the cache entry so a
 * save-then-load round trip never touches the disk.
 *
 * ## Thread-safety
 *
 * Compound operations (LRU eviction, list+decode, delete) run under the
 * single internal lock; file writes are atomic renames, so even a
 * concurrent reader worst case decodes a complete old or new document.
 */
class ProjectStore(private val root: File, private val maxCache: Int = 16) {

    /** One directory entry of `root/projects`. */
    data class ProjectSummary(
        /** Project id (the directory name). */
        val id: String,
        /** Project name (from meta, else the file base name). */
        val name: String,
        /** The `.pixellab.json` document. */
        val file: File,
        /** Canvas width in pixels (meta, else 0). */
        val width: Int,
        /** Canvas height in pixels (meta, else 0). */
        val height: Int,
        /** Frame count (meta, else 0). */
        val frameCount: Int,
        /** Layer count (meta, else 0). */
        val layerCount: Int,
        /** Save timestamp in ms (meta, else the file's mtime). */
        val savedAtMs: Long,
        /** Whether the `.thumb.png` sidecar exists. */
        val hasThumbnail: Boolean,
    )

    init {
        require(maxCache in 1..1024) { "maxCache must be in [1, 1024] (was $maxCache)" }
        if (!root.exists() && !root.mkdirs()) {
            throw IllegalArgumentException("cannot create project store root ${root.absolutePath}")
        }
        require(root.isDirectory) { "project store root ${root.absolutePath} is not a directory" }
        // Write probe: fail fast when the root is not writable (read-only
        // volume, permissions, full disk).
        val probe = File(root, ".probe-${System.nanoTime()}")
        try {
            probe.writeText("ok")
            probe.delete()
        } catch (error: IOException) {
            throw IllegalArgumentException(
                "project store root ${root.absolutePath} is not writable: ${error.message}",
            )
        }
    }

    /** Serializes every public operation (LRU + disk compound steps). */
    private val lock = Any()

    /** Access-ordered LRU: `true` = access-order mode. */
    private val cache = object : LinkedHashMap<String, SpriteProject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SpriteProject>?): Boolean {
            return size > maxCache
        }
    }

    /** Number of project documents decoded from disk (diagnostics/tests). */
    private var diskReads: Int = 0

    /** Number of cache entries currently held (diagnostics/tests). */
    fun cacheSize(): Int = synchronized(lock) { cache.size }

    /** Number of documents decoded from disk since construction (diagnostics/tests). */
    fun diskReadCount(): Int = synchronized(lock) { diskReads }

    /** `root/projects`, created on demand. */
    private fun projectsDir(): File {
        val dir = File(root, "projects")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** The directory of project [id]. */
    private fun projectDir(id: String): File = File(projectsDir(), sanitize(id, "project"))

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * Persists [project] and returns the document file.
     *
     * Writes the three artifacts atomically (see the class KDoc):
     *
     *  1. `<dir>/<name>.pixellab.json` — `ProjectCodec.save` text (UTF-8);
     *  2. `<file>.meta.json` — schema-1 sidecar (dims, counts, palette,
     *     `savedAtMs`) so [list] never has to decode documents;
     *  3. `<file>.thumb.png` — 64px preview of the composited active frame
     *     (best effort: fully transparent or failing renders are skipped).
     *
     * A previous document under a different name in the same directory is
     * removed. The in-memory cache is refreshed.
     *
     * @throws IllegalArgumentException when [project] is not persistable
     *   (name/id sanitize to nothing) — codec validation failures
     *   propagate unchanged.
     * @throws IOException when the file system rejects a write.
     */
    fun save(project: SpriteProject): File {
        val text = ProjectCodec.save(project)
        synchronized(lock) {
            val dir = projectDir(project.id)
            if (!dir.exists() && !dir.mkdirs()) {
                throw IOException("cannot create project directory ${dir.absolutePath}")
            }
            val document = File(dir, "${sanitize(project.name, "project")}.pixellab.json")

            // 1. the document (atomic rename).
            writeAtomic(document, text.toByteArray(Charsets.UTF_8))

            // 2. the metadata sidecar (atomic rename).
            val meta = metaText(project, System.currentTimeMillis())
            writeAtomic(File(document.path + META_SUFFIX), meta.toByteArray(Charsets.UTF_8))

            // 3. the thumbnail (best effort).
            try {
                thumbnailOf(project)?.let { png ->
                    writeAtomic(File(document.path + THUMB_SUFFIX), png)
                }
            } catch (error: Exception) {
                // Optional by contract: a broken thumbnail never fails the save.
            }

            // Exactly one document per directory: drop renamed-away siblings.
            for (other in dir.listFiles { f -> f.name.endsWith(DOC_SUFFIX) } ?: emptyArray()) {
                if (other != document) {
                    other.delete()
                    File(other.path + META_SUFFIX).delete()
                    File(other.path + THUMB_SUFFIX).delete()
                }
            }

            cache.put(project.id, project)
            return document
        }
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    /**
     * Scans every `projects/<id>` directory's `.pixellab.json` document and
     * summarizes each project.
     *
     * Metadata comes from the `.meta.json` sidecar; missing or unreadable
     * sidecars fall back to defaults (name from the file base name, zero
     * dims/counts, the document's mtime as `savedAtMs`) — documents are
     * never decoded here.
     */
    fun list(): List<ProjectSummary> = synchronized(lock) {
        val out = ArrayList<ProjectSummary>()
        val base = File(root, "projects")
        val dirs = base.listFiles { f -> f.isDirectory } ?: return emptyList()
        for (dir in dirs.sortedBy { it.name }) {
            val docs = dir.listFiles { f -> f.name.endsWith(DOC_SUFFIX) } ?: continue
            val document = docs.firstOrNull() ?: continue
            out.add(summaryOf(dir.name, document))
        }
        return out
    }

    /** Builds one summary from the sidecar, with documented fallbacks. */
    private fun summaryOf(id: String, document: File): ProjectSummary {
        val metaFile = File(document.path + META_SUFFIX)
        val meta = if (metaFile.isFile) parseMeta(metaFile) else emptyMap()
        return ProjectSummary(
            id = id,
            name = meta["name"] as? String ?: document.name.removeSuffix(DOC_SUFFIX),
            file = document,
            width = (meta["width"] as? Number)?.toInt() ?: 0,
            height = (meta["height"] as? Number)?.toInt() ?: 0,
            frameCount = (meta["frameCount"] as? Number)?.toInt() ?: 0,
            layerCount = (meta["layerCount"] as? Number)?.toInt() ?: 0,
            savedAtMs = (meta["savedAtMs"] as? Number)?.toLong() ?: document.lastModified(),
            hasThumbnail = File(document.path + THUMB_SUFFIX).isFile,
        )
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Loads the project with id [id] (the directory name under
     * `projects/`).
     *
     * Cache hits never touch the disk; misses decode the document with
     * [ProjectCodec.load] and insert it as the newest cache entry (evicting
     * the least recently used one when the cap would be exceeded).
     *
     * @throws IllegalArgumentException when no project with that id exists
     *   or the document fails codec validation.
     */
    fun load(id: String): SpriteProject {
        val key = sanitize(id, "project")
        synchronized(lock) {
            cache.get(key)?.let { return it }
            val document = projectDir(key).listFiles { f -> f.name.endsWith(DOC_SUFFIX) }?.firstOrNull()
                ?: throw IllegalArgumentException("no project '$id' in ${root.absolutePath} (see list())")
            diskReads += 1
            val project = ProjectCodec.load(document.readText(Charsets.UTF_8))
            cache.put(key, project)
            return project
        }
    }

    // ------------------------------------------------------------------
    // Delete / thumbnail
    // ------------------------------------------------------------------

    /**
     * Deletes the whole project directory (document + sidecars) and drops
     * the cache entry.
     *
     * @return true when the directory existed and was removed.
     */
    fun delete(id: String): Boolean {
        val key = sanitize(id, "project")
        synchronized(lock) {
            cache.remove(key)
            val dir = projectDir(key)
            if (!dir.exists()) return false
            return deleteRecursively(dir)
        }
    }

    /** Raw `.thumb.png` bytes of [id], or null when absent. */
    fun thumbnail(id: String): ByteArray? {
        val key = sanitize(id, "project")
        synchronized(lock) {
            val document = projectDir(key).listFiles { f -> f.name.endsWith(DOC_SUFFIX) }?.firstOrNull()
                ?: return null
            val thumb = File(document.path + THUMB_SUFFIX)
            return if (thumb.isFile) thumb.readBytes() else null
        }
    }

    // ------------------------------------------------------------------
    // Migration
    // ------------------------------------------------------------------

    /**
     * Forward-migration hook: walks every stored project and normalizes it
     * to the current **schema version 1** sidecar.
     *
     * Today this is a normalizing no-op pass: for each document it
     * (re)writes the meta sidecar when it is missing, unreadable or not
     * `schema: 1`, and decodes nothing else. Future schema bumps extend
     * this method — the contract is "after `migrateAll()`, every stored
     * project carries a current-version sidecar".
     *
     * Documents that fail to decode are skipped (left untouched), because
     * migration must never destroy data; hosts can surface them via
     * [list] + [load] error paths.
     *
     * @return the number of projects whose sidecar was rewritten.
     */
    fun migrateAll(): Int = synchronized(lock) {
        var rewritten = 0
        val base = File(root, "projects")
        val dirs = base.listFiles { f -> f.isDirectory } ?: return 0
        for (dir in dirs) {
            val document = dir.listFiles { f -> f.name.endsWith(DOC_SUFFIX) }?.firstOrNull() ?: continue
            val metaFile = File(document.path + META_SUFFIX)
            val meta = if (metaFile.isFile) parseMeta(metaFile) else emptyMap()
            val version = (meta["schema"] as? Number)?.toInt()
            if (version == SCHEMA_VERSION) continue
            val project = try {
                ProjectCodec.load(document.readText(Charsets.UTF_8))
            } catch (error: Exception) {
                continue // undecodable documents are left untouched
            }
            writeAtomic(metaFile, metaText(project, document.lastModified()).toByteArray(Charsets.UTF_8))
            rewritten += 1
        }
        return rewritten
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Atomic write: staging file + rename (see the class KDoc). */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        val staging = File(target.path + ".tmp")
        staging.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            staging.delete()
            throw IOException("cannot replace ${target.absolutePath}")
        }
        if (!staging.renameTo(target)) {
            staging.delete()
            throw IOException("cannot promote ${staging.absolutePath} to ${target.absolutePath}")
        }
    }

    /** The 64px thumbnail PNG of [project]'s composited active frame, or null. */
    private fun thumbnailOf(project: SpriteProject): ByteArray? {
        val frame = project.compositeActiveFrame()
        if (frame.pixels.all { it ushr 24 == 0 }) return null
        val thumb = fitWithin(frame, THUMB_SIZE)
        return PngCodec.encode(thumb)
    }

    /** Nearest downscale so the longest edge fits [maxSize] (no upscale). */
    private fun fitWithin(frame: PixelFrame, maxSize: Int): PixelFrame {
        val longest = maxOf(frame.width, frame.height)
        if (longest <= maxSize) return frame
        val scale = longest.toDouble() / maxSize
        val w = maxOf(1, (frame.width / scale).toInt())
        val h = maxOf(1, (frame.height / scale).toInt())
        return frame.transformed(w, h) { x, y ->
            frame[minOf(frame.width - 1, x * frame.width / w), minOf(frame.height - 1, y * frame.height / h)]
        }
    }

    /** Schema-1 sidecar text for [project]. */
    private fun metaText(project: SpriteProject, savedAtMs: Long): String = buildString {
        append("{\"schema\":").append(SCHEMA_VERSION)
        append(",\"projectId\":").append(jsonString(project.id))
        append(",\"name\":").append(jsonString(project.name))
        append(",\"width\":").append(project.width)
        append(",\"height\":").append(project.height)
        append(",\"frameCount\":").append(project.frameCount)
        append(",\"layerCount\":").append(project.layerCount)
        append(",\"paletteCount\":").append(project.palette.colors.size)
        append(",\"savedAtMs\":").append(savedAtMs)
        append("}")
    }

    /**
     * Tolerant flat-object parser for self-produced sidecars: recognizes
     * string and number values, ignores nesting and unknown keys. Every
     * anomaly (missing quotes, truncated file) yields a partial map — the
     * documented fallback behavior, not an error.
     */
    private fun parseMeta(file: File): Map<String, Any> {
        val out = HashMap<String, Any>()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (error: IOException) {
            return out
        }
        var i = 0
        while (i < text.length) {
            val keyStart = text.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = text.indexOf('"', keyStart + 1)
            if (keyEnd < 0) break
            val colon = text.indexOf(':', keyEnd + 1)
            if (colon < 0) break
            val key = text.substring(keyStart + 1, keyEnd)
            var j = colon + 1
            while (j < text.length && text[j].isWhitespace()) j += 1
            when {
                j < text.length && text[j] == '"' -> {
                    val valueEnd = text.indexOf('"', j + 1)
                    if (valueEnd < 0) break
                    out[key] = text.substring(j + 1, valueEnd)
                    i = valueEnd + 1
                }

                j < text.length && (text[j] == '-' || text[j].isDigit()) -> {
                    var k = j + 1
                    while (k < text.length && (text[k].isDigit() || text[k] == '.' || text[k] == 'e' || text[k] == 'E')) {
                        k += 1
                    }
                    val raw = text.substring(j, k)
                    out[key] = raw.toLongOrNull() ?: raw.toDoubleOrNull() ?: raw
                    i = k
                }

                else -> {
                    // true/false/null/objects/arrays: skip to the next comma.
                    val comma = text.indexOf(',', j)
                    i = if (comma < 0) text.length else comma
                }
            }
        }
        return out
    }

    /** JSON string literal with the minimal escape set. */
    private fun jsonString(value: String): String {
        val escaped = StringBuilder(value.length + 2)
        escaped.append('"')
        for (c in value) {
            when {
                c == '"' -> escaped.append("\\\"")
                c == '\\' -> escaped.append("\\\\")
                c == '\n' -> escaped.append("\\n")
                c == '\r' -> escaped.append("\\r")
                c == '\t' -> escaped.append("\\t")
                c < ' ' -> escaped.append("\\u").append("%04x".format(c.code))
                else -> escaped.append(c)
            }
        }
        escaped.append('"')
        return escaped.toString()
    }

    /** Keeps `[A-Za-z0-9._-]`, never empty. */
    private fun sanitize(raw: String, fallback: String): String {
        val cleaned = raw.map { c ->
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == '-') c else '_'
        }.joinToString("").trim('.')
        return cleaned.ifBlank { fallback }
    }

    /** Depth-first delete (java.io has no recursive delete). */
    private fun deleteRecursively(dir: File): Boolean {
        var ok = true
        for (child in dir.listFiles() ?: emptyArray()) {
            if (child.isDirectory) ok = deleteRecursively(child) && ok else ok = child.delete() && ok
        }
        return dir.delete() && ok
    }

    private companion object {
        /** Document file suffix. */
        private const val DOC_SUFFIX: String = ".pixellab.json"

        /** Metadata sidecar suffix (appended to the document path). */
        private const val META_SUFFIX: String = ".meta.json"

        /** Thumbnail sidecar suffix (appended to the document path). */
        private const val THUMB_SUFFIX: String = ".thumb.png"

        /** Longest thumbnail edge in pixels. */
        private const val THUMB_SIZE: Int = 64

        /** Current sidecar schema version. */
        private const val SCHEMA_VERSION: Int = 1
    }
}
