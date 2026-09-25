package com.pixellab.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Semantic color palette of the Pixel Lab editor suite, expressed as packed
 * ARGB `Int` values so it stays usable from pure-JVM (non-Compose) code —
 * tests and logic layers can assert on plain ints. Compose-facing call sites
 * lift any field through [argbColor].
 *
 * The palette is dark-first and deliberately warm: primary is a saturated
 * amber, accent a teal — no blue/indigo defaults, per the Pixel Lab design
 * brief. Semantic slots:
 *
 *  * `background` / `surface` / `elevated` — the three depth tiers of panels;
 *  * `primary` — the amber focus color (selection, active states);
 *  * `accent` — the teal contrast color for secondary emphasis;
 *  * `textPrimary` / `textSecondary` / `textDisabled` — ink hierarchy;
 *  * `canvasCheckerLight` / `canvasCheckerDark` — transparency checkerboard;
 *  * `gridLine` — the per-pixel canvas grid (typically semi-transparent);
 *  * `selectionAntsA` / `selectionAntsB` — the two marching-ants dashes;
 *  * `danger` / `success` — destructive / affirmative feedback.
 */
data class PixelThemeColors(
    val background: Int,
    val surface: Int,
    val elevated: Int,
    val primary: Int,
    val accent: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textDisabled: Int,
    val canvasCheckerLight: Int,
    val canvasCheckerDark: Int,
    val gridLine: Int,
    val selectionAntsA: Int,
    val selectionAntsB: Int,
    val danger: Int,
    val success: Int,
) {
    init {
        // Every field is a packed ARGB int; structural colors (surfaces, ink,
        // primary/accent/danger/success, checkerboard, ants) must be fully
        // opaque so panel chrome composites predictably — translucent values
        // are reserved for overlay colors like gridLine. Packed ints with
        // alpha >= 0x80 are negative as Int, so the check reads the alpha
        // byte instead of comparing the raw value.
        for (c in intArrayOf(
            background, surface, elevated, primary, accent,
            textPrimary, textSecondary, textDisabled,
            canvasCheckerLight, canvasCheckerDark,
            selectionAntsA, selectionAntsB, danger, success,
        )) {
            require((c ushr 24) == 0xFF) {
                "Theme color must be opaque ARGB: 0x${(c.toLong() and 0xFFFFFFFFL).toString(16)}"
            }
        }
    }
}

/**
 * Layout metrics shared by every editor-suite component.
 *
 * [controlHeight] is the 44dp minimum touch target (accessibility guidance);
 * [compactFactor] scales secondary chrome down when a host needs a denser
 * look.
 */
data class PixelThemeMetrics(
    val panelPadding: androidx.compose.ui.unit.Dp,
    val panelGap: androidx.compose.ui.unit.Dp,
    val controlHeight: androidx.compose.ui.unit.Dp,
    val iconSize: androidx.compose.ui.unit.Dp,
    val compactFactor: Float,
) {
    init {
        require(panelPadding.value >= 0f) { "panelPadding must be >= 0dp" }
        require(panelGap.value >= 0f) { "panelGap must be >= 0dp" }
        require(controlHeight.value >= 44f) { "controlHeight must stay >= 44dp (touch target)" }
        require(iconSize.value > 0f) { "iconSize must be > 0dp" }
        require(compactFactor > 0f && compactFactor <= 1f) { "compactFactor must be in (0, 1]" }
    }
}

/** Which of the built-in [PixelTheme] presets to use. */
enum class ThemeScheme { DARK, LIGHT }

/**
 * A resolved theme: colors + metrics + provenance. Components of the editor
 * suite ([com.pixellab.ui.PixelEditorScaffold] and friends) take a
 * [PixelTheme] as an explicit parameter instead of reading ambient state.
 */
