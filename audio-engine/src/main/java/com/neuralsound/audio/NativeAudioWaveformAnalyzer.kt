package com.neuralsound.audio

import android.os.ParcelFileDescriptor
import com.neuralsound.audio.internal.JniNativeWaveformBridge
import com.neuralsound.audio.internal.OboeRecorderNativeLibrary
import com.neuralsound.audio.internal.WaveformAnalyzerRuntime
import kotlinx.coroutines.Dispatchers

sealed interface NativeWaveformAnalysisResult {
    class Success(val levels: FloatArray) : NativeWaveformAnalysisResult

    data class Failure(
        val error: NativeWaveformAnalysisError,
    ) : NativeWaveformAnalysisResult
}

enum class NativeWaveformAnalysisError {
    Unreadable,
    Unsupported,
    Corrupt,
    EmptyMedia,
    Cancelled,
    InvalidArgument,
    NativeUnavailable,
}

/**
 * Decodes and analyzes media entirely in native code. Analysis runs on
 * [Dispatchers.IO] and remains independent of the real-time Oboe playback
 * callback.
 *
 * The caller retains ownership of [source]. Native code duplicates its file
 * descriptor, and coroutine cancellation is forwarded to the native decode
 * loop before the analyzer handle is released.
 */
class NativeAudioWaveformAnalyzer {
    private val runtime = WaveformAnalyzerRuntime(
        dispatcher = Dispatchers.IO,
        nativeBridge = JniNativeWaveformBridge,
        nativeLibraryLoader = OboeRecorderNativeLibrary::isAvailable,
    )

    suspend fun analyze(
        source: ParcelFileDescriptor,
        maxSamples: Int = MAX_OUTPUT_SAMPLES,
    ): NativeWaveformAnalysisResult {
        val fd = runCatching { source.fd }.getOrElse {
            return NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.Unreadable,
            )
        }
        return runtime.analyzeFileDescriptor(fd, maxSamples)
    }

    /**
     * Requests cancellation for every analysis currently owned by this
     * analyzer. An analysis that observes the request completes with
     * [NativeWaveformAnalysisError.Cancelled]; analyses already finishing may
     * complete normally. Analyses started afterward are unaffected.
     */
    fun cancel() {
        runtime.cancel()
    }

    companion object {
        const val ALGORITHM_VERSION = 3
        const val MAX_OUTPUT_SAMPLES = 1024
    }
}
