package com.neuralsound.audio.internal.video

import kotlin.math.abs

/**
 * Pure, stateful correction policy for the muted video renderer.
 *
 * Drift is always `audioPositionMs - videoPositionMs`: a positive value means that video is
 * behind the authoritative native-audio clock.
 *
 * - Within the dead-band nothing happens.
 * - Between the dead-band and the hard threshold the video speed is nudged proportionally
 *   (at most ±[VideoSyncPolicyConfig.maxSoftCorrection]) until drift settles within
 *   [VideoSyncPolicyConfig.softSettleMs]. A seek always lands a little behind (the decoder
 *   needs time), and on real media that residual stayed constant below the hard threshold.
 * - Above the hard threshold (with hysteresis, confirmation and cooldown) the video seeks.
 */
internal class VideoSyncPolicy(
    private val config: VideoSyncPolicyConfig = VideoSyncPolicyConfig(),
) {
    private var hardBandEntered = false
    private var qualifyingSamples = 0
    private var cooldownUntilMs = NO_DEADLINE
    private var graceUntilMs = NO_DEADLINE
    private var softActive = false

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
            if (!softActive) return suppressed(driftMs, VideoSyncSuppressionReason.DEADBAND)
            if (absoluteDriftMs <= config.softSettleMs) {
                softActive = false
                return VideoSyncDecision(driftMs, VideoSyncAction.SoftCorrect(1f))
            }
            return softCorrection(driftMs)
        }

        if (hardBandEntered) {
            if (absoluteDriftMs <= config.hardCorrectionExitMs) {
                clearConfirmation()
                return softCorrection(driftMs)
            }
        } else {
            if (absoluteDriftMs < config.hardCorrectionEnterMs) {
                qualifyingSamples = 0
                return softCorrection(driftMs)
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
        softActive = false
        return VideoSyncDecision(
            driftMs = driftMs,
            action = VideoSyncAction.HardSeek(targetPositionMs),
        )
    }

    /** Clears correction history and optionally starts a bounded tempo-transition grace window. */
    @Synchronized
    fun reset(elapsedRealtimeMs: Long, tempoGraceMs: Long = 0L) {
        clearConfirmation()
        softActive = false
        cooldownUntilMs = NO_DEADLINE
        graceUntilMs = if (tempoGraceMs > 0L) {
            deadlineAfter(elapsedRealtimeMs, tempoGraceMs)
        } else {
            NO_DEADLINE
        }
    }

    /** Speed factor for the video: above 1 catches up when it is behind, below 1 lets audio catch up. */
    private fun softCorrection(driftMs: Long): VideoSyncDecision {
        softActive = true
        val factor = 1f + (driftMs.toFloat() / config.softHorizonMs)
            .coerceIn(-config.maxSoftCorrection, config.maxSoftCorrection)
        return VideoSyncDecision(driftMs, VideoSyncAction.SoftCorrect(factor))
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
    /** Drift that a soft correction removes per second at full proportion (gain = 1 / horizon). */
    val softHorizonMs: Long = 1_000L,
    /** Largest speed change used by soft correction, as a fraction of the tempo. */
    val maxSoftCorrection: Float = 0.1f,
    /** A soft correction ends once drift is within this many milliseconds. */
    val softSettleMs: Long = 20L,
) {
    init {
        require(deadbandMs >= 0L)
        require(hardCorrectionExitMs >= deadbandMs)
        require(hardCorrectionEnterMs > hardCorrectionExitMs)
        require(confirmationSamples >= 1)
        require(hardCorrectionCooldownMs >= 0L)
        require(softHorizonMs > 0L)
        require(maxSoftCorrection in 0f..0.5f)
        require(softSettleMs in 0L..deadbandMs)
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

    /** Multiply the tempo by [speedFactor] for the video; 1 ends the correction. */
    data class SoftCorrect(val speedFactor: Float) : VideoSyncAction
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
