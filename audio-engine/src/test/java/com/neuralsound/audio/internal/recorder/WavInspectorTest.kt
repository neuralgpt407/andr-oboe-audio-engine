package com.neuralsound.audio.internal.recorder

import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavInspectorTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray, name: String = "t.wav") = WavTestHelper.write(tmp.root, name, bytes)

    @Test fun acceptsNonEmptyMonoPcm16() {
        val f = file(WavTestHelper.wavBytes(ShortArray(441)))
        val info = WavInspector.inspect(f)!!
        assertEquals(44_100, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(882L, info.dataBytes)
        assertEquals(44L, info.dataOffset)
        assertEquals(441L, info.frames)
        assertTrue(WavInspector.isValidDraft(f))
    }

    @Test fun rejectsEmptyStereoAndTruncated() {
        assertNull(WavInspector.inspect(file(WavTestHelper.wavBytes(ShortArray(0)), "e.wav")))
        assertNull(WavInspector.inspect(file(WavTestHelper.wavBytes(ShortArray(50), channels = 2), "s.wav")))
        val truncated = file(WavTestHelper.wavBytes(ShortArray(50)), "tr.wav")
        RandomAccessFile(truncated, "rw").use { it.setLength(it.length() - 10) }
        assertNull(WavInspector.inspect(truncated))
        assertFalse(WavInspector.isValidDraft(truncated))
    }

    @Test fun rejectsOddDataLength() {
        val f = file(WavTestHelper.wavBytes(ShortArray(10), dataBytesOverride = 19))
        assertNull(WavInspector.inspect(f))
    }

    @Test fun skipsExtraListChunk() {
        val list = WavTestHelper.listChunk(ByteArray(7) { 1 }) // odd payload + pad
        val f = file(WavTestHelper.wavBytes(ShortArray(100), extraChunkBeforeData = list))
        val info = WavInspector.inspect(f)!!
        assertEquals(100L, info.frames)
        assertEquals(44L + list.size, info.dataOffset)
    }

    @Test fun rejectsTruncatedHeader() {
        val bytes = WavTestHelper.wavBytes(ShortArray(100))
        assertNull(WavInspector.inspect(file(bytes.copyOf(30), "h1.wav")))
        assertNull(WavInspector.inspect(file(bytes.copyOf(20), "h2.wav")))
    }

    @Test fun rejectsWrongRateForDraftButInspectsIt() {
        val f = file(WavTestHelper.wavBytes(ShortArray(100), sampleRate = 48_000))
        assertNotNull(WavInspector.inspect(f))
        assertFalse(WavInspector.isValidDraft(f))
        assertTrue(WavInspector.isValidDraft(f, 48_000))
    }

    @Test fun rejectsNon16BitAndNonWave() {
        assertNull(WavInspector.inspect(file(WavTestHelper.wavBytes(ShortArray(100), bits = 8), "b.wav")))
        assertNull(WavInspector.inspect(file(ByteArray(100), "z.wav")))
        assertFalse(WavInspector.isValidDraft(tmp.root.resolve("missing.wav")))
    }
}
