package com.pixellab.core.motion

import com.pixellab.core.gen.SeededRng
import com.pixellab.core.model.PixelFrame
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * One particle's simulation state. Instances live in one pre-allocated
 * pool inside [ParticleSystem] — they are never created or dropped
 * during stepping.
 */
class Particle internal constructor(
    internal var x: Double,
    internal var y: Double,
    internal var vx: Double,
    internal var vy: Double,
    internal var life: Double,
    internal var maxLife: Double,
    internal var seed: Double,
) {
    /** Age-normalized progress through the lifetime in `[0, 1]`. */
    val progress: Double get() = if (maxLife <= 0.0) 1.0 else (1.0 - (life / maxLife)).coerceIn(0.0, 1.0)

    /** Whether the particle is still alive. */
    val alive: Boolean get() = life > 0.0
}

/**
 * Deterministic 2D particle system for sprite effects: fire, sparks,
 * rain, starfields, explosions and smoke.
 *
 * Design contract:
 *  * **Deterministic** — the same seed, emitter config and step count
 *    produce the identical particle cloud, frame after frame, so agents
 *    can regenerate effects on demand and diff them.
 *  * **Pool-friendly** — particles live in one pre-allocated array;
 *    stepping mutates in place with zero per-step allocations.
 *  * **Pixel-art native** — [render] rasterizes onto a [PixelFrame]
 *    with hard 1-px points (or 2x2 soft sprites), quantizing color over
 *    lifetime through a small ramp.
 */
