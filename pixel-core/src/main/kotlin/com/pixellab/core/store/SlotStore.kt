package com.pixellab.core.store

import com.pixellab.core.model.SpriteProject
import java.io.File
import java.io.IOException

/**
 * Named **session slots**: persistent, human-named pointers to projects
 * saved through [ProjectStore].
 *
 * `ProjectStore` addresses projects by opaque project id — perfect for the
 * local gallery, useless for an agent that wants to say "save this as
 * `knight-idle` and bring it back tomorrow". `SlotStore` adds that name
 * layer as a flat directory of one JSON file per slot:
 *
 * ```
 * <root>/slots/
 *   knight-idle.json        slot document (schema 1)
 *   knight-idle.json.tmp    transient atomic-write staging
 * ```
 *
 * The slot document points at the project by id; the pixels themselves
 * live in ProjectStore's own layout (which keeps its atomic-write,
 * thumbnail and cache guarantees). Saving a slot is therefore two writes
 * — project first (source of truth), slot second — so a crash between
 * them leaves a saved project with no slot pointing at it, never a slot
 * pointing at unsaved pixels.
 *
 * ## Semantics
 *
 *  * A slot name is an address, not a history: saving the same name again
 *    overwrites the slot (it may point at a different project id).
 *  * [list] tolerates corrupt or foreign files: unreadable slots are
 *    reported with `readable: false` instead of poisoning the listing.
 *  * [load] goes through [ProjectStore.load] and therefore benefits from
 *    its LRU cache — a save-then-load round trip never touches the disk
 *    for pixels twice.
 *
 * ## Thread-safety
 *
 * All public methods synchronize on one internal lock (the same
 * discipline as [ProjectStore]); every file write is atomic-by-rename.
 */
class SlotStore(private val root: File) {

    /** One slot entry as returned by [list]/[find]. */
    data class SlotSummary(
        /** Slot name (the address the agent uses). */
        val name: String,
        /** Project id the slot points at. */
        val projectId: String,
        /** Project display name at save time. */
        val projectName: String,
        /** Canvas width at save time. */
        val width: Int,
        /** Canvas height at save time. */
        val height: Int,
        /** Frame count at save time. */
        val frames: Int,
        /** Layer count at save time. */
        val layers: Int,
        /** Palette id at save time. */
        val paletteId: String,
        /** MCP session id that saved the slot (provenance). */
        val savedBySession: String,
        /** Wall-clock save time (ms). */
        val savedAtMs: Long,
        /** True when the slot document parsed cleanly. */
        val readable: Boolean,
    )

    private val lock = Any()

    /** Directory holding the slot documents (created lazily). */
    private fun slotsDir(): File = File(root, "slots")

    /**
     * Saves [project] under the slot [name], recording [sessionId] as
     * provenance. The project is written first (via [projects]), then the
     * slot document — the ordering guarantee above.
     *
     * @throws IllegalArgumentException when the name is blank or over
     *   [MAX_NAME_LENGTH] characters after sanitizing down to identity.
     * @throws IOException when either write fails.
     */
    fun save(name: String, project: SpriteProject, sessionId: String, projects: ProjectStore): SlotSummary {
        val clean = requireName(name)
        val summary = synchronized(lock) {
            projects.save(project)
            val document = buildString {
                append("{")
                append("\"schema\":1,")
                append("\"name\":").append(jsonString(clean)).append(',')
                append("\"project_id\":").append(jsonString(project.id)).append(',')
                append("\"project_name\":").append(jsonString(project.name)).append(',')
                append("\"width\":").append(project.width).append(',')
                append("\"height\":").append(project.height).append(',')
                append("\"frames\":").append(project.frameCount).append(',')
                append("\"layers\":").append(project.layerCount).append(',')
                append("\"palette_id\":").append(jsonString(project.palette.id)).append(',')
                append("\"saved_by_session\":").append(jsonString(sessionId)).append(',')
                append("\"saved_at_ms\":").append(System.currentTimeMillis())
                append("}")
            }
            writeAtomic(slotFile(clean), document.toByteArray(Charsets.UTF_8))
            SlotSummary(
                name = clean,
                projectId = project.id,
                projectName = project.name,
                width = project.width,
                height = project.height,
                frames = project.frameCount,
                layers = project.layerCount,
                paletteId = project.palette.id,
                savedBySession = sessionId,
                savedAtMs = System.currentTimeMillis(),
                readable = true,
            )
        }
        return summary
    }

