package com.pixellab.core.engine

/**
 * One named checkpoint: a mark of the engine's undo depth for a specific
 * project, taken at a moment the caller considered "known good".
 *
 * Rollback to a checkpoint is "undo everything committed *after* it" —
 * which makes checkpoints the natural unit for agent experiments: set a
 * checkpoint, try a risky multi-step edit, roll back in one call if the
 * result reads wrong (compare with `canvas_diff` first).
 *
 * A checkpoint binds to the [projectId] it was taken on. When the session
 * swaps to a different project lineage (template apply, project import)
 * the depths no longer describe the new project's history, so rollback
 * refuses with a stale-checkpoint error instead of guessing.
 */
data class Checkpoint(
    /** Caller-chosen name, unique per session. */
    val name: String,
    /** Project id the depth was recorded for. */
    val projectId: String,
    /** Undo-stack depth at record time (0 = pristine). */
    val depth: Int,
    /** Wall-clock creation time (ms). */
    val createdAtMs: Long,
    /** Label of the *next* entry that would sit above the checkpoint,
     *  recorded for context; null when history was empty. */
    val nextLabel: String?,
)

/**
 * Per-session checkpoint registry with rollback policy.
 *
 * The tracker owns *no* engine state: depths are read from the engine via
 * [PixelEngine.historyInfo] when a checkpoint is set, and rollback is a
 * loop of [PixelEngine.undo] calls the tracker drives on demand. That
 * keeps the tracker a pure, testable policy object:
 *
 *  * [set] records the current undo depth under a name (or overwrites the
 *    previous checkpoint of that name),
 *  * [rollback] validates the project lineage, then undoes until the undo
 *    stack is back at the checkpoint depth — a no-op when history was
 *    already unwound past it (idempotent),
 *  * [list]/[remove]/[clearFor] are bookkeeping.
 *
 * Capacity: at most [MAX_CHECKPOINTS] per session key; setting one more
 * drops the oldest (FIFO by creation time). Session keys are opaque — the
 * MCP layer passes the `session_id`.
 *
 * Thread-safety: all mutating methods synchronize on an internal lock,
 * consistent with the store/engine discipline (the MCP router serializes
 * tool execution anyway; the lock guards programmatic misuse).
 */
class CheckpointTracker {

    /** Rollback outcome: what happened and how far the stack unwound. */
    data class RollbackResult(
        /** True when the working project was actually rewound. */
        val rewound: Boolean,
        /** Number of undo steps performed (0 when already at target). */
        val steps: Int,
        /** Label of the history entry just above the checkpoint after
         *  rollback — i.e. the newest *remaining* change; null when the
         *  stack is now empty at or below the checkpoint. */
        val topRemainingLabel: String?,
        /** The project state the caller should adopt as working copy —
         *  the pre-state of the oldest undone entry, i.e. exactly the
         *  snapshot the checkpoint marked. Null when nothing was undone. */
        val restoredProject: com.pixellab.core.model.SpriteProject?,
    )

    private val lock = Any()

    /** sessionKey → ordered checkpoints (oldest first). */
    private val bySession = LinkedHashMap<String, MutableList<Checkpoint>>()

    /**
     * Records a checkpoint named [name] for [sessionKey] against the
     * engine's current undo depth of [projectId].
     *
     * Setting the same name again replaces the earlier checkpoint (a name
     * is a slot, not an event log).
     *
     * @return the recorded checkpoint.
     */
    fun set(
        sessionKey: String,
        name: String,
        projectId: String,
        engine: PixelEngine,
    ): Checkpoint = synchronized(lock) {
        require(name.isNotBlank()) { "checkpoint name must not be blank" }
        val history = engine.historyInfo(projectId)
        val checkpoint = Checkpoint(
            name = name.trim(),
            projectId = projectId,
            depth = history.size,
            createdAtMs = System.currentTimeMillis(),
            nextLabel = history.lastOrNull()?.label,
        )
        val list = bySession.getOrPut(sessionKey) { ArrayList() }
        list.removeAll { it.name == checkpoint.name }
        list.add(checkpoint)
        while (list.size > MAX_CHECKPOINTS) list.removeAt(0)
        checkpoint
    }

    /** All checkpoints of [sessionKey], oldest first (copy). */
    fun list(sessionKey: String): List<Checkpoint> = synchronized(lock) {
        (bySession[sessionKey] ?: emptyList()).toList()
    }

    /** The checkpoint [sessionKey]:[name], or null. */
    fun find(sessionKey: String, name: String): Checkpoint? = synchronized(lock) {
        bySession[sessionKey]?.firstOrNull { it.name == name.trim() }
    }

    /** Removes one checkpoint; true when it existed. */
    fun remove(sessionKey: String, name: String): Boolean = synchronized(lock) {
        val list = bySession[sessionKey] ?: return false
        val removed = list.removeAll { it.name == name.trim() }
        if (list.isEmpty()) bySession.remove(sessionKey)
        removed
    }

    /** Drops every checkpoint of [sessionKey] (session teardown). */
    fun clearFor(sessionKey: String) = synchronized(lock) {
        bySession.remove(sessionKey)
        Unit
    }

    /**
     * Rolls [sessionKey]'s checkpoint [name] back on the engine.
     *
     * Stale-lineage guard: when the current [projectId] differs from the
     * checkpoint's project, rollback throws [IllegalStateException] —
     * depths describe the old lineage and undoing the new project's
     * history to reach them would corrupt unrelated work.
     *
     * Idempotence: when history is already at or below the checkpoint
     * depth (the user undid past it), the result reports zero steps and
     * `rewound = false` rather than failing.
     *
     * @throws IllegalStateException on stale lineage or missing checkpoint.
     */
    fun rollback(
        sessionKey: String,
        name: String,
        projectId: String,
        engine: PixelEngine,
    ): RollbackResult = synchronized(lock) {
        val checkpoint = find(sessionKey, name)
            ?: throw IllegalStateException(
                "checkpoint '$name' not found in session (see checkpoint_list)",
            )
        if (checkpoint.projectId != projectId) {
            throw IllegalStateException(
                "checkpoint '$name' belongs to project ${checkpoint.projectId} but the session " +
                    "now holds $projectId (project was swapped); the depth no longer describes this history",
            )
        }
        var steps = 0
        var restored: com.pixellab.core.model.SpriteProject? = null
        while (engine.historyInfo(projectId).size > checkpoint.depth) {
            val step = engine.undo(projectId) ?: break
            restored = step
            steps++
        }
        val top = engine.historyInfo(projectId).lastOrNull()?.label
        RollbackResult(
            rewound = steps > 0,
            steps = steps,
            topRemainingLabel = top,
            restoredProject = restored,
        )
    }

    private companion object {
        /** Per-session checkpoint cap (FIFO eviction beyond it). */
        const val MAX_CHECKPOINTS: Int = 32
    }
}