class ParticleSystem(
    /** Emitter description (rate, geometry, forces, ramp). */
    @Suppress("unused") val config: EmitterConfigView,
    seed: Long,
    /** Maximum simultaneously live particles (pool size). */
    val capacity: Int = 512,
) {
    private val rng = SeededRng(seed)
    internal val pool = Array(capacity) { Particle(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
    internal var liveCount = 0
    internal var spawnAccumulator = 0.0

    /** Number of live particles right now. */
    val liveParticles: Int get() = liveCount

    /** Total particles ever spawned. */
    var totalSpawned: Long = 0
        private set

    /** Simulation time in seconds. */
    var time: Double = 0.0
        private set

    /**
     * Advances the simulation by [dt] seconds (clamped to `[0, 0.1]` to
     * keep determinism on huge host frames).
     */
    fun step(dt: Double) {
        val clamped = dt.coerceIn(0.0, 0.1)
        time += clamped
        // ── Spawn phase ────────────────────────────────────────────────
        var toSpawn = (config.rate * clamped).toInt()
        spawnAccumulator += config.rate * clamped - toSpawn
        if (spawnAccumulator >= 1.0) {
            toSpawn += spawnAccumulator.toInt()
            spawnAccumulator -= spawnAccumulator.toInt()
        }
        var spawned = 0
        while (spawned < toSpawn && liveCount < capacity) {
            spawnOne()
            spawned++
        }
        // ── Integrate phase ────────────────────────────────────────────
        for (i in 0 until liveCount) {
            val p = pool[i]
            p.life -= clamped
            if (p.life <= 0.0) continue
            // Turbulence: rotating pseudo-random force, stable per particle.
            val phase = p.seed * 6.2831853 + time * config.turbulenceSpeed
            p.vx += cos(phase) * config.turbulence * clamped
            p.vy += sin(phase * 1.7) * config.turbulence * clamped
            // Drag: exponential decay.
            if (config.drag != 0.0) {
                val decay = exp(-config.drag * clamped)
                p.vx *= decay
                p.vy *= decay
            }
            // Gravity.
            p.vy += config.gravity * clamped
            // Translate.
            p.x += p.vx * clamped
            p.y += p.vy * clamped
        }
        // ── Compact dead particles (swap-remove) ───────────────────────
        var i = 0
        while (i < liveCount) {
            if (!pool[i].alive) {
                liveCount--
                if (i != liveCount) {
                    // Move the last live particle into the hole.
                    val dead = pool[i]
                    val moved = pool[liveCount]
                    dead.x = moved.x; dead.y = moved.y
                    dead.vx = moved.vx; dead.vy = moved.vy
                    dead.life = moved.life; dead.maxLife = moved.maxLife
                    dead.seed = moved.seed
                }
            } else {
                i++
            }
        }
    }

    private fun spawnOne() {
        if (liveCount >= capacity) return
        val p = pool[liveCount]
        liveCount++
        totalSpawned++
        val angle = Math.toRadians(
            config.angleDeg + nextSigned(config.spreadDeg / 2),
        )
        val speed = config.speed * (1.0 + nextSigned(config.speedJitter))
        p.x = config.originX + nextSigned(config.originJitter)
        p.y = config.originY + nextSigned(config.originJitter)
        p.vx = cos(angle) * speed
        p.vy = -sin(angle) * speed // y grows downward on screen.
        p.maxLife = config.lifetime * (1.0 + nextSigned(config.lifetimeJitter))
        p.life = p.maxLife
        p.seed = rng.nextDouble()
    }

    /** Uniform value in `[-span, +span]` from the seeded generator. */
    private fun nextSigned(span: Double): Double = rng.nextDouble() * 2.0 * span - span

    /**
     * Renders live particles onto a fresh `width x height` frame using
     * the [EmitterConfigView.colorRamp] quantized by lifetime progress
     * (transparent background). `soft` particles occupy a 2x2 block.
     */
    fun render(width: Int, height: Int, soft: Boolean = false): PixelFrame {
        require(width > 0 && height > 0) { "Positive dimensions required" }
        val out = IntArray(width * height)
        for (i in 0 until liveCount) {
            val p = pool[i]
            if (!p.alive) continue
            val x = p.x.toInt()
            val y = p.y.toInt()
            val color = rampColor(p.progress)
            if (soft) {
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val px = x + dx
                        val py = y + dy
                        if (px in 0 until width && py in 0 until height) {
                            out[py * width + px] = color
                        }
                    }
                }
            } else {
                if (x in 0 until width && y in 0 until height) {
                    out[y * width + x] = color
                }
            }
        }
        return PixelFrame.of(width, height, out)
    }

    /** The ramp color for a lifetime progress value in `[0, 1]`. */
    fun rampColor(progress: Double): Int {
        val ramp = config.colorRamp
        if (ramp.isEmpty()) return 0xFFFFFFFF.toInt()
        val t = progress.coerceIn(0.0, 1.0)
        val idx = (t * (ramp.size - 1)).toInt().coerceIn(0, ramp.size - 1)
        return ramp[idx]
    }

    /**
     * Visits every live particle (tests and pipeline hooks use this to
     * inspect positions without touching the pool directly).
     */
    fun forEachLive(action: (index: Int, x: Double, y: Double, progress: Double) -> Unit) {
        for (i in 0 until liveCount) {
            action(i, pool[i].x, pool[i].y, pool[i].progress)
        }
    }
}

/**
 * Immutable emitter description: spawn rate, geometry, forces and the
 * color ramp. The [ParticleSystem] reads it; nothing mutates it.
 */
