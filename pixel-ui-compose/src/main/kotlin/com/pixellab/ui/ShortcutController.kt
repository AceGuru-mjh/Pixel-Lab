package com.pixellab.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import com.pixellab.ui.theme.PixelTheme

/**
 * One keyboard shortcut: a key name plus modifier flags.
 *
 * [key] is compared case-insensitively (physical keys carry no case), so
 * `"x"` and `"X"` are the same shortcut. Modifier semantics follow the
 * desktop convention: `Ctrl` on desktop platforms, `Cmd`-as-`Ctrl` on macOS
 * hosts, `Alt`/`Option` mapped by the host.
 */
data class Shortcut(
    /** Key name, e.g. `"B"`, `"Z"`, `"+"`, `"ArrowLeft"`, `"Space"`. */
    val key: String,
    /** Whether Ctrl (platform primary modifier) must be held. */
    val ctrl: Boolean = false,
    /** Whether Shift must be held. */
    val shift: Boolean = false,
    /** Whether Alt/Option must be held. */
    val alt: Boolean = false,
) {
    init {
        require(key.isNotBlank()) { "Shortcut key must not be blank" }
    }

    /**
     * True when a key press (`key`, `ctrl`, `shift`, `alt`) triggers this
     * shortcut. Key names are trimmed and compared case-insensitively.
     */
    fun matches(key: String, ctrl: Boolean, shift: Boolean, alt: Boolean): Boolean =
        this.key.trim().equals(key.trim(), ignoreCase = true) &&
            this.ctrl == ctrl && this.shift == shift && this.alt == alt

    /**
     * Human-readable form, modifiers first, key last:
     * `"Ctrl+Shift+Z"`, `"Alt+P"`, `"B"`, `"ArrowLeft"`.
     */
    fun display(): String = buildString {
        if (ctrl) append("Ctrl+")
        if (alt) append("Alt+")
        if (shift) append("Shift+")
        append(key.trim().uppercase())
    }
}

/**
 * Everything a shortcut can do in the editor suite. The `TOOL_*` values map
 * 1:1 onto [DrawTool] (see [ShortcutMap.toolFor]); the rest drive canvas
 * state, history or the save flow.
 */
enum class ShortcutAction {
    TOOL_PENCIL, TOOL_ERASER, TOOL_FILL, TOOL_PICKER, TOOL_LINE, TOOL_RECT,
    TOOL_ELLIPSE, TOOL_SELECT, TOOL_MOVE,
    /** Undo one history entry. */
    UNDO,
    /** Redo one history entry. */
    REDO,
    /** Grow the brush by one pixel (key `]`). */
    BRUSH_GROW,
    /** Shrink the brush by one pixel (key `[`). */
    BRUSH_SHRINK,
    /** Zoom the canvas in one rung (`+` / `=`). */
    ZOOM_IN,
    /** Zoom the canvas out one rung (`-`). */
    ZOOM_OUT,
    /** Swap primary and secondary colors (`X`). */
    SWAP_COLORS,
    /** Mark the session saved (`Ctrl+S`). */
    SAVE,
    /** Nudge the selection one pixel left. */
    NUDGE_LEFT,
    /** Nudge the selection one pixel right. */
    NUDGE_RIGHT,
    /** Nudge the selection one pixel up. */
    NUDGE_UP,
    /** Nudge the selection one pixel down. */
    NUDGE_DOWN,
    /** Select palette slot 1..9 (the digit itself is the shortcut's key). */
    SELECT_PALETTE_SLOT,
    /** Space is held — canvas drags pan instead of drawing. */
    PAN_MODIFIER,
}

