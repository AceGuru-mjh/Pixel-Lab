package com.pixellab.core.history

import com.pixellab.core.model.SpriteProject

/**
 * Command-level undo/redo for immutable [SpriteProject] documents.
 *
 * ## Why stored-state (revert) instead of inverse-application
 *
 * [com.pixellab.core.engine.PixelEngine] already offers snapshot-based undo
 * per project. This type complements it at the *command* granularity that
 * editor UIs want: a labelled entry per user action ("Brush stroke",
 * "Fill layer", "Resize canvas") with metadata for history panels.
 *
 * There are two classic ways to implement command undo:
 *
 *  1. **Inverse application** — each command knows how to un-apply itself
 *     (`command.undo(project)`). This is memory-cheap but breaks down the
 *     moment an operation is lossy or non-injective: quantization collapses
 *     many colors into one, `TrimStep`-style crops discard pixels, alpha
 *     thresholding erases semi-transparent values. No inverse can recover
 *     the lost information, so "undo" silently degrades into
 *     "approximately undo".
 *  2. **Stored state** — each entry simply remembers the exact
 *     [HistoryEntry.before] and [HistoryEntry.after] snapshots. Reverting is
 *     exact even for lossy operations, and it needs zero cooperation from
 *     the command implementation.
 *
 * Pixel Lab projects are immutable value objects whose
 * [SpriteProject.equals] is a deep content comparison (cels compare by pixel
 * content), so the stored-state approach is a natural fit: `undo` restores
 * the captured `before` instance verbatim and the no-op detection in
 * [CommandHistory.record] falls out of `before == after` for free.
 *
 * Memory stays bounded: at most [limit] entries are retained on the undo
 * stack (oldest evicted, see [CommandHistory.record]); every eviction is
 * final — the evicted states become unreachable, exactly like the editor
 * "forgetting" history older than the buffer.
 *
 * ## Coalescing
 *
 * Consecutive commands that share a non-null [EditorCommand.coalesceKey]
 * (e.g. every brush dab of one stroke carries `"brush"` and the stroke id)
 * merge into the single top entry: the entry keeps its original `before`
 * and label but adopts the new `after` and timestamp. One stroke = one undo
 * step, which is what users expect.
 *
 * ## Redo invalidation
 *
 * Recording a new command clears the redo stack: once the user branches the
 * timeline, the abandoned future is unreachable (standard editor semantics).
 *
 * All methods are thread-compatible but not thread-safe; editors are
 * expected to own one [CommandHistory] on their single write path, the same
 * contract [com.pixellab.core.engine.PixelEngine] uses.
 */
interface EditorCommand {

    /** Human-readable action name shown in history panels, e.g. `"Brush stroke"`. */
    val label: String

    /**
     * Semantic identity used to merge *consecutive* entries: when the top of
     * the undo stack was produced by a command with the same non-null key,
     * [CommandHistory.record] replaces that entry's `after` instead of
     * pushing a new one. `null` (default) never coalesces.
     *
     * The key should encode both the operation family *and* the interaction
     * that groups the dabs, e.g. `"brush#17"` for stroke 17 — two separate
     * strokes then coalesce into two entries, not one.
     */
    val coalesceKey: String? get() = null
}

/**
 * One committed history record: the command that caused the transition plus
 * the exact project states on both sides of it.
 *
 * [HistoryEntry] is immutable; [timestampMs] is wall-clock milliseconds from
 * `System.currentTimeMillis()` at record time (coalescing refreshes it) and
 * exists for UI display only — it never participates in equality decisions
 * made by [CommandHistory].
 */
data class HistoryEntry(
    /** The command that produced the transition (metadata only). */
    val command: EditorCommand,
    /** Project state immediately before the command ran. */
    val before: SpriteProject,
    /** Project state immediately after the command ran. */
    val after: SpriteProject,
    /** Wall-clock timestamp in ms when the entry was (re)recorded. */
    val timestampMs: Long,
)

/**
 * Undo/redo stack with entry coalescing, dirty tracking and a change
 * listener. See the package documentation for the design rationale.
 *
 * ### Worked example
 * ```
 * val history = CommandHistory()
 * var project = SpriteFactory.create("hero", 8, 8, palette)   // p0
 * val p1 = project.withActiveCel(frame.withPixel(4, 4, red))
 * history.record(BrushCommand("stroke 1"), project, p1)
 * project = p1
 * ...
 * val restored = history.undo(project)   // == p0 because project == p1
 * ```
 *
 * ### Position accounting
 * Internally the stack maintains an integer *position*: it advances on
 * every committed entry (record/redo) and retreats on every undo. The save
 * point is simply the position captured by [markSaved]; [isModified] is
 * `position != savePoint`. Coalesced records do not advance the position
 * because they extend the existing top entry rather than adding one.
 */
