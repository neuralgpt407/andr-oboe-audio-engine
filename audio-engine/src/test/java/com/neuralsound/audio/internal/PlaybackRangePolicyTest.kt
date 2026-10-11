package com.neuralsound.audio.internal

import com.neuralsound.audio.PlaybackRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRangePolicyTest {
    @Test
    fun `seek target is clamped to active playback range`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertEquals(2_000L, PlaybackRangePolicy.seekTarget(500L, range, 10_000L))
        assertEquals(5_000L, PlaybackRangePolicy.seekTarget(5_000L, range, 10_000L))
        assertEquals(8_000L, PlaybackRangePolicy.seekTarget(9_000L, range, 10_000L))
    }

    @Test
    fun `play starts at range start when position is outside range`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertEquals(2_000L, PlaybackRangePolicy.playStartPosition(1_000L, range, 10_000L))
        assertEquals(4_000L, PlaybackRangePolicy.playStartPosition(4_000L, range, 10_000L))
        assertEquals(2_000L, PlaybackRangePolicy.playStartPosition(8_000L, range, 10_000L))
    }

    @Test
    fun `play starts at range start when position is near range end`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertEquals(2_000L, PlaybackRangePolicy.playStartPosition(7_960L, range, 10_000L))
    }

    @Test
    fun `loop is requested when position reaches range end`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertFalse(PlaybackRangePolicy.shouldLoopAtPosition(7_900L, range, 10_000L))
        assertTrue(PlaybackRangePolicy.shouldLoopAtPosition(7_960L, range, 10_000L))
    }

    @Test
    fun `completion loops when active range exists without explicit looping`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertTrue(
            PlaybackRangePolicy.shouldLoopOnCompletion(
                activeRange = range,
                explicitLooping = false,
            )
        )
    }

    @Test
    fun `completion stops when no active range exists without explicit looping`() {
        assertFalse(
            PlaybackRangePolicy.shouldLoopOnCompletion(
                activeRange = null,
                explicitLooping = false,
            )
        )
    }

    @Test
    fun `completion loops when explicit looping is enabled without active range`() {
        assertTrue(
            PlaybackRangePolicy.shouldLoopOnCompletion(
                activeRange = null,
                explicitLooping = true,
            )
        )
    }

    @Test
    fun `loop start uses active range start`() {
        val range = PlaybackRange(2_000L, 8_000L)

        assertEquals(2_000L, PlaybackRangePolicy.loopStart(range))
    }
}
