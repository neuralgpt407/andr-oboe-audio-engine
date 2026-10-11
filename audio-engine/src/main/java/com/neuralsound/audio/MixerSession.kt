package com.neuralsound.audio

import kotlinx.coroutines.flow.StateFlow

interface MixerSession : AutoCloseable {
    val state: StateFlow<MixerState>

    suspend fun prepare(request: MixerRequest): AudioResult
    fun appendTrack(track: MixerTrack): AudioResult
    fun play(): AudioResult
    fun pause(): AudioResult
    fun seekTo(positionMs: Long): AudioResult
    fun setPlaybackRange(range: PlaybackRange?): AudioResult
    fun setLooping(looping: Boolean): AudioResult
    fun updateTrackMix(trackId: TrackId, mix: TrackMix): AudioResult
    fun setTrackOffset(trackId: TrackId, offsetMs: Long): AudioResult
    fun setEffects(effects: PlaybackEffects): AudioResult
    override fun close()
}
