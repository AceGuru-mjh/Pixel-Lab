package com.pixellab.core.animation

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The seven parametric easing curves used by the animation layer.
 *
 * Each curve maps a normalized progress `t` in `[0, 1]` to a progress value
 * in `[0, 1]` (see [at]; overshooting curves such as [ELASTIC_OUT] are
 * clamped back into that range by contract). Curves are pure functions: the
 * same `t` always yields the same value, on any platform, with no state.
 *
 * Evaluation is Float-in/Float-out; the internal math for the transcendental
 * curves ([ELASTIC_OUT], [BOUNCE_OUT]) runs in Double for precision.
 */
enum class Easing {

    /** Identity: `y = t`. Constant speed. */
    LINEAR,

    /**
     * Quadratic ease-in: `y = t^2`. Starts at standstill, accelerates to
     * full speed — the classic "winding up" half of an anticipation motion.
     */
    EASE_IN,

    /**
     * Quadratic ease-out: `y = 1 - (1 - t)^2`. Starts at full speed and
     * decelerates into the target — the natural default for settling
     * motions such as drops and landings.
     */
    EASE_OUT,

    /**
     * Quadratic ease-in-out: `y = 2t^2` for `t < 0.5`, otherwise
     * `y = 1 - 2(1 - t)^2`. Smooth standstill at both ends, continuous at
     * `t = 0.5` where the curve passes exactly through `0.5`.
     */
    EASE_IN_OUT,

    /**
     * Elastic ease-out (decaying sine):
     * `y = 2^(-10t) * sin((10t - 0.75) * 2pi/3) + 1`.
     *
     * The term `2^(-10t)` decays exponentially while the sine oscillates
     * around 1, producing the springy "arrive, overshoot, oscillate, settle"
     * shape. The raw curve overshoots above 1 mid-flight; per this library's
     * evaluation contract ([at] clamps its output into `[0, 1]`) the
     * overshoot peaks are flattened to 1.
     */
    ELASTIC_OUT,

    /**
     * Bounce ease-out (piecewise parabolas): the timeline is split at
     * `1/2.75`, `2/2.75` and `2.5/2.75` into four segments; each segment
     * evaluates `y = 7.5625 * (t - k)^2 + c` with offsets `k` of
     * `0`, `1.5/2.75`, `2.25/2.75`, `2.625/2.75` and pedestals `c` of
     * `0`, `0.75`, `0.9375`, `0.984375`. The result is a ball bouncing
     * on a floor with geometrically shrinking hops; at `t = 1` the parabola
     * closes exactly on `1`.
     */
    BOUNCE_OUT,

    /**
     * Hard step with a 0.5 threshold: `y = 0` for `t < 0.5`, `y = 1` for
     * `t >= 0.5`. No interpolation at all — the output snaps exactly once,
     * halfway through the interval.
     */
    STEP,
}

/**
 * Evaluates this curve at normalized progress [t].
 *
 * The input is clamped into `[0, 1]` first (values below 0 evaluate as 0,
 * values above 1 as 1) and the output is clamped into `[0, 1]` last, so the
 * function is total: any Float produces a well-defined progress value.
 * Formulas per curve are documented on the [Easing] constants; in short:
 * linear identity, quadratic ease in/out/in-out, elastic decaying sine,
 * piecewise-parabolic bounce and a 0.5-threshold step.
 *
 * @param t progress in `[0, 1]`; out-of-range values are clamped.
 * @return the eased progress, always within `[0, 1]`.
 */
fun Easing.at(t: Float): Float {
    val u = t.coerceIn(0f, 1f).toDouble()
    val raw = when (this) {
        Easing.LINEAR -> u
        Easing.EASE_IN -> u * u
        Easing.EASE_OUT -> 1.0 - (1.0 - u) * (1.0 - u)
        Easing.EASE_IN_OUT ->
            if (u < 0.5) 2.0 * u * u else 1.0 - 2.0 * (1.0 - u) * (1.0 - u)
        Easing.ELASTIC_OUT ->
            2.0.pow(-10.0 * u) * sin((u * 10.0 - 0.75) * (2.0 * PI / 3.0)) + 1.0
        Easing.BOUNCE_OUT -> {
            val n1 = 7.5625
            val d1 = 2.75
            when {
                u < 1.0 / d1 -> n1 * u * u
                u < 2.0 / d1 -> {
                    val t2 = u - 1.5 / d1
                    n1 * t2 * t2 + 0.75
                }
                u < 2.5 / d1 -> {
                    val t2 = u - 2.25 / d1
                    n1 * t2 * t2 + 0.9375
                }
                else -> {
                    val t2 = u - 2.625 / d1
                    n1 * t2 * t2 + 0.984375
                }
            }
        }
        Easing.STEP -> if (u < 0.5) 0.0 else 1.0
    }
    return raw.toFloat().coerceIn(0f, 1f)
}

/**
 * Samples this curve at [steps] evenly spaced points, **including both
 * endpoints**: element `i` is `at(i / (steps - 1))`, so the first sample is
 * always `0` and the last is always `1` for every curve (the elastic tail
 * reaches 1 only thanks to [at]'s output clamp).
 *
 * The samples are the keyframe progress values a host can use to tween
 * between two pinned animation frames over `steps` frames.
 *
 * @param steps number of samples; must be >= 2 so the sequence can contain
 * a first and a last value.
 * @return `[steps]` progress values in `[0, 1]`, first `0`, last `1`.
 * @throws IllegalArgumentException if [steps] < 2.
 */
fun Easing.sample(steps: Int): List<Float> {
    require(steps >= 2) { "steps must be >= 2 (was $steps)" }
    val last = steps - 1
    return List(steps) { i -> at(i.toFloat() / last) }
}

/**
 * Tolerant name lookup companion for [Easing].
 *
 * Hosts that persist easing choices as user-facing strings (settings files,
 * tool descriptions, script parameters) resolve them back through
 * [valueOf], which matches case-insensitively after trimming surrounding
 * whitespace and returns null instead of throwing for unknown input.
 */
object Easings {

    /**
     * Resolves [name] to an [Easing] constant, ignoring case and surrounding
     * whitespace (`"ease_out"`, `"EaseOut"` and `" EASE_OUT "` all match
     * [Easing.EASE_OUT]).
     *
     * @param name candidate curve name; null/blank-safe.
     * @return the matching constant, or null when no curve has that name.
     */
    fun valueOf(name: String): Easing? {
        val key = name.trim().uppercase()
        return Easing.values().firstOrNull { it.name == key }
    }
}