/**
 * Key binding registry: built-in defaults plus a custom override layer.
 *
 * Lookup ([find]) checks the custom layer first — an explicit [bind] always
 * wins over the default — then the defaults. All lookups are pure and
 * side-effect free, so the map is trivially testable on the JVM.
 *
 * Built-in bindings (documented contract of the editor suite):
 *
 * | Keys | Action |
 * | --- | --- |
 * | `B` `E` `G` `I` `L` `R` `O` `S` `M` | tools pencil / eraser / fill / picker / line / rect / ellipse / select / move |
 * | `Ctrl+Z` | undo |
 * | `Ctrl+Shift+Z`, `Ctrl+Y` | redo |
 * | `[` `]` | brush size -/+ |
 * | `+` / `=`, `-` | zoom in / out |
 * | `X` | swap colors |
 * | `Ctrl+S` | save |
 * | arrows | nudge selection |
 * | `1..9` | select palette slot |
 * | `Space` | pan modifier |
 */
class ShortcutMap {

    private val defaults: Map<Shortcut, ShortcutAction> = buildMap {
        put(Shortcut("B"), ShortcutAction.TOOL_PENCIL)
        put(Shortcut("E"), ShortcutAction.TOOL_ERASER)
        put(Shortcut("G"), ShortcutAction.TOOL_FILL)
        put(Shortcut("I"), ShortcutAction.TOOL_PICKER)
        put(Shortcut("L"), ShortcutAction.TOOL_LINE)
        put(Shortcut("R"), ShortcutAction.TOOL_RECT)
        put(Shortcut("O"), ShortcutAction.TOOL_ELLIPSE)
        put(Shortcut("S"), ShortcutAction.TOOL_SELECT)
        put(Shortcut("M"), ShortcutAction.TOOL_MOVE)
        put(Shortcut("Z", ctrl = true), ShortcutAction.UNDO)
        put(Shortcut("Z", ctrl = true, shift = true), ShortcutAction.REDO)
        put(Shortcut("Y", ctrl = true), ShortcutAction.REDO)
        put(Shortcut("["), ShortcutAction.BRUSH_SHRINK)
        put(Shortcut("]"), ShortcutAction.BRUSH_GROW)
        put(Shortcut("+"), ShortcutAction.ZOOM_IN)
        put(Shortcut("="), ShortcutAction.ZOOM_IN)
        put(Shortcut("-"), ShortcutAction.ZOOM_OUT)
        put(Shortcut("X"), ShortcutAction.SWAP_COLORS)
        put(Shortcut("S", ctrl = true), ShortcutAction.SAVE)
        put(Shortcut("ArrowLeft"), ShortcutAction.NUDGE_LEFT)
        put(Shortcut("ArrowRight"), ShortcutAction.NUDGE_RIGHT)
        put(Shortcut("ArrowUp"), ShortcutAction.NUDGE_UP)
        put(Shortcut("ArrowDown"), ShortcutAction.NUDGE_DOWN)
        put(Shortcut("Space"), ShortcutAction.PAN_MODIFIER)
        for (digit in 1..9) put(Shortcut(digit.toString()), ShortcutAction.SELECT_PALETTE_SLOT)
    }

    /** Host overrides; consulted before [defaults] on every lookup. */
    private val custom = LinkedHashMap<Shortcut, ShortcutAction>()

    /**
     * Resolves a key press to its action.
     *
     * @param key key name; compared case-insensitively.
     * @param ctrl whether the platform primary modifier is held.
     * @param shift whether Shift is held.
     * @param alt whether Alt/Option is held.
     * @return the bound action, or null when nothing matches.
     */
    fun find(key: String, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): ShortcutAction? =
        findShortcut(key, ctrl, shift, alt)?.let { custom[it] ?: defaults[it] }

    /**
     * The [Shortcut] record a key press resolves to (custom layer first),
     * or null. Hosts needing the trigger details — e.g. which digit a
     * [ShortcutAction.SELECT_PALETTE_SLOT] press carried — prefer this.
     */
    fun findShortcut(key: String, ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): Shortcut? =
        custom.keys.firstOrNull { it.matches(key, ctrl, shift, alt) }
            ?: defaults.keys.firstOrNull { it.matches(key, ctrl, shift, alt) }

    /**
     * Binds (or rebinds) [shortcut] to [action]. The custom layer shadows the
     * built-ins from now on; use [unbind] to restore the default.
     */
    fun bind(shortcut: Shortcut, action: ShortcutAction) {
        custom[shortcut] = action
    }