class EmitterConfigView(
    /** Particles per second. */
    val rate: Double = 24.0,
    /** Emission direction in degrees (0 = right, 90 = up on screen). */
    val angleDeg: Double = 90.0,
    /** Total cone width in degrees (spread ± half). */
    val spreadDeg: Double = 30.0,
    /** Initial speed in pixels/second. */
    val speed: Double = 24.0,
    /** ± relative speed jitter in `[0, 1]`. */
    val speedJitter: Double = 0.15,
    /** Mean lifetime in seconds. */
    val lifetime: Double = 1.2,
    /** ± relative lifetime jitter in `[0, 1]`. */
    val lifetimeJitter: Double = 0.25,
    /** Emitter origin in pixels. */
    val originX: Double = 32.0,
    val originY: Double = 32.0,
    /** ± spawn position jitter in pixels. */
    val originJitter: Double = 1.5,
    /** Gravity in px/s² (positive pulls down). */
    val gravity: Double = 0.0,
    /** Turbulence force magnitude in px/s². */
    val turbulence: Double = 0.0,
    /** Turbulence rotation speed scalar. */
    val turbulenceSpeed: Double = 3.0,
    /** Velocity drag coefficient (higher = faster slowdown). */
    val drag: Double = 0.0,
    /** Quantized lifetime color ramp (progress 0 → 1). */
    val colorRamp: IntArray = intArrayOf(
        0xFFFFFF88.toInt(), 0xFFCC6A1E.toInt(), 0x99340CFF.toInt(),
    ),
) {
    init {
        require(rate >= 0.0) { "rate must be ≥ 0" }
        require(speed >= 0.0) { "speed must be ≥ 0" }
        require(spreadDeg in 0.0..360.0) { "spreadDeg out of range" }
        require(speedJitter in 0.0..1.0) { "speedJitter out of range" }
        require(lifetime > 0.0) { "lifetime must be > 0" }
        require(lifetimeJitter in 0.0..1.0) { "lifetimeJitter out of range" }
        require(originJitter >= 0.0) { "originJitter must be ≥ 0" }
    }

    companion object {
        /** Upward fire: rise, flicker, fade to dark red. */
        fun fire(originX: Double, originY: Double): EmitterConfigView = EmitterConfigView(
            rate = 60.0, angleDeg = 90.0, spreadDeg = 24.0,
            speed = 20.0, speedJitter = 0.35,
            lifetime = 0.9, lifetimeJitter = 0.3,
            originX = originX, originY = originY, originJitter = 2.0,
            drag = 1.2, turbulence = 30.0, turbulenceSpeed = 9.0,
            colorRamp = intArrayOf(
                0xFFFFE082.toInt(), 0xFF7C4100.toInt(), 0xFF1A0800.toInt(),
            ),
        )

        /** Falling rain: fast, narrow, blue ramp. */
        fun rain(originX: Double, originY: Double): EmitterConfigView = EmitterConfigView(
            rate = 90.0, angleDeg = -78.0, spreadDeg = 6.0,
            speed = 90.0, speedJitter = 0.05,
            lifetime = 1.4, lifetimeJitter = 0.1,
            originX = originX, originY = originY, originJitter = 6.0,
            gravity = 60.0,
            colorRamp = intArrayOf(
                0xFF9CC6E8.toInt(), 0xFF5A8FC0.toInt(), 0xFF3E6C9E.toInt(),
            ),
        )

        /** Starfield: slow drift, twinkle ramp. */
        fun starfield(originX: Double, originY: Double): EmitterConfigView = EmitterConfigView(
            rate = 6.0, angleDeg = 0.0, spreadDeg = 360.0,
            speed = 6.0, speedJitter = 0.9,
            lifetime = 4.0, lifetimeJitter = 0.6,
            originX = originX, originY = originY, originJitter = 40.0,
            colorRamp = intArrayOf(
                0xFF202040.toInt(), 0xFFFFFFFF.toInt(), 0xFF606080.toInt(),
            ),
        )

        /** Explosion burst: radial, fast, quick fade (step once). */
        fun explosion(originX: Double, originY: Double): EmitterConfigView = EmitterConfigView(
            rate = 800.0, angleDeg = 0.0, spreadDeg = 360.0,
            speed = 70.0, speedJitter = 0.7,
            lifetime = 0.6, lifetimeJitter = 0.4,
            originX = originX, originY = originY, originJitter = 1.0,
            drag = 2.5,
            colorRamp = intArrayOf(
                0xFFFFFFFF.toInt(), 0xFFC88020.toInt(), 0x99300AEE.toInt(),
            ),
        )

        /** Smoke: slow rise with heavy curl. */
        fun smoke(originX: Double, originY: Double): EmitterConfigView = EmitterConfigView(
            rate = 14.0, angleDeg = 90.0, spreadDeg = 40.0,
            speed = 12.0, speedJitter = 0.2,
            lifetime = 3.0, lifetimeJitter = 0.4,
            originX = originX, originY = originY, originJitter = 2.0,
            drag = 0.4, turbulence = 22.0, turbulenceSpeed = 2.0,
            colorRamp = intArrayOf(
                0xFF909090.toInt(), 0xFF5A5A5A.toInt(), 0xFF2C2C2C.toInt(),
            ),
        )
    }
}
