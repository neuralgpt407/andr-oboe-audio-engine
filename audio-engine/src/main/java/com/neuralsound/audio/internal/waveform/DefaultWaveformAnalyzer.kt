package com.neuralsound.audio.internal.waveform

import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.internal.JniNativeWaveformBridge
import com.neuralsound.audio.internal.OboeRecorderNativeLibrary
import com.neuralsound.audio.internal.WaveformAnalyzerRuntime
import com.neuralsound.audio.internal.legacy.NativeWaveformAnalysisError
import com.neuralsound.audio.internal.legacy.NativeWaveformAnalysisResult
import com.neuralsound.audio.waveform.Waveform
import com.neuralsound.audio.waveform.WaveformAnalyzer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers

/** [WaveformAnalyzer] over the native decoder/RMS pipeline (algorithm version 3). */
internal class DefaultWaveformAnalyzer(
    private val runtime: WaveformAnalyzerRuntime = WaveformAnalyzerRuntime(
        dispatcher = Dispatchers.IO,
        nativeBridge = JniNativeWaveformBridge,
        nativeLibraryLoader = OboeRecorderNativeLibrary::isAvailable,
    ),
) : WaveformAnalyzer {
    override val algorithmVersion: Int = ALGORITHM_VERSION

    override suspend fun analyze(descriptor: ParcelFileDescriptor, maxLevels: Int): AudioResult<Waveform> {
        if (maxLevels !in 1..WaveformAnalyzer.MAX_LEVELS) {
            return AudioResult.Err(
                AudioError.InvalidConfiguration("maxLevels must be between 1 and ${WaveformAnalyzer.MAX_LEVELS}"),
            )
        }
        val fd = runCatching { descriptor.fd }.getOrElse { return AudioResult.Err(AudioError.FileError) }
        return when (val result = runtime.analyzeFileDescriptor(fd, maxLevels)) {
            is NativeWaveformAnalysisResult.Success -> AudioResult.Ok(Waveform(result.levels, ALGORITHM_VERSION))
            is NativeWaveformAnalysisResult.Failure -> AudioResult.Err(toPublic(result.error))
        }
    }

    private fun toPublic(error: NativeWaveformAnalysisError): AudioError = when (error) {
        NativeWaveformAnalysisError.Unreadable -> AudioError.FileError
        NativeWaveformAnalysisError.Unsupported -> AudioError.Native("waveform:unsupported-codec")
        NativeWaveformAnalysisError.Corrupt -> AudioError.Native("waveform:corrupt-media")
        NativeWaveformAnalysisError.EmptyMedia -> AudioError.Native("waveform:empty-media")
        NativeWaveformAnalysisError.InvalidArgument -> AudioError.InvalidConfiguration("waveform analysis argument")
        NativeWaveformAnalysisError.NativeUnavailable -> AudioError.Unavailable
        // Analysis is cancelled by cancelling the calling coroutine.
        NativeWaveformAnalysisError.Cancelled -> throw CancellationException("Waveform analysis cancelled")
    }

    companion object {
        const val ALGORITHM_VERSION = 3
    }
}
