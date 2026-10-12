package com.neuralsound.audio.internal.player

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Turns coarse (~40 ms) playback position ticks into a smooth per-frame position.
 *
 * While playing, the estimate advances with frame time scaled by the playback rate. When a
 * known rate is supplied (the tempo) it is used directly; otherwise the rate is measured over
 * spans of at least 500 ms so that frame-observation jitter stays around 3%, then smoothed, and
 * rates within 4% of 1x are treated as exactly 1x. Tick errors beyond one frame of observation
 * jitter are blended in over the following frames. Large errors (seeks), pause and resume snap
 * to the reported value and keep the learned rate. The estimate never moves backwards while
 * playing, except on a snap.
 * Not thread-safe; callers synchronize.
 */
internal class SmoothPositionEstimator(
    private val snapThresholdMs: Long = DEFAULT_SNAP_THRESHOLD_MS,
    private val blendPerFrame: Double = DEFAULT_BLEND_PER_FRAME,
) {
    private var estimateMs = 0.0
    private var pendingCorrectionMs = 0.0
    private var lastFrameNanos = 0L
    private var playing = false
    private var initialized = false

    private var rate = 1.0
    private var knownRate: Double? = null
    private var rateAnchorReportedMs = 0L
    private var rateAnchorFrameNanos = 0L

    fun onTick(reportedMs: Long, isPlaying: Boolean, frameTimeNanos: Long, knownRate: Double? = null) {
        if (playing && isPlaying) advanceTo(frameTimeNanos)
        this.knownRate = knownRate

        val errorMs = reportedMs - estimateMs
        val resumed = isPlaying && !playing
        if (!initialized || !isPlaying || resumed || abs(errorMs) > snapThresholdMs) {
            if (!initialized) rate = 1.0
            snapTo(reportedMs, frameTimeNanos)
        } else {
            // Soft dead band: errors within one frame of observation jitter are not corrected.
            val excessMs = (abs(errorMs) - CORRECTION_DEAD_BAND_MS).coerceAtLeast(0.0)
            pendingCorrectionMs = if (errorMs < 0) -excessMs else excessMs
            if (knownRate != null) setRateAnchor(reportedMs, frameTimeNanos) else updateRate(reportedMs, frameTimeNanos)
        }
        playing = isPlaying
        initialized = true
    }

    /** Jumps to [reportedMs] without blending, e.g. on a discontinuity. Keeps the learned rate. */
    fun snap(reportedMs: Long, isPlaying: Boolean, frameTimeNanos: Long, knownRate: Double? = null) {
        this.knownRate = knownRate
        snapTo(reportedMs, frameTimeNanos)
        playing = isPlaying
        initialized = true
    }

    fun positionAt(frameTimeNanos: Long): Long {
        if (playing) advanceTo(frameTimeNanos)
        return estimateMs.roundToLong()
    }

    private fun snapTo(reportedMs: Long, frameTimeNanos: Long) {
        estimateMs = reportedMs.toDouble()
        pendingCorrectionMs = 0.0
        lastFrameNanos = frameTimeNanos
        setRateAnchor(reportedMs, frameTimeNanos)
    }

    private fun updateRate(reportedMs: Long, frameTimeNanos: Long) {
        val frameDeltaMs = (frameTimeNanos - rateAnchorFrameNanos) / NANOS_PER_MS
        // Keep the anchor until the span is long enough for frame jitter to be negligible.
        if (frameDeltaMs < MIN_RATE_SAMPLE_SPAN_MS) return
        val sample = (reportedMs - rateAnchorReportedMs) / frameDeltaMs
        rate = (rate + RATE_EMA_ALPHA * (sample - rate)).coerceIn(MIN_RATE, MAX_RATE)
        setRateAnchor(reportedMs, frameTimeNanos)
    }

    private fun setRateAnchor(reportedMs: Long, frameTimeNanos: Long) {
        rateAnchorReportedMs = reportedMs
        rateAnchorFrameNanos = frameTimeNanos
    }

    private fun advanceTo(frameTimeNanos: Long) {
        val elapsedMs = (frameTimeNanos - lastFrameNanos) / NANOS_PER_MS
        if (elapsedMs <= 0.0) return
        lastFrameNanos = frameTimeNanos

        val effectiveRate = knownRate ?: if (abs(rate - 1.0) <= UNITY_RATE_DEAD_BAND) 1.0 else rate
        val advanceMs = elapsedMs * effectiveRate
        // A negative correction may slow the clock down but never reverse it.
        val correctionMs = (pendingCorrectionMs * blendPerFrame).coerceAtLeast(-advanceMs)
        pendingCorrectionMs -= correctionMs
        estimateMs += advanceMs + correctionMs
    }

    companion object {
        const val DEFAULT_SNAP_THRESHOLD_MS = 150L
        const val DEFAULT_BLEND_PER_FRAME = 0.2
        private const val RATE_EMA_ALPHA = 0.25
        private const val MIN_RATE = 0.25
        private const val MAX_RATE = 4.0
        private const val UNITY_RATE_DEAD_BAND = 0.04
        private const val MIN_RATE_SAMPLE_SPAN_MS = 500.0
        private const val CORRECTION_DEAD_BAND_MS = 16.0
        private const val NANOS_PER_MS = 1_000_000.0
    }
}
