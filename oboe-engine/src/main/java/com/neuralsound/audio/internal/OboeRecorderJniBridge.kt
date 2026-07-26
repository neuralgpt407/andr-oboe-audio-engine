package com.neuralsound.audio.internal

import com.neuralsound.audio.RecorderTelemetry

internal interface OboeRecorderNativeBridge {
    fun create(owner: NativeRecorderSession): Long
    fun startMicSession(owner: NativeRecorderSession, handle: Long): Boolean
    fun startWriting(owner: NativeRecorderSession, handle: Long, outputPath: String, startOffsetMs: Long): Boolean
    fun pauseWriting(owner: NativeRecorderSession, handle: Long)
    fun stopWriting(owner: NativeRecorderSession, handle: Long): LongArray
    fun getMicPeak(owner: NativeRecorderSession, handle: Long): Float
    fun getTelemetry(
        owner: NativeRecorderSession,
        handle: Long,
        lastConsumedBucketIndex: Int,
    ): RecorderTelemetry?
    fun getWrittenDurationMs(owner: NativeRecorderSession, handle: Long): Long
    fun getSampleRate(owner: NativeRecorderSession, handle: Long): Int
    fun hasFailed(owner: NativeRecorderSession, handle: Long): Boolean
    fun getLastError(owner: NativeRecorderSession, handle: Long): String
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

    override fun pauseWriting(owner: NativeRecorderSession, handle: Long) {
        owner.nativePauseWriting(handle)
    }

    override fun stopWriting(owner: NativeRecorderSession, handle: Long): LongArray {
        return owner.nativeStopWriting(handle)
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

    override fun getLastError(owner: NativeRecorderSession, handle: Long): String {
        return owner.nativeGetLastError(handle)
    }

    override fun releaseMicSession(owner: NativeRecorderSession, handle: Long) {
        owner.nativeReleaseMicSession(handle)
    }

    override fun release(owner: NativeRecorderSession, handle: Long) {
        owner.nativeRelease(handle)
    }
}
