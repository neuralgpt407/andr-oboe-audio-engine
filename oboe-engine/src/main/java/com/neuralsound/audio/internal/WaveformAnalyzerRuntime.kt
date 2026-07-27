package com.neuralsound.audio.internal

import com.neuralsound.audio.NativeAudioWaveformAnalyzer
import com.neuralsound.audio.NativeWaveformAnalysisError
import com.neuralsound.audio.NativeWaveformAnalysisResult
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal interface NativeWaveformBridge {
    fun create(fd: Int, maxOutputSamples: Int): Long
    fun analyze(handle: Long): FloatArray?
    fun failure(handle: Long): Int
    fun cancel(handle: Long)
    fun release(handle: Long)
}

internal object JniNativeWaveformBridge : NativeWaveformBridge {
    override fun create(fd: Int, maxOutputSamples: Int): Long {
        return nativeCreate(fd, maxOutputSamples)
    }

    override fun analyze(handle: Long): FloatArray? = nativeAnalyze(handle)

    override fun failure(handle: Long): Int = nativeGetFailureKind(handle)

    override fun cancel(handle: Long) {
        nativeCancel(handle)
    }

    override fun release(handle: Long) {
        nativeRelease(handle)
    }

    private external fun nativeCreate(fd: Int, maxOutputSamples: Int): Long
    private external fun nativeAnalyze(handle: Long): FloatArray?
    private external fun nativeGetFailureKind(handle: Long): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeRelease(handle: Long)
}

internal enum class NativeWaveformFailure(val code: Int) {
    NONE(0),
    UNREADABLE(1),
    UNSUPPORTED(2),
    CORRUPT(3),
    EMPTY_MEDIA(4),
    CANCELLED(5),
    INVALID_ARGUMENT(6),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): NativeWaveformFailure {
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}

internal class WaveformAnalyzerRuntime(
    private val dispatcher: CoroutineDispatcher,
    private val nativeBridge: NativeWaveformBridge,
    private val nativeLibraryLoader: () -> Boolean,
) {
    private val activeHandles = ConcurrentHashMap.newKeySet<Long>()

    suspend fun analyzeFileDescriptor(
        fd: Int,
        maxSamples: Int = NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES,
    ): NativeWaveformAnalysisResult = withContext(dispatcher) {
        if (fd < 0) {
            return@withContext failure(NativeWaveformAnalysisError.Unreadable)
        }
        if (maxSamples !in 1..NativeAudioWaveformAnalyzer.MAX_OUTPUT_SAMPLES) {
            return@withContext failure(NativeWaveformAnalysisError.InvalidArgument)
        }
        if (!nativeLibraryLoader()) {
            return@withContext failure(NativeWaveformAnalysisError.NativeUnavailable)
        }

        suspendCancellableCoroutine<NativeWaveformAnalysisResult> { continuation ->
            val handle = runCatching {
                nativeBridge.create(fd, maxSamples)
            }.getOrDefault(0L)
            if (handle == 0L) {
                if (continuation.isActive) {
                    continuation.resume(
                        failure(NativeWaveformAnalysisError.NativeUnavailable)
                    )
                }
                return@suspendCancellableCoroutine
            }
            activeHandles += handle

            continuation.invokeOnCancellation {
                cancelHandle(handle)
            }

            val result = try {
                val levels = nativeBridge.analyze(handle)
                when {
                    levels == null -> failure(
                        NativeWaveformFailure.fromCode(
                            nativeBridge.failure(handle)
                        ).toAnalysisError()
                    )

                    levels.isEmpty() ||
                        levels.size > maxSamples ||
                        levels.any { !it.isFinite() || it !in 0.0f..1.0f } ->
                        failure(NativeWaveformAnalysisError.Corrupt)

                    else -> NativeWaveformAnalysisResult.Success(levels)
                }
            } catch (_: UnsatisfiedLinkError) {
                failure(NativeWaveformAnalysisError.NativeUnavailable)
            } finally {
                activeHandles -= handle
                runCatching { nativeBridge.release(handle) }
            }

            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    fun cancel() {
        activeHandles.forEach(::cancelHandle)
    }

    private fun cancelHandle(handle: Long) {
        runCatching { nativeBridge.cancel(handle) }
    }

    private fun failure(error: NativeWaveformAnalysisError) =
        NativeWaveformAnalysisResult.Failure(error)
}

private fun NativeWaveformFailure.toAnalysisError(): NativeWaveformAnalysisError = when (this) {
    NativeWaveformFailure.UNREADABLE -> NativeWaveformAnalysisError.Unreadable
    NativeWaveformFailure.UNSUPPORTED -> NativeWaveformAnalysisError.Unsupported
    NativeWaveformFailure.CORRUPT -> NativeWaveformAnalysisError.Corrupt
    NativeWaveformFailure.EMPTY_MEDIA -> NativeWaveformAnalysisError.EmptyMedia
    NativeWaveformFailure.CANCELLED -> NativeWaveformAnalysisError.Cancelled
    NativeWaveformFailure.INVALID_ARGUMENT -> NativeWaveformAnalysisError.InvalidArgument
    NativeWaveformFailure.NONE,
    NativeWaveformFailure.UNKNOWN,
    -> NativeWaveformAnalysisError.Corrupt
}
