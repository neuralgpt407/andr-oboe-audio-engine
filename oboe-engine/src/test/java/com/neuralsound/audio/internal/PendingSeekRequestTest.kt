package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSeekRequestTest {
    @Test
    fun `queued seek keeps latest target and preserves play request`() {
        val queued = PendingSeekRequest(targetMs = 2_000L, playAfterSeek = true)

        val replacement = queued.replacedBy(targetMs = 6_000L, playAfterSeek = false)

        assertEquals(6_000L, replacement.targetMs)
        assertTrue(replacement.playAfterSeek)
    }
}
