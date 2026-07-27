package com.neuralsound.audio

import kotlinx.coroutines.flow.StateFlow

interface FramePreciseRecorderSession : RecorderSession {
    val currentFailure: StateFlow<RecorderFailure?>

    fun startWriting(request: FrameRecordingRequest): RecorderOperationResult
}
