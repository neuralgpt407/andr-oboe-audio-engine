package com.neuralsound.audio

/**
 * Every failure the engine reports. Errors never carry media URIs or file paths.
 */
sealed interface AudioError {
    /** The native engine library could not be loaded on this device. */
    data object Unavailable : AudioError

    /** The session was released; create a new one. */
    data object Released : AudioError

    /** A player call needs a loaded source first. */
    data object NotLoaded : AudioError

    /** The call is not valid in the session's current state. */
    data object InvalidState : AudioError

    /** A track could not be opened, decoded or played. */
    data class TrackFailed(val trackId: TrackId, val reason: TrackFailureReason) : AudioError

    /** The player already holds its configured maximum number of tracks. */
    data class TrackLimitExceeded(val limit: Int) : AudioError

    /** A session option is outside its supported range. */
    data class InvalidConfiguration(val reason: String) : AudioError

    /** The app has not been granted RECORD_AUDIO. */
    data object PermissionDenied : AudioError

    /** The microphone input stream could not be opened. */
    data object MicUnavailable : AudioError

    /** The audio device used by the session was disconnected. */
    data object Disconnected : AudioError

    /** Recording fell behind and audio was lost; the take was stopped. */
    data object WriterOverflow : AudioError

    /** The take file could not be created, read or written. */
    data object FileError : AudioError

    /** The requested feature is not allowed on the current output route. */
    data object RouteNotSupported : AudioError

    /** A native operation failed for a reason not covered above. */
    data class Native(val operation: String) : AudioError
}

enum class TrackFailureReason {
    UNREADABLE_SOURCE,
    NO_AUDIO_TRACK,
    UNSUPPORTED_CODEC,
    CORRUPT_MEDIA,
    DECODE_FAILURE,
    EMPTY_SOURCE,
}
