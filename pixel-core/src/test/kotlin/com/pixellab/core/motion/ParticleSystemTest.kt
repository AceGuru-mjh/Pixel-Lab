package com.pixellab.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParticleSystemTest {

    @Test
    fun `deterministic simulation for same seed`() {
        fun simulate(): Int {
            val sys = ParticleSystem(EmitterConfigView.fire(32.0, 48.0), seed = 7L, capacity = 64)
            repeat(20) { sys.step(1.0 / 60.0) }
            return sys.liveParticles
        }
        assertEquals(simulate(), simulate())
    }

    @Test
    fun `spawning respects rate and time`() {
        val sys = ParticleSystem(
            EmitterConfigView(rate = 60.0, lifetime = 100.0),
            seed = 1L, capacity = 512,
        )
        // dt is clamped to 0.1s internally for determinism, so one big
        // step spawns 60 * 0.1 = 6 particles.
        sys.step(1.0)
        assertEquals(6, sys.liveParticles)
        assertEquals(6, sys.totalSpawned)
        // Ten small steps accumulate exactly the rate.
        val sys2 = ParticleSystem(
            EmitterConfigView(rate = 60.0, lifetime = 100.0),
            seed = 1L, capacity = 512,
        )
        repeat(10) { sys2.step(0.1) }
        assertTrue("spawned=${sys2.totalSpawned}", sys2.totalSpawned in 54..60)
    }

    @Test
    fun `particles die after lifetime`() {
        // 100/s × 0.3s life. Spawning happens at the START of each step
        // and integration (life -= dt) at the END, so a particle born in
        // step k is dead by the end of step k+2 (0.3s of integrated time
        // spanning three steps). After 5 steps only steps 4 and 5's
        // batches (20 particles) are alive.
        val sys = ParticleSystem(
            EmitterConfigView(rate = 100.0, lifetime = 0.3, lifetimeJitter = 0.0),
            seed = 1L, capacity = 512,
        )
        repeat(5) { sys.step(0.1) }
        assertEquals(50, sys.totalSpawned)
        assertTrue("live=${sys.liveParticles}", sys.liveParticles < sys.totalSpawned)
        assertEquals(20, sys.liveParticles)
        // The emitter keeps running, so live count settles at the steady
        // state (spawn per step == deaths per step == 10): it stays at 20
        // instead of climbing to totalSpawned — that plateau IS the proof
        // that particles die.
        repeat(3) { sys.step(0.1) }
        assertEquals(80, sys.totalSpawned)
        assertEquals(20, sys.liveParticles)
    }

    @Test
    fun `gravity pulls particles down`() {
        val config = EmitterConfigView(
            rate = 10.0, angleDeg = 90.0, spreadDeg = 0.0,
            speed = 0.0, speedJitter = 0.0,
            lifetime = 10.0, lifetimeJitter = 0.0,
            originX = 50.0, originY = 50.0, originJitter = 0.0,
            gravity = 100.0,
        )
        val sys = ParticleSystem(config, seed = 3L, capacity = 8)
        sys.step(0.1) // spawn 1 at (50, 50) with zero velocity.
        assertEquals(1, sys.liveParticles)
        sys.step(1.0) // gravity for 1s: y += 100·1²/2 = 50.
        var y = -1.0
        sys.forEachLive { _, _, py, _ -> y = py }
        assertTrue("y=$y should be below origin", y > 50.0)
    }

    @Test
    fun `explosion spreads radially`() {
        // Drag disabled for a crisp kinematic assertion.
        val config = EmitterConfigView.explosion(50.0, 50.0).copyForTest(rate = 40.0, drag = 0.0)
        val sys = ParticleSystem(config, seed = 9L, capacity = 256)
        sys.step(0.016)
        sys.step(0.1)
        val distances = ArrayList<Double>(sys.liveParticles)
        sys.forEachLive { _, x, y, _ ->
            distances.add(kotlin.math.sqrt((x - 50.0) * (x - 50.0) + (y - 50.0) * (y - 50.0)))
        }
        assertTrue("expected spread, min=${distances.min()}", distances.min() > 1.0)
        assertTrue("expected some far particles, max=${distances.max()}", distances.max() > 5.0)
    }

    @Test
    fun `capacity is respected`() {
        val sys = ParticleSystem(
            EmitterConfigView(rate = 10000.0, lifetime = 100.0),
            seed = 1L, capacity = 16,
        )
        sys.step(1.0)
        assertEquals(16, sys.liveParticles)
    }

    @Test
    fun `render paints live particles with ramp colors`() {
        val ramp = intArrayOf(0xFFFFFF00.toInt(), 0xFF800000.toInt())
        val sys = ParticleSystem(
            EmitterConfigView(rate = 10.0, lifetime = 2.0, colorRamp = ramp, originX = 5.0, originY = 5.0),
            seed = 2L, capacity = 32,
        )
        sys.step(1.0)
        val frame = sys.render(16, 16)
        assertEquals(16, frame.width)
        val painted = frame.pixels.count { it != 0 }
        assertTrue("painted=$painted", painted in 1..sys.liveParticles * 2)
        assertTrue(frame.pixels.filter { it != 0 }.all { it in ramp.toList() })
    }

    @Test
    fun `soft render paints 2x2 blocks`() {
        val sys = ParticleSystem(
            EmitterConfigView(rate = 10.0, lifetime = 10.0, originX = 8.0, originY = 8.0, originJitter = 0.0),
            seed = 4L, capacity = 4,
        )
        sys.step(0.1)
        assertEquals(1, sys.liveParticles)
        val frame = sys.render(16, 16, soft = true)
        assertTrue(frame.pixels.count { it != 0 } >= 2)
    }

    @Test
    fun `rampColor quantizes progress`() {
        val sys = ParticleSystem(
            EmitterConfigView(colorRamp = intArrayOf(1, 2, 3)),
            seed = 1L,
        )
        assertEquals(1, sys.rampColor(0.0))
        assertEquals(2, sys.rampColor(0.5))
        assertEquals(3, sys.rampColor(1.0))
    }

    @Test
    fun `step clamps huge dt`() {
        val sys = ParticleSystem(EmitterConfigView.fire(10.0, 10.0), seed = 6L, capacity = 64)
        sys.step(999.0) // clamped to 0.1 internally, no explosion.
        assertTrue(sys.liveParticles <= 64)
        assertTrue(sys.time <= 0.1)
    }

    @Test
    fun `presets construct valid configs`() {
        EmitterConfigView.fire(0.0, 0.0)
        EmitterConfigView.rain(0.0, 0.0)
        EmitterConfigView.starfield(0.0, 0.0)
        EmitterConfigView.explosion(0.0, 0.0)
        EmitterConfigView.smoke(0.0, 0.0)
    }

    @Test
    fun `validation rejects bad configs`() {
        try {
            EmitterConfigView(lifetime = 0.0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
        try {
            EmitterConfigView(speedJitter = 2.0)
            throw AssertionError("expected rejection")
        } catch (expected: IllegalArgumentException) { }
    }
}

/** Test-side copy helper (configs are immutable). */
private fun EmitterConfigView.copyForTest(rate: Double, drag: Double = this.drag): EmitterConfigView =
    EmitterConfigView(
        rate = rate,
        angleDeg = angleDeg, spreadDeg = spreadDeg,
        speed = speed, speedJitter = speedJitter,
        lifetime = lifetime, lifetimeJitter = lifetimeJitter,
        originX = originX, originY = originY, originJitter = originJitter,
        gravity = gravity, turbulence = turbulence,
        turbulenceSpeed = turbulenceSpeed, drag = drag,
        colorRamp = colorRamp,
    )
