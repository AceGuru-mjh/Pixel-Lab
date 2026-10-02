package com.pixellab.mcp

import com.pixellab.core.PixelLab
import com.pixellab.core.export.PngCodec
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.store.ProjectStore
import com.pixellab.core.store.SlotStore
import com.pixellab.mcp.json.JsonBoolean
import com.pixellab.mcp.json.JsonNumber
import com.pixellab.mcp.json.JsonObject
import com.pixellab.mcp.json.JsonString
import com.pixellab.mcp.json.jsonarray
import com.pixellab.mcp.json.jsonobj
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end closed-loop tool tests (PR #52 round): every tool added or
 * rewired for the closed-loop architecture is driven through its real
 * registry against a live [PixelSessionStore], asserting on the *pixels*
 * that land in the session — not just the shape of the JSON reply.
 *
 * Covered loops:
 *  * **bytes-in** — io_import_image turns PNG bytes into an editable
 *    session whose pixels read back exactly.
 *  * **bytes-out** — export_png answers inline data_b64 that decodes to
 *    bytes io_import_image accepts (export -> import round trip).
 *  * **text-in** — draw_text stamps lit pixels onto the active cel.
 *  * **sketch loop** — canvas_read(format=sketch) output re-enters the
 *    session through sketch_draw at an offset.
 *  * **palette loop** — palette_set_colors applies a custom palette and
 *    palette_switch accepts PaletteLibrary ids (domain unification).
 *  * **target selection** — frame_set_active / layer_set_active steer
 *    where subsequent draws land.
 *  * **convert loop** — convert_image(session_id) commits converted pixels.
 *  * **persistence** — session_save then project_delete removes the project.
 *  * **contract** — checkpoint_rollback answers -32602 for unknown
 *    checkpoints; oversized session ids are rejected.
 */
class ClosedLoopToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lab = PixelLab.create()
    private val store = PixelSessionStore()
    private val v1 = McpToolRegistry(lab)
    private val v3 = McpToolRegistryV3(lab)
    private val v5 = McpToolRegistryV5(lab)
    private val v2 = McpToolRegistryV2(lab)
    private val v6 = McpToolRegistryV6(lab)

    private fun run(vararg pairs: Pair<String, Any>): JsonObject = jsonobj {
        for ((key, value) in pairs) {
            when (value) {
                is String -> put(key, JsonString(value))
                is Int -> put(key, JsonNumber(value.toDouble(), value.toString()))
                is Boolean -> put(key, JsonBoolean(value))
                is List<*> -> put(key, jsonarray {
                    for (item in value) {
                        when (item) {
                            is Int -> add(JsonNumber(item.toDouble(), item.toString()))
                            is String -> add(JsonString(item))
                            is Map<*, *> -> add(jsonobj {
                                for ((mk, mv) in item) {
                                    val mkString = mk as String
                                    when (mv) {
                                        is Int -> put(mkString, JsonNumber(mv.toDouble(), mv.toString()))
                                        is String -> put(mkString, JsonString(mv))
                                    }
                                }
                            })
                        }
                    }
                })
            }
        }
    }

    private fun tool(
        registry: suspend (String, JsonObject, PixelSessionStore) -> JsonObject,
        name: String,
        params: JsonObject,
    ): JsonObject = runBlocking { registry(name, params, store) }

    private fun v1Tool(name: String, params: JsonObject): JsonObject = tool({ n, p, s -> v1.execute(n, p, s) }, name, params)
    private fun v2Tool(name: String, params: JsonObject): JsonObject = tool({ n, p, s -> v2.execute(n, p, s) }, name, params)
    private fun v3Tool(name: String, params: JsonObject): JsonObject = tool({ n, p, s -> v3.execute(n, p, s) }, name, params)
    private fun v5Tool(name: String, params: JsonObject): JsonObject = tool({ n, p, s -> v5.execute(n, p, s) }, name, params)
    private fun v6Tool(name: String, params: JsonObject): JsonObject = tool({ n, p, s -> v6.execute(n, p, s) }, name, params)

    /** Non-transparent cell count of a frame's active-layer cel. */
    private fun litPixels(project: com.pixellab.core.model.SpriteProject, frameIndex: Int = project.activeFrameIndex): Int {
        val cel = project.frames[frameIndex].cels[project.activeLayerId] ?: return 0
        return cel.pixels.count { it ushr 24 != 0 }
    }

    // ---- bytes in -----------------------------------------------------------

    @Test
    fun `io_import_image turns png bytes into an editable session`() {
        val frame = PixelFrame.of(3, 2, intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(),
            0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        ))
        val png = PngCodec.encode(frame)
        val result = v3Tool("io_import_image", run("data_b64" to Base64.getEncoder().encodeToString(png)))
        assertEquals(3, (result.raw("width") as JsonNumber).value.toInt())
        assertEquals(2, (result.raw("height") as JsonNumber).value.toInt())
        assertEquals("png", (result.raw("format") as JsonString).value)
        val sessionId = (result.raw("session_id") as JsonString).value
        // The imported pixels must be editable: draw one and verify through
        // the read-only vision tool that the canvas holds BOTH the imported
        // content and the new stroke.
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 0, "y" to 0, "color" to "#FF000000"))
        val read = v5Tool("canvas_read", run(
            "session_id" to sessionId, "x" to 0, "y" to 0, "width" to 3, "height" to 2, "format" to "hex",
        ))
        val grid = (read.raw("content") as JsonString).value
        assertTrue("grid must contain the repainted corner: $grid", grid.contains("#000000"))
        assertTrue("grid must keep imported content: $grid", grid.contains("#00ff00"))
    }

    // ---- bytes out: export -> import round trip -----------------------------

    @Test
    fun `export_png answers data_b64 that reimports to the same size`() {
        val create = v1Tool("canvas_create", run("width" to 4, "height" to 4))
        val sessionId = (create.raw("session_id") as JsonString).value
        v1Tool("draw_pixels", run(
            "session_id" to sessionId,
            "points" to listOf(mapOf("x" to 1, "y" to 1), mapOf("x" to 2, "y" to 2)),
            "color" to "#FFA300",
        ))
        val export = v1Tool("export_png", run("session_id" to sessionId))
        val dataB64 = (export.raw("data_b64") as? JsonString)?.value
        assertNotNull("export_png must inline data_b64 for small frames", dataB64)
        val reimport = v3Tool("io_import_image", run("data_b64" to dataB64!!))
        assertEquals(4, (reimport.raw("width") as JsonNumber).value.toInt())
        assertEquals(4, (reimport.raw("height") as JsonNumber).value.toInt())
    }

    // ---- text in --------------------------------------------------------------

    @Test
    fun `draw_text stamps lit pixels into the active cel`() {
        val create = v1Tool("canvas_create", run("width" to 24, "height" to 16))
        val sessionId = (create.raw("session_id") as JsonString).value
        val result = v2Tool("draw_text", run(
            "session_id" to sessionId, "text" to "A", "x" to 2, "y" to 2, "color" to "#FFFF0000",
        ))
        val placed = (result.raw("placed_pixels") as JsonNumber).value.toInt()
        assertTrue("a 5x7 glyph must place some pixels (was $placed)", placed > 0)
        assertEquals(0, (result.raw("clipped_pixels") as JsonNumber).value.toInt())
        val project = store.get(sessionId, create = false)!!.project
        assertEquals(placed, litPixels(project))
    }

    // ---- sketch loop ----------------------------------------------------------

    @Test
    fun `sketch_draw redraws canvas_read sketch output at an offset`() {
        val create = v1Tool("canvas_create", run("width" to 12, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        v1Tool("draw_pixels", run(
            "session_id" to sessionId,
            "points" to listOf(
                mapOf("x" to 0, "y" to 0), mapOf("x" to 1, "y" to 0), mapOf("x" to 0, "y" to 1),
            ),
            "color" to "#FFFFFF00",
        ))
        val read = v5Tool("canvas_read", run(
            "session_id" to sessionId, "x" to 0, "y" to 0, "width" to 12, "height" to 8, "format" to "sketch",
        ))
        val sketch = (read.raw("content") as JsonString).value
        assertTrue("sketch output must carry a legend: $sketch", sketch.contains("=#"))
        val stamp = v6Tool("sketch_draw", run("session_id" to sessionId, "sketch" to sketch, "x" to 3, "y" to 2))
        val placed = (stamp.raw("placed") as JsonNumber).value.toInt()
        assertEquals("the 3 source pixels must all land", 3, placed)
        // (3,2), (4,2), (3,3) now hold yellow; the originals at (0,0) etc. too.
        val project = store.get(sessionId, create = false)!!.project
        val cel = project.frames[0].cels[project.activeLayerId]!!
        assertEquals(0xFFFFFF00.toInt(), cel.pixels[2 * 12 + 3])
        assertEquals(0xFFFFFF00.toInt(), cel.pixels[2 * 12 + 4])
        assertEquals(0xFFFFFF00.toInt(), cel.pixels[3 * 12 + 3])
    }

    // ---- palette loop ---------------------------------------------------------

    @Test
    fun `palette_set_colors applies a custom palette to the session`() {
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        val result = v1Tool("palette_set_colors", run(
            "session_id" to sessionId,
            "colors" to listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF123456.toInt()),
        ))
        val paletteCount = ((result.raw("palette") as JsonObject).raw("color_count") as JsonNumber).value.toInt()
        assertEquals(3, paletteCount)
        val project = store.get(sessionId, create = false)!!.project
        assertEquals(3, project.palette.colors.size)
        assertEquals(0xFF123456.toInt(), project.palette.colors[2])
    }

    @Test
    fun `palette_switch accepts palette library ids beyond the built-in six`() {
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        // 'sweetie-16' lives in the PaletteLibrary (21 palettes), not the
        // built-in six — pre-fix this call answered -32602.
        val switched = v1Tool("palette_switch", run("session_id" to sessionId, "palette_id" to "sweetie-16"))
        val paletteId = (switched.raw("palette_id") as? JsonString)?.value
            ?: ((switched.entries["palette"] as? JsonObject)?.raw("id") as? JsonString)?.value
        assertTrue("palette_switch must succeed for library palettes", paletteId != null)
    }

    // ---- target selection -------------------------------------------------------

    @Test
    fun `frame_set_active steers subsequent draws to the selected frame`() {
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        v1Tool("frame_add", run("session_id" to sessionId))
        val set = v1Tool("frame_set_active", run("session_id" to sessionId, "frame_index" to 1))
        assertEquals(1, (set.raw("active_frame_index") as JsonNumber).value.toInt())
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 0, "y" to 0, "color" to "#00FF00"))
        val project = store.get(sessionId, create = false)!!.project
        assertEquals(1, litPixels(project, frameIndex = 1))
        assertEquals(0, litPixels(project, frameIndex = 0))
    }

    @Test
    fun `layer_set_active steers subsequent draws to the selected layer`() {
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        val added = v1Tool("layer_add", run("session_id" to sessionId, "name" to "ink"))
        val layerId = (added.raw("active_layer_id") as JsonNumber).value.toInt()
        val set = v1Tool("layer_set_active", run("session_id" to sessionId, "layer_id" to layerId))
        assertEquals(layerId, (set.raw("active_layer_id") as JsonNumber).value.toInt())
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 1, "y" to 1, "color" to "#0000FF"))
        val project = store.get(sessionId, create = false)!!.project
        assertNotNull("the new layer must own a cel now", project.frames[0].cels[layerId])
        assertEquals(1, litPixels(project))
    }

    // ---- convert loop -----------------------------------------------------------

    @Test
    fun `convert_image with session_id commits converted pixels`() {
        val create = v1Tool("canvas_create", run("width" to 4, "height" to 4))
        val sessionId = (create.raw("session_id") as JsonString).value
        val pixels = (0 until 16).map { 0xFF804020.toInt() + it }
        val result = v1Tool("convert_image", run(
            "session_id" to sessionId,
            "width" to 4, "height" to 4,
            "pixels" to pixels,
            "palette_id" to "pico-8",
        ))
        val committed = (result.raw("committed") as? JsonBoolean)?.value
            ?: (((result.entries["result"] as? JsonObject)?.raw("committed")) as? JsonBoolean)?.value
        assertTrue("committed flag must be set (result keys: ${result.entries.keys})", committed == true)
        val project = store.get(sessionId, create = false)!!.project
        assertTrue("converted pixels must land in the session", litPixels(project) > 0)
    }

    // ---- persistence ------------------------------------------------------------

    @Test
    fun `session_save then project_delete removes the persisted project`() {
        val root: File = tmp.root
        val v6p = McpToolRegistryV6(lab, McpPersistence(ProjectStore(root), SlotStore(root)))
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        v1Tool("draw_pixel", run("session_id" to sessionId, "x" to 0, "y" to 0, "color" to "#FF0000"))
        val saved = tool({ n, p, s -> v6p.execute(n, p, s) }, "session_save", run("session_id" to sessionId, "name" to "keepme"))
        val projectId = (saved.raw("project_id") as JsonString).value
        val deleted = tool({ n, p, s -> v6p.execute(n, p, s) }, "project_delete", run("project_id" to projectId))
        assertTrue((deleted.raw("deleted") as JsonBoolean).value)
        try {
            tool({ n, p, s -> v6p.execute(n, p, s) }, "project_delete", run("project_id" to projectId))
            throw AssertionError("second delete must report the project as gone")
        } catch (expected: McpToolException) {
            assertTrue(expected.message!!.contains("no stored project"))
        }
    }

    // ---- contract --------------------------------------------------------------

    @Test
    fun `checkpoint_rollback for an unknown checkpoint answers INVALID_PARAMS`() {
        val create = v1Tool("canvas_create", run("width" to 8, "height" to 8))
        val sessionId = (create.raw("session_id") as JsonString).value
        try {
            v6Tool("checkpoint_rollback", run("session_id" to sessionId, "name" to "ghost"))
            throw AssertionError("rollback must reject unknown checkpoints")
        } catch (expected: McpToolException) {
            assertEquals(JsonRpc.INVALID_PARAMS, expected.code)
        }
    }

    @Test
    fun `oversized session ids are rejected at the store boundary`() {
        val longId = "s".repeat(129)
        try {
            v1Tool("canvas_info", run("session_id" to longId))
            throw AssertionError("129-char session ids must be rejected")
        } catch (expected: McpToolException) {
            assertTrue(expected.message!!.contains("128"))
        }
    }
}
