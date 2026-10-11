package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerPreparationPolicyTest {

    @Test
    fun preparationSucceedsOnlyWhenInitializationOffsetsAndSeekComplete() {
        assertEquals(
            MixerPreparationResult.Success,
            MixerPreparationPolicy.result(
                initialized = true,
                offsetsApplied = true,
                seekCompleted = true,
            ),
        )
    }

    @Test
    fun preparationReportsTheFailedStage() {
        val result = MixerPreparationPolicy.result(
            initialized = true,
            offsetsApplied = false,
            seekCompleted = false,
        )

        assertTrue(result is MixerPreparationResult.Failure)
        assertEquals("track offsets were not applied", (result as MixerPreparationResult.Failure).message)
    }

    @Test
    fun positiveUserVocalOffsetAdvancesTheSourceReadTime() {
        assertEquals(
            1_120L,
            TrackReadOffsetPolicy.sourcePositionMs(
                timelinePositionMs = 1_000L,
                offsetMs = 120L,
            ),
        )
    }

    @Test
    fun negativeOffsetBeforeTheSourceStartsUsesZeroFill() {
        assertEquals(
            null,
            TrackReadOffsetPolicy.sourcePositionMs(
                timelinePositionMs = 50L,
                offsetMs = -120L,
            ),
        )
    }

    @Test
    fun positiveOffsetOverflowSaturatesAtTheEndOfTheTimeline() {
        assertEquals(
            Long.MAX_VALUE,
            TrackReadOffsetPolicy.sourcePositionMs(
                timelinePositionMs = Long.MAX_VALUE - 5L,
                offsetMs = 10L,
            ),
        )
    }

    @Test
    fun negativeOffsetOverflowRemainsBeforeTheSourceStart() {
        assertEquals(
            null,
            TrackReadOffsetPolicy.sourcePositionMs(
                timelinePositionMs = Long.MIN_VALUE + 5L,
                offsetMs = -10L,
            ),
        )
    }
}
