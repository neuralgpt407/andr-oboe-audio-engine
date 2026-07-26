package com.neuralsound.audio

import kotlinx.coroutines.flow.StateFlow

interface RecorderSession : AutoCloseable {
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
