package com.neuralsound.audio.internal.player

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.legacy.AudioFailure
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.internal.legacy.ChannelGain as InternalChannelGain
import com.neuralsound.audio.internal.legacy.PlaybackEffects as InternalPlaybackEffects
import com.neuralsound.audio.internal.legacy.PlaybackRange as InternalPlaybackRange

/** Translation between the public player models and the mixer controller's internal models. */
internal object LegacyMapping {
    fun toInternal(gain: ChannelGain) = InternalChannelGain(left = gain.left, right = gain.right)

    fun toInternal(pitchTempo: PitchTempo): InternalPlaybackEffects {
        val clamped = pitchTempo.clamped()
        return InternalPlaybackEffects(tempo = clamped.tempo, pitchSemitones = clamped.semitones)
    }

    fun toInternal(range: PlaybackRange?) = range?.let { InternalPlaybackRange(it.startMs, it.endMs) }

    fun toPublic(range: InternalPlaybackRange?) = range?.let { PlaybackRange(it.startMs, it.endMs) }

    /**
     * Maps a controller failure to the public error model. URIs carried by the internal
     * failure are resolved back to the caller's [TrackId] and never exposed.
     */
    fun toPublic(
        failure: AudioFailure,
        trackIdForUri: (String) -> TrackId?,
        nativeAvailable: Boolean = true,
    ): AudioError = when (failure) {
        AudioFailure.NativeUnavailable -> AudioError.Unavailable
        AudioFailure.Released -> AudioError.Released
        AudioFailure.NotPrepared -> AudioError.NotLoaded
        AudioFailure.EngineInactive -> if (nativeAvailable) AudioError.InvalidState else AudioError.Unavailable
        is AudioFailure.SourceUnavailable -> trackIdForUri(failure.uri)
            ?.let { AudioError.TrackFailed(it, TrackFailureReason.UNREADABLE_SOURCE) }
            ?: AudioError.Native("openTrack")
        is AudioFailure.TrackFailed -> AudioError.TrackFailed(failure.trackId, failure.reason)
        is AudioFailure.UnsupportedTrackFormat ->
            AudioError.TrackFailed(failure.trackId, TrackFailureReason.UNSUPPORTED_CODEC)
        is AudioFailure.NativeOperationFailed -> AudioError.Native(failure.operation)
    }
}
