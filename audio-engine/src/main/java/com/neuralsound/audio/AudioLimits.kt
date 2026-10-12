package com.neuralsound.audio

import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.TrackSettings
import com.neuralsound.audio.waveform.WaveformAnalyzer

/** The single source of truth for the value ranges used by app UI controls and by export. */
object AudioLimits {
    const val MIN_BOOST_DB: Int = TrackSettings.MIN_BOOST_DB
    const val MAX_BOOST_DB: Int = TrackSettings.MAX_BOOST_DB
    const val MIN_PITCH_SEMITONES: Int = PitchTempo.MIN_SEMITONES
    const val MAX_PITCH_SEMITONES: Int = PitchTempo.MAX_SEMITONES
    const val MIN_TEMPO: Float = PitchTempo.MIN_TEMPO
    const val MAX_TEMPO: Float = PitchTempo.MAX_TEMPO
    const val MIN_OFFSET_MS: Long = TrackSettings.MIN_OFFSET_MS
    const val MAX_OFFSET_MS: Long = TrackSettings.MAX_OFFSET_MS
    const val MAX_TRACKS: Int = PlayerOptions.MAX_SUPPORTED_TRACKS
    const val MAX_WAVEFORM_LEVELS: Int = WaveformAnalyzer.MAX_LEVELS

    /** Master limiter ceiling (linear), shared by playback and export. */
    const val LIMITER_CEILING: Float = 0.98f
    const val LIMITER_ATTACK_MS: Int = 5
    const val LIMITER_RELEASE_MS: Int = 50
}
