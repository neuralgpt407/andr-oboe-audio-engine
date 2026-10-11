package com.neuralsound.audio.internal

internal sealed interface MixerPlaybackResult {
    data object Success : MixerPlaybackResult

    data class Failure(
        val reason: MixerPlaybackFailureReason,
    ) : MixerPlaybackResult
}

internal enum class MixerPlaybackFailureReason {
    NOT_PREPARED,
    ENGINE_INACTIVE,
    NATIVE_START_FAILED,
}
