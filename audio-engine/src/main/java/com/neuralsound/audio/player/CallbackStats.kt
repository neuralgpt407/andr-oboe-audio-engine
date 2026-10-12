package com.neuralsound.audio.player

/**
 * Real-time health of a player's audio pipeline, for diagnostics and soak tests.
 * The audio callback only copies already-rendered audio; mixing, pitch/tempo and limiting run
 * on the render thread, whose load is reported as a fraction of real time.
 */
data class CallbackStats(
    val callbackMaxNs: Long,
    val callbackP99Ns: Long,
    /** Duration of the most recent output buffer. */
    val bufferPeriodNs: Long,
    /** 99th-percentile render time divided by the audio duration it produced (1.0 = real time). */
    val renderLoadP99: Float,
    val underruns: Long,
    val callbacks: Long,
) {
    /** 99th-percentile callback time as a fraction of the buffer period. */
    val callbackLoadP99: Float
        get() = if (bufferPeriodNs > 0) callbackP99Ns.toFloat() / bufferPeriodNs else 0f
}
