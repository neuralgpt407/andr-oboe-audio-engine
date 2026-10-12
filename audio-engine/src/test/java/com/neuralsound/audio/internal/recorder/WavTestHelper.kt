package com.neuralsound.audio.internal.recorder

import java.io.ByteArrayOutputStream
import java.io.File

internal object WavTestHelper {
    private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xff); write((v ushr 8) and 0xff) }
    private fun ByteArrayOutputStream.le32(v: Int) { le16(v and 0xffff); le16((v ushr 16) and 0xffff) }

    fun wavBytes(
        samples: ShortArray,
        sampleRate: Int = 44_100,
        channels: Int = 1,
        bits: Int = 16,
        extraChunkBeforeData: ByteArray? = null,
        dataBytesOverride: Int? = null,
    ): ByteArray {
        val data = ByteArrayOutputStream().also { o -> samples.forEach { o.le16(it.toInt()) } }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.le32(36 + data.size + (extraChunkBeforeData?.size ?: 0))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); out.le32(16)
        out.le16(1); out.le16(channels); out.le32(sampleRate)
        val align = channels * bits / 8
        out.le32(sampleRate * align); out.le16(align); out.le16(bits)
        extraChunkBeforeData?.let { out.write(it) }
        out.write("data".toByteArray()); out.le32(dataBytesOverride ?: data.size)
        out.write(data)
        return out.toByteArray()
    }

    fun listChunk(payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("LIST".toByteArray()); o.le32(payload.size); o.write(payload)
        if (payload.size % 2 == 1) o.write(0)
        return o.toByteArray()
    }

    fun write(dir: File, name: String, bytes: ByteArray): File = File(dir, name).also { it.writeBytes(bytes) }
}
