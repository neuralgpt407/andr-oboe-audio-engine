package com.neuralsound.audio.export

import com.neuralsound.audio.AudioLimits
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerMix
import com.neuralsound.audio.player.TrackMix
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.pow

/** Builds the ffmpeg audio filter graph that renders a [PlayerMix] the way the player plays it. */
object ExportAudioGraph {
    private const val SAMPLE_RATE = 44_100
    private const val MIN_ATEMPO = 0.5f
    private const val MAX_ATEMPO = 2.0f

    /** [inputs] lists the track ids in ffmpeg input order ([i:a] = inputs[i]). Tracks in [inputs] missing from the mix use default settings. */
    fun build(mix: PlayerMix, inputs: List<TrackId>, outputLabel: String = "out"): ExportGraph {
        require(inputs.isNotEmpty()) { "At least one input is required" }
        require(inputs.distinct().size == inputs.size) { "Input track ids must be distinct" }
        require(outputLabel.isNotBlank()) { "Output label must not be blank" }

        val pitchTempo = mix.pitchTempo.clamped()
        val tracks = mix.tracks.mapValues { (_, t) -> clampTrack(t) }
        val applied = mix.copy(tracks = tracks, pitchTempo = pitchTempo)
        val perInput = inputs.map { tracks[it] ?: TrackMix() }
        val anySolo = perInput.any { it.solo }

        val mixFilters = mixFilters(applied.range, pitchTempo)
        val segments = ArrayList<String>()

        if (inputs.size == 1) {
            val chain = inputFilters(perInput[0], anySolo) + mixFilters
            segments += "[0:a]" + chain.joinToString(",") + "[$outputLabel]"
        } else {
            for (i in inputs.indices) {
                segments += "[$i:a]" + inputFilters(perInput[i], anySolo).joinToString(",") + "[a$i]"
            }
            val labels = inputs.indices.joinToString("") { "[a$it]" }
            val chain = listOf(
                "amix=inputs=${inputs.size}:duration=longest:dropout_transition=0:normalize=0",
            ) + mixFilters
            segments += labels + chain.joinToString(",") + "[$outputLabel]"
        }
        return ExportGraph(segments.joinToString(";"), inputs.toList(), outputLabel, applied)
    }

    private fun clampTrack(t: TrackMix): TrackMix = t.copy(
        volume = t.volume.coerceIn(0f, 1f),
        offsetMs = t.offsetMs.coerceIn(AudioLimits.MIN_OFFSET_MS, AudioLimits.MAX_OFFSET_MS),
        boostDb = t.boostDb.coerceIn(AudioLimits.MIN_BOOST_DB, AudioLimits.MAX_BOOST_DB),
        balance = ChannelGain(
            t.balance.left.coerceIn(ChannelGain.MIN_GAIN, ChannelGain.MAX_GAIN),
            t.balance.right.coerceIn(ChannelGain.MIN_GAIN, ChannelGain.MAX_GAIN),
        ),
    )

    private fun inputFilters(t: TrackMix, anySolo: Boolean): List<String> {
        val filters = ArrayList<String>()
        filters += "aformat=channel_layouts=stereo"
        when {
            t.offsetMs > 0L -> filters += "atrim=start=${seconds(t.offsetMs)},asetpts=PTS-STARTPTS"
            t.offsetMs < 0L -> filters += "adelay=${-t.offsetMs}|${-t.offsetMs}"
        }
        val audible = !(t.muted || (anySolo && !t.solo))
        val gain = if (audible) t.volume.toDouble() * 10.0.pow(t.boostDb / 20.0) else 0.0
        filters += "volume=${String.format(Locale.US, "%.6f", gain)}"
        if (t.balance != ChannelGain.Center) {
            filters += "pan=stereo|c0=${gainText(t.balance.left)}*c0|c1=${gainText(t.balance.right)}*c1"
        }
        return filters
    }

    private fun mixFilters(range: PlaybackRange?, pt: PitchTempo): List<String> {
        val filters = ArrayList<String>()
        if (range != null) {
            filters += "atrim=start=${seconds(range.startMs)}:end=${seconds(range.endMs)},asetpts=PTS-STARTPTS"
        }
        if (pt.semitones != 0) {
            val f = 2.0.pow(pt.semitones / 12.0)
            filters += "asetrate=$SAMPLE_RATE*${String.format(Locale.US, "%.6f", f)}"
            filters += "aresample=$SAMPLE_RATE"
            filters += "atempo=${atempo((1.0 / f).toFloat())}"
        }
        if (pt.tempo != 1f) filters += tempoChain(pt.tempo)
        filters += "alimiter=level_in=1:level_out=1:limit=${
            String.format(Locale.US, "%.2f", AudioLimits.LIMITER_CEILING)
        }:attack=${AudioLimits.LIMITER_ATTACK_MS}:release=${AudioLimits.LIMITER_RELEASE_MS}" +
            ":asc=0:asc_level=0.5:level=0:latency=1"
        return filters
    }

    private fun tempoChain(tempo: Float): List<String> {
        val parts = ArrayList<Float>()
        var remaining = tempo.coerceIn(AudioLimits.MIN_TEMPO, AudioLimits.MAX_TEMPO)
        while (remaining > MAX_ATEMPO) {
            parts += MAX_ATEMPO
            remaining /= MAX_ATEMPO
        }
        while (remaining < MIN_ATEMPO) {
            parts += MIN_ATEMPO
            remaining /= MIN_ATEMPO
        }
        parts += remaining
        return parts.map { "atempo=${atempo(it)}" }
    }

    private fun atempo(v: Float): String = String.format(Locale.US, "%.4f", v)

    private fun gainText(v: Float): String = String.format(Locale.US, "%.4f", v)

    private fun seconds(ms: Long): String = String.format(Locale.US, "%.3f", ms / 1000.0)
}

data class ExportGraph(
    val filterComplex: String,
    val inputs: List<TrackId>,
    val outputLabel: String,
    /** The mix after clamping to [AudioLimits] — what the graph renders. */
    val applied: PlayerMix,
) {
    /** Rendered duration for a source of [sourceDurationMs]: the range (if any, clamped to the source) divided by tempo, rounded up. */
    fun outputDurationMs(sourceDurationMs: Long): Long {
        require(sourceDurationMs >= 0L) { "Source duration must not be negative" }
        val range = applied.range
        val length = if (range != null && sourceDurationMs > 0L) {
            range.clampTo(sourceDurationMs).durationMs
        } else {
            sourceDurationMs
        }
        return ceil(length.toDouble() / applied.pitchTempo.tempo.toDouble()).toLong()
    }
}
