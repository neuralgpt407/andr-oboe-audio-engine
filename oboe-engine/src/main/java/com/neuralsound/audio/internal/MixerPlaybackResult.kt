package com.neuralsound.audio.internal

sealed interface MixerPlaybackResult {
    data object Success : MixerPlaybackResult

    data class Failure(
        val reason: MixerPlaybackFailureReason,
    ) : MixerPlaybackResult
}

enum class MixerPlaybackFailureReason {
    NOT_PREPARED,
    ENGINE_INACTIVE,
    NATIVE_START_FAILED,
}
