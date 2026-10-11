package com.neuralsound.audio.internal.legacy

import com.neuralsound.audio.TrackId

import kotlinx.coroutines.flow.StateFlow

internal interface RecorderSession : AutoCloseable {
    val status: StateFlow<RecorderStatus>
    val micAmplitude: Float
    val writtenDurationMs: Long

    fun startMicSession(): RecorderOperationResult
    fun startWriting(request: RecordingRequest): RecorderOperationResult
    fun pauseWriting(): RecorderOperationResult
    fun stopWriting(): RecordingResult
    fun telemetry(afterBucketIndex: Int): RecorderTelemetry?
    fun releaseMicSession(): RecorderOperationResult
    override fun close()
}
