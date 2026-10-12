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
     * The current mix: every loaded track's applied (clamped) settings as [TrackMix], solo flags
     * included, in load/append order, plus the applied pitch/tempo, range and loop. Tracks whose
     * settings are still waiting for [appendTrack] (see [applyMix]) are not listed. Feed it to
     * [PlayerMix.toText] to persist it and to [applyMix] to restore it.
     */
    val mix: StateFlow<PlayerMix>

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

    /**
     * Applies a whole [PlayerMix] as one queued command. For track ids that are loaded, settings
     * are clamped like [updateTrack] and solo flags set; ids not loaded yet are remembered and
     * applied (then forgotten) when that id is added with [appendTrack], overriding the settings
     * on the appended [Track]. Loaded tracks absent from [mix] are left unchanged. Pitch/tempo,
     * range and loop are applied too. The result lists the ids applied now and those pending.
     *
     * Called before [load] it succeeds with every id pending; the mix (including pitch/tempo,
     * range and loop) is then applied by the next [load] and overrides that source's own values.
     * Any other [load] starts with no solo and no pending mix. Fails with `Released` after [release].
     */
    suspend fun applyMix(mix: PlayerMix): AudioResult<MixApplyResult>

    /**
     * Solos or un-solos a track. While any track is soloed, only soloed tracks are audible;
     * a track you muted stays silent even when soloed. [PlayerState] keeps reporting the user's
     * own mute, never the solo-implied silence. Unknown ids are ignored.
     */
    fun setSolo(id: TrackId, solo: Boolean)

    /** Controls the optional video surface. */
    val video: VideoController

    /**
     * Releases this player and returns what is needed to continue in a new player
     * (see [restore]), or null if nothing was loaded.
     *
     * The snapshot does not carry solo or pending mix settings: save [mix] before detaching and
     * call [applyMix] on the new player after [restore] to bring them back.
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
