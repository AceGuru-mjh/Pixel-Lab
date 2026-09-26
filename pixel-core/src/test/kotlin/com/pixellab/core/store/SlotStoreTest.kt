package com.pixellab.core.store

import com.pixellab.core.analysis.TestFrames
import com.pixellab.core.model.PixelPoint
import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for [SlotStore]: save/load round trips, name policy,
 * overwrite semantics, corrupt-document tolerance and listing order —
 * each against a real [ProjectStore] in a temp folder.
 */
class SlotStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun newStores(): Pair<ProjectStore, SlotStore> {
        val root = folder.newFolder("root-${System.nanoTime()}")
        return ProjectStore(root) to SlotStore(root)
    }

    private fun project(name: String = "hero"): com.pixellab.core.model.SpriteProject =
        SpriteFactory.create(name, 8, 8, BuiltInPalettes.PICO8)

    @Test
    fun `save then load round trips pixels`() {
        val (projects, slots) = newStores()
        val drawn = com.pixellab.core.PixelLab.create().engine.drawPixels(
            project(), listOf(PixelPoint(2, 2), PixelPoint(3, 3)), TestFrames.RED,
        )
        slots.save("knight-idle", drawn, "sess-1", projects)

        val loaded = slots.load("knight-idle", projects)
        assertEquals(drawn.id, loaded.id)
        assertEquals(TestFrames.RED, loaded.compositeActiveFrame()[2, 2])
        assertEquals(TestFrames.RED, loaded.compositeActiveFrame()[3, 3])
    }

    @Test
    fun `find reports the saved summary`() {
        val (projects, slots) = newStores()
        val drawn = com.pixellab.core.PixelLab.create().engine.drawPixel(
            project("sword"), 0, 0, TestFrames.BLUE,
        )
        val summary = slots.save("sword", drawn, "sess-42", projects)
        assertEquals("sword", summary.name)
        assertEquals(drawn.id, summary.projectId)
        assertEquals("sword", summary.projectName)
        assertEquals(8, summary.width)
        assertEquals(8, summary.height)
        assertEquals(1, summary.frames)
        assertEquals(1, summary.layers)
        assertEquals(BuiltInPalettes.PICO8.id, summary.paletteId)
        assertEquals("sess-42", summary.savedBySession)
        assertTrue(summary.savedAtMs > 0)
        assertTrue(summary.readable)

        val found = slots.find("sword")
        assertNotNull(found)
        assertEquals(drawn.id, found!!.projectId)
        assertNull(slots.find("missing"))
    }

    @Test
    fun `saving the same name overwrites the slot`() {
        val (projects, slots) = newStores()
        val a = project("first")
        val b = project("second")
        slots.save("slot", a, "s1", projects)
        Thread.sleep(2) // distinct saved_at_ms
        slots.save("slot", b, "s1", projects)
        val list = slots.list()
        assertEquals(1, list.size)
        assertEquals(b.id, list[0].projectId)
        assertEquals("second", list[0].projectName)
        // Both projects still exist on disk; only the pointer moved.
        assertEquals(2, projects.list().size)
    }

    @Test
    fun `list is newest first`() {
        val (projects, slots) = newStores()
        slots.save("one", project("p1"), "s", projects)
        Thread.sleep(2)
        slots.save("two", project("p2"), "s", projects)
        Thread.sleep(2)
        slots.save("three", project("p3"), "s", projects)
        val names = slots.list().map { it.name }
        assertEquals(listOf("three", "two", "one"), names)
    }

    @Test
    fun `delete removes the slot but not the project`() {
        val (projects, slots) = newStores()
        val p = project()
        slots.save("gone", p, "s", projects)
        assertTrue(slots.delete("gone"))
        assertFalse(slots.delete("gone"))
        assertNull(slots.find("gone"))
        assertTrue(projects.list().any { it.id == p.id })
    }

    @Test
    fun `name policy rejects blanks, bad characters and oversize`() {
        val (projects, slots) = newStores()
        val p = project()
        for (bad in listOf("", "   ", "with space", "斜杠/名", "a".repeat(65))) {
            try {
                slots.save(bad, p, "s", projects)
                throw AssertionError("name '$bad' should have been rejected")
            } catch (expected: IllegalArgumentException) {
                // expected
            }
        }
        // Legal names survive: letters, digits, dots, underscores, hyphens.
        for (good in listOf("a", "Knight.Idle_v2-x", "0", "_", "-")) {
            slots.save(good, p, "s", projects)
        }
        assertEquals(5, slots.list().size)
    }

    @Test
    fun `corrupt slot documents surface as unreadable, not poison`() {
        val (projects, slots) = newStores()
        val p = project()
        slots.save("healthy", p, "s", projects)
        // Corrupt one slot document directly on disk.
        val root = folder.root.listFiles()!!.first { it.name.startsWith("root-") }
        val target = java.io.File(java.io.File(root, "slots"), "broken.json")
        target.parentFile.mkdirs()
        target.writeText("{not json at all")
        val list = slots.list()
        assertEquals(2, list.size)
        val broken = list.first { it.name == "broken" }
        assertFalse(broken.readable)
        assertTrue(list.first { it.name == "healthy" }.readable)
        // Loading a corrupt slot errors with an actionable message.
        try {
            slots.load("broken", projects)
            throw AssertionError("expected corrupt-slot failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("corrupt"))
        }
        // Deleting by name still works (self-service repair).
        assertTrue(slots.delete("broken"))
    }

    @Test
    fun `load of missing slot is an actionable error`() {
        val (projects, slots) = newStores()
        try {
            slots.load("nope", projects)
            throw AssertionError("expected missing-slot failure")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("no saved session"))
            assertTrue(expected.message!!.contains("session_saved_list"))
        }
    }

    @Test
    fun `load of dangling slot (project deleted) is an actionable error`() {
        val (projects, slots) = newStores()
        val p = project()
        slots.save("dangling", p, "s", projects)
        projects.delete(p.id)
        try {
            slots.load("dangling", projects)
            throw AssertionError("expected missing-project failure")
        } catch (expected: Exception) {
            // ProjectStore.load errors when the project directory is gone.
            assertTrue(expected.message != null)
        }
    }
}
