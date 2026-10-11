package com.neuralsound.audio.player

/**
 * Per-player behaviour. Values are checked when the player loads; an invalid value makes
 * [Player.load] fail with `AudioError.InvalidConfiguration`.
 */
data class PlayerOptions(
    /** Maximum number of tracks this player accepts (1..[MAX_SUPPORTED_TRACKS]). */
    val maxTracks: Int = DEFAULT_MAX_TRACKS,
    val focus: FocusPolicy = FocusPolicy.Duck(),
    /** Pause when headphones are unplugged or a Bluetooth headset disconnects. */
    val pauseOnNoisy: Boolean = true,
) {
    companion object {
        const val DEFAULT_MAX_TRACKS = 8
        const val MAX_SUPPORTED_TRACKS = 16
    }
}

/** What the player does when another app takes audio focus. Permanent focus loss always pauses. */
sealed interface FocusPolicy {
    /** Lower the output to [level] (0..1) while another app plays briefly. */
    data class Duck(val level: Float = 0.2f) : FocusPolicy

    /** Pause on transient loss and resume automatically when focus returns. */
    data object PauseAndResume : FocusPolicy

    /** Do not request audio focus (e.g. a short preview inside a recording screen). */
    data object Ignore : FocusPolicy
}
