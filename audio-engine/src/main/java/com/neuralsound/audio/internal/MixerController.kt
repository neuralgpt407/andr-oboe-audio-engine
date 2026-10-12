package com.neuralsound.audio.internal

import android.net.Uri
import com.neuralsound.audio.internal.legacy.AudioFailure
import com.neuralsound.audio.internal.legacy.ChannelGain
import com.neuralsound.audio.internal.legacy.MixerTrackState
import com.neuralsound.audio.internal.legacy.PlaybackEffects
import com.neuralsound.audio.internal.legacy.PlaybackRange
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.FocusState
import kotlinx.coroutines.flow.Flow
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

    /** Audio-focus transitions handled by the session's focus policy. */
    val focusChanges: Flow<FocusState>

    /** Emits once each time playback reaches its natural end without looping. */
    val completions: Flow<MixerCompletion>

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
        initialOffsetMs: Long,
    ): MixerAppendResult

    fun play(): MixerPlaybackResult
    fun pause(): MixerPlaybackResult
    fun setVolume(type: TrackId, volume: Float)
    fun setChannelGain(type: TrackId, channelGain: ChannelGain)
    fun setMuted(type: TrackId, muted: Boolean)
    fun setTrackOffset(type: TrackId, offsetMs: Long)
    fun setSourceBoostDb(type: TrackId, sourceBoostDb: Int)

    /** Post-limiter output gain 0..1, ramped natively; used for focus ducking. */
    fun setOutputAttenuation(attenuation: Float)
    fun setTempo(speed: Float)
    fun setPitchSemitones(semitones: Int)
    fun setLooping(looping: Boolean)
    fun seekToMs(positionMs: Long): Boolean
    fun snapshotTracks(): Map<TrackId, MixerTrackState>
    fun currentPlaybackRange(): PlaybackRange?
    fun isPrepared(): Boolean
    fun closeNow()
}

internal enum class MixerCompletion {
    END_OF_MEDIA,
    END_OF_RANGE,
}