class PixelTheme(
    /** Semantic colors; all packed ARGB ints, see [PixelThemeColors]. */
    val colors: PixelThemeColors,
    /** Shared layout metrics; see [PixelThemeMetrics]. */
    val metrics: PixelThemeMetrics,
    /** Which scheme this theme was built from. */
    val scheme: ThemeScheme = ThemeScheme.DARK,
) {
    // ---- Compose conveniences (Color lifts of the packed ints) -------------
    /** [PixelThemeColors.background] as a Compose [Color]. */
    val background: Color get() = colors.background.argbColor()
    /** [PixelThemeColors.surface] as a Compose [Color]. */
    val surface: Color get() = colors.surface.argbColor()
    /** [PixelThemeColors.elevated] as a Compose [Color]. */
    val elevated: Color get() = colors.elevated.argbColor()
    /** [PixelThemeColors.primary] as a Compose [Color]. */
    val primary: Color get() = colors.primary.argbColor()
    /** [PixelThemeColors.accent] as a Compose [Color]. */
    val accent: Color get() = colors.accent.argbColor()
    /** [PixelThemeColors.textPrimary] as a Compose [Color]. */
    val textPrimary: Color get() = colors.textPrimary.argbColor()
    /** [PixelThemeColors.textSecondary] as a Compose [Color]. */
    val textSecondary: Color get() = colors.textSecondary.argbColor()
    /** [PixelThemeColors.textDisabled] as a Compose [Color]. */
    val textDisabled: Color get() = colors.textDisabled.argbColor()
    /** [PixelThemeColors.danger] as a Compose [Color]. */
    val danger: Color get() = colors.danger.argbColor()
    /** [PixelThemeColors.success] as a Compose [Color]. */
    val success: Color get() = colors.success.argbColor()
}

/**
 * Converts a packed ARGB int into a Compose [Color].
 *
 * The editor suite stores every color as a plain `Int` (so pure-JVM logic
 * and tests can reason about it); this extension is the single funnel used
 * at the Compose boundary.
 */
fun Int.argbColor(): Color = Color(this)

/** Ambient [PixelTheme] slot for hosts that prefer implicit theming. */
val LocalPixelTheme: androidx.compose.runtime.ProvidableCompositionLocal<PixelTheme> =
    staticCompositionLocalOf { PixelThemes.DARK }

/**
 * Built-in theme presets.
 *
 * Note on [LocalPixelTheme]: the compile-only stub family models
 * `staticCompositionLocalOf(default)` as a *value* parameter (real Compose
 * takes a `() -> T` factory) and provides no `CompositionLocalProvider`
 * composable nor a `.current` accessor, so the PR10 components thread
 * [PixelTheme] through explicit parameters. `LocalPixelTheme` is still
 * declared with the real Compose type
 * (`ProvidableCompositionLocal<PixelTheme>`) so hosts running against the
 * real library may install it with `CompositionLocalProvider` and read
 * `LocalPixelTheme.current` — the explicit parameter remains the source of
 * truth for the suite.
 */
object PixelThemes {

    /** Warm dark theme: near-black surfaces, amber primary, teal accent. */
    val DARK: PixelTheme = PixelTheme(
        PixelThemeColors(
            background = 0xFF14161A.toInt(),
            surface = 0xFF1C1F24.toInt(),
            elevated = 0xFF24282E.toInt(),
            primary = 0xFFFFB454.toInt(),
            accent = 0xFF29C9B0.toInt(),
            textPrimary = 0xFFF2EDE4.toInt(),
            textSecondary = 0xFFA8A29A.toInt(),
            textDisabled = 0xFF5C5952.toInt(),
            canvasCheckerLight = 0xFF3A3E44.toInt(),
            canvasCheckerDark = 0xFF2A2D33.toInt(),
            gridLine = 0x33FFFFFF,
            selectionAntsA = 0xFFFFFFFF.toInt(),
            selectionAntsB = 0xFF000000.toInt(),
            danger = 0xFFFF5D5D.toInt(),
            success = 0xFF71D98B.toInt(),
        ),
        PixelThemeMetrics(
            panelPadding = 12.dp,
            panelGap = 8.dp,
            controlHeight = 44.dp,
            iconSize = 24.dp,
            compactFactor = 0.75f,
        ),
        ThemeScheme.DARK,
    )

