package com.neuralsound.audio.waveform

import android.net.Uri
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import kotlinx.coroutines.flow.Flow

/**
 * Waveforms of audio files, cached by content (not by path), in memory and on disk.
 *
 * Concurrent requests for the same content and level count share one analysis; the analysis is
 * cancelled when every requester has gone away. Failures are returned typed and never cached.
 */
interface WaveformCache {
    /** Waveform of [uri] (file or content Uri), from memory, disk, or a new analysis. */
    suspend fun get(uri: Uri, levels: Int = WaveformAnalyzer.MAX_LEVELS): AudioResult<Waveform>

    /** Loading → Ready/Failed for [uri]; cold Flow; cancelling the collector cancels its share of the analysis. */
    fun load(uri: Uri, levels: Int = WaveformAnalyzer.MAX_LEVELS): Flow<WaveformLoad>

    /** Removes every cached waveform (memory and disk). */
    suspend fun clear()
}

sealed interface WaveformLoad {
    data object Loading : WaveformLoad

    data class Ready(val waveform: Waveform) : WaveformLoad

    data class Failed(val error: AudioError) : WaveformLoad
}

/** Size limits of a [WaveformCache]. */
data class WaveformCacheOptions(
    val maxDiskBytes: Long = 32L shl 20,
    val memoryEntries: Int = 32,
)
