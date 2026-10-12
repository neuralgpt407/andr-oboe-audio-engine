package com.neuralsound.audio.internal.recorder

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmWaveformReaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(samples: ShortArray, rate: Int = 44_100, name: String = "w.wav") =
        WavTestHelper.write(tmp.root, name, WavTestHelper.wavBytes(samples, sampleRate = rate))

    @Test fun rationalBucketsAt44100() {
        val values = shortArrayOf(4_096, -8_192, 12_288, -16_384, 20_480)
        val frames = intArrayOf(1_411, 1_411, 1_411, 1_411, 1_412)
        val samples = buildList {
            values.forEachIndexed { i, v -> repeat(frames[i]) { add(v) } }
            repeat(17) { add(Short.MIN_VALUE) }
        }.toShortArray()
        val w = PcmWaveformReader.read(file(samples))!!
        assertEquals(6, w.size)
        values.indices.forEach { assertTrue(abs(w[it] - (it + 1) * 0.125f) < 0.001f) }
        assertEquals(1f, w.last(), 0f)
    }

    @Test fun retainsEveryBucketForLongFile() {
        val n = 300 * 1_411
        val w = PcmWaveformReader.read(file(ShortArray(n) { 16_384 }))!!
        assertTrue(w.size >= 300)
        assertTrue(w.all { abs(it - 0.5f) < 0.001f })
    }

    @Test fun silenceFullScaleAndSine() {
        assertTrue(PcmWaveformReader.read(file(ShortArray(4_000), name = "s.wav"))!!.all { it == 0f })
        assertTrue(PcmWaveformReader.read(file(ShortArray(4_000) { Short.MAX_VALUE }, name = "f.wav"))!!.all { it in 0.999f..1f })
        val sine = ShortArray(44_100) { (sin(2 * PI * 441 * it / 44_100) * 16_384).toInt().toShort() }
        val w = PcmWaveformReader.read(file(sine, name = "n.wav"))!!
        assertEquals(1_000 / 32 + 1, w.size)
        w.dropLast(1).forEach { assertEquals(0.5f / 1.41421356f, it, 0.01f) }
        assertTrue(w.all { it in 0f..1f })
    }

    @Test fun lastPartialBucketIncluded() {
        assertEquals(2, PcmWaveformReader.read(file(ShortArray(1_411 + 5) { 8_192 }))!!.size)
    }

    @Test fun streamsAcrossBufferBoundaries() {
        // > 8 KiB buffer, odd-aligned reads
        val w = PcmWaveformReader.read(file(ShortArray(20_000) { 8_192 }))!!
        assertTrue(w.all { abs(it - 0.25f) < 0.001f })
    }

    @Test fun invalidFilesReturnNull() {
        val bad = WavTestHelper.write(tmp.root, "bad.wav", byteArrayOf(1, 2, 3))
        assertNull(PcmWaveformReader.read(bad))
        assertNull(PcmWaveformReader.read(file(ShortArray(100), rate = 48_000, name = "r.wav")))
        assertNull(PcmWaveformReader.read(tmp.root.resolve("none.wav")))
    }
}
