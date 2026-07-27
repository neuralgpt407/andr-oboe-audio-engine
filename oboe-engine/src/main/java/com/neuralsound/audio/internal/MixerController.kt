package com.neuralsound.audio.internal

import android.net.Uri
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.ChannelGain
import com.neuralsound.audio.MixerTrackState
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackId
import kotlinx.coroutines.flow.StateFlow

/**
 * Boundary between the public session state machine and Android/native mixer
 * resources.
 */
internal interface MixerController {
    val routeRevision: StateFlow<Long>
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val totalDuration: StateFlow<Long>
    val tempoSpeed: StateFlow<Float>
    val pitchSemitones: StateFlow<Int>
    val runtimeFailure: StateFlow<AudioFailure?>

    fun currentRoutedOutputDeviceId(): Int?
    fun currentRoutedOutputDeviceType(deviceId: Int?): Int?
    fun currentOutputTopologyRevision(): Long
    fun setPlaybackRange(range: PlaybackRange?)

    suspend fun preparePlayers(
        tracks: Map<TrackId, Uri>,
        startPositionMs: Long,
        looping: Boolean,
        effects: PlaybackEffects,
        trackOffsetsMs: Map<TrackId, Long>,
        initialVolumes: Map<TrackId, Float>,
        initialChannelGains: Map<TrackId, ChannelGain>,
    ): MixerPreparationResult

    fun appendTrack(
        type: TrackId,
        uri: Uri,
        initialVolume: Float,
        muted: Boolean,
        initialChannelGain: ChannelGain,
    ): MixerAppendResult

    fun play(): MixerPlaybackResult
    fun pause(): MixerPlaybackResult
    fun setVolume(type: TrackId, volume: Float)
    fun setChannelGain(type: TrackId, channelGain: ChannelGain)
    fun setMuted(type: TrackId, muted: Boolean)
    fun setTrackOffset(type: TrackId, offsetMs: Long)
    fun setTempo(speed: Float)
    fun setPitchSemitones(semitones: Int)
    fun setLooping(looping: Boolean)
    fun seekToMs(positionMs: Long): Boolean
    fun snapshotTracks(): Map<TrackId, MixerTrackState>
    fun currentPlaybackRange(): PlaybackRange?
    fun isPrepared(): Boolean
    fun closeNow()
}
