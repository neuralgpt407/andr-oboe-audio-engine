package com.neuralsound.audio.internal.waveform

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

private const val FINGERPRINT_WINDOWS = 16
private const val FINGERPRINT_WINDOW_BYTES = 8 * 1024

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }

internal fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * Content identity for derived-media caches, so a byte-identical copy at another path reuses
 * what was generated for the original.
 *
 * Hashes the length plus 16 evenly spaced 8 KB windows (head and tail included), so it costs
 * ~128 KB of reads regardless of file size. Uses positional reads, leaving the descriptor's file
 * offset untouched. Null when the descriptor is not seekable or readable.
 */
// Sampled, not a full hash: two same-length files identical at every sampled window collide.
internal fun contentFingerprint(descriptor: ParcelFileDescriptor): String? = try {
    // Deliberately not closed: closing the channel would close the caller's descriptor.
    val channel: FileChannel = FileInputStream(descriptor.fileDescriptor).channel
    val length = channel.size()
    if (length < 0) {
        null
    } else {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(length.toString().toByteArray(Charsets.UTF_8))
        val window = ByteArray(minOf(FINGERPRINT_WINDOW_BYTES.toLong(), length).toInt())
        val lastStart = length - window.size
        var ok = true
        for (index in 0 until FINGERPRINT_WINDOWS) {
            val start = lastStart * index / (FINGERPRINT_WINDOWS - 1)
            val buffer = ByteBuffer.wrap(window)
            var position = start
            while (buffer.hasRemaining()) {
                val read = channel.read(buffer, position)
                if (read < 0) {
                    ok = false
                    break
                }
                position += read
            }
            if (!ok) break
            digest.update(window)
        }
        if (ok) digest.digest().joinToString(separator = "") { "%02x".format(it) } else null
    }
} catch (_: Exception) {
    null
}