class CommandHistory(private val limit: Int = 64) {

    init {
        require(limit in 1..MAX_LIMIT) { "limit must be in [1, $MAX_LIMIT] (was $limit)" }
    }

    /** Committed entries, oldest first. The last element is the undo top. */
    private val undoStack = ArrayDeque<HistoryEntry>()

    /** Entries moved off the undo stack by [undo], newest first is the redo top (last element). */
    private val redoStack = ArrayDeque<HistoryEntry>()

    /** Listener invoked after every structural change; single slot, see [onChanged]. */
    private var listener: (() -> Unit)? = null

    /** Number of committed entries at "now"; see the class KDoc. */
    private var position: Int = 0

    /** Position captured by the last [markSaved]; starts at 0 (clean). */
    private var savedPosition: Int = 0

    /** True when [undo] can run right now (at least one committed entry). */
    val canUndo: Boolean get() = undoStack.isNotEmpty()

    /** True when [redo] can run right now (at least one redoable entry). */
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /**
     * True when the current project state differs from the save point.
     * Starts `false`, flips to `true` on the first effective record after
     * [markSaved] (or before any save), returns to `false` when undo/redo
     * walk the timeline back onto the saved position.
     */
    val isModified: Boolean get() = position != savedPosition

    /**
     * Records a state transition.
     *
     * * **No-op guard**: when `before == after` (deep content equality) the
     *   command changed nothing and nothing is recorded — the stacks and
     *   position are untouched and the listener does not fire.
     * * **Coalescing**: when the undo top's [EditorCommand.coalesceKey] is
     *   non-null and equals [command]'s key, the top entry is rebuilt with
     *   the new `after` and a fresh timestamp; no new entry is pushed and
     *   the position is unchanged. The redo stack is still cleared (a
     *   coalesced dab is new work on top of the timeline).
     * * Otherwise a new [HistoryEntry] is pushed, the redo stack is cleared
     *   and the position advances.
     * * **Eviction**: when the undo stack exceeds [limit] entries the
     *   oldest entry is dropped and the position (and therefore the save
     *   point semantics) shifts down accordingly — an evicted entry can no
     *   longer be undone, which may strand a save point that referenced it;
     *   in that case [isModified] reports `true` because the save point is
     *   no longer reachable exactly.
     */
    fun record(command: EditorCommand, before: SpriteProject, after: SpriteProject) {
        if (before == after) return
        val key = command.coalesceKey
        val top = undoStack.lastOrNull()
        if (key != null && top != null && top.command.coalesceKey == key) {
            undoStack.removeLast()
            undoStack.addLast(HistoryEntry(top.command, top.before, after, now()))
            redoStack.clear()
            notifyChanged()
            return
        }
        undoStack.addLast(HistoryEntry(command, before, after, now()))
        redoStack.clear()
        position += 1
        while (undoStack.size > limit) {
            undoStack.removeFirst()
            position -= 1
            if (savedPosition > position) savedPosition = position
        }
        notifyChanged()
    }

    /**
     * Undoes the newest committed entry.
     *
     * @param current the project state the editor holds *right now*.
     * @return the stored [HistoryEntry.before] to restore, or `null` when
     *   undo is impossible: the stack is empty, or [current] does not equal
     *   the entry's `after` (the guard — the editor diverged from the
     *   history, e.g. because it restored a different document). A `null`
     *   result leaves both stacks exactly as they were.
     */
    fun undo(current: SpriteProject): SpriteProject? {
        val top = undoStack.lastOrNull() ?: return null
        if (current != top.after) return null
        undoStack.removeLast()
        redoStack.addLast(top)
        position -= 1
        notifyChanged()
        return top.before
    }

    /**
     * Re-applies the newest redoable entry; symmetric to [undo].
     *
     * @param current the project state the editor holds *right now*.
     * @return the stored [HistoryEntry.after] to restore, or `null` when the
     *   redo stack is empty or [current] does not equal the entry's `before`
     *   (divergence guard).
     */
    fun redo(current: SpriteProject): SpriteProject? {
        val top = redoStack.lastOrNull() ?: return null
        if (current != top.before) return null
        redoStack.removeLast()
        undoStack.addLast(top)
        position += 1
        notifyChanged()
        return top.after
    }

    /**
     * Snapshot of the committed entries, oldest first — the data source for
     * history panels (labels + timestamps). Redoable entries are not
     * included; use [redoDepth] and walk [redo] if a panel needs them.
     * The returned list is a defensive copy.
     */
    fun entries(): List<HistoryEntry> = undoStack.toList()

    /** Number of entries available to [undo]. */
    fun undoDepth(): Int = undoStack.size