    /**
     * Removes a custom binding.
     *
     * @return true when a custom binding existed and was removed.
     */
    fun unbind(shortcut: Shortcut): Boolean = custom.remove(shortcut) != null

    /** Drops every custom binding, restoring pure default behavior. */
    fun reset() = custom.clear()

    /**
     * Effective bindings (defaults overlaid with customs), in no guaranteed
     * order — for settings UIs and documentation.
     */
    fun bindings(): List<Pair<Shortcut, ShortcutAction>> {
        val merged = LinkedHashMap<Shortcut, ShortcutAction>()
        for ((k, v) in defaults) merged[k] = v
        for ((k, v) in custom) merged[k] = v
        return merged.toList()
    }

    companion object {
        /**
         * The [DrawTool] a `TOOL_*` action selects, or null for non-tool
         * actions. Keeps the shortcut layer aligned with the real
         * [CanvasState] tool enum instead of stringly-typed mapping.
         */
        fun toolFor(action: ShortcutAction): DrawTool? = when (action) {
            ShortcutAction.TOOL_PENCIL -> DrawTool.PENCIL
            ShortcutAction.TOOL_ERASER -> DrawTool.ERASER
            ShortcutAction.TOOL_FILL -> DrawTool.FILL
            ShortcutAction.TOOL_PICKER -> DrawTool.PICKER
            ShortcutAction.TOOL_LINE -> DrawTool.LINE
            ShortcutAction.TOOL_RECT -> DrawTool.RECT
            ShortcutAction.TOOL_ELLIPSE -> DrawTool.ELLIPSE
            ShortcutAction.TOOL_SELECT -> DrawTool.SELECT
            ShortcutAction.TOOL_MOVE -> DrawTool.MOVE
            else -> null
        }
    }
}

/**
 * Keyboard interception wrapper for the whole editor UI.
 *
 * Every key event preview is resolved through [map]; a match dispatches
 * [onAction] and the event is considered consumed, unmatched events pass
 * through to the children untouched.
 *
 * Stub-world note: the compile-only `KeyEvent` stub normalizes a key press
 * to (`key name`, ctrl/shift/alt flags) with no down/up distinction, so
 * [ShortcutAction.PAN_MODIFIER] fires on the Space *press* — a real-Compose
 * host bridges platform key events (key + `isCtrlPressed`/`isShiftPressed`/
 * `isAltPressed` extensions) into the same [ShortcutMap.find] call and may
 * additionally track key-up to release the pan mode.
 *
 * @param theme active theme (reserved for future shortcut-help chrome).
 * @param map binding registry.
 * @param onAction invoked for every resolved shortcut.
 * @param modifier host modifier.
 * @param onShortcut additionally invoked with the matched [Shortcut] record
 *   (additive defaulted param; hosts that need trigger details — e.g. which
 *   digit a [ShortcutAction.SELECT_PALETTE_SLOT] carried — read
 *   `shortcut.key`).
 * @param content the wrapped UI, usually the entire editor scaffold.
 */
@Composable
fun ShortcutLayer(
    theme: PixelTheme,
    map: ShortcutMap,
    onAction: (ShortcutAction) -> Unit,
    modifier: Modifier = Modifier,
    onShortcut: (Shortcut) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val currentOnAction = rememberUpdatedState(onAction)
    val currentOnShortcut = rememberUpdatedState(onShortcut)
    Box(
        modifier = modifier.onPreviewKeyEvent { event: KeyEvent ->
            val shortcut = map.findShortcut(
                event.key.keyName,
                event.isCtrlPressed,
                event.isShiftPressed,
                event.isAltPressed,
            )
            if (shortcut != null) {
                val action = map.find(
                    event.key.keyName,
                    event.isCtrlPressed,
                    event.isShiftPressed,
                    event.isAltPressed,
                )
                if (action != null) {
                    currentOnShortcut.value.invoke(shortcut)
                    currentOnAction.value.invoke(action)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        },
    ) {
        content()
    }
}
