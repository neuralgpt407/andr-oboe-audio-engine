package com.neuralsound.audio.internal.video

import kotlin.math.abs

internal object VideoSyncPolicy {
    private const val MAX_DRIFT_MS = 250L

    fun shouldSeek(
        audioPositionMs: Long,
        videoPositionMs: Long,
        videoReady: Boolean,
    ): Boolean {
        return videoReady && abs(audioPositionMs - videoPositionMs) > MAX_DRIFT_MS
    }

    fun aspectRatio(
        width: Int,
        height: Int,
        pixelWidthHeightRatio: Float,
    ): Float? {
        if (width <= 0 || height <= 0 || pixelWidthHeightRatio <= 0f) return null
        return (width.toFloat() * pixelWidthHeightRatio) / height.toFloat()
    }
}
