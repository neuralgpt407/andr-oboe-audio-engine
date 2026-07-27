package com.neuralsound.audio

import kotlinx.coroutines.flow.StateFlow

interface FramePreciseRecorderSession : RecorderSession {
    val state: StateFlow<RecorderState>
    val currentFailure: StateFlow<RecorderFailure?>

    fun startWriting(request: FrameRecordingRequest): RecorderOperationResult
}
