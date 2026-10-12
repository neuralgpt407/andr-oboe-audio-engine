package com.neuralsound.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.neuralsound.audio.internal.NativeMixerController
import com.neuralsound.audio.internal.NativeRecorderSession
import com.neuralsound.audio.internal.recorder.DefaultRecorder
import com.neuralsound.audio.internal.recorder.MicForegroundController
import com.neuralsound.audio.internal.player.DefaultPlayer
import com.neuralsound.audio.internal.video.PlayerVideo
import com.neuralsound.audio.internal.waveform.DefaultWaveformAnalyzer
import com.neuralsound.audio.player.Player
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.internal.waveform.DefaultWaveformCache
import com.neuralsound.audio.waveform.WaveformAnalyzer
import com.neuralsound.audio.waveform.WaveformCache
import com.neuralsound.audio.waveform.WaveformCacheOptions
import java.io.File

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
            focusPolicy = options.focus,
            pauseOnNoisy = options.pauseOnNoisy,
        ),
        options = options,
        nativeAvailable = isAvailable,
        videoFactory = { state, publish -> PlayerVideo(applicationContext, state, publish) },
    )

    /** Creates an independent recorder. Release it with [Recorder.release] when the owner goes away. */
    fun newRecorder(): Recorder = DefaultRecorder(
        session = NativeRecorderSession(),
        hasRecordPermission = {
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        },
        foregroundControl = { MicForegroundController(applicationContext) },
    )

    /** Offline file waveform analysis. */
    val waveforms: WaveformAnalyzer by lazy { DefaultWaveformAnalyzer() }

    /** Process-wide waveform cache with default options. */
    val waveformCache: WaveformCache by lazy { newWaveformCache(WaveformCacheOptions()) }

    /** Creates a waveform cache with its own memory limit; caches share the on-disk directory. */
    fun newWaveformCache(options: WaveformCacheOptions): WaveformCache = DefaultWaveformCache(
        cacheDir = File(applicationContext.cacheDir, "neuralsound-waveforms"),
        contentResolver = applicationContext.contentResolver,
        options = options,
        analyzer = waveforms,
    )

    companion object {
        @JvmStatic
        fun create(context: Context): AudioEngine = AudioEngine(context)
    }
}