    /**
     * The slot [name], or null when no such slot exists. Unreadable slot
     * documents surface as a summary with `readable = false` (name and
     * file provenance survive; the pointer does not).
     */
    fun find(name: String): SlotSummary? {
        val clean = requireName(name)
        return synchronized(lock) { readSlot(clean) }
    }

    /**
     * Lists every slot, newest save first. Corrupt documents are included
     * with `readable = false` so the agent can delete them by name.
     */
    fun list(): List<SlotSummary> = synchronized(lock) {
        val dir = slotsDir()
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?: return emptyList()
        files.mapNotNull { file ->
            val name = file.name.removeSuffix(".json")
            readSlot(name)
        }.sortedByDescending { it.savedAtMs }
    }

    /**
     * Loads the project a slot points at. Refuses unreadable or missing
     * slots with actionable errors, then delegates to [projects].
     *
     * @throws IllegalArgumentException when the slot is missing/unreadable
     *   or its project no longer exists on disk.
     */
    fun load(name: String, projects: ProjectStore): SpriteProject {
        val clean = requireName(name)
        val slot = synchronized(lock) { readSlot(clean) }
            ?: throw IllegalArgumentException("no saved session named '$clean' (see session_saved_list)")
        if (!slot.readable) {
            throw IllegalArgumentException(
                "slot '$clean' has a corrupt document; delete it (session_saved_delete) and re-save",
            )
        }
        return projects.load(slot.projectId)
    }

    /** Deletes the slot [name] (the pointed-at project is untouched);
     *  true when the slot existed. */
    fun delete(name: String): Boolean {
        val clean = requireName(name)
        return synchronized(lock) {
            val file = slotFile(clean)
            file.exists() && file.delete()
        }
    }

    // ---- internals ----------------------------------------------------------

    /** Slot document path for [name]. */
    private fun slotFile(name: String): File = File(slotsDir(), "$name.json")

    /**
     * Parses one slot document. Missing file → null. Malformed content →
     * a summary with `readable = false` carrying the name.
     */
    private fun readSlot(name: String): SlotSummary? {
        val file = slotFile(name)
        if (!file.exists()) return null
        val fallback = SlotSummary(
            name = name, projectId = "", projectName = "",
            width = 0, height = 0, frames = 0, layers = 0, paletteId = "",
            savedBySession = "", savedAtMs = file.lastModified(), readable = false,
        )
        return try {
            val text = file.readText()
            val fields = parseFlatJson(text)
            val schema = fields["schema"]?.toIntOrNull()
            if (schema != 1) return fallback
            SlotSummary(
                name = fields["name"] ?: name,
                projectId = fields["project_id"] ?: return fallback,
                projectName = fields["project_name"] ?: "",
                width = fields["width"]?.toIntOrNull() ?: 0,
                height = fields["height"]?.toIntOrNull() ?: 0,
                frames = fields["frames"]?.toIntOrNull() ?: 0,
                layers = fields["layers"]?.toIntOrNull() ?: 0,
                paletteId = fields["palette_id"] ?: "",
                savedBySession = fields["saved_by_session"] ?: "",
                savedAtMs = fields["saved_at_ms"]?.toLongOrNull() ?: file.lastModified(),
                readable = true,
            )
        } catch (error: Exception) {
            fallback
        }
    }

