package com.pixellab.app

import com.pixellab.core.PixelLab
import com.pixellab.core.model.SpriteProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Upscale factor of the exported PNG sprite. */
private const val PngExportScale = 8

/** Single Codex track exported by the sample app, spanning the whole timeline. */
private const val CodexIdleTrack = "idle"

/**
 * The four export formats offered by the sample app's export menu.
 *
 * @property label human-readable text of the menu entry.
 * @property ext extension of the written file name.
 */
internal enum class ExportKind(val label: String, val ext: String) {
    /** Single PNG sprite of the active frame, composited from all layers. */
    PNG("PNG", "png"),

    /** Animated GIF89a of the whole timeline. */
    GIF("GIF", "gif"),

    /** Animated APNG of the whole timeline. */
    APNG("APNG", "apng"),

    /** Codex companion-pet ZIP: `spritesheet.png` plus `pet.json`. */
    CODEX_ZIP("Codex ZIP", "zip"),
}

/**
 * One finished export.
 *
 * @property file the file written into the app's external files directory.
 * @property byteCount number of bytes written to [file].
 */
internal data class ExportResult(
    val file: File,
    val byteCount: Long,
)

/** File naming and byte-level writing for exports. */
internal object ExportIO {

    /**
     * Builds the export file name for [kind] at timestamp [ts]:
     * `pixel-lab-export-{ts}.{ext}`.
     */
    fun fileName(kind: ExportKind, ts: Long): String = "pixel-lab-export-$ts.${kind.ext}"

    /**
     * Writes [bytes] to [dir]/[name], creating [dir] when it does not exist
     * yet, and returns the written file.
     */
    fun writeBytes(dir: File, name: String, bytes: ByteArray): File {
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, name)
        file.writeBytes(bytes)
        return file
    }
}

/**
 * Encodes [project] through [lab]'s exporter in the format selected by
 * [kind] and writes the bytes into [dir], all on [Dispatchers.IO].
 *
 * PNG composites the project's active frame and upscales it 8x; GIF and
 * APNG animate every frame; the Codex ZIP packs a pet named after the
 * project with a single `"idle"` track covering the entire timeline.
 *
 * @param kind export format to run.
 * @param lab library instance providing the exporter.
 * @param project project to export.
 * @param dir destination directory (the app's external files dir).
 * @return the written file and its byte count.
 * @throws IllegalStateException when the exporter reports a failure; other
 *   argument problems surface as [IllegalArgumentException] from the
 *   exporter itself.
 */
internal suspend fun doExport(
    kind: ExportKind,
    lab: PixelLab,
    project: SpriteProject,
    dir: File,
): ExportResult = withContext(Dispatchers.IO) {
    val bytes = when (kind) {
        ExportKind.PNG -> lab.exporter.exportPng(project, scale = PngExportScale)
        ExportKind.GIF -> lab.exporter.exportGif(project)
        ExportKind.APNG -> lab.exporter.exportApng(project)
        ExportKind.CODEX_ZIP -> lab.exporter.exportCodexPet(
            petName = project.name,
            project = project,
            trackMap = mapOf(CodexIdleTrack to 0..(project.frameCount - 1)),
        )
    }.getOrThrow()
    val file = ExportIO.writeBytes(dir, ExportIO.fileName(kind, System.currentTimeMillis()), bytes)
    ExportResult(file = file, byteCount = bytes.size.toLong())
}
