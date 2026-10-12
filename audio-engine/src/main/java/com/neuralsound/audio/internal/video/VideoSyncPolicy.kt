package com.neuralsound.audio.internal.video

import kotlin.math.abs

/**
 * Pure, stateful correction policy for the muted video renderer.
 *
 * Drift is always `audioPositionMs - videoPositionMs`: a positive value means that video is
 * behind the authoritative native-audio clock. The first production policy intentionally uses
 * hard correction only. Soft speed correction remains an evidence-gated experiment.
 */
internal class VideoSyncPolicy(
    private val config: VideoSyncPolicyConfig = VideoSyncPolicyConfig(),
) {
    private var hardBandEntered = false
    private var qualifyingSamples = 0
    private var cooldownUntilMs = NO_DEADLINE
    private var graceUntilMs = NO_DEADLINE

    @Synchronized
    fun decide(sample: VideoSyncSample): VideoSyncDecision {
        val audioPositionMs = sample.audioPositionMs.coerceAtLeast(0L)
        val videoPositionMs = sample.videoPositionMs.coerceAtLeast(0L)
        val driftMs = audioPositionMs - videoPositionMs
        val absoluteDriftMs = abs(driftMs)

        if (sample.durationMs <= 0L) {
            clearConfirmation()
            return suppressed(driftMs, VideoSyncSuppressionReason.INVALID_DURATION)
        }

        if (!sample.explicitDiscontinuity && beforeDeadline(sample.elapsedRealtimeMs, graceUntilMs)) {
            clearConfirmation()
            return suppressed(driftMs, VideoSyncSuppressionReason.TEMPO_GRACE)
        }

        if (!sample.explicitDiscontinuity && beforeDeadline(sample.elapsedRealtimeMs, cooldownUntilMs)) {
            clearConfirmation()
            return suppressed(driftMs, VideoSyncSuppressionReason.COOLDOWN)
        }

        if (absoluteDriftMs <= config.deadbandMs) {
            clearConfirmation()
            return suppressed(driftMs, VideoSyncSuppressionReason.DEADBAND)
        }

        if (hardBandEntered) {
            if (absoluteDriftMs <= config.hardCorrectionExitMs) {
                clearConfirmation()
                return suppressed(driftMs, VideoSyncSuppressionReason.BELOW_HARD_THRESHOLD)
            }
        } else {
            if (absoluteDriftMs < config.hardCorrectionEnterMs) {
                qualifyingSamples = 0
                return suppressed(driftMs, VideoSyncSuppressionReason.BELOW_HARD_THRESHOLD)
            }
            hardBandEntered = true
        }

        qualifyingSamples += 1
        if (!sample.explicitDiscontinuity && qualifyingSamples < config.confirmationSamples) {
            return suppressed(driftMs, VideoSyncSuppressionReason.CONFIRMATION)
        }

        val targetPositionMs = audioPositionMs.coerceIn(0L, sample.durationMs)
        cooldownUntilMs = deadlineAfter(sample.elapsedRealtimeMs, config.hardCorrectionCooldownMs)
        clearConfirmation()
        return VideoSyncDecision(
            driftMs = driftMs,
            action = VideoSyncAction.HardSeek(targetPositionMs),
        )
    }

    /** Clears correction history and optionally starts a bounded tempo-transition grace window. */
    @Synchronized
    fun reset(elapsedRealtimeMs: Long, tempoGraceMs: Long = 0L) {
        clearConfirmation()
        cooldownUntilMs = NO_DEADLINE
        graceUntilMs = if (tempoGraceMs > 0L) {
            deadlineAfter(elapsedRealtimeMs, tempoGraceMs)
        } else {
            NO_DEADLINE
        }
    }

    private fun clearConfirmation() {
        hardBandEntered = false
        qualifyingSamples = 0
    }

    private fun suppressed(
        driftMs: Long,
        reason: VideoSyncSuppressionReason,
    ) = VideoSyncDecision(
        driftMs = driftMs,
        action = VideoSyncAction.NoAction,
        suppressionReason = reason,
    )

    private fun beforeDeadline(nowMs: Long, deadlineMs: Long): Boolean =
        deadlineMs != NO_DEADLINE && nowMs < deadlineMs

    private fun deadlineAfter(nowMs: Long, durationMs: Long): Long =
        if (durationMs > Long.MAX_VALUE - nowMs) Long.MAX_VALUE else nowMs + durationMs

    private companion object {
        const val NO_DEADLINE = -1L
    }
}

internal data class VideoSyncPolicyConfig(
    val deadbandMs: Long = 75L,
    val hardCorrectionEnterMs: Long = 450L,
    val hardCorrectionExitMs: Long = 250L,
    val confirmationSamples: Int = 2,
    val hardCorrectionCooldownMs: Long = 2_500L,
) {
    init {
        require(deadbandMs >= 0L)
        require(hardCorrectionExitMs >= deadbandMs)
        require(hardCorrectionEnterMs > hardCorrectionExitMs)
        require(confirmationSamples >= 1)
        require(hardCorrectionCooldownMs >= 0L)
    }
}

internal data class VideoSyncSample(
    val audioPositionMs: Long,
    val videoPositionMs: Long,
    val durationMs: Long,
    val elapsedRealtimeMs: Long,
    /** Reserved for a known discontinuity; ordinary periodic drift samples always leave this false. */
    val explicitDiscontinuity: Boolean = false,
)

internal data class VideoSyncDecision(
    val driftMs: Long,
    val action: VideoSyncAction,
    val suppressionReason: VideoSyncSuppressionReason? = null,
)

internal sealed interface VideoSyncAction {
    data object NoAction : VideoSyncAction
    data class HardSeek(val targetPositionMs: Long) : VideoSyncAction
}

internal enum class VideoSyncSuppressionReason {
    DEADBAND,
    BELOW_HARD_THRESHOLD,
    CONFIRMATION,
    COOLDOWN,
    TEMPO_GRACE,
    INVALID_DURATION,
}

/** Aspect ratio of a decoded video frame, or null until its size is known. */
internal fun videoAspectRatio(width: Int, height: Int, pixelWidthHeightRatio: Float): Float? {
    if (width <= 0 || height <= 0 || pixelWidthHeightRatio <= 0f) return null
    return (width.toFloat() * pixelWidthHeightRatio) / height.toFloat()
}