    /**
     * Flat-JSON parser for the slot document: string, integer and boolean
     * values only, exactly the grammar [save] writes. Tolerates arbitrary
     * whitespace; throws on malformed input (callers convert to the
     * unreadable-slot fallback).
     */
    private fun parseFlatJson(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var pos = 0
        fun skip() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }
        skip()
        if (pos >= text.length || text[pos] != '{') throw IOException("slot: expected '{'")
        pos++
        skip()
        if (pos < text.length && text[pos] == '}') return out
        while (true) {
            skip()
            if (pos >= text.length || text[pos] != '"') throw IOException("slot: expected key")
            val key = parseJsonString(text, pos).let { pos = it.second; it.first }
            skip()
            if (pos >= text.length || text[pos] != ':') throw IOException("slot: expected ':'")
            pos++
            skip()
            when {
                pos < text.length && text[pos] == '"' -> {
                    val parsed = parseJsonString(text, pos)
                    pos = parsed.second
                    out[key] = parsed.first
                }
                pos < text.length && (text[pos] == '-' || text[pos] in '0'..'9') -> {
                    val start = pos
                    if (text[pos] == '-') pos++
                    while (pos < text.length && text[pos] in '0'..'9') pos++
                    out[key] = text.substring(start, pos)
                }
                pos < text.length && text.startsWith("true", pos) -> {
                    out[key] = "true"; pos += 4
                }
                pos < text.length && text.startsWith("false", pos) -> {
                    out[key] = "false"; pos += 5
                }
                else -> throw IOException("slot: unexpected value at $pos")
            }
            skip()
            when {
                pos < text.length && text[pos] == ',' -> pos++
                pos < text.length && text[pos] == '}' -> return out
                else -> throw IOException("slot: expected ',' or '}'")
            }
        }
    }

    /** Parses one JSON string literal starting at [start]; returns value
     *  plus the position just past the closing quote. */
    private fun parseJsonString(text: String, start: Int): Pair<String, Int> {
        var pos = start + 1
        val out = StringBuilder()
        while (pos < text.length) {
            when (val c = text[pos]) {
                '"' -> return out.toString() to pos + 1
                '\\' -> {
                    pos++
                    if (pos >= text.length) throw IOException("slot: unterminated escape")
                    when (val e = text[pos]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (pos + 4 >= text.length) throw IOException("slot: truncated \\u")
                            val hex = text.substring(pos + 1, pos + 5)
                            val code = hex.toIntOrNull(16) ?: throw IOException("slot: bad \\u")
                            out.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IOException("slot: bad escape '\\$e'")
                    }
                    pos++
                }
                else -> {
                    if (c < ' ') throw IOException("slot: control character in string")
                    out.append(c)
                    pos++
                }
            }
        }
        throw IOException("slot: unterminated string")
    }

    /** Atomic write: staging file, then delete-target + rename. */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, target.name + ".tmp")
        staging.writeBytes(bytes)
        if (target.exists()) target.delete()
        if (!staging.renameTo(target)) {
            staging.delete()
            throw IOException("slot: could not move ${staging.name} into place")
        }
    }

    /** JSON string literal escaping (same rules as ProjectStore). */
    private fun jsonString(value: String): String {
        val out = StringBuilder(value.length + 2)
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (ch < ' ') {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
        return out.toString()
    }

    /**
     * Slot-name policy: non-blank, at most [MAX_NAME_LENGTH] characters,
     * restricted to `[A-Za-z0-9._-]` (the same alphabet ProjectStore
     * sanitizes file names to, so the slot name *is* the file stem).
     */
    private fun requireName(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "slot name must not be blank" }
        require(trimmed.length <= MAX_NAME_LENGTH) {
            "slot name longer than $MAX_NAME_LENGTH characters (was ${trimmed.length})"
        }
        require(trimmed.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '_' || it == '-' }) {
            "slot name '$trimmed' may only contain letters, digits, '.', '_' and '-'"
        }
        return trimmed
    }

    private companion object {
        /** Maximum slot-name length. */
        const val MAX_NAME_LENGTH: Int = 64
    }
}