    /** Warm light theme: paper surfaces, deep amber primary, deep teal accent. */
    val LIGHT: PixelTheme = PixelTheme(
        PixelThemeColors(
            background = 0xFFF4F1EA.toInt(),
            surface = 0xFFFBF9F4.toInt(),
            elevated = 0xFFFFFFFF.toInt(),
            primary = 0xFFB96A00.toInt(),
            accent = 0xFF0E8A7B.toInt(),
            textPrimary = 0xFF22242A.toInt(),
            textSecondary = 0xFF6B6A66.toInt(),
            textDisabled = 0xFFB4B1AA.toInt(),
            canvasCheckerLight = 0xFFE8E4DC.toInt(),
            canvasCheckerDark = 0xFFC9C4BA.toInt(),
            gridLine = 0x33202020,
            selectionAntsA = 0xFFFFFFFF.toInt(),
            selectionAntsB = 0xFF000000.toInt(),
            danger = 0xFFC23A3A.toInt(),
            success = 0xFF2E7D44.toInt(),
        ),
        PixelThemeMetrics(
            panelPadding = 12.dp,
            panelGap = 8.dp,
            controlHeight = 44.dp,
            iconSize = 24.dp,
            compactFactor = 0.75f,
        ),
        ThemeScheme.LIGHT,
    )

    /** Resolves the preset for [scheme]. */
    fun forScheme(scheme: ThemeScheme): PixelTheme = when (scheme) {
        ThemeScheme.DARK -> DARK
        ThemeScheme.LIGHT -> LIGHT
    }
}

/** Corner radius of [PixelPanel] surfaces. */
private val PanelCorner = 6.dp

/** Height of the [PixelPanel] title bar. */
private val PanelTitleHeight = 28.dp

/**
 * Panel chrome: [PixelTheme.colors.surface] background, hairline border and
 * an optional title bar with a primary accent notch. The PR10 suite builds
 * every floating region (color, palette, layers, history, timeline) on top of
 * this container so depth and spacing stay consistent.
 *
 * @param theme active theme.
 * @param modifier host modifier.
 * @param title optional header label; the panel grows a title bar when set.
 * @param content panel body inside [PixelThemeMetrics.panelPadding].
 */
@Composable
fun PixelPanel(
    theme: PixelTheme,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PanelCorner)
    Column(
        modifier = modifier
            .background(theme.surface, shape)
            .border(
                width = 1.dp,
                color = theme.elevated,
                shape = shape,
            )
            .padding(theme.metrics.panelPadding),
    ) {
        if (title != null) {
            Row(modifier = Modifier.height(PanelTitleHeight)) {
                Column(
                    modifier = Modifier
                        .background(theme.primary, RoundedCornerShape(1.dp))
                        .padding(horizontal = 2.dp),
                ) {}
                Text(
                    text = title,
                    fontSize = 11.sp,
                    color = theme.textSecondary,
                )
            }
            Spacer(modifier = Modifier.height(theme.metrics.panelGap))
        }
        content()
    }
}

/**
 * Modifier-only variant of the [PixelPanel] chrome (background + border) for
 * hosts that need the panel look without the container layout.
 */
fun Modifier.pixelPanel(theme: PixelTheme): Modifier = this
    .background(theme.surface, RoundedCornerShape(PanelCorner))
    .border(1.dp, theme.elevated, RoundedCornerShape(PanelCorner))

/**
 * Small caps-style section label used inside panels.
 *
 * @param theme active theme.
 * @param label text to render.
 * @param modifier host modifier.
 */
@Composable
internal fun PixelSectionLabel(theme: PixelTheme, label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        modifier = modifier,
        fontSize = 10.sp,
        color = theme.textSecondary,
    )
}
