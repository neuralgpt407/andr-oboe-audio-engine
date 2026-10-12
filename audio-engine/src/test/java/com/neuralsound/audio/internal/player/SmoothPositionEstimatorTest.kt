package com.neuralsound.audio.internal.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SmoothPositionEstimatorTest {

    private fun ms(value: Long) = value * 1_000_000L

    private fun playingAt(positionMs: Long, frameMs: Long) = SmoothPositionEstimator().apply {
        onTick(positionMs, isPlaying = true, frameTimeNanos = ms(frameMs))
    }

    @Test
    fun `advances with frame time while playing`() {
        val clock = playingAt(1_000, frameMs = 0)
        assertEquals(1_016, clock.positionAt(ms(16)))
        assertEquals(1_100, clock.positionAt(ms(100)))
    }

    @Test
    fun `blends toward a late tick over following frames`() {
        val clock = playingAt(1_000, frameMs = 0)
        clock.positionAt(ms(40))
        // Estimate is 1040; the player reports 1140 (100 ms ahead, below the snap threshold).
        clock.onTick(1_140, isPlaying = true, frameTimeNanos = ms(40))
        assertEquals(1_040, clock.positionAt(ms(40)))

        // 40 ms is too short a span for a rate sample, so the rate stays 1x. Only the 84 ms beyond
        // the 16 ms jitter dead band is blended in: 16 ms + 20% of 84 = 1072.8.
        assertEquals(1_073, clock.positionAt(ms(56)))
        // 16 ms + 20% of the remaining 67.2 ms = 1102.24.
        assertEquals(1_102, clock.positionAt(ms(72)))
    }

    @Test
    fun `snaps forward on seek`() {
        val clock = playingAt(1_000, frameMs = 0)
        clock.positionAt(ms(40))
        clock.onTick(30_000, isPlaying = true, frameTimeNanos = ms(40))
        assertEquals(30_000, clock.positionAt(ms(40)))
        assertEquals(30_016, clock.positionAt(ms(56)))
    }

    @Test
    fun `snaps backwards on seek`() {
        val clock = playingAt(10_000, frameMs = 0)
        clock.positionAt(ms(40))
        clock.onTick(2_000, isPlaying = true, frameTimeNanos = ms(40))
        assertEquals(2_000, clock.positionAt(ms(40)))
    }

    @Test
    fun `never goes backwards during a small negative correction`() {
        val clock = playingAt(1_000, frameMs = 0)
        var last = clock.positionAt(ms(100))
        // 100 ms behind the estimate: below the snap threshold, so it must blend, not jump back.
        clock.onTick(1_000, isPlaying = true, frameTimeNanos = ms(100))
        for (frame in 1..60) {
            val next = clock.positionAt(ms(100 + frame * 16L))
            assertTrue("went backwards: $next < $last", next >= last)
            last = next
        }
        // 1100 + 960 ms of playback, minus the 84 ms beyond the 16 ms jitter dead band.
        assertEquals(1_976.0, last.toDouble(), 2.0)
    }

    @Test
    fun `frozen at the reported value when paused`() {
        val clock = playingAt(1_000, frameMs = 0)
        clock.positionAt(ms(40))
        clock.onTick(1_035, isPlaying = false, frameTimeNanos = ms(40))
        assertEquals(1_035, clock.positionAt(ms(40)))
        assertEquals(1_035, clock.positionAt(ms(5_000)))

        clock.onTick(4_000, isPlaying = false, frameTimeNanos = ms(5_000))
        assertEquals(4_000, clock.positionAt(ms(6_000)))
    }

    @Test
    fun `snaps on resume`() {
        val clock = playingAt(1_000, frameMs = 0)
        clock.onTick(1_000, isPlaying = false, frameTimeNanos = ms(0))
        // Resume after a long pause with a small drift: snap, no blending, no time jump.
        clock.onTick(1_050, isPlaying = true, frameTimeNanos = ms(10_000))
        assertEquals(1_050, clock.positionAt(ms(10_000)))
        assertEquals(1_066, clock.positionAt(ms(10_016)))
    }

    /** Drives [clock] at [rate] with frames every 16 ms and exact ticks every 48 ms. */
    private fun playSteadily(
        clock: SmoothPositionEstimator,
        rate: Double,
        fromFrame: Int,
        toFrame: Int,
        startMs: Double = 0.0,
        onTick: (estimateMs: Long, reportedMs: Long, frame: Int) -> Unit = { _, _, _ -> }
    ) {
        for (frame in fromFrame..toFrame) {
            val frameMs = frame * 16L
            if (frame % 3 == 0) {
                val reportedMs = (startMs + (frameMs - fromFrame * 16L) * rate).toLong()
                clock.onTick(reportedMs, isPlaying = true, frameTimeNanos = ms(frameMs))
                onTick(clock.positionAt(ms(frameMs)), reportedMs, frame)
            } else {
                clock.positionAt(ms(frameMs))
            }
        }
    }

    private fun assertTracksRate(rate: Double) {
        val clock = playingAt(0, frameMs = 0)
        // The rate converges over several 500 ms samples, so check the steady state after 8 s.
        playSteadily(clock, rate, fromFrame = 0, toFrame = 900) { estimateMs, reportedMs, frame ->
            if (frame > 500) {
                assertTrue(
                    "frame $frame: estimate $estimateMs vs reported $reportedMs",
                    abs(estimateMs - reportedMs) <= 20
                )
            }
        }
    }

    @Test
    fun `tracks steady 1_5x playback within 20 ms`() = assertTracksRate(1.5)

    @Test
    fun `tracks steady 0_5x playback within 20 ms`() = assertTracksRate(0.5)

    @Test
    fun `seek keeps the learned rate`() {
        val clock = playingAt(0, frameMs = 0)
        playSteadily(clock, rate = 1.5, fromFrame = 0, toFrame = 600)

        // 600 * 16 ms at 1.5x = 14400 ms; seek far ahead.
        clock.onTick(60_000, isPlaying = true, frameTimeNanos = ms(9_600))
        assertEquals(60_000, clock.positionAt(ms(9_600)))
        // No ticks for 160 ms: the estimate keeps advancing at ~1.5x, not 1x.
        assertEquals(60_240.0, clock.positionAt(ms(9_760)).toDouble(), 5.0)
    }

    @Test
    fun `jittery 1x ticks do not make the speed wobble`() {
        val random = Random(42)
        val clock = SmoothPositionEstimator()
        // Mixer ticks every 40 ms carrying their emission time; each is observed at the first
        // 16 ms frame after emission, i.e. with a random 0-16 ms delay. Like the composable,
        // onTick is called only when a new value is observed.
        var nextTickIndex = 0
        var nextEmissionMs = 0L
        var previousPosition: Long? = null
        for (frame in 0..1_000) {
            val frameMs = frame * 16L
            var observedMs: Long? = null
            while (nextEmissionMs <= frameMs) {
                observedMs = nextEmissionMs
                nextTickIndex++
                nextEmissionMs = nextTickIndex * 40L + random.nextLong(0, 16)
            }
            observedMs?.let { clock.onTick(it, isPlaying = true, frameTimeNanos = ms(frameMs)) }
            val position = clock.positionAt(ms(frameMs))
            if (frameMs > 1_000 && previousPosition != null) {
                val advance = position - previousPosition
                assertTrue("frame $frame advanced $advance ms in 16 ms", abs(advance - 16) <= 0.8)
            }
            previousPosition = position
        }
    }

    @Test
    fun `uses a known rate directly without learning`() {
        val clock = SmoothPositionEstimator()
        clock.onTick(0, isPlaying = true, frameTimeNanos = ms(0), knownRate = 1.25)
        assertEquals(1_250, clock.positionAt(ms(1_000)))
        // Exact ticks at the known rate never cause a correction.
        clock.onTick(1_250, isPlaying = true, frameTimeNanos = ms(1_000), knownRate = 1.25)
        assertEquals(2_500, clock.positionAt(ms(2_000)))
    }
}
