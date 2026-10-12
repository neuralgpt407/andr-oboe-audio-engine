package com.neuralsound.audio.internal.legacy

import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId

internal sealed interface AudioResult {
    data object Success : AudioResult
    data class Failure(val failure: AudioFailure) : AudioResult
}

internal sealed interface AudioFailure {
    data object NativeUnavailable : AudioFailure
    data object Released : AudioFailure
    data object NotPrepared : AudioFailure
    data object EngineInactive : AudioFailure
    data class SourceUnavailable(val uri: String) : AudioFailure
    data class UnsupportedTrackFormat(
        val trackId: TrackId,
        val sampleRate: Int?,
        val channelCount: Int?,
        val requiredSampleRate: Int?,
    ) : AudioFailure
    /** A specific track failed; carries the caller's id and a reason, never the media URI. */
    data class TrackFailed(val trackId: TrackId, val reason: TrackFailureReason) : AudioFailure
    data class NativeOperationFailed(val operation: String, val detail: String? = null) : AudioFailure
}
