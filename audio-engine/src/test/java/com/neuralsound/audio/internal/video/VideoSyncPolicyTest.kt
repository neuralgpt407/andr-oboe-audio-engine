package com.neuralsound.audio.internal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSyncPolicyTest {
    @Test
    fun `seek is requested when ready video drifts beyond threshold`() {
        assertTrue(
            VideoSyncPolicy.shouldSeek(
                audioPositionMs = 1_000L,
                videoPositionMs = 1_251L,
                videoReady = true,
            )
        )
    }

    @Test
    fun `seek is skipped while video is not ready`() {
        assertFalse(
            VideoSyncPolicy.shouldSeek(
                audioPositionMs = 1_000L,
                videoPositionMs = 2_000L,
                videoReady = false,
            )
        )
    }

    @Test
    fun `aspect ratio includes pixel width height ratio`() {
        assertEquals(
            2f,
            VideoSyncPolicy.aspectRatio(
                width = 1_920,
                height = 1_080,
                pixelWidthHeightRatio = 1.125f,
            ),
        )
    }
}
