package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.recorder.Recorder
import java.io.File
import java.io.RandomAccessFile

/** Parsed PCM16 mono WAV header facts. */
internal data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataBytes: Long,
    val frames: Long,
)

/** Validates PCM16 mono WAV files (ported from the OND `inspectPcm16MonoWav`). */
internal object WavInspector {
    private const val MIN_WAV_BYTES = 44L
    private const val CHUNK_HEADER_BYTES = 8L
    private const val PCM_FORMAT_BYTES = 16L
    private const val PCM_FORMAT = 1
    private const val BYTES_PER_FRAME = 2L

    /** Returns header info for a well-formed PCM16 mono WAV, or null otherwise. */
    fun inspect(file: File): WavInfo? {
        if (!file.isFile || file.length() < MIN_WAV_BYTES) return null
        return runCatching { parse(file) }.getOrNull()
    }

    fun isValidDraft(file: File, expectedSampleRate: Int = Recorder.SAMPLE_RATE): Boolean =
        inspect(file)?.sampleRate == expectedSampleRate

    private fun parse(file: File): WavInfo? = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (input.readAscii(4) != "RIFF") return null
        input.skipBytes(4)
        if (input.readAscii(4) != "WAVE") return null

        var audioFormat: Int? = null
        var channels: Int? = null
        var sampleRate: Int? = null
        var bits: Int? = null
        var dataBytes: Long? = null
        var dataOffset: Long? = null

        while (input.filePointer + CHUNK_HEADER_BYTES <= length) {
            val chunkId = input.readAscii(4)
            val chunkSize = input.readLeUInt()
            val payloadOffset = input.filePointer
            val payloadEnd = payloadOffset + chunkSize
            if (payloadEnd > length) return null

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < PCM_FORMAT_BYTES) return null
                    audioFormat = input.readLeUShort()
                    channels = input.readLeUShort()
                    sampleRate = input.readLeUInt().toInt()
                    input.skipBytes(6)
                    bits = input.readLeUShort()
                }
                "data" -> {
                    if (dataBytes != null) return null
                    dataBytes = chunkSize
                    dataOffset = payloadOffset
                }
            }
            val paddedEnd = payloadEnd + (chunkSize and 1L)
            // A final odd-sized chunk may legitimately omit its pad byte.
            if (paddedEnd > length) {
                if (payloadEnd == length) break
                return null
            }
            input.seek(paddedEnd)
        }

        val rate = sampleRate ?: return null
        val ch = channels ?: return null
        val bps = bits ?: return null
        val pcmBytes = dataBytes ?: return null
        if (audioFormat != PCM_FORMAT || ch != 1 || bps != 16 || rate <= 0 ||
            pcmBytes <= 0L || pcmBytes % BYTES_PER_FRAME != 0L
        ) return null

        WavInfo(rate, ch, bps, dataOffset ?: return null, pcmBytes, pcmBytes / BYTES_PER_FRAME)
    }
}

internal fun RandomAccessFile.readAscii(count: Int): String {
    val bytes = ByteArray(count)
    readFully(bytes)
    return bytes.toString(Charsets.US_ASCII)
}

internal fun RandomAccessFile.readLeUShort(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)

internal fun RandomAccessFile.readLeUInt(): Long {
    val b0 = readUnsignedByte().toLong()
    val b1 = readUnsignedByte().toLong()
    val b2 = readUnsignedByte().toLong()
    val b3 = readUnsignedByte().toLong()
    return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
}
