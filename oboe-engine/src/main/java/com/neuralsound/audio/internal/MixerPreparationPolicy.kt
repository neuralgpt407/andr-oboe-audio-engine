package com.neuralsound.audio.internal

import com.neuralsound.audio.AudioFailure

sealed interface MixerPreparationResult {
    data object Success : MixerPreparationResult

    data class Failure(
        val message: String,
        val failure: AudioFailure? = null,
    ) : MixerPreparationResult
}

internal sealed interface MixerAppendResult {
    data object Success : MixerAppendResult
    data class Failure(val failure: AudioFailure) : MixerAppendResult
}

internal object MixerPreparationPolicy {
    fun result(
        initialized: Boolean,
        offsetsApplied: Boolean,
        seekCompleted: Boolean,
    ): MixerPreparationResult {
        return when {
            !initialized -> MixerPreparationResult.Failure("native mixer was not initialized")
            !offsetsApplied -> MixerPreparationResult.Failure("track offsets were not applied")
            !seekCompleted -> MixerPreparationResult.Failure("requested seek did not complete")
            else -> MixerPreparationResult.Success
        }
    }
}

internal object TrackReadOffsetPolicy {
    fun sourcePositionMs(timelinePositionMs: Long, offsetMs: Long): Long? {
        return (timelinePositionMs + offsetMs).takeIf { it >= 0L }
    }
}
