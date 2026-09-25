package com.pixellab.ui

import com.pixellab.core.history.CommandHistory
import com.pixellab.core.history.EditorCommand
import com.pixellab.core.history.HistoryEntry
import com.pixellab.core.history.HistoryTransaction
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject

/**
 * A labelled single edit recorded by [EditorSession.edit].
 *
 * Pure metadata: [EditorSession] uses the stored-state undo of
 * [CommandHistory], so the command only contributes its [label] (history
 * panels) and its [coalesceKey] (stroke merging).
 */
private class SessionCommand(
    override val label: String,
    override val coalesceKey: String? = null,
) : EditorCommand

/**
 * The single source of truth a Compose editor scaffold binds to snapshot
 * state: an immutable [SpriteProject] plus a command-level
 * [CommandHistory] driving undo/redo/dirty bookkeeping.
 *
 * This class is deliberately **pure Kotlin with no Compose dependency** so
 * the whole editing contract is testable on the JVM: the scaffold wraps it
 * (typically `remember { EditorSession(project) }`), routes every mutation
 * through [edit] / [beginTransaction], and re-renders from the
 * [setOnProjectChanged] notifications. Fps and playback state are *not* kept
 * here — they are a render-layer concern owned by
 * [PlaybackState]/[TimelineController].
 *
 * ## Editing contract
 *
 * * [edit] applies `transform` to the current project, records one history
 *   entry and publishes the result. A transform whose result equals its
 *   input (deep [SpriteProject.equals]) is a no-op: nothing is recorded, no
 *   notification fires, the same instance is returned.
 * * Consecutive edits sharing a non-null `coalesceKey` merge into one entry
 *   (one brush stroke = one undo step), delegated to
 *   [CommandHistory.record].
 * * [undo] / [redo] re-apply stored snapshots and use the history's
 *   current-guard: if the caller's project diverged from the recorded
 *   timeline, the call returns `null` and changes nothing.
 * * [beginTransaction] applies several transforms *live* (the project is
 *   updated after every step) and folds them into ONE history entry on
 *   [SessionTransaction.commit]; [SessionTransaction.cancel] rolls the whole
 *   run back to the pre-transaction state.
 *
 * ## Dirty tracking
 *
 * `isModified` is delegated to the history position vs. the [markSaved]
 * save point; the scaffold maps it to a "unsaved changes" indicator and
 * binds `Ctrl+S` to [markSaved].
 *
 * Thread-compatible but not thread-safe: own one session per editor on a
 * single write path (the same contract [CommandHistory] uses).
 *
 * @param initial the document the session starts from; treated as the
 *   original save point (clean state).
 * @param historyLimit maximum undo entries retained, `1..1024`.
 */
