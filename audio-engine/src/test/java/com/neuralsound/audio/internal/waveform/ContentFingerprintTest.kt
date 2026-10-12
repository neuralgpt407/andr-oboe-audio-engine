package com.neuralsound.audio.internal.waveform

import android.os.ParcelFileDescriptor
import java.io.File
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContentFingerprintTest {
    private fun fileOf(bytes: ByteArray) =
        File.createTempFile("fingerprint", ".bin").apply { writeBytes(bytes); deleteOnExit() }

    private fun fingerprint(file: File): String? =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { contentFingerprint(it) }

    private fun randomBytes(size: Int) = ByteArray(size).also { Random(7).nextBytes(it) }

    @Test
    fun sameContentAtDifferentPathsHashesEqual() {
        val bytes = randomBytes(300_000)
        val a = fingerprint(fileOf(bytes))
        assertNotNull(a)
        assertEquals(a, fingerprint(fileOf(bytes.copyOf())))
    }

    @Test
    fun changedByteInSampledWindowChangesHash() {
        val bytes = randomBytes(300_000)
        val original = fingerprint(fileOf(bytes))
        // Head and tail windows are always sampled.
        for (index in intArrayOf(0, bytes.size - 1)) {
            val changed = bytes.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNotEquals(original, fingerprint(fileOf(changed)))
        }
    }

    @Test
    fun differentLengthChangesHash() {
        val bytes = randomBytes(10_000)
        assertNotEquals(fingerprint(fileOf(bytes)), fingerprint(fileOf(bytes.copyOf(10_001))))
    }

    @Test
    fun filesSmallerThanAWindowWork() {
        val small = fingerprint(fileOf(randomBytes(100)))
        assertNotNull(small)
        assertEquals(64, small!!.length)
        assertTrue(fingerprint(fileOf(ByteArray(0))) != null)
        assertNotEquals(small, fingerprint(fileOf(randomBytes(101))))
    }

    @Test
    fun doesNotMoveTheDescriptorOffset() {
        val file = fileOf(randomBytes(50_000))
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            contentFingerprint(pfd)
            val channel = java.io.FileInputStream(pfd.fileDescriptor).channel
            assertEquals(0L, channel.position())
        }
    }
}
