package com.neuralsound.audio.waveform

import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioResult

/** Computes a bounded RMS envelope of an audio file. Cancel by cancelling the calling coroutine. */
interface WaveformAnalyzer {
    /** Changes whenever output for the same file would change; use it in cache keys. */
    val algorithmVersion: Int

    suspend fun analyze(
        descriptor: ParcelFileDescriptor,
        maxLevels: Int = MAX_LEVELS,
    ): AudioResult<Waveform>

    companion object {
        const val MAX_LEVELS = 1024
    }
}

class Waveform(
    /** Normalized levels 0..1, at most `maxLevels` entries. */
    val levels: FloatArray,
    val algorithmVersion: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is Waveform && algorithmVersion == other.algorithmVersion && levels.contentEquals(other.levels)

    override fun hashCode(): Int = 31 * algorithmVersion + levels.contentHashCode()

    override fun toString(): String = "Waveform(levels=${levels.size}, algorithmVersion=$algorithmVersion)"
}
