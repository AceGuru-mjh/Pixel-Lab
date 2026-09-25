package com.pixellab.core.animation

import com.pixellab.core.PixelLabConfig
import com.pixellab.core.model.AnimationTag
import com.pixellab.core.model.Frame
import com.pixellab.core.model.PixelFrame
import com.pixellab.core.model.SpriteProject
import com.pixellab.core.palette.LabColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Direction of [AnimationEffects.fade]: vanish towards transparent or appear from it. */
enum class FadeDirection {
    /** Fade out: overall alpha walks the staircase `0xFF -> 0x00`. */
    OUT,
    /** Fade in: overall alpha walks the staircase `0x00 -> 0xFF` (the reverse of [OUT]). */
    IN,
}

/**
 * Procedural animation effects for [SpriteProject]: a library of 14
 * deterministic, whole-timeline transforms (bounce, shake, wave, fade,
 * squash, stretch, pulse, orbit, float, glow pulse, scanline, typewriter,
 * blink, rainbow shift) plus a fault-tolerant name-based dispatcher
 * ([apply]).
 *
 * The class is stateless and holds no mutable state of its own: every
 * operation validates its arguments first and then returns a **new**
 * [SpriteProject] built through the model's immutable `with*` copy family;
 * the input project is never modified. It deliberately coexists with
 * [AnimationEngine] — [AnimationEngine.breathe] stays the canonical
 * translation-based idle effect and no file of that engine is touched by
 * this library.
 *
 * Shared conventions for all effects (each effect's KDoc declares its exact
 * frame structure):
 *  * Derived frames receive freshly allocated ids from the project's id
 *    allocator (`SpriteProject.allocateFrameId`), so frame ids stay unique
 *    across the session; source frames that are kept verbatim keep their
 *    ids and cel references.
 *  * Per-cel translation is expressed with [PixelFrame.shifted], which is
 *    row-equivalent displacement: rows move as a whole, rows shifted past
 *    the canvas edge are lost and the vacated strip becomes transparent.
 *    Every cel of every layer is transformed individually, so layer
 *    structure and per-frame `durationMs` overrides survive.
 *  * Each effect installs a tag named after itself spanning the whole
 *    resulting timeline (replacing any previous tag of that name, keeping
 *    all other tags; no effect shrinks the timeline, so foreign tag spans
 *    stay valid).
 *  * Amplitudes and repetition counts are guarded with
 *    [IllegalArgumentException] carrying a clear message; amplitudes larger
 *    than the canvas are not errors — they simply shift content off-canvas,
 *    exactly like [AnimationEngine.breathe].
 *  * Everything is deterministic: the same project and parameters always
 *    yield the same output, and exactly one debug log line per call is
 *    emitted through [PixelLabConfig.effectiveLogger].
 *
 * @param config library configuration; used solely as the diagnostics sink
 * for the single debug line each effect emits.
 */
class AnimationEffects(private val config: PixelLabConfig) {

    // ---- displacement effects ------------------------------------------------

