package com.neuralsound.audio.internal

internal enum class AppendTrackDecision {
    APPEND,
    ALREADY_APPENDED,
    CONFLICTING_TRACK,
}

internal object AppendTrackPolicy {
    fun decide(existingUriString: String?, requestedUriString: String): AppendTrackDecision {
        return when {
            existingUriString == null -> AppendTrackDecision.APPEND
            existingUriString == requestedUriString -> AppendTrackDecision.ALREADY_APPENDED
            else -> AppendTrackDecision.CONFLICTING_TRACK
        }
    }
}
