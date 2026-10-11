package com.neuralsound.audio

import android.net.Uri

data class ChannelGain(
    val left: Float = 1f,
    val right: Float = 1f,
) {
    init {
        require(left in MIN_GAIN..MAX_GAIN) { "Left channel gain must be between $MIN_GAIN and $MAX_GAIN" }
        require(right in MIN_GAIN..MAX_GAIN) { "Right channel gain must be between $MIN_GAIN and $MAX_GAIN" }
    }

    companion object {
        const val MIN_GAIN = 0f
        const val MAX_GAIN = 2f
        val Center = ChannelGain()
        val Left = ChannelGain(left = 1f, right = 0f)
        val Right = ChannelGain(left = 0f, right = 1f)
    }
}

data class TrackMix(
    val volume: Float = 0.8f,
    val muted: Boolean = false,
    val channelGain: ChannelGain = ChannelGain.Center,
) {
    init {
        require(volume in 0f..1f) { "Track volume must be between 0 and 1" }
    }
}

data class MixerTrack(
    val id: TrackId,
    val uri: Uri,
    val mix: TrackMix = TrackMix(),
    val offsetMs: Long = 0L,
)

data class PlaybackRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0L) { "Playback range start must not be negative" }
        require(endMs > startMs) { "Playback range end must be greater than start" }
    }

    fun clampTo(durationMs: Long): PlaybackRange {
        require(durationMs > 0L) { "Duration must be positive" }
        val clampedStart = startMs.coerceAtMost(durationMs - 1L)
        val clampedEnd = endMs.coerceIn(clampedStart + 1L, durationMs)
        return PlaybackRange(clampedStart, clampedEnd)
    }
}

data class PlaybackEffects(
    val tempo: Float = DEFAULT_TEMPO,
    val pitchSemitones: Int = DEFAULT_PITCH_SEMITONES,
) {
    init {
        require(tempo in MIN_TEMPO..MAX_TEMPO) { "Tempo must be between $MIN_TEMPO and $MAX_TEMPO" }
        require(pitchSemitones in MIN_PITCH_SEMITONES..MAX_PITCH_SEMITONES) {
            "Pitch must be between $MIN_PITCH_SEMITONES and $MAX_PITCH_SEMITONES semitones"
        }
    }

    companion object {
        const val DEFAULT_TEMPO = 1f
        const val MIN_TEMPO = 0.25f
        const val MAX_TEMPO = 4f
        const val DEFAULT_PITCH_SEMITONES = 0
        const val MIN_PITCH_SEMITONES = -12
        const val MAX_PITCH_SEMITONES = 12
    }
}

data class MixerRequest(
    val tracks: List<MixerTrack>,
    val startPositionMs: Long = 0L,
    val playbackRange: PlaybackRange? = null,
    val looping: Boolean = false,
    val effects: PlaybackEffects = PlaybackEffects(),
    val autoPlay: Boolean = false,
) {
    init {
        require(tracks.isNotEmpty()) { "At least one track is required" }
        require(tracks.map(MixerTrack::id).distinct().size == tracks.size) {
            "Mixer track IDs must be unique"
        }
        require(startPositionMs >= 0L) { "Start position must not be negative" }
    }
}

data class MixerTrackState(
    val mix: TrackMix,
    val offsetMs: Long,
)

data class AudioRoute(
    val deviceId: Int?,
    val deviceType: Int?,
    val topologyRevision: Long,
)

enum class MixerStatus {
    IDLE,
    PREPARING,
    READY,
    PLAYING,
    PAUSED,
    FAILED,
    RELEASED,
}

data class MixerState(
    val status: MixerStatus = MixerStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackRange: PlaybackRange? = null,
    val looping: Boolean = false,
    val effects: PlaybackEffects = PlaybackEffects(),
    val tracks: Map<TrackId, MixerTrackState> = emptyMap(),
    val route: AudioRoute? = null,
    val failure: AudioFailure? = null,
) {
    val isPlaying: Boolean
        get() = status == MixerStatus.PLAYING

    val progress: Float
        get() = if (durationMs > 0L) {
            (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
