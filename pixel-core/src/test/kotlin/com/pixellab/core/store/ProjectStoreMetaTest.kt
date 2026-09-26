package com.pixellab.core.store

import com.pixellab.core.model.SpriteFactory
import com.pixellab.core.palette.BuiltInPalettes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression tests for the ProjectStore sidecar hardening (audit round):
 *
 *  * project names containing `"` / `\` used to corrupt the meta sidecar
 *    read-back (the parser cut values at the first quote and desynced the
 *    rest of the scan) — now escaped on write and unescaped on read,
 *  * atomic writes keep the previous document intact across failures
 *    (tmp + atomic move).
 */
class ProjectStoreMetaTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Creates a store under a fresh subfolder, remembering its root. */
    private var storeRoot: java.io.File? = null

    private fun newStore(): ProjectStore =
        folder.newFolder().also { storeRoot = it }.let { ProjectStore(it) }

    @Test
    fun `project names with quotes and backslashes round trip through meta`() {
        val store = newStore()
        val tricky = "we\"ird\\name"
        val project = SpriteFactory.create(tricky, 8, 8, BuiltInPalettes.PICO8)
        store.save(project)

        val summaries = store.list()
        assertEquals(1, summaries.size)
        assertEquals(tricky, summaries[0].name)
        // Load still resolves by the project id (documents are the truth).
        val loaded = store.load(project.id)
        assertEquals(tricky, loaded.name)
    }

    @Test
    fun `atomic write leaves the previous document intact when promotion fails`() {
        val store = newStore()
        val project = SpriteFactory.create("keeper", 8, 8, BuiltInPalettes.PICO8)
        store.save(project)

        // Simulate a crash-before-promotion: a staged .tmp for the NEXT save
        // exists while the current document stays in place. The store must
        // keep loading the previous document.
        val doc = projectDir(store, project.id)
            .listFiles { f -> f.name.endsWith(".pixellab.json") }!!.first()
        val staging = java.io.File(doc.parentFile, doc.name + ".tmp")
        staging.writeText("{\"schema\":2,\"truncated\"")

        val loaded = store.load(project.id)
        assertEquals("keeper", loaded.name)
        assertEquals(project.id, loaded.id)

        // A fresh save under the SAME document name must overwrite the
        // planted staging file and promote it — the stale tmp is consumed,
        // not left behind.
        store.save(loaded)
        assertTrue("stale staging tmp should be consumed by the next save", !staging.exists())
        assertEquals("keeper", store.load(project.id).name)
    }

    @Test
    fun `list tolerates a corrupt meta sidecar`() {
        val store = newStore()
        val project = SpriteFactory.create("solid", 8, 8, BuiltInPalettes.PICO8)
        store.save(project)
        // Corrupt the meta sidecar directly.
        val doc = projectDir(store, project.id)
            .listFiles { f -> f.name.endsWith(".pixellab.json") }!!.first()
        val meta = java.io.File(doc.parentFile, doc.name + ".meta.json")
        meta.writeText("{ this is not json")
        val summaries = store.list()
        // The document is the source of truth; the summary survives.
        assertEquals(1, summaries.size)
        assertEquals(project.id, summaries[0].id)
    }

    /** The `projects/<id>` directory of one saved project. */
    private fun projectDir(store: ProjectStore, projectId: String): java.io.File {
        val projects = java.io.File(storeRoot!!, "projects")
        return projects.listFiles()!!.first { it.name == projectId }
    }
}