    /**
     * Bounce (fall-and-rebound) expansion of the timeline.
     *
     * **Frame structure — replaces the timeline:** for each of the `n`
     * source frames, `(bounces + 1) * 2 + 1` phase frames are generated, so
     * the result has `n * phases` frames; result frame `i * phases + p`
     * derives from source frame `i` at phase `p`. The vertical displacement
     * of phase `p` (positive = downwards) is the pinned keyframe sequence
     * `0, amp, 0, amp/2, 0, ...`: even phases rest at 0, odd phase
     * `p = 2j + 1` drops to the `j`-th peak height
     * `round(amp / 2^j)` — `(bounces + 1)` halving peaks in total. Peaks
     * that round to 0 render as additional rest frames (graceful
     * degradation, never an error).
     *
     * The [easing] curve is the tweening policy hosts apply **between** the
     * pinned phase keyframes at playback time (e.g. through a resampling
     * player); the generated keyframes themselves are pinned because every
     * curve evaluates to exactly 1 at a segment endpoint. It is echoed in
     * the debug log line. [Easing.STEP] yields the same keyframes.
     *
     * A tag named `"bounce"` spans the whole result. Per-cel displacement
     * uses [PixelFrame.shifted]; duration and layer structure are
     * preserved.
     *
     * @param project project to animate; never mutated.
     * @param amplitudePx first drop depth in pixels; >= 1.
     * @param bounces number of halving rebounds after the initial drop;
     * `1..30` (the `2^j` peak divisor must stay inside Int range).
     * @param easing playback tweening curve between keyframes.
     * @return a new project with `n * ((bounces + 1) * 2 + 1)` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun bounce(
        project: SpriteProject,
        amplitudePx: Int = 2,
        bounces: Int = 2,
        easing: Easing = Easing.EASE_OUT,
    ): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        require(bounces in 1..MAX_BOUNCES) { "bounces must be in [1, $MAX_BOUNCES] (was $bounces)" }
        val phases = (bounces + 1) * 2 + 1
        val drop = IntArray(phases) { p ->
            if (p % 2 == 1) roundNearest(amplitudePx.toFloat() / (1 shl (p / 2))) else 0
        }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * phases)
        for (source in project.frames) {
            for (p in 0 until phases) {
                frames.add(Frame(id = nextId, cels = shiftCels(source.cels, 0, drop[p]), durationMs = source.durationMs))
                nextId++
            }
        }
        return finish(
            project, frames, "bounce",
            "bounce: $n frame(s) -> ${frames.size} frames ($phases phases each, amplitude ${amplitudePx}px, " +
                "$bounces bounces, easing ${easing.name}), tag 'bounce' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Horizontal jitter (earthquake) replacement of the timeline.
     *
     * **Frame structure — replaces the timeline:** each source frame expands
     * into [speed] sub-frames, so the result has `n * speed` frames; result
     * frame `i * speed + k` derives from source frame `i` shifted
     * horizontally by `+amplitudePx` for even `k` and `-amplitudePx` for
     * odd `k` (a strict ±amp alternation, starting to the right).
     *
     * Displacement per cel goes through [PixelFrame.shifted]; duration and
     * layer structure are preserved. A tag named `"shake"` spans the whole
     * result.
     *
     * @param project project to animate; never mutated.
     * @param amplitudePx horizontal jitter amplitude in pixels; >= 1.
     * @param speed shake sub-frames generated per source frame; `2..1024`
     * (an alternation needs at least two sub-frames).
     * @return a new project with `n * speed` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun shake(project: SpriteProject, amplitudePx: Int = 1, speed: Int = 2): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        require(speed in 2..MAX_REPEAT) { "speed must be in [2, $MAX_REPEAT] (was $speed)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * speed)
        for (source in project.frames) {
            for (k in 0 until speed) {
                val dx = if (k % 2 == 0) amplitudePx else -amplitudePx
                frames.add(Frame(id = nextId, cels = shiftCels(source.cels, dx, 0), durationMs = source.durationMs))
                nextId++
            }
        }
        return finish(
            project, frames, "shake",
            "shake: $n frame(s) -> ${frames.size} frames ($speed sub-frames each, amplitude ${amplitudePx}px), " +
                "tag 'shake' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Sine displacement wave, either uniform or per-row.
     *
     * **Frame structure — replaces the timeline, frame for frame:** result
     * frame `k` is source frame `k` displaced by
     * `round(sin(2*PI*k / wavelengthFrames) * amplitudePx)`:
     *  * [horizontal] = true — the whole frame shifts horizontally by the
     *    sine of the frame index (a uniform sway).
     *  * [horizontal] = false — each row `y` shifts horizontally by
     *    `round(sin(2*PI*(k + y) / wavelengthFrames) * amplitudePx)`, a
     *    per-row displacement in which the wave crests travel down the
     *    sprite over time (a vertical-direction wave, flag/jelly style).
     *
     * Both variants displace rows through [PixelFrame.shifted] or its
     * per-row equivalent, preserving layer structure and duration. A tag
     * named `"wave"` spans the whole result. Note that integer frame
     * sampling makes wavelengths of 1 or 2 produce all-zero displacement;
     * [wavelengthFrames] is therefore guarded at >= 2 (2 stays a
     * deterministic, if motionless, degenerate).
     *
     * @param project project to animate; never mutated.
     * @param amplitudePx wave amplitude in pixels; >= 1.
     * @param wavelengthFrames sine period in frames; >= 2.
     * @param horizontal true for a uniform horizontal sway, false for the
     * per-row vertical-direction wave.
     * @return a new project with the same `n` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun wave(
        project: SpriteProject,
        amplitudePx: Int = 1,
        wavelengthFrames: Int = 4,
        horizontal: Boolean = true,
    ): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        require(wavelengthFrames >= 2) { "wavelengthFrames must be >= 2 (was $wavelengthFrames)" }
        var nextId = project.allocateFrameId()
        val frames = project.frames.mapIndexed { k, source ->
            val cels = if (horizontal) {
                val dx = roundNearest((sin(TWO_PI * k / wavelengthFrames) * amplitudePx).toFloat())
                shiftCels(source.cels, dx, 0)
            } else {
                rowWaveCels(source.cels, k, amplitudePx, wavelengthFrames)
            }
            Frame(id = nextId, cels = cels, durationMs = source.durationMs).also { nextId++ }
        }
        return finish(
            project, frames, "wave",
            "wave: ${project.frameCount} frame(s) -> ${frames.size} frames (${if (horizontal) "uniform horizontal" else "per-row vertical-direction"}" +
                " wave, amplitude ${amplitudePx}px, wavelength $wavelengthFrames), tag 'wave' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Slow vertical bobbing — the low-frequency vertical specialization of
     * [wave].
     *
     * **Frame structure — replaces the timeline, frame for frame:** result
     * frame `k` is source frame `k` shifted vertically by
     * `round(sin(2*PI*k / wavelengthFrames) * amplitudePx)`; with the
     * default wavelength of 8 frames the sprite bobs up and down gently
     * over time (a lazier, vertical cousin of [AnimationEngine.breathe],
     * which uses discrete fixed offsets instead of a sine). A tag named
     * `"float"` spans the whole result.
     *
     * @param project project to animate; never mutated.
     * @param amplitudePx bobbing amplitude in pixels; >= 1.
     * @param wavelengthFrames sine period in frames; >= 2.
     * @return a new project with the same `n` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun float(project: SpriteProject, amplitudePx: Int = 1, wavelengthFrames: Int = 8): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        require(wavelengthFrames >= 2) { "wavelengthFrames must be >= 2 (was $wavelengthFrames)" }
        var nextId = project.allocateFrameId()
        val frames = project.frames.mapIndexed { k, source ->
            val dy = roundNearest((sin(TWO_PI * k / wavelengthFrames) * amplitudePx).toFloat())
            Frame(id = nextId, cels = shiftCels(source.cels, 0, dy), durationMs = source.durationMs).also { nextId++ }
        }
        return finish(
            project, frames, "float",
            "float: ${project.frameCount} frame(s) -> ${frames.size} frames (vertical sine bob, " +
                "amplitude ${amplitudePx}px, wavelength $wavelengthFrames), tag 'float' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Circular orbit around each frame's rest position.
     *
     * **Frame structure — originals kept at the front:** the `n` original
     * frames stay at indices `0..n-1`, then one block of [steps] orbit
     * phase frames is appended per source frame (source `i`'s block starts
     * at `n + i * steps`), for `n * (1 + steps)` frames total. Phase `k` of
     * a source frame is translated by `(round(cos(theta)*r),
     * round(sin(theta)*r))` with `theta = 2*PI*k / steps`; phase 0 starts
     * at `(r, 0)` — one pixel step to the right of rest — and positive y
     * runs downwards, so the default 8 steps trace a pixel-rounded circle
     * `(+r,0), (+r,+r), (0,+r), (-r,+r), (-r,0), (-r,-r), (0,-r), (+r,-r)`.
     *
     * Translation per cel uses [PixelFrame.shifted]; duration and layer
     * structure are preserved. A tag named `"orbit"` spans the whole
     * result.
     *
     * @param project project to animate; never mutated.
     * @param radiusPx orbit radius in pixels; >= 1.
     * @param steps phase frames per source frame; `2..1024`.
     * @return a new project with `n * (steps + 1)` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun orbit(project: SpriteProject, radiusPx: Int = 2, steps: Int = 8): SpriteProject {
        require(radiusPx >= 1) { "radiusPx must be >= 1 (was $radiusPx)" }
        require(steps in 2..MAX_REPEAT) { "steps must be in [2, $MAX_REPEAT] (was $steps)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val orbitFrames = ArrayList<Frame>(n * steps)
        for (source in project.frames) {
            for (k in 0 until steps) {
                val theta = TWO_PI * k / steps
                val dx = roundNearest((cos(theta) * radiusPx).toFloat())
                val dy = roundNearest((sin(theta) * radiusPx).toFloat())
                orbitFrames.add(
                    Frame(id = nextId, cels = shiftCels(source.cels, dx, dy), durationMs = source.durationMs),
                )
                nextId++
            }
        }
        val frames = project.frames + orbitFrames
        return finish(
            project, frames, "orbit",
            "orbit: $n frame(s) -> ${frames.size} frames (radius ${radiusPx}px, $steps steps per source), " +
                "tag 'orbit' spans 0..${frames.lastIndex}",
        )
    }

    // ---- alpha effects -------------------------------------------------------

    /**
     * Whole-animation fade towards or from transparency.
     *
     * **Frame structure — replaces the timeline:** the result has
     * `n * steps` frames. Result frame `g` shows source frame
     * `g mod n` — the animation keeps looping — at overall alpha level
     * `floor(g / n)`: a linear staircase of [steps] levels walking
     * `0xFF -> 0x00` for [FadeDirection.OUT] and `0x00 -> 0xFF` for
     * [FadeDirection.IN], each level held for the `n` frames of one loop.
     * For a single-frame project this degenerates to the plain
     * frame-by-frame fade. Level 0 produces canonical `0x00000000` pixels
     * (fully transparent, not merely zero-alpha RGB).
     *
     * Scaling is applied per cel (layer structure and duration preserved):
     * a pixel with alpha `a` at level `L` becomes alpha `(a * L) / 255`,
     * RGB untouched. A tag named `"fade"` spans the whole result.
     *
     * @param project project to animate; never mutated.
     * @param steps number of alpha staircase levels; `2..1024`.
     * @param direction fade direction.
     * @return a new project with `n * steps` frames.
     * @throws IllegalArgumentException if [steps] is out of range.
     */
    fun fade(
        project: SpriteProject,
        steps: Int = 4,
        direction: FadeDirection = FadeDirection.OUT,
    ): SpriteProject {
        require(steps in 2..MAX_REPEAT) { "steps must be in [2, $MAX_REPEAT] (was $steps)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * steps)
        for (g in 0 until n * steps) {
            val levelIndex = g / n
            val source = project.frames[g % n]
            val level = when (direction) {
                FadeDirection.OUT -> roundNearest(255f * (steps - 1 - levelIndex) / (steps - 1))
                FadeDirection.IN -> roundNearest(255f * levelIndex / (steps - 1))
            }
            frames.add(Frame(id = nextId, cels = source.cels.mapValues { scaleAlpha(it.value, level) }, durationMs = source.durationMs))
            nextId++
        }
        return finish(
            project, frames, "fade",
            "fade: $n frame(s) -> ${frames.size} frames ($steps alpha levels, ${direction.name}), " +
                "tag 'fade' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Blink: alternates each source frame with fully transparent holds.
     *
     * **Frame structure — originals interleaved (as specified):** every
     * source frame is followed by [closedFrames] blank frames, so the
     * result has `n * (1 + closedFrames)` frames; source `i` lands at index
     * `i * (1 + closedFrames)`, its blanks right after it. Blank frames
     * carry the same cel keys as their source (each rendered as a fresh
     * `PixelFrame.blank`) and the source's `durationMs`, so the blink
     * cadence follows the frame it interrupts.
     *
     * A tag named `"blink"` spans the whole result.
     *
     * @param project project to animate; never mutated.
     * @param closedFrames blank frames inserted after each source frame;
     * `1..1024`.
     * @return a new project with `n * (1 + closedFrames)` frames.
     * @throws IllegalArgumentException if [closedFrames] is out of range.
     */
    fun blink(project: SpriteProject, closedFrames: Int = 1): SpriteProject {
        require(closedFrames in 1..MAX_REPEAT) { "closedFrames must be in [1, $MAX_REPEAT] (was $closedFrames)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * (1 + closedFrames))
        for (source in project.frames) {
            frames.add(source)
            repeat(closedFrames) {
                frames.add(
                    Frame(
                        id = nextId,
                        cels = source.cels.mapValues { PixelFrame.blank(it.value.width, it.value.height) },
                        durationMs = source.durationMs,
                    ),
                )
                nextId++
            }
        }
        return finish(
            project, frames, "blink",
            "blink: $n frame(s) -> ${frames.size} frames ($closedFrames blank(s) after each), " +
                "tag 'blink' spans 0..${frames.lastIndex}",
        )
    }

    // ---- scale effects -------------------------------------------------------

    /**
     * Squash: flattens each frame's content against its ground line.
     *
     * **Frame structure — originals kept at the front:** the `n` originals
     * stay at indices `0..n-1`, followed by their squashed counterparts at
     * `n..2n-1`, for `2n` frames total.
     *
     * Each squashed cel resamples the frame's content bounding box (the
     * union of non-transparent pixels across all cels, shared as one anchor
     * so layers stay aligned) by `height * factor` and `width / factor`
     * with `factor = factorNum / factorDen` — nearest-neighbor row and
     * column sampling, so the content area is preserved ("area feel");
     * `factor = 1/2` halves the content height and doubles its width. The
     * scaled box is anchored **bottom-aligned** (its bottom row stays on
     * the content's ground row) and horizontally centered on the source
     * box (half the difference, truncated toward zero); parts growing past
     * the canvas clip, vacated strips become transparent. Degenerate
     * single-row/column targets sample the source box middle. Frames with
     * no content pass through unchanged. A tag named `"squash"` spans the
     * result.
     *
     * @param project project to animate; never mutated.
     * @param factorNum squash factor numerator; `1..1024`.
     * @param factorDen squash factor denominator; `1..1024`.
     * @return a new project with `2n` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun squash(project: SpriteProject, factorNum: Int = 1, factorDen: Int = 2): SpriteProject =
        squashStretch(project, factorNum, factorDen, "squash")

    /**
     * Stretch: the inverse of [squash] — elongates each frame upward.
     *
     * **Frame structure — originals kept at the front:** identical to
     * [squash] (`2n` frames, originals at `0..n-1`, stretched copies at
     * `n..2n-1`), but the factor is inverted: with the default
     * `factor = 2` the content height doubles and the width halves while
     * the area is preserved. The scaled box is bottom-aligned to the
     * content's ground row and horizontally centered exactly like
     * [squash]; parts growing past the canvas top clip away. A tag named
     * `"stretch"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @param factorNum stretch factor numerator; `1..1024`.
     * @param factorDen stretch factor denominator; `1..1024`.
     * @return a new project with `2n` frames.
     * @throws IllegalArgumentException on any out-of-range parameter.
     */
    fun stretch(project: SpriteProject, factorNum: Int = 2, factorDen: Int = 1): SpriteProject =
        squashStretch(project, factorNum, factorDen, "stretch")

    private fun squashStretch(project: SpriteProject, factorNum: Int, factorDen: Int, effect: String): SpriteProject {
        require(factorNum in 1..MAX_FACTOR) { "factorNum must be in [1, $MAX_FACTOR] (was $factorNum)" }
        require(factorDen in 1..MAX_FACTOR) { "factorDen must be in [1, $MAX_FACTOR] (was $factorDen)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val derived = project.frames.map { source ->
            val box = contentBox(source)
            val cels = if (box == null) {
                source.cels
            } else {
                source.cels.mapValues { squashCel(it.value, box, factorNum, factorDen) }
            }
            Frame(id = nextId, cels = cels, durationMs = source.durationMs).also { nextId++ }
        }
        val frames = project.frames + derived
        return finish(
            project, frames, effect,
            "$effect: $n frame(s) -> ${frames.size} frames (originals first, factor $factorNum/$factorDen, " +
                "ground-aligned), tag '$effect' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Pulse: center-scaled breathing, the scale-based sibling of
     * [AnimationEngine.breathe].
     *
     * **Frame structure — originals kept at the front:** `3n` frames —
     * the `n` originals at `0..n-1`, then `n` copies **expanded** by
     * [amplitudePx] pixels in all four directions at `n..2n-1`, then `n`
     * copies **contracted** by [amplitudePx] pixels in all four directions
     * at `2n..3n-1`.
     *
     * Unlike the displacement effects this is genuine resampling, **not**
     * translation: the bounding box of the frame's combined content (the
     * union of non-transparent pixels across all cels) grows/shrinks by
     * `2 * amplitudePx` on each axis, anchored at that box's center, with
     * endpoint-preserving nearest-neighbor sampling — edge content
     * replicates outward on expansion, so a 4px wide sprite becomes 6px
     * wide at `amplitudePx = 1`. All cels of a frame share one bounding
     * box, so layers stay perfectly aligned; a frame with no content at
     * all passes through unchanged. Growth past the canvas clips. A tag
     * named `"pulse"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @param amplitudePx expansion/contraction in pixels per side; >= 1.
     * @return a new project with `3n` frames.
     * @throws IllegalArgumentException if [amplitudePx] < 1.
     */
    fun pulse(project: SpriteProject, amplitudePx: Int = 1): SpriteProject {
        require(amplitudePx >= 1) { "amplitudePx must be >= 1 (was $amplitudePx)" }
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        fun scaledCopies(grow: Int): List<Frame> = project.frames.map { source ->
            val box = contentBox(source)
            val cels = if (box == null) {
                source.cels
            } else {
                source.cels.mapValues { pulseCel(it.value, box, grow) }
            }
            Frame(id = nextId, cels = cels, durationMs = source.durationMs).also { nextId++ }
        }
        val frames = project.frames + scaledCopies(amplitudePx) + scaledCopies(-amplitudePx)
        return finish(
            project, frames, "pulse",
            "pulse: $n frame(s) -> ${frames.size} frames (originals, +${amplitudePx}px expansion, " +
                "-${amplitudePx}px contraction), tag 'pulse' spans 0..${frames.lastIndex}",
        )
    }

    // ---- overlay effects -----------------------------------------------------

    /**
     * Pulsing outline glow around each frame's content.
     *
     * **Frame structure — replaces the timeline:** each source frame
     * expands into [steps] frames (`n * steps` total); result frame
     * `i * steps + s` derives from source `i` with **unchanged content**
     * plus a glow overlay whose strength decays. The glow is the frame's
     * outline — transparent pixels with at least one non-transparent
     * 4-neighbor — painted in [color]'s RGB at alpha level `0x80 shr s`
     * for `s < steps - 1` and `0` for the final step: the default
     * `steps = 4` walks exactly `0x80 -> 0x40 -> 0x20 -> 0`, the last step
     * being pixel-identical to the untouched source.
     *
     * The outline is computed per cel (layer structure and duration
     * preserved); pixels of the cel itself are never overwritten, so the
     * content stays byte-identical in every step. [color]'s own alpha
     * channel is ignored — the staircase supplies the alpha. A tag named
     * `"glowPulse"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @param color glow color (ARGB packed; alpha channel ignored).
     * @param steps glow staircase length; `2..1024`.
     * @return a new project with `n * steps` frames.
     * @throws IllegalArgumentException if [steps] is out of range.
     */
    fun glowPulse(project: SpriteProject, color: Int, steps: Int = 4): SpriteProject {
        require(steps in 2..MAX_REPEAT) { "steps must be in [2, $MAX_REPEAT] (was $steps)" }
        val glowRgb = color and 0x00FFFFFF
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * steps)
        for (source in project.frames) {
            for (s in 0 until steps) {
                val level = if (s == steps - 1) 0 else GLOW_BASE ushr s
                frames.add(Frame(id = nextId, cels = source.cels.mapValues { glowCel(it.value, glowRgb, level) }, durationMs = source.durationMs))
                nextId++
            }
        }
        return finish(
            project, frames, "glowPulse",
            "glowPulse: $n frame(s) -> ${frames.size} frames ($steps glow steps from 0x${GLOW_BASE.toString(16)}), " +
                "tag 'glowPulse' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * A horizontal scan line sweeping down each frame.
     *
     * **Frame structure — replaces the timeline:** each source frame
     * expands into `height` frames (`n * height` total); result frame
     * `i * height + k` derives from source `i` with a 1px-tall horizontal
     * line of [color] drawn across the full width at row `k`, overlaid on
     * the original content (that row's pixels are replaced by [color],
     * written verbatim including its alpha — pass e.g. `0xFFFFFFFF` for a
     * solid line; a color with zero alpha erases the row instead). The
     * line position `k` walks `0..height-1`, so the scan sweeps top to
     * bottom once per source frame.
     *
     * The line is written into every cel (layer structure and duration
     * preserved); compositing shows it on top regardless of stacking. A
     * tag named `"scanline"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @param color scan line pixel value (ARGB packed, written verbatim).
     * @return a new project with `n * height` frames.
     */
    fun scanline(project: SpriteProject, color: Int): SpriteProject {
        val height = project.height
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * height)
        for (source in project.frames) {
            for (k in 0 until height) {
                frames.add(Frame(id = nextId, cels = source.cels.mapValues { scanCel(it.value, k, color) }, durationMs = source.durationMs))
                nextId++
            }
        }
        return finish(
            project, frames, "scanline",
            "scanline: $n frame(s) -> ${frames.size} frames ($height scan positions each), " +
                "tag 'scanline' spans 0..${frames.lastIndex}",
        )
    }

    /**
     * Row-by-row reveal from full transparency.
     *
     * **Frame structure — replaces the timeline:** each source frame
     * expands into `height` frames (`n * height` total); result frame
     * `i * height + k` derives from source `i` keeping rows `y <= k` of
     * the original content verbatim while rows `y > k` are cleared to
     * canonical `0x00000000`. The reveal walks top to bottom; the final
     * step (`k = height - 1`) is pixel-identical to the source (its cels
     * are shared by reference).
     *
     * [color] is accepted for call-site symmetry with [scanline] — hosts
     * driving both effects through one uniform pipeline (see [apply]) can
     * pass the same color through — but the reveal renders the source
     * pixels unchanged, so this value never reaches the raster. Per cel
     * (layer structure and duration preserved). A tag named
     * `"typewriter"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @param color reserved for uniform-pipeline call sites; unused by the
     * reveal itself.
     * @return a new project with `n * height` frames.
     */
    fun typewriter(project: SpriteProject, color: Int): SpriteProject {
        val height = project.height
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = ArrayList<Frame>(n * height)
        for (source in project.frames) {
            for (k in 0 until height) {
                frames.add(Frame(id = nextId, cels = source.cels.mapValues { revealCel(it.value, k) }, durationMs = source.durationMs))
                nextId++
            }
        }
        return finish(
            project, frames, "typewriter",
            "typewriter: $n frame(s) -> ${frames.size} frames ($height reveal rows each), " +
                "tag 'typewriter' spans 0..${frames.lastIndex}",
        )
    }

    // ---- color effects -------------------------------------------------------

    /**
     * Hue cycle: rotates each frame's chroma around the Lab neutral axis.
     *
     * **Frame structure — replaces the timeline, frame for frame:** result
     * frame `k` is source frame `k` with every non-transparent pixel's
     * CIELAB `(a*, b*)` pair rotated by `(360 / n) * k` degrees around the
     * neutral axis — lightness `L*` and every pixel's alpha stay untouched
     * (transparent pixels remain canonical `0x00000000`). Frame 0 rotates
     * by 0 degrees and therefore shares the source cels verbatim; the
     * full n-frame cycle sweeps 360 degrees, so a looped playback runs
     * through the whole hue wheel. Rotation is per cel (layer structure
     * and duration preserved) via [LabColor]. A tag named
     * `"rainbowShift"` spans the result.
     *
     * @param project project to animate; never mutated.
     * @return a new project with the same `n` frames.
     */
    fun rainbowShift(project: SpriteProject): SpriteProject {
        val n = project.frameCount
        var nextId = project.allocateFrameId()
        val frames = project.frames.mapIndexed { k, source ->
            val theta = TWO_PI * k / n
            val cels = if (k == 0) source.cels else source.cels.mapValues { rotateLabCel(it.value, theta) }
            Frame(id = nextId, cels = cels, durationMs = source.durationMs).also { nextId++ }
        }
        return finish(
            project, frames, "rainbowShift",
            "rainbowShift: $n frame(s) -> ${frames.size} frames (Lab hue rotation ${360.0 / n} degrees/frame), " +
                "tag 'rainbowShift' spans 0..${frames.lastIndex}",
        )
    }

    // ---- name-based dispatch -------------------------------------------------

    /**
     * The canonical effect names understood by [apply] (the method names
     * of this class): `bounce`, `shake`, `wave`, `fade`, `squash`,
     * `stretch`, `pulse`, `orbit`, `float`, `glowPulse`, `scanline`,
     * `typewriter`, `blink`, `rainbowShift`.
     *
     * @return the dispatchable effect names in dispatch order.
     */
    fun effectNames(): List<String> = listOf(
        "bounce", "shake", "wave", "fade", "squash", "stretch", "pulse",
        "orbit", "float", "glowPulse", "scanline", "typewriter", "blink", "rainbowShift",
    )

    /**
     * Dispatches to an effect by [name] with fault-tolerant positional
     * parameters.
     *
     * Matching: [name] is trimmed and lowercased, so `"Wave"` and
     `"WAVE"` both dispatch to [wave]. Parameters map positionally onto
     * the effect's signature; missing (or explicitly null) parameters
     * fall back to the effect's defaults, and surplus parameters are
     * rejected. Values are converted leniently: Int slots accept [Int],
     * [Long], [Short], [Byte], [Float]/[Double] (rounded half away from
     * zero) and [String] (decimal, `0x`/`#`-prefixed hex — handy for
     * packed ARGB colors — or a parsable float); Boolean slots accept
     * [Boolean] and the strings `"true"`/`"false"`; the easing slot
     * accepts an [Easing] or its name (any casing, via [Easings.valueOf]);
     * the fade direction slot accepts a [FadeDirection] or `"IN"`/`"OUT"`.
     * A present-but-unknowable value throws
     * [IllegalArgumentException] with the effect, parameter position and
     * name, expected type and offending value.
     *
     * @param name effect name (case-insensitive).
     * @param project project to animate; never mutated.
     * @param params positional parameters for the effect.
     * @return the new project produced by the dispatched effect.
     * @throws IllegalArgumentException for an unknown effect name, surplus
     * parameters or a parameter that cannot be converted to its slot's
     * type (the message names the effect and the offending parameter).
     */
    fun apply(name: String, project: SpriteProject, vararg params: Any?): SpriteProject {
        val key = name.trim().lowercase()
        return when (key) {
            "bounce" -> {
                rejectExtra(key, params, 3)
                bounce(
                    project,
                    paramInt(key, params, 0, "amplitudePx", 2),
                    paramInt(key, params, 1, "bounces", 2),
                    paramEasing(key, params, 2, "easing", Easing.EASE_OUT),
                )
            }
            "shake" -> {
                rejectExtra(key, params, 2)
                shake(
                    project,
                    paramInt(key, params, 0, "amplitudePx", 1),
                    paramInt(key, params, 1, "speed", 2),
                )
            }
            "wave" -> {
                rejectExtra(key, params, 3)
                wave(
                    project,
                    paramInt(key, params, 0, "amplitudePx", 1),
                    paramInt(key, params, 1, "wavelengthFrames", 4),
                    paramBoolean(key, params, 2, "horizontal", true),
                )
            }
            "fade" -> {
                rejectExtra(key, params, 2)
                fade(
                    project,
                    paramInt(key, params, 0, "steps", 4),
                    paramFadeDirection(key, params, 1, "direction", FadeDirection.OUT),
                )
            }
            "squash" -> {
                rejectExtra(key, params, 2)
                squash(
                    project,
                    paramInt(key, params, 0, "factorNum", 1),
                    paramInt(key, params, 1, "factorDen", 2),
                )
            }
            "stretch" -> {
                rejectExtra(key, params, 2)
                stretch(
                    project,
                    paramInt(key, params, 0, "factorNum", 2),
                    paramInt(key, params, 1, "factorDen", 1),
                )
            }
            "pulse" -> {
                rejectExtra(key, params, 1)
                pulse(project, paramInt(key, params, 0, "amplitudePx", 1))
            }
            "orbit" -> {
                rejectExtra(key, params, 2)
                orbit(
                    project,
                    paramInt(key, params, 0, "radiusPx", 2),
                    paramInt(key, params, 1, "steps", 8),
                )
            }
            "float" -> {
                rejectExtra(key, params, 2)
                float(
                    project,
                    paramInt(key, params, 0, "amplitudePx", 1),
                    paramInt(key, params, 1, "wavelengthFrames", 8),
                )
            }
            "glowPulse" -> {
                rejectExtra(key, params, 2)
                glowPulse(
                    project,
                    paramInt(key, params, 0, "color", 0xFFFFFF),
                    paramInt(key, params, 1, "steps", 4),
                )
            }
            "scanline" -> {
                rejectExtra(key, params, 1)
                scanline(project, paramInt(key, params, 0, "color", 0xFFFFFF))
            }
            "typewriter" -> {
                rejectExtra(key, params, 1)
                typewriter(project, paramInt(key, params, 0, "color", 0xFFFFFF))
            }
            "blink" -> {
                rejectExtra(key, params, 1)
                blink(project, paramInt(key, params, 0, "closedFrames", 1))
            }
            "rainbowShift" -> {
                rejectExtra(key, params, 0)
                rainbowShift(project)
            }
            else -> throw IllegalArgumentException(
                "Unknown effect name '$name' (available: ${effectNames().joinToString()})"
            )
        }
    }

    // ---- parameter tolerance -------------------------------------------------

    /** Rejects surplus parameters for [key] beyond [expected] slots. */
    private fun rejectExtra(effect: String, params: Array<out Any?>, expected: Int) {
        if (params.size > expected) {
            throw IllegalArgumentException(
                "Effect '$effect' accepts at most $expected parameter(s) but ${params.size} were given " +
                    "(${params.joinToString()})"
            )
        }
    }

    /**
     * Reads the Int at [index] of [params], or [default] when absent/null;
     * converts [Int], [Long], [Short], [Byte], [Float], [Double] and
     * [String] (decimal, `0x`/`#` hex, or float text).
     */
    private fun paramInt(effect: String, params: Array<out Any?>, index: Int, name: String, default: Int): Int {
        if (index >= params.size || params[index] == null) return default
        return when (val value = params[index]) {
            is Int -> value
            is Long -> value.toInt()
            is Short -> value.toInt()
            is Byte -> value.toInt()
            is Float -> roundNearest(value)
            is Double -> roundNearest(value.toFloat())
            is String -> parseTolerantInt(value)
                ?: throw paramTypeError(effect, index, name, value, "Int")
            else -> throw paramTypeError(effect, index, name, value, "Int")
        }
    }

    /** Decimal, `0x`/`#`-prefixed hex (32-bit ARGB friendly) or float text to Int, else null. */
    private fun parseTolerantInt(raw: String): Int? {
        val s = raw.trim()
        s.toIntOrNull()?.let { return it }
        val hexBody = when {
            s.startsWith("0x") || s.startsWith("0X") -> s.substring(2)
            s.startsWith("#") -> s.substring(1)
            else -> s
        }
        hexBody.toLongOrNull(16)?.let { return it.toInt() }
        s.toFloatOrNull()?.let { return roundNearest(it) }
        return null
    }

    /** Reads the Boolean at [index], or [default]; accepts Boolean or "true"/"false" (any case). */
    private fun paramBoolean(effect: String, params: Array<out Any?>, index: Int, name: String, default: Boolean): Boolean {
        if (index >= params.size || params[index] == null) return default
        return when (val value = params[index]) {
            is Boolean -> value
            is String -> when (value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw paramTypeError(effect, index, name, value, "Boolean")
            }
            else -> throw paramTypeError(effect, index, name, value, "Boolean")
        }
    }

    /** Reads the [Easing] at [index], or [default]; accepts an [Easing] or its (any-case) name. */
    private fun paramEasing(effect: String, params: Array<out Any?>, index: Int, name: String, default: Easing): Easing {
        if (index >= params.size || params[index] == null) return default
        return when (val value = params[index]) {
            is Easing -> value
            is String -> Easings.valueOf(value)
                ?: throw IllegalArgumentException(
                    "Effect '$effect' parameter $index ($name): unknown easing '$value' " +
                        "(available: ${Easing.values().joinToString { it.name }})"
                )
            else -> throw paramTypeError(effect, index, name, value, "Easing")
        }
    }

    /** Reads the [FadeDirection] at [index], or [default]; accepts the enum or "IN"/"OUT" (any case). */
    private fun paramFadeDirection(
        effect: String,
        params: Array<out Any?>,
        index: Int,
        name: String,
        default: FadeDirection,
    ): FadeDirection {
        if (index >= params.size || params[index] == null) return default
        return when (val value = params[index]) {
            is FadeDirection -> value
            is String -> when (value.trim().uppercase()) {
                "IN" -> FadeDirection.IN
                "OUT" -> FadeDirection.OUT
                else -> throw paramTypeError(effect, index, name, value, "FadeDirection")
            }
            else -> throw paramTypeError(effect, index, name, value, "FadeDirection")
        }
    }

    /** Builds the "expected X, got Y" [IllegalArgumentException] for a parameter slot. */
    private fun paramTypeError(effect: String, index: Int, name: String, value: Any?, expected: String): IllegalArgumentException =
        IllegalArgumentException(
            "Effect '$effect' parameter $index ($name): expected $expected, " +
                "got ${value!!.let { it::class.simpleName } ?: "unknown type"} (value=$value)"
        )

    // ---- raster internals ----------------------------------------------------

    /**
     * Shifts every cel of [cels] by (`dx`, `dy`) through
     * [PixelFrame.shifted] (row-equivalent displacement); the identity
     * shift returns the map itself so rest phases share cel references.
     */
    private fun shiftCels(cels: Map<Int, PixelFrame>, dx: Int, dy: Int): Map<Int, PixelFrame> {
        if (dx == 0 && dy == 0) return cels
        return cels.mapValues { it.value.shifted(dx, dy) }
    }

    /** Scales every pixel's alpha to [level] (`0..255`); level 0 yields canonical zeros. */
    private fun scaleAlpha(cel: PixelFrame, level: Int): PixelFrame {
        if (level >= 255) return cel
        return cel.map { p ->
            val a = ((p ushr 24) * level) / 255
            if (a == 0) 0 else (a shl 24) or (p and 0x00FFFFFF)
        }
    }

    /**
     * Per-row horizontal wave of one cel's row set: row `y` shifts by
     * `round(sin(2*PI*(frameIndex + y) / wavelengthFrames) * amplitudePx)`.
     */
    private fun rowWaveCels(
        cels: Map<Int, PixelFrame>,
        frameIndex: Int,
        amplitudePx: Int,
        wavelengthFrames: Int,
    ): Map<Int, PixelFrame> = cels.mapValues { cel ->
        val shifts = IntArray(cel.value.height) { y ->
            roundNearest((sin(TWO_PI * (frameIndex + y) / wavelengthFrames) * amplitudePx).toFloat())
        }
        if (shifts.all { it == 0 }) {
            cel.value
        } else {
            cel.value.transformed(cel.value.width, cel.value.height) { x, y ->
                cel.value[x - shifts[y], y]
            }
        }
    }

    /**
     * Squash/stretch resampling of one cel into the same canvas: the
     * content box ([box]) scales to `boxWidth * factorDen / factorNum` x
     * `boxHeight * factorNum / factorDen` (min 1, nearest rounding, area
     * preserved), the scaled box is bottom-aligned (target bottom row =
     * content ground row) and horizontally centered on the source box
     * (half the difference, truncated toward zero). Sampling is
     * endpoint-preserving nearest neighbor; pixels outside the scaled box
     * are transparent. Identity factors share the source cel.
     */
    private fun squashCel(cel: PixelFrame, box: ContentBox, factorNum: Int, factorDen: Int): PixelFrame {
        val bw = box.x1 - box.x0 + 1
        val bh = box.y1 - box.y0 + 1
        val tw = maxOf(1, roundNearest(bw.toFloat() * factorDen / factorNum))
        val th = maxOf(1, roundNearest(bh.toFloat() * factorNum / factorDen))
        if (tw == bw && th == bh) return cel
        val tx0 = box.x0 + (bw - tw) / 2
        val ty0 = box.y1 - th + 1
        return cel.transformed(cel.width, cel.height) { x, y ->
            if (x >= tx0 && x < tx0 + tw && y >= ty0 && y < ty0 + th) {
                cel[samplePos(x, tx0, tw, box.x0, bw), samplePos(y, ty0, th, box.y0, bh)]
            } else {
                0
            }
        }
    }

    /**
     * One pulse step of [cel]: the content box ([box]) grows ([grow] > 0)
     * or shrinks ([grow] < 0) by `|grow|` pixels on each side, anchored at
     * the box center, endpoint-preserving nearest neighbor. Pixels outside
     * the scaled box are transparent, so only the target region samples
     * content.
     */
    private fun pulseCel(cel: PixelFrame, box: ContentBox, grow: Int): PixelFrame {
        val bw = box.x1 - box.x0 + 1
        val bh = box.y1 - box.y0 + 1
        val tw = maxOf(1, bw + 2 * grow)
        val th = maxOf(1, bh + 2 * grow)
        if (tw == bw && th == bh) return cel
        val tx0 = box.x0 + (bw - tw) / 2
        val ty0 = box.y0 + (bh - th) / 2
        return cel.transformed(cel.width, cel.height) { x, y ->
            if (x >= tx0 && x < tx0 + tw && y >= ty0 && y < ty0 + th) {
                cel[samplePos(x, tx0, tw, box.x0, bw), samplePos(y, ty0, th, box.y0, bh)]
            } else {
                0
            }
        }
    }

    /**
     * Union bounding box of all non-transparent pixels across every cel of
     * [frame], or null when the frame has no content at all. Shared by all
     * cels of the frame so pulse scaling keeps layers aligned.
     */
    private fun contentBox(frame: Frame): ContentBox? {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (cel in frame.cels.values) {
            val w = cel.width
            val px = cel.pixels
            for (i in px.indices) {
                if (px[i] ushr 24 == 0) continue
                val x = i % w
                val y = i / w
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        return if (x1 < 0) null else ContentBox(x0, y0, x1, y1)
    }

    /** Inclusive bounding box of a frame's combined content. */
    private class ContentBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /**
     * Endpoint-preserving nearest-neighbor coordinate mapping: maps
     * `outPos` in the target span `[targetStart, targetStart + targetSpan)`
     * onto `[sourceStart, sourceStart + sourceSpan]`, with both span ends
     * mapping exactly. Degenerate single-pixel targets sample the source
     * middle.
     */
    private fun samplePos(outPos: Int, targetStart: Int, targetSpan: Int, sourceStart: Int, sourceSpan: Int): Int {
        if (targetSpan <= 1) return sourceStart + (sourceSpan - 1) / 2
        val t = (outPos - targetStart).toFloat() / (targetSpan - 1)
        return sourceStart + roundNearest(t * (sourceSpan - 1))
    }

    /**
     * Adds the outline glow of [cel] at alpha [level] (`0..255`): every
     * transparent pixel with a non-transparent 4-neighbor becomes
     * `(level shl 24) or [glowRgb]`; content pixels are untouched. Level
     * 0 shares the source cel (the pulse's final step).
     */
    private fun glowCel(cel: PixelFrame, glowRgb: Int, level: Int): PixelFrame {
        if (level <= 0) return cel
        val w = cel.width
        val h = cel.height
        val src = cel.pixels
        var out: IntArray? = null
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (src[i] ushr 24 != 0) continue
                val touchesContent =
                    (x > 0 && src[i - 1] ushr 24 != 0) ||
                        (x < w - 1 && src[i + 1] ushr 24 != 0) ||
                        (y > 0 && src[i - w] ushr 24 != 0) ||
                        (y < h - 1 && src[i + w] ushr 24 != 0)
                if (touchesContent) {
                    val o = out ?: src.copyOf().also { out = it }
                    o[i] = (level shl 24) or glowRgb
                }
            }
        }
        return out?.let { PixelFrame.of(w, h, it) } ?: cel
    }

    /** Overwrites row [row] of [cel] with [color] across the full width. */
    private fun scanCel(cel: PixelFrame, row: Int, color: Int): PixelFrame {
        val out = cel.pixels.copyOf()
        val base = row * cel.width
        for (x in 0 until cel.width) out[base + x] = color
        return PixelFrame.of(cel.width, cel.height, out)
    }

    /** Keeps rows `0..[rowsVisible]` of [cel], clears the rest to canonical zeros. */
    private fun revealCel(cel: PixelFrame, rowsVisible: Int): PixelFrame {
        if (rowsVisible >= cel.height - 1) return cel
        val out = cel.pixels.copyOf()
        for (y in rowsVisible + 1 until cel.height) {
            val base = y * cel.width
            for (x in 0 until cel.width) out[base + x] = 0
        }
        return PixelFrame.of(cel.width, cel.height, out)
    }

    /**
     * Rotates every non-transparent pixel of [cel] in the CIELAB `ab`
     * plane by [theta] radians around the neutral axis: `L*` and alpha are
     * preserved, `(a*, b*)` rotate by the standard 2D rotation. Zero angle
     * shares the source cel.
     */
    private fun rotateLabCel(cel: PixelFrame, theta: Double): PixelFrame {
        if (theta == 0.0) return cel
        val c = cos(theta)
        val s = sin(theta)
        return cel.map { p ->
            if (p ushr 24 == 0) {
                0
            } else {
                val lab = LabColor.fromArgb(p)
                val a2 = lab.a * c - lab.b * s
                val b2 = lab.a * s + lab.b * c
                val rgb = LabColor.toArgb(LabColor.Lab(lab.l, a2, b2)) and 0x00FFFFFF
                (p and 0xFF000000.toInt()) or rgb
            }
        }
    }

    /**
     * Commits a derived frame list: rebuilds the project through
     * [SpriteProject.withFrames] (fresh ids already allocated from the
     * project's allocator), installs the effect tag over the whole
     * timeline (replacing a same-named tag, keeping the others) and emits
     * exactly one debug log line.
     */
    private fun finish(project: SpriteProject, frames: List<Frame>, tagName: String, summary: String): SpriteProject {
        val rebuilt = project.withFrames(frames)
        val tag = AnimationTag(name = tagName, startFrame = 0, endFrame = frames.lastIndex)
        val tagged = rebuilt.withTags(rebuilt.tags.filterNot { it.name == tagName } + tag)
        config.effectiveLogger.d(LOG_TAG, summary)
        return tagged
    }

    /** Rounds to the nearest integer, ties away from zero (symmetric for negative displacements). */
    private fun roundNearest(v: Float): Int =
        if (v >= 0f) (v + 0.5f).toInt() else -((-v + 0.5f).toInt())

    private companion object {
        /** Debug log tag for this library. */
        private const val LOG_TAG = "AnimationEffects"

        /** Full turn in radians. */
        private const val TWO_PI = 2.0 * PI

        /** First glow alpha of [glowPulse]; the staircase halves from here. */
        private const val GLOW_BASE = 0x80

        /** Upper bound for repetition-style parameters (steps, speed, closed frames). */
        private const val MAX_REPEAT = 1024

        /** Upper bound for squash/stretch factor numerator and denominator. */
        private const val MAX_FACTOR = 1024

        /** Upper bound for [bounce]'s bounces (the `2^j` peak divisor must fit an Int). */
        private const val MAX_BOUNCES = 30
    }
}
