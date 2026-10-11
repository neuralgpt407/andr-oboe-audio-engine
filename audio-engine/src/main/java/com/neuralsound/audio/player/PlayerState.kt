package com.neuralsound.audio.player

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId

/** Single source of truth for a player's UI. */
data class PlayerState(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** Tracks in load/append order. */
    val tracks: Map<TrackId, TrackState> = emptyMap(),
    /** The pitch/tempo actually applied (after clamping). */
    val pitchTempo: PitchTempo = PitchTempo(),
    val range: PlaybackRange? = null,
    val loop: Boolean = false,
    val route: AudioRoute? = null,
    val video: VideoState = VideoState(),
    /** Set while [status] is [PlayerStatus.FAILED]. */
    val error: AudioError? = null,
) {
    val isPlaying: Boolean
        get() = status == PlayerStatus.PLAYING

    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

enum class PlayerStatus {
    IDLE,
    LOADING,
    READY,
    PLAYING,
    PAUSED,
    /** Reached the end of the media or of a non-looping range. */
    ENDED,
    FAILED,
    RELEASED,
}

data class TrackState(
    /** Applied settings (after clamping). */
    val settings: TrackSettings,
    /** Set when this track could not be played; the other tracks keep playing. */
    val failure: TrackFailureReason? = null,
)

/** The output device the player is currently routed to. */
data class AudioRoute(
    val kind: RouteKind,
    /** `AudioDeviceInfo.getId()` of the routed device, when known. */
    val deviceId: Int? = null,
    /** Raw `AudioDeviceInfo.getType()`, when known. */
    val deviceType: Int? = null,
    /** Increments whenever output devices are added or removed. */
    val topologyRevision: Long = 0L,
)

enum class RouteKind {
    SPEAKER,
    WIRED,
    USB,
    BLUETOOTH,
    OTHER,
    UNKNOWN,
    ;

    /** Live microphone monitoring is only allowed on low-latency private outputs. */
    val allowsMonitoring: Boolean
        get() = this == WIRED || this == USB
}

data class VideoState(
    /** Media3 player to render in a `PlayerView`; null when there is no video or it is detached. */
    val player: androidx.media3.common.Player? = null,
    val hasVideo: Boolean = false,
    val firstFrameReady: Boolean = false,
    val aspectRatio: Float? = null,
    /** Increments whenever [player] is replaced, so views can rebind. */
    val revision: Long = 0L,
    val sync: VideoSyncStats = VideoSyncStats(),
)

data class VideoSyncStats(
    val lastDriftMs: Long = 0L,
    val hardCorrections: Long = 0L,
)
