package com.neuralsound.audio.export

import com.neuralsound.audio.TrackId
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerMix
import com.neuralsound.audio.player.TrackMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportAudioGraphTest {
    private val a = TrackId("a")
    private val b = TrackId("b")
    private val c = TrackId("c")
    private val limiter =
        "alimiter=level_in=1:level_out=1:limit=0.98:attack=5:release=50:asc=0:asc_level=0.5:level=0:latency=1"

    @Test
    fun singleInputNoEffects() {
        val g = ExportAudioGraph.build(PlayerMix(), listOf(a))
        assertEquals("[0:a]aformat=channel_layouts=stereo,volume=1.000000,$limiter[out]", g.filterComplex)
    }

    @Test
    fun threeInputsMuteSoloBalanceBoostOffsets() {
        val mix = PlayerMix(
            tracks = mapOf(
                a to TrackMix(solo = true, boostDb = 6, offsetMs = 500),
                b to TrackMix(solo = true, balance = ChannelGain.balance(-0.5f), offsetMs = -250),
                c to TrackMix(muted = true, solo = true),
            ),
        )
        val g = ExportAudioGraph.build(mix, listOf(a, b, c))
        val expected = listOf(
            "[0:a]aformat=channel_layouts=stereo,atrim=start=0.500,asetpts=PTS-STARTPTS,volume=1.995262[a0]",
            "[1:a]aformat=channel_layouts=stereo,adelay=250|250,volume=1.000000," +
                "pan=stereo|c0=1.0000*c0|c1=0.5000*c1[a1]",
            "[2:a]aformat=channel_layouts=stereo,volume=0.000000[a2]",
            "[a0][a1][a2]amix=inputs=3:duration=longest:dropout_transition=0:normalize=0,$limiter[out]",
        ).joinToString(";")
        assertEquals(expected, g.filterComplex)
    }

    @Test
    fun soloSilencesOthers() {
        val mix = PlayerMix(tracks = mapOf(a to TrackMix(solo = true)))
        val g = ExportAudioGraph.build(mix, listOf(a, b))
        assertTrue(g.filterComplex.contains("[0:a]aformat=channel_layouts=stereo,volume=1.000000[a0]"))
        assertTrue(g.filterComplex.contains("[1:a]aformat=channel_layouts=stereo,volume=0.000000[a1]"))
    }

    @Test
    fun rangeAndTempo() {
        val mix = PlayerMix(
            range = PlaybackRange(10_000, 40_000),
            pitchTempo = PitchTempo(0, 1.25f),
        )
        val g = ExportAudioGraph.build(mix, listOf(a))
        assertEquals(24_000L, g.outputDurationMs(60_000))
        assertTrue(g.filterComplex.contains("atrim=start=10.000:end=40.000,asetpts=PTS-STARTPTS"))
        assertTrue(g.filterComplex.contains("atempo=1.2500"))
    }

    @Test
    fun outOfRangeValuesClamped() {
        val mix = PlayerMix(
            tracks = mapOf(a to TrackMix(boostDb = 30, offsetMs = 99_999)),
            pitchTempo = PitchTempo(14, 2.5f),
        )
        val g = ExportAudioGraph.build(mix, listOf(a))
        assertEquals(10, g.applied.pitchTempo.semitones)
        assertEquals(2.0f, g.applied.pitchTempo.tempo)
        assertEquals(18, g.applied.tracks.getValue(a).boostDb)
        assertEquals(10_000L, g.applied.tracks.getValue(a).offsetMs)
        assertTrue(g.filterComplex.contains("volume=7.943282")) // 10^(18/20)
        assertTrue(g.filterComplex.contains("atrim=start=10.000,"))
        assertTrue(g.filterComplex.contains("asetrate=44100*1.781797"))
        assertTrue(g.filterComplex.endsWith("atempo=2.0000,$limiter[out]"))
    }

    @Test
    fun allMutedIsStillValid() {
        val mix = PlayerMix(tracks = mapOf(a to TrackMix(muted = true), b to TrackMix(muted = true)))
        val g = ExportAudioGraph.build(mix, listOf(a, b))
        assertEquals(2, Regex("volume=0\\.000000").findAll(g.filterComplex).count())
    }

    @Test
    fun limiterPresentExactlyOnce() {
        val mix = PlayerMix(pitchTempo = PitchTempo(3, 1.5f), range = PlaybackRange(0, 1000))
        for (inputs in listOf(listOf(a), listOf(a, b))) {
            val g = ExportAudioGraph.build(mix, inputs)
            assertEquals(1, Regex("alimiter=").findAll(g.filterComplex).count())
        }
    }

    @Test
    fun tempoBelowMinClampsToSingleAtempo() {
        val g = ExportAudioGraph.build(PlayerMix(pitchTempo = PitchTempo(0, 0.4f)), listOf(a))
        assertEquals(1, Regex("atempo=").findAll(g.filterComplex).count())
        assertTrue(g.filterComplex.contains("atempo=0.5000"))
    }

    @Test
    fun tempoTwoIsSingleAtempo() {
        val g = ExportAudioGraph.build(PlayerMix(pitchTempo = PitchTempo(0, 2.0f)), listOf(a))
        assertEquals(1, Regex("atempo=").findAll(g.filterComplex).count())
        assertTrue(g.filterComplex.contains("atempo=2.0000"))
    }

    @Test
    fun filterOrder() {
        val mix = PlayerMix(
            range = PlaybackRange(1000, 5000),
            pitchTempo = PitchTempo(2, 1.5f),
        )
        val s = ExportAudioGraph.build(mix, listOf(a, b)).filterComplex
        val order = listOf("aformat", "volume=", "amix=", "atrim=start=1.000:end=5.000", "asetrate", "aresample", "atempo=1.5000", "alimiter")
        val idx = order.map { s.indexOf(it) }
        assertTrue(idx.all { it >= 0 })
        assertEquals(idx.sorted(), idx)
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyInputsRejected() {
        ExportAudioGraph.build(PlayerMix(), emptyList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateInputsRejected() {
        ExportAudioGraph.build(PlayerMix(), listOf(a, a))
    }
}
