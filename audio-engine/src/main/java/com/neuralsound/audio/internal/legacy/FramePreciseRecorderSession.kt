package com.neuralsound.audio.internal.legacy

import com.neuralsound.audio.TrackId

import kotlinx.coroutines.flow.StateFlow

internal interface FramePreciseRecorderSession : RecorderSession {
    val state: StateFlow<RecorderState>
    val currentFailure: StateFlow<RecorderFailure?>

    fun startWriting(request: FrameRecordingRequest): RecorderOperationResult
}
