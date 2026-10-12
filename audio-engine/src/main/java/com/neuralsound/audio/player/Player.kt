package com.neuralsound.audio.player

import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays one or more tracks on a single clock, with an optional muted video kept in sync.
 *
 * Collect [state] for everything the UI renders and [events] for one-off happenings. Every
 * method is safe to call from the main thread; transport and settings calls return
 * immediately and their effect shows up in [state].
 */
interface Player {
    val state: StateFlow<PlayerState>
    val events: Flow<PlayerEvent>

    /**
     * Smooth playback position for frame-rate UI such as lyrics, and UI-rate position updates.
     * After a seek, [PlayerState.positionMs] shows the target immediately and never reverts to
     * the pre-seek position.
     */
    val clock: PlaybackClock

    /** Replaces whatever is loaded with [source]. */
    suspend fun load(source: PlayerSource): AudioResult<Unit>

    /** Adds a track to the loaded source, joining at the current position (also while playing). */
    suspend fun appendTrack(track: Track): AudioResult<Unit>

    fun play()
    fun pause()
    fun togglePlayPause()

    fun seekTo(positionMs: Long)

    /** Seeks to [fraction] (0..1) of the duration. */
    fun seekToFraction(fraction: Float)

    /** Seeks relative to the current position, e.g. -10_000 for a 10 s skip back. */
    fun seekBy(deltaMs: Long)

    fun seekAndPlay(positionMs: Long)

    /** Restricts playback to [range]; with [loop] it repeats, otherwise it ends at the range end. */
    fun setRange(range: PlaybackRange?, loop: Boolean)

    /** Applies pitch and tempo to the whole mix. Values are clamped; the applied value is in [state]. */
    fun setPitchTempo(pitchTempo: PitchTempo)

    /** Changes one track's settings in a single call, e.g. `updateTrack(id) { it.copy(muted = true) }`. */
    fun updateTrack(id: TrackId, transform: (TrackSettings) -> TrackSettings)

    /** Controls the optional video surface. */
    val video: VideoController

    /**
     * Releases this player and returns what is needed to continue in a new player
     * (see [restore]), or null if nothing was loaded.
     */
    suspend fun detach(): PlayerSnapshot?

    /** Loads [snapshot] into this player, resuming position, settings and play state. */
    suspend fun restore(snapshot: PlayerSnapshot): AudioResult<Unit>

    /** Pipeline timing since the last reset, or null when nothing is loaded. */
    fun diagnostics(reset: Boolean = false): CallbackStats?

    /** Releases native resources. Returns after they are freed; later calls fail with `Released`. */
    suspend fun release()
}

/** Presentation control for the player's muted video. Audio is never interrupted by these calls. */
interface VideoController {
    /** Creates (or re-creates) the video player; it appears in [PlayerState.video]. */
    fun attachPresentation()

    /** Stops decoding video frames while audio continues, e.g. when the surface is hidden. */
    fun suspendPresentation()

    /** Releases the video player; audio continues. */
    fun detachPresentation()

    /** Call when the hosting surface was recreated so observers can rebind. */
    fun notifySurfaceRecreated()
}
