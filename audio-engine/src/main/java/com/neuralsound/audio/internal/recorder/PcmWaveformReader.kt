package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/** Rebuilds an RMS waveform from a PCM16 mono WAV (ported from OND `rebuildPcm16MonoWaveform`). */
internal object PcmWaveformReader {
    private const val STREAM_BUFFER_BYTES = 8 * 1024
    private const val PCM16_NORMALIZER = 32_768.0

    /**
     * One RMS value (0..1) per [bucketMs] bucket, last partial bucket included.
     * Bucket boundaries are rational (e.g. 1411/1412 frames at 44.1 kHz) so they match
     * the native engine. Returns null for invalid files.
     */
    fun read(
        file: File,
        bucketMs: Int = WaveformBuckets.BUCKET_MS,
        sampleRate: Int = Recorder.SAMPLE_RATE,
    ): FloatArray? {
        val info = WavInspector.inspect(file) ?: return null
        if (info.sampleRate != sampleRate || bucketMs <= 0) return null
        return runCatching { stream(file, info, bucketMs, sampleRate) }.getOrNull()
    }

    private fun stream(file: File, info: WavInfo, bucketMs: Int, sampleRate: Int): FloatArray? =
        RandomAccessFile(file, "r").use { input ->
            input.seek(info.dataOffset)
            val out = ArrayList<Float>()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var remaining = info.dataBytes
            var bucketIndex = 0
            var bucketFrames = 0
            var framesInBucket = bucketFrames(bucketIndex, sampleRate, bucketMs)
            var squaredSum = 0.0
            var pendingLow = -1

            fun accept(sample: Short) {
                val n = sample.toDouble() / PCM16_NORMALIZER
                squaredSum += n * n
                bucketFrames++
                if (bucketFrames == framesInBucket) {
                    out += sqrt(squaredSum / bucketFrames).toFloat().coerceIn(0f, 1f)
                    bucketIndex++
                    bucketFrames = 0
                    framesInBucket = bucketFrames(bucketIndex, sampleRate, bucketMs)
                    squaredSum = 0.0
                }
            }

            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) return null
                remaining -= read
                var i = 0
                if (pendingLow >= 0) {
                    accept((pendingLow or (buffer[0].toInt() shl 8)).toShort())
                    pendingLow = -1
                    i = 1
                }
                while (i + 1 < read) {
                    accept(((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort())
                    i += 2
                }
                if (i < read) pendingLow = buffer[i].toInt() and 0xff
            }
            if (pendingLow >= 0) return null
            if (bucketFrames > 0) out += sqrt(squaredSum / bucketFrames).toFloat().coerceIn(0f, 1f)
            out.toFloatArray()
        }

    internal fun bucketFrames(bucketIndex: Int, sampleRate: Int, bucketMs: Int): Int {
        if (bucketIndex < 0 || sampleRate <= 0) return 1
        val start = bucketIndex.toLong() * sampleRate * bucketMs / 1_000L
        val end = (bucketIndex.toLong() + 1L) * sampleRate * bucketMs / 1_000L
        return (end - start).coerceAtLeast(1L).toInt()
    }
}
