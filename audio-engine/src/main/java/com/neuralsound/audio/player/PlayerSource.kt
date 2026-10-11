package com.neuralsound.audio.player

import android.net.Uri
import com.neuralsound.audio.TrackId

/** Everything a player needs to start: tracks, optional video and initial transport state. */
data class PlayerSource(
    val tracks: List<Track>,
    /** Muted video shown in sync with the audio clock. */
    val videoUri: Uri? = null,
    val startAtMs: Long = 0L,
    val range: PlaybackRange? = null,
    val loop: Boolean = false,
    val pitchTempo: PitchTempo = PitchTempo(),
    val autoPlay: Boolean = false,
) {
    init {
        require(tracks.isNotEmpty()) { "At least one track is required" }
        require(tracks.map(Track::id).distinct().size == tracks.size) { "Track IDs must be unique" }
        require(startAtMs >= 0L) { "Start position must not be negative" }
    }
}

data class Track(
    val id: TrackId,
    val uri: Uri,
    val settings: TrackSettings = TrackSettings(),
)

/**
 * All per-track mix settings. Out-of-range values are clamped by the player; the applied
 * values are reported in [TrackState.settings].
 */
data class TrackSettings(
    /** Linear gain 0..1. */
    val volume: Float = 1f,
    val muted: Boolean = false,
    val balance: ChannelGain = ChannelGain.Center,
    /**
     * Read offset against the shared timeline. Positive reads the track ahead (it sounds
     * earlier, e.g. compensating recording latency); negative delays it behind leading silence.
     */
    val offsetMs: Long = 0L,
    /** Pre-mix gain in whole dB, [MIN_BOOST_DB]..[MAX_BOOST_DB]; the master limiter prevents clipping. */
    val boostDb: Int = 0,
) {
    init {
        require(volume.isFinite()) { "Volume must be finite" }
    }

    fun clamped(): TrackSettings = copy(
        volume = volume.coerceIn(0f, 1f),
        offsetMs = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS),
        boostDb = boostDb.coerceIn(MIN_BOOST_DB, MAX_BOOST_DB),
    )

    companion object {
        const val MIN_BOOST_DB = 0
        const val MAX_BOOST_DB = 18
        const val MIN_OFFSET_MS = -10_000L
        const val MAX_OFFSET_MS = 10_000L
    }
}

/** Independent left/right gains (0..2 each). */
data class ChannelGain(
    val left: Float = 1f,
    val right: Float = 1f,
) {
    init {
        require(left in MIN_GAIN..MAX_GAIN) { "Left gain must be between $MIN_GAIN and $MAX_GAIN" }
        require(right in MIN_GAIN..MAX_GAIN) { "Right gain must be between $MIN_GAIN and $MAX_GAIN" }
    }

    companion object {
        const val MIN_GAIN = 0f
        const val MAX_GAIN = 2f

        @JvmField val Center = ChannelGain()
        @JvmField val Left = ChannelGain(left = 1f, right = 0f)
        @JvmField val Right = ChannelGain(left = 0f, right = 1f)

        /**
         * Balance from -1 (left only) through 0 (center) to 1 (right only): the opposite
         * channel is attenuated linearly.
         */
        @JvmStatic
        fun balance(value: Float): ChannelGain {
            val clamped = value.coerceIn(-1f, 1f)
            return ChannelGain(
                left = if (clamped > 0f) 1f - clamped else 1f,
                right = if (clamped < 0f) 1f + clamped else 1f,
            )
        }
    }
}

/** Pitch shift and time stretch applied to the whole mix. */
data class PitchTempo(
    val semitones: Int = 0,
    val tempo: Float = 1f,
) {
    init {
        require(tempo.isFinite() && tempo > 0f) { "Tempo must be a positive finite number" }
    }

    fun clamped(): PitchTempo = copy(
        semitones = semitones.coerceIn(MIN_SEMITONES, MAX_SEMITONES),
        tempo = tempo.coerceIn(MIN_TEMPO, MAX_TEMPO),
    )

    val isNeutral: Boolean
        get() = semitones == 0 && tempo == 1f

    companion object {
        const val MIN_SEMITONES = -10
        const val MAX_SEMITONES = 10
        const val MIN_TEMPO = 0.5f
        const val MAX_TEMPO = 2f
    }
}

data class PlaybackRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0L) { "Range start must not be negative" }
        require(endMs > startMs) { "Range end must be after its start" }
    }

    val durationMs: Long
        get() = endMs - startMs

    fun clampTo(durationMs: Long): PlaybackRange {
        require(durationMs > 0L) { "Duration must be positive" }
        val start = startMs.coerceAtMost(durationMs - 1L)
        return PlaybackRange(start, endMs.coerceIn(start + 1L, durationMs))
    }
}
