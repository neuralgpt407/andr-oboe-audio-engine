package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Test

class AppendTrackPolicyTest {

    @Test
    fun sameUriForExistingTypeIsAlreadyAppended() {
        val uri = "file://vocal.m4a"

        val decision = AppendTrackPolicy.decide(existingUriString = uri, requestedUriString = uri)

        assertEquals(AppendTrackDecision.ALREADY_APPENDED, decision)
    }

    @Test
    fun differentUriForExistingTypeIsConflictingTrack() {
        val decision = AppendTrackPolicy.decide(
            existingUriString = "file://vocal-old.m4a",
            requestedUriString = "file://vocal-new.m4a"
        )

        assertEquals(AppendTrackDecision.CONFLICTING_TRACK, decision)
    }

    @Test
    fun missingExistingTypeCanAppend() {
        val decision = AppendTrackPolicy.decide(
            existingUriString = null,
            requestedUriString = "file://bass.m4a"
        )

        assertEquals(AppendTrackDecision.APPEND, decision)
    }
}
