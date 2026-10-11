package com.neuralsound.audio

import android.content.Context
import com.neuralsound.audio.internal.NativeMixerController
import com.neuralsound.audio.internal.player.DefaultPlayer
import com.neuralsound.audio.internal.waveform.DefaultWaveformAnalyzer
import com.neuralsound.audio.player.Player
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.waveform.WaveformAnalyzer

/**
 * Entry point of the SDK. Create one per process (for example in your DI graph) and ask it
 * for a [Player] per screen, a [Recorder] per recording flow, and the shared [waveforms]
 * analyzer.
 */
class AudioEngine private constructor(context: Context) {
    private val applicationContext: Context = context.applicationContext

    /** False when the native library could not be loaded; every session call then fails with [AudioError.Unavailable]. */
    val isAvailable: Boolean
        get() = NativeMixerController.nativeLibraryAvailable

    /** Creates an independent player. Release it with [Player.release] when the owner goes away. */
    fun newPlayer(options: PlayerOptions = PlayerOptions()): Player = DefaultPlayer(
        controller = NativeMixerController(
            applicationContext,
            maxTracks = options.maxTracks.coerceIn(1, PlayerOptions.MAX_SUPPORTED_TRACKS),
        ),
        options = options,
        nativeAvailable = isAvailable,
    )

    /** Creates an independent recorder. Release it with [Recorder.release] when the owner goes away. */
    fun newRecorder(): Recorder {
        TODO("Implemented in FEAT-0001 Task 21")
    }

    /** Offline file waveform analysis. */
    val waveforms: WaveformAnalyzer by lazy { DefaultWaveformAnalyzer() }

    companion object {
        @JvmStatic
        fun create(context: Context): AudioEngine = AudioEngine(context)
    }
}
