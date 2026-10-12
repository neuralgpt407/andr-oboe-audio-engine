package com.neuralsound.audio.internal.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSyncPolicyTest {
    private val config = VideoSyncPolicyConfig(
        deadbandMs = 75L,
        hardCorrectionEnterMs = 450L,
        hardCorrectionExitMs = 250L,
        confirmationSamples = 2,
        hardCorrectionCooldownMs = 2_500L,
    )

    @Test
    fun `drift sign is positive when video is behind and negative when video is ahead`() {
        val behind = VideoSyncPolicy(config).decide(sample(audioMs = 1_200L, videoMs = 1_100L))
        val ahead = VideoSyncPolicy(config).decide(sample(audioMs = 1_100L, videoMs = 1_200L))

        assertEquals(100L, behind.driftMs)
        assertEquals(-100L, ahead.driftMs)
    }

    @Test
    fun `deadband includes its boundary`() {
        val policy = VideoSyncPolicy(config)

        val decision = policy.decide(sample(audioMs = 1_075L, videoMs = 1_000L))

        assertEquals(VideoSyncAction.NoAction, decision.action)
        assertEquals(VideoSyncSuppressionReason.DEADBAND, decision.suppressionReason)
    }

    @Test
    fun `hard entry boundary requires two consecutive samples`() {
        val policy = VideoSyncPolicy(config)

        val first = policy.decide(sample(audioMs = 1_450L, videoMs = 1_000L, nowMs = 0L))
        val second = policy.decide(sample(audioMs = 1_450L, videoMs = 1_000L, nowMs = 250L))

        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, first.suppressionReason)
        assertEquals(VideoSyncAction.HardSeek(1_450L), second.action)
    }

    @Test
    fun `hysteresis keeps a qualifying sequence above the exit threshold`() {
        val policy = VideoSyncPolicy(config)

        val first = policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 0L))
        val second = policy.decide(sample(audioMs = 1_300L, videoMs = 1_000L, nowMs = 250L))

        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, first.suppressionReason)
        assertEquals(VideoSyncAction.HardSeek(1_300L), second.action)
    }

    @Test
    fun `one spike does not survive a return to the exit boundary`() {
        val policy = VideoSyncPolicy(config)

        policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 0L))
        val exited = policy.decide(sample(audioMs = 1_250L, videoMs = 1_000L, nowMs = 250L))
        val nextSpike = policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 500L))

        assertEquals(VideoSyncSuppressionReason.BELOW_HARD_THRESHOLD, exited.suppressionReason)
        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, nextSpike.suppressionReason)
    }

    @Test
    fun `cooldown suppresses until its exact expiry`() {
        val policy = VideoSyncPolicy(config)
        policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 0L))
        policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 250L))

        val beforeExpiry = policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 2_749L))
        val atExpiry = policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 2_750L))

        assertEquals(VideoSyncSuppressionReason.COOLDOWN, beforeExpiry.suppressionReason)
        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, atExpiry.suppressionReason)
    }

    @Test
    fun `tempo grace suppresses correction and confirmation starts after exact expiry`() {
        val policy = VideoSyncPolicy(config)
        policy.reset(elapsedRealtimeMs = 10_000L, tempoGraceMs = 1_000L)

        val duringGrace = policy.decide(
            sample(audioMs = 2_000L, videoMs = 1_000L, nowMs = 10_999L),
        )
        val afterGrace = policy.decide(
            sample(audioMs = 2_000L, videoMs = 1_000L, nowMs = 11_000L),
        )

        assertEquals(VideoSyncSuppressionReason.TEMPO_GRACE, duringGrace.suppressionReason)
        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, afterGrace.suppressionReason)
    }

    @Test
    fun `reset clears confirmation and automatic seek cooldown`() {
        val policy = VideoSyncPolicy(config)
        policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 0L))
        policy.decide(sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 250L))

        policy.reset(elapsedRealtimeMs = 300L)
        val firstAfterReset = policy.decide(
            sample(audioMs = 1_500L, videoMs = 1_000L, nowMs = 300L),
        )

        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, firstAfterReset.suppressionReason)
    }

    @Test
    fun `hard seek target is clamped to duration for either drift direction`() {
        val behindPolicy = VideoSyncPolicy(config.copy(confirmationSamples = 1))
        val aheadPolicy = VideoSyncPolicy(config.copy(confirmationSamples = 1))

        val pastEnd = behindPolicy.decide(
            sample(audioMs = 12_000L, videoMs = 10_000L, durationMs = 10_500L),
        )
        val atStart = aheadPolicy.decide(
            sample(audioMs = -100L, videoMs = 1_000L, durationMs = 10_500L),
        )

        assertEquals(VideoSyncAction.HardSeek(10_500L), pastEnd.action)
        assertEquals(VideoSyncAction.HardSeek(0L), atStart.action)
    }

    @Test
    fun `invalid duration cannot produce a correction`() {
        val decision = VideoSyncPolicy(config.copy(confirmationSamples = 1)).decide(
            sample(audioMs = 2_000L, videoMs = 1_000L, durationMs = 0L),
        )

        assertEquals(VideoSyncAction.NoAction, decision.action)
        assertEquals(VideoSyncSuppressionReason.INVALID_DURATION, decision.suppressionReason)
    }

    @Test
    fun `known discontinuity bypasses grace cooldown and confirmation`() {
        val policy = VideoSyncPolicy(config)
        policy.reset(elapsedRealtimeMs = 1_000L, tempoGraceMs = 5_000L)

        val decision = policy.decide(
            sample(
                audioMs = 2_000L,
                videoMs = 1_000L,
                nowMs = 1_001L,
                explicitDiscontinuity = true,
            ),
        )

        assertEquals(VideoSyncAction.HardSeek(2_000L), decision.action)
    }

    @Test
    fun `deadline arithmetic saturates at long max without wrapping`() {
        val policy = VideoSyncPolicy(config)
        policy.reset(elapsedRealtimeMs = Long.MAX_VALUE - 5L, tempoGraceMs = 10L)

        val beforeMax = policy.decide(
            sample(audioMs = 2_000L, videoMs = 1_000L, nowMs = Long.MAX_VALUE - 1L),
        )
        val atMax = policy.decide(
            sample(audioMs = 2_000L, videoMs = 1_000L, nowMs = Long.MAX_VALUE),
        )

        assertEquals(VideoSyncSuppressionReason.TEMPO_GRACE, beforeMax.suppressionReason)
        assertEquals(VideoSyncSuppressionReason.CONFIRMATION, atMax.suppressionReason)
        assertTrue(atMax.driftMs > 0L)
    }

    private fun sample(
        audioMs: Long,
        videoMs: Long,
        durationMs: Long = 10_000L,
        nowMs: Long = 0L,
        explicitDiscontinuity: Boolean = false,
    ) = VideoSyncSample(
        audioPositionMs = audioMs,
        videoPositionMs = videoMs,
        durationMs = durationMs,
        elapsedRealtimeMs = nowMs,
        explicitDiscontinuity = explicitDiscontinuity,
    )
}
