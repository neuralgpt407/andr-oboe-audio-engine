package com.neuralsound.audio

sealed interface AudioResult {
    data object Success : AudioResult
    data class Failure(val failure: AudioFailure) : AudioResult
}

sealed interface AudioFailure {
    data object NativeUnavailable : AudioFailure
    data object Released : AudioFailure
    data object NotPrepared : AudioFailure
    data object EngineInactive : AudioFailure
    data class SourceUnavailable(val uri: String) : AudioFailure
    data class NativeOperationFailed(val operation: String, val detail: String? = null) : AudioFailure
}