class EditorSession(
    initial: SpriteProject,
    historyLimit: Int = 64,
) {

    /** Backing store of the live document; mutated only by this class. */
    var project: SpriteProject = initial
        private set

    /** Command history powering undo/redo, coalescing and dirty tracking. */
    private val history = CommandHistory(historyLimit)

    /** Single change slot invoked after every effective edit/undo/redo. */
    private var projectListener: ((SpriteProject) -> Unit)? = null

    /**
     * The document the session holds right now. Compose hosts usually read
     * [project] directly; this accessor exists for non-Compose call sites
     * that prefer an explicit functional style.
     */
    fun current(): SpriteProject = project

    /**
     * Applies [transform] as one labelled, undoable edit.
     *
     * @param label human-readable action name shown in history panels.
     * @param coalesceKey when non-null and equal to the top entry's key, the
     *   edit extends that entry instead of pushing a new one (brush dabs of
     *   one stroke share a key).
     * @param transform pure mapping from the current project to the next.
     * @return the new project (the input instance when the transform was a
     *   no-op — nothing recorded, no notification).
     */
    fun edit(
        label: String,
        coalesceKey: String? = null,
        transform: (SpriteProject) -> SpriteProject,
    ): SpriteProject {
        val before = project
        val after = transform(before)
        if (after == before) return after
        history.record(SessionCommand(label, coalesceKey), before, after)
        project = after
        notifyProjectChanged(after)
        return after
    }

    /**
     * Undoes the newest committed entry.
     *
     * @return the restored project, or `null` when undo is impossible (empty
     *   stack, or the current project diverged from the recorded `after`
     *   state). `null` leaves everything — stacks, project, listener —
     *   untouched.
     */
    fun undo(): SpriteProject? {
        val restored = history.undo(project) ?: return null
        project = restored
        notifyProjectChanged(restored)
        return restored
    }

    /**
     * Re-applies the newest redoable entry; symmetric to [undo].
     *
     * @return the re-applied project, or `null` when redo is impossible.
     */
    fun redo(): SpriteProject? {
        val reapplied = history.redo(project) ?: return null
        project = reapplied
        notifyProjectChanged(reapplied)
        return reapplied
    }

    /**
     * Starts a multi-step edit that will commit as ONE undo entry.
     *
     * Steps run live: [SessionTransaction.edit] updates [project] after
     * every step so intermediate previews stay possible, but the change
     * listener stays silent until [SessionTransaction.commit] (or
     * [SessionTransaction.cancel]) finishes the run.
     *
     * @param label the label of the single resulting history entry.
     * @throws IllegalArgumentException when [label] is blank.
     */
    fun beginTransaction(label: String): SessionTransaction {
        require(label.isNotBlank()) { "Transaction label must not be blank" }
        return SessionTransaction(label)
    }

    /**
     * True when at least one entry can be undone right now.
     */
    val canUndo: Boolean get() = history.canUndo

    /**
     * True when at least one entry can be redone right now.
     */
    val canRedo: Boolean get() = history.canRedo

    /**
     * Committed entries, oldest first — data source for history panels.
     * Redoable entries are not included (see [CommandHistory.entries]).
     */
    fun historyEntries(): List<HistoryEntry> = history.entries()

    /** Number of entries currently on the undo stack. */
    fun undoDepth(): Int = history.undoDepth()

    /** Number of entries currently on the redo stack. */
    fun redoDepth(): Int = history.redoDepth()

    /**
     * Marks the current timeline position as the save point:
     * [isModified] becomes false until the position moves again.
     */
    fun markSaved() {
        history.markSaved()
    }

    /**
     * True when the current project state differs from the last
     * [markSaved] position (or from the session start, before any save).
     */
    val isModified: Boolean get() = history.isModified

    /**
     * Installs (or with `null`, removes) the project-change listener. It
     * fires after every *effective* [edit], successful [undo]/[redo] and
     * transaction [SessionTransaction.commit]/[SessionTransaction.cancel]
     * with the new document instance. No-op edits never fire it.
     */
    fun setOnProjectChanged(listener: ((SpriteProject) -> Unit)?) {
        projectListener = listener
    }

    /**
     * The cel receiving drawing operations (active layer of the active
     * frame), or null when the layer has no content there yet.
     */
    fun activeCel(): PixelFrame? = project.activeCel()

    /**
     * Replaces the active cel as one recorded edit ("Edit cel"), returning
     * the resulting project. The cel must match the project dimensions.
     *
     * @throws IllegalArgumentException via [SpriteProject.withActiveCel] on
     *   dimension mismatch.
     */
    fun withActiveCel(cel: PixelFrame): SpriteProject =
        edit("Edit cel") { it.withActiveCel(cel) }

    /** Fires the installed listener with the freshly installed project. */
    private fun notifyProjectChanged(next: SpriteProject) {
        projectListener?.invoke(next)
    }

    /**
     * A run of fine-grained edits folded into ONE undo entry, built by
     * [EditorSession.beginTransaction].
     *
     * Every [edit] applies its transform to the live project immediately
     * (preview-friendly) and collects the step; nothing touches the history
     * until [commit]. Steps whose transform is a no-op are skipped. The
     * change listener is silent for the whole run and fires once at
     * [commit]/[cancel].
     */
    inner class SessionTransaction internal constructor(
        /** Label of the single history entry produced by [commit]. */
        val label: String,
    ) {

        private val tx = HistoryTransaction(label)

        /** Project state captured when the transaction began. */
        private val start: SpriteProject = project

        /** True once [commit] or [cancel] ran; the tx is single-shot. */
        private var finished = false

        /** Micro-edits collected so far (defensive copy of the step list). */
        val steps: List<HistoryTransaction.Step> get() = tx.steps

        /**
         * Applies one micro-edit to the live project and collects it.
         *
         * @param stepLabel label of the micro-edit (kept in the grouped
         *   command for inspection/logging only).
         * @param transform mapping from the current project to the next.
         * @return the new project (unchanged instance for no-op steps).
         * @throws IllegalStateException when the transaction already
         *   finished.
         */
        fun edit(stepLabel: String, transform: (SpriteProject) -> SpriteProject): SpriteProject {
            check(!finished) { "Transaction '$label' already finished" }
            val before = project
            val after = transform(before)
            if (after == before) return after
            tx.add(SessionCommand(stepLabel), before, after)
            project = after
            return after
        }

        /**
         * Commits the run as a single [GroupedCommand] history entry
         * (`start -> project`) and notifies the project listener. A run whose
         * net effect is the identity records nothing at all (no entry, no
         * notification) — still a legal finish.
         *
         * @return the final project.
         * @throws IllegalStateException when the transaction already
         *   finished.
         */
        fun commit(): SpriteProject {
            check(!finished) { "Transaction '$label' already finished" }
            finished = true
            val final = project
            tx.commit(history, start, final)
            if (final != start) notifyProjectChanged(final)
            return final
        }

        /**
         * Abandons the run: restores the pre-transaction state without
         * recording anything and notifies the listener (the live project
         * changed back). A no-op when nothing was applied.
         *
         * @return the restored (pre-transaction) project.
         * @throws IllegalStateException when the transaction already
         *   finished.
         */
        fun cancel(): SpriteProject {
            check(!finished) { "Transaction '$label' already finished" }
            finished = true
            if (project != start) {
                project = start
                notifyProjectChanged(start)
            }
            return start
        }
    }
}
