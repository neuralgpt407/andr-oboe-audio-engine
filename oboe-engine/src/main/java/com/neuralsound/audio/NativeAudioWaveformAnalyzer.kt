package com.neuralsound.audio

import android.os.ParcelFileDescriptor
import com.neuralsound.audio.internal.OboeRecorderNativeLibrary
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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
 * [Dispatchers.IO] by default and remains independent of the real-time Oboe
 * playback callback.
 *
 * The caller retains ownership of [source]. Native code duplicates its file
 * descriptor, and coroutine cancellation is forwarded to the native decode
 * loop before the analyzer handle is released.
 */
class NativeAudioWaveformAnalyzer internal constructor(
    private val dispatcher: CoroutineDispatcher,
    private val nativeBridge: NativeWaveformBridge,
    private val nativeLibraryLoader: () -> Boolean,
) {
    constructor() : this(
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
        return analyzeFileDescriptor(fd, maxSamples)
    }

    internal suspend fun analyzeFileDescriptor(
        fd: Int,
        maxSamples: Int = MAX_OUTPUT_SAMPLES,
    ): NativeWaveformAnalysisResult = withContext(dispatcher) {
        if (fd < 0) {
            return@withContext NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.Unreadable,
            )
        }
        if (maxSamples !in 1..MAX_OUTPUT_SAMPLES) {
            return@withContext NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.InvalidArgument,
            )
        }
        if (!nativeLibraryLoader()) {
            return@withContext NativeWaveformAnalysisResult.Failure(
                NativeWaveformAnalysisError.NativeUnavailable,
            )
        }

        suspendCancellableCoroutine<NativeWaveformAnalysisResult> { continuation ->
            val handle = runCatching {
                nativeBridge.create(
                    this@NativeAudioWaveformAnalyzer,
                    fd,
                    maxSamples,
                )
            }.getOrDefault(0L)
            if (handle == 0L) {
                if (continuation.isActive) {
                    continuation.resume(
                        NativeWaveformAnalysisResult.Failure(
                            NativeWaveformAnalysisError.NativeUnavailable,
                        ),
                    )
                }
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                runCatching {
                    nativeBridge.cancel(
                        this@NativeAudioWaveformAnalyzer,
                        handle,
                    )
                }
            }

            val result = try {
                val levels = nativeBridge.analyze(
                    this@NativeAudioWaveformAnalyzer,
                    handle,
                )
                when {
                    levels == null -> NativeWaveformAnalysisResult.Failure(
                        nativeBridge.failureKind(
                            this@NativeAudioWaveformAnalyzer,
                            handle,
                        ).toAnalysisError(),
                    )

                    levels.isEmpty() ||
                        levels.size > maxSamples ||
                        levels.any { !it.isFinite() || it !in 0.0f..1.0f } ->
                        NativeWaveformAnalysisResult.Failure(
                            NativeWaveformAnalysisError.Corrupt,
                        )

                    else -> NativeWaveformAnalysisResult.Success(levels)
                }
            } catch (_: UnsatisfiedLinkError) {
                NativeWaveformAnalysisResult.Failure(
                    NativeWaveformAnalysisError.NativeUnavailable,
                )
            } finally {
                runCatching {
                    nativeBridge.release(
                        this@NativeAudioWaveformAnalyzer,
                        handle,
                    )
                }
            }

            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    private external fun nativeCreate(fd: Int, maxOutputSamples: Int): Long
    private external fun nativeAnalyze(handle: Long): FloatArray?
    private external fun nativeGetFailureKind(handle: Long): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeRelease(handle: Long)

    companion object {
        const val ALGORITHM_VERSION = 3
        const val MAX_OUTPUT_SAMPLES = 1024
    }

    internal interface NativeWaveformBridge {
        fun create(
            owner: NativeAudioWaveformAnalyzer,
            fd: Int,
            maxOutputSamples: Int,
        ): Long

        fun analyze(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): FloatArray?

        fun failureKind(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): Int

        fun cancel(owner: NativeAudioWaveformAnalyzer, handle: Long)
        fun release(owner: NativeAudioWaveformAnalyzer, handle: Long)
    }

    private object JniNativeWaveformBridge : NativeWaveformBridge {
        override fun create(
            owner: NativeAudioWaveformAnalyzer,
            fd: Int,
            maxOutputSamples: Int,
        ): Long = owner.nativeCreate(fd, maxOutputSamples)

        override fun analyze(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): FloatArray? = owner.nativeAnalyze(handle)

        override fun failureKind(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ): Int = owner.nativeGetFailureKind(handle)

        override fun cancel(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ) {
            owner.nativeCancel(handle)
        }

        override fun release(
            owner: NativeAudioWaveformAnalyzer,
            handle: Long,
        ) {
            owner.nativeRelease(handle)
        }
    }
}

private fun Int.toAnalysisError(): NativeWaveformAnalysisError = when (this) {
    1 -> NativeWaveformAnalysisError.Unreadable
    2 -> NativeWaveformAnalysisError.Unsupported
    3 -> NativeWaveformAnalysisError.Corrupt
    4 -> NativeWaveformAnalysisError.EmptyMedia
    5 -> NativeWaveformAnalysisError.Cancelled
    6 -> NativeWaveformAnalysisError.InvalidArgument
    else -> NativeWaveformAnalysisError.Corrupt
}
