package com.neuralsound.audio.player

/**
 * State captured by [Player.detach] so another player can continue seamlessly via
 * [Player.restore] (e.g. separation preview → full player).
 */
data class PlayerSnapshot(
    /** Tracks with their applied settings. */
    val source: PlayerSource,
    val positionMs: Long,
    val wasPlaying: Boolean,
)
