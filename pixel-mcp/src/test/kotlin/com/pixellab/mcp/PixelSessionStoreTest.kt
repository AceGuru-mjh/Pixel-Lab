package com.pixellab.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the session-store hardening (audit round):
 *
 *  * registering a session under an EXISTING id must fire the project
 *    discard callback for the displaced session (engine undo history
 *    keys by project id — a silent overwrite leaked it forever),
 *  * evictions fire the discard callbacks exactly once per session,
 *  * remove/clear keep the single-shot callback contract,
 *  * update() swapping project lineage fires the project discard.
 */
class PixelSessionStoreTest {

    private fun store(max: Int = 4): Triple<PixelSessionStore, MutableList<String>, MutableList<String>> {
        val discardedProjects = ArrayList<String>()
        val discardedSessions = ArrayList<String>()
        val s = PixelSessionStore(
            maxSessions = max,
            onProjectDiscarded = { discardedProjects.add(it) },
            onSessionDiscarded = { discardedSessions.add(it) },
        )
        return Triple(s, discardedProjects, discardedSessions)
    }

    @Test
    fun `new session under existing id fires discard for the displaced project`() {
        val (s, projects, _) = store()
        val first = s.newSession(id = "dup")
        s.newSession(id = "dup")
        assertEquals(listOf(first.project.id), projects)
        // The session id count stays one — the entry was replaced.
        assertEquals(1, s.sessionCount)
    }

    @Test
    fun `LRU eviction fires callbacks exactly once per evicted session`() {
        val (s, projects, sessions) = store(max = 2)
        val a = s.newSession(id = "a")
        s.newSession(id = "b")
        s.newSession(id = "c") // evicts a (LRU)
        assertEquals(listOf(a.project.id), projects)
        assertEquals(listOf("a"), sessions)
        // Make room, then re-register the evicted id: the store must not
        // fire spurious callbacks and must stay at capacity.
        s.remove("b")
        val before = projects.size
        s.get("a", create = true)
        assertEquals(before, projects.size)
        assertEquals(2, s.sessionCount)
    }

    @Test
    fun `remove reports existence and fires callbacks once`() {
        val (s, projects, sessions) = store()
        s.newSession(id = "x")
        assertTrue(s.remove("x"))
        assertFalse(s.remove("x"))
        assertEquals(1, projects.size)
        assertEquals(1, sessions.size)
    }

    @Test
    fun `clear fires callbacks for every live session`() {
        val (s, projects, sessions) = store()
        s.newSession(id = "1")
        s.newSession(id = "2")
        s.newSession(id = "3")
        s.clear()
        assertEquals(3, projects.size)
        assertEquals(3, sessions.size)
        assertEquals(0, s.sessionCount)
    }

    @Test
    fun `get with create=false misses return null without callbacks`() {
        val (s, projects, _) = store()
        assertNull(s.get("ghost", create = false))
        assertTrue(projects.isEmpty())
    }

    @Test
    fun `update swapping project lineage fires project discard`() {
        val (s, projects, _) = store()
        val session = s.newSession(id = "swap")
        val originalProjectId = session.project.id // capture BEFORE the swap
        val otherProject = com.pixellab.core.model.SpriteFactory.create(
            "other", 8, 8, com.pixellab.core.palette.BuiltInPalettes.PICO8,
        )
        s.update(session.id, otherProject)
        assertEquals(listOf(originalProjectId), projects)
        // Session still live, now holding the other project.
        assertEquals(otherProject.id, s.get(session.id)!!.project.id)
    }

    @Test
    fun `update on unknown session is a no-op`() {
        val (s, projects, _) = store()
        val otherProject = com.pixellab.core.model.SpriteFactory.create(
            "other", 8, 8, com.pixellab.core.palette.BuiltInPalettes.PICO8,
        )
        assertNull(s.update("ghost", otherProject))
        assertTrue(projects.isEmpty())
    }

    @Test
    fun `list summaries reflect current project state`() {
        val (s, _, _) = store()
        val session = s.newSession(id = "listed", width = 20, height = 10)
        val entries = s.list()
        assertEquals(1, entries.size)
        val summary = entries[0]
        assertEquals("listed", summary.string("session_id"))
        assertEquals(20, summary.int("width"))
        assertEquals(10, summary.int("height"))
        assertEquals(session.project.id, summary.string("project_id"))
    }
}