    /** Number of entries available to [redo]. */
    fun redoDepth(): Int = redoStack.size

    /**
     * Marks the *current* timeline position as the save point:
     * [isModified] becomes `false` until the position moves again.
     */
    fun markSaved() {
        savedPosition = position
    }

    /**
     * Installs (or with `null`, removes) the single change listener. The
     * listener fires after every structural change of this history:
     * committed records (including coalesces), successful undo/redo and
     * [clear]. It does *not* fire for no-op records, failed guard checks or
     * [markSaved]. Replacing a listener is allowed at any time.
     */
    fun onChanged(listener: (() -> Unit)?) {
        this.listener = listener
    }

    /**
     * Forgets the whole timeline: undo and redo stacks are dropped and the
     * save point resets to *now* (the current project state becomes the new
     * baseline, so [isModified] is `false` immediately after). Callers that
     * care about unsaved work should persist before clearing.
     */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        position = 0
        savedPosition = 0
        notifyChanged()
    }

    /** Single funnel for structural-change notifications. */
    private fun notifyChanged() {
        listener?.invoke()
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        /** Upper bound for [limit]; keeps the deque allocation sane. */
        public const val MAX_LIMIT: Int = 1024
    }
}

/**
 * An [EditorCommand] that stands for several sub-commands committed as one
 * undo step via [HistoryTransaction.commit]: the entry's label is the
 * transaction label and its `before`/`after` are the outermost states.
 */
class GroupedCommand(
    override val label: String,
    /** The sub-commands folded into this group, in execution order. */
    val commands: List<EditorCommand> = emptyList(),
) : EditorCommand {
    // Grouped entries never coalesce: each transaction is its own step.
    override val coalesceKey: String? get() = null
}

/**
 * Builder that folds a run of fine-grained edits into ONE undo entry.
 *
 * Typical use — an interactive tool that mutates the project through many
 * micro-ops but wants "one gesture, one undo":
 * ```
 * val tx = HistoryTransaction("Flood fill selection")
 * tx.add(floodCommand, project, projectAfterFlood)
 * tx.add(cleanupCommand, projectAfterFlood, projectAfterCleanup)
 * tx.commit(history, project, projectAfterCleanup)
 * ```
 *
 * [commit] records a single entry whose command is a [GroupedCommand]
 * (label = the transaction label) and whose `before`/`after` are the
 * outermost states passed to [commit]. The intermediate `before`/`after`
 * pairs collected by [add] are kept in [steps] for inspection/logging only —
 * they are intentionally *not* used for undo (see the package KDoc for why
 * stored-state revert is exact).
 */
class HistoryTransaction(private val label: String) {

    /** One collected micro-edit: command plus the states around it. */
    data class Step(
        val command: EditorCommand,
        val before: SpriteProject,
        val after: SpriteProject,
    )

    private val collected = mutableListOf<Step>()

    init {
        require(label.isNotBlank()) { "Transaction label must not be blank" }
    }

    /** Number of micro-edits collected so far. */
    val size: Int get() = collected.size

    /** True when no micro-edit was added yet. */
    val isEmpty: Boolean get() = collected.isEmpty()

    /** The collected micro-edits in order (defensive copy). */
    val steps: List<Step> get() = collected.toList()

    /**
     * Collects one micro-edit. Pairs are stored in order; validation of the
     * chain happens once in [commit].
     */
    fun add(command: EditorCommand, before: SpriteProject, after: SpriteProject) {
        collected.add(Step(command, before, after))
    }

    /**
     * Commits the transaction to [history] as a single entry.
     *
     * When micro-edits were collected, [initial] must equal the first step's
     * `before` and [final] the last step's `after` — this catches the
     * off-by-one mistake of passing a mid-transaction state as the outer
     * boundary. With no collected steps the transaction records the raw
     * `initial -> final` transition (still a legal single entry).
     *
     * Like [CommandHistory.record], a no-op (`initial == final` with nothing
     * effective inside) records nothing at all.
     *
     * @throws IllegalArgumentException when the boundary states do not match
     *   the collected chain.
     */
    fun commit(history: CommandHistory, initial: SpriteProject, final: SpriteProject) {
        if (collected.isEmpty()) {
            require(initial == final) {
                "Transaction '$label': collected no steps, so initial and final state must be equal"
            }
            return
        }
        val first = collected.first()
        val last = collected.last()
        require(initial == first.before) {
            "Transaction '$label': initial state does not match the first step's before"
        }
        require(final == last.after) {
            "Transaction '$label': final state does not match the last step's after"
        }
        if (initial == final) return
        history.record(
            GroupedCommand(label, collected.map { it.command }),
            initial,
            final,
        )
    }
}
