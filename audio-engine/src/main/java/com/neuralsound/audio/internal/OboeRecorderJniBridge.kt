package com.neuralsound.audio.internal

import com.neuralsound.audio.RecorderTelemetry

internal data class NativeRecordingResult(
    val durationMs: Long,
    val acceptedFrames: Long,
    val writtenFrames: Long,
    val sampleRate: Int,
    val failed: Boolean,
)

internal data class NativeRecorderFailureSnapshot(
    val code: Int,
    val message: String,
)

internal enum class NativeRecorderFailure(val code: Int) {
    NONE(0),
    MIC_SESSION_OPEN_FAILED(1),
    STREAM_DISCONNECTED(2),
    WRITER_OVERFLOW(3),
    WRITER_FILE_ERROR(4),
    INVALID_OUTPUT(5),
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): NativeRecorderFailure {
            return entries.firstOrNull { it.code == code } ?: UNKNOWN
        }
    }
}

internal interface OboeRecorderNativeBridge {
    fun create(owner: NativeRecorderSession): Long
    fun startMicSession(owner: NativeRecorderSession, handle: Long): Boolean
    fun startWriting(owner: NativeRecorderSession, handle: Long, outputPath: String, startOffsetMs: Long): Boolean
    fun startWritingAtFrame(
        owner: NativeRecorderSession,
        handle: Long,
        outputPath: String,
        startOffsetFrames: Long,
    ): Boolean
    fun pauseWriting(owner: NativeRecorderSession, handle: Long)
    fun stopWriting(owner: NativeRecorderSession, handle: Long): NativeRecordingResult
    fun getMicPeak(owner: NativeRecorderSession, handle: Long): Float
    fun getTelemetry(
        owner: NativeRecorderSession,
        handle: Long,
        lastConsumedBucketIndex: Int,
    ): RecorderTelemetry?
    fun getWrittenDurationMs(owner: NativeRecorderSession, handle: Long): Long
    fun getSampleRate(owner: NativeRecorderSession, handle: Long): Int
    fun hasFailed(owner: NativeRecorderSession, handle: Long): Boolean
    fun getFailureSnapshot(
        owner: NativeRecorderSession,
        handle: Long,
    ): NativeRecorderFailureSnapshot
    fun releaseMicSession(owner: NativeRecorderSession, handle: Long)
    fun release(owner: NativeRecorderSession, handle: Long)
}

internal object OboeRecorderJniBridge : OboeRecorderNativeBridge {
    override fun create(owner: NativeRecorderSession): Long = owner.nativeCreate()

    override fun startMicSession(owner: NativeRecorderSession, handle: Long): Boolean {
        return owner.nativeStartMicSession(handle)
    }

    override fun startWriting(
        owner: NativeRecorderSession,
        handle: Long,
        outputPath: String,
        startOffsetMs: Long,
    ): Boolean {
        return owner.nativeStartWriting(handle, outputPath, startOffsetMs)
    }

    override fun startWritingAtFrame(
        owner: NativeRecorderSession,
        handle: Long,
        outputPath: String,
        startOffsetFrames: Long,
    ): Boolean {
        return owner.nativeStartWritingAtFrame(handle, outputPath, startOffsetFrames)
    }

    override fun pauseWriting(owner: NativeRecorderSession, handle: Long) {
        owner.nativePauseWriting(handle)
    }

    override fun stopWriting(owner: NativeRecorderSession, handle: Long): NativeRecordingResult {
        val values = owner.nativeStopWriting(handle)
        return NativeRecordingResult(
            durationMs = values.getOrNull(0)?.coerceAtLeast(0L) ?: 0L,
            acceptedFrames = values.getOrNull(1)?.coerceAtLeast(0L) ?: 0L,
            writtenFrames = values.getOrNull(2)?.coerceAtLeast(0L) ?: 0L,
            sampleRate = values.getOrNull(3)?.coerceAtLeast(0L)?.toInt() ?: 0,
            failed = values.size != NATIVE_RESULT_FIELD_COUNT || values.getOrNull(4) == 1L,
        )
    }

    override fun getMicPeak(owner: NativeRecorderSession, handle: Long): Float {
        return owner.nativeGetMicPeak(handle)
    }

    override fun getTelemetry(
        owner: NativeRecorderSession,
        handle: Long,
        lastConsumedBucketIndex: Int,
    ): RecorderTelemetry? {
        return owner.nativeGetTelemetry(handle, lastConsumedBucketIndex)
    }

    override fun getWrittenDurationMs(owner: NativeRecorderSession, handle: Long): Long {
        return owner.nativeGetWrittenDurationMs(handle)
    }

    override fun getSampleRate(owner: NativeRecorderSession, handle: Long): Int {
        return owner.nativeGetSampleRate(handle)
    }

    override fun hasFailed(owner: NativeRecorderSession, handle: Long): Boolean {
        return owner.nativeHasFailed(handle)
    }

    override fun getFailureSnapshot(
        owner: NativeRecorderSession,
        handle: Long,
    ): NativeRecorderFailureSnapshot {
        return owner.nativeGetFailureSnapshot(handle)
    }

    override fun releaseMicSession(owner: NativeRecorderSession, handle: Long) {
        owner.nativeReleaseMicSession(handle)
    }

    override fun release(owner: NativeRecorderSession, handle: Long) {
        owner.nativeRelease(handle)
    }

    private const val NATIVE_RESULT_FIELD_COUNT = 5
}
