package com.neuralsound.audio.player

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId

/** One-off happenings; persistent facts live in [PlayerState]. */
sealed interface PlayerEvent {
    /** Playback reached its natural end. Emitted once per end, never while looping. */
    data class Completed(val reason: CompletionReason, val positionMs: Long) : PlayerEvent

    /** A track failed during load or append; other tracks continue. */
    data class TrackFailed(val trackId: TrackId, val reason: TrackFailureReason) : PlayerEvent

    data class FocusChanged(val focus: FocusState) : PlayerEvent

    data class RouteChanged(val route: AudioRoute) : PlayerEvent

    /** The player hit an error that moved it to `FAILED`. */
    data class Failed(val error: AudioError) : PlayerEvent
}

enum class CompletionReason {
    END_OF_MEDIA,
    END_OF_RANGE,
}

enum class FocusState {
    GAINED,
    DUCKED,
    LOST_TRANSIENT,
    LOST,
}
