package com.neuralsound.audio.internal

import android.media.MediaExtractor
import android.media.MediaFormat
import com.neuralsound.audio.TrackId
import java.io.FileDescriptor

internal data class DecodedAudioFormat(
    val sampleRate: Int?,
    val channelCount: Int?,
)

internal data class MixerTrackFormatProblem(
    val trackId: TrackId,
    val format: DecodedAudioFormat,
    val requiredSampleRate: Int?,
)

internal object MixerTrackFormatPolicy {
    fun findProblem(
        formats: List<Pair<TrackId, DecodedAudioFormat>>,
    ): MixerTrackFormatProblem? {
        val requiredSampleRate = formats.firstOrNull()?.second?.sampleRate
        return formats.firstNotNullOfOrNull { (trackId, format) ->
            val hasSupportedShape = format.sampleRate != null &&
                format.sampleRate > 0 &&
                format.channelCount != null &&
                format.channelCount in 1..2
            val matchesMixerRate = requiredSampleRate == null ||
                format.sampleRate == requiredSampleRate
            if (hasSupportedShape && matchesMixerRate) {
                null
            } else {
                MixerTrackFormatProblem(trackId, format, requiredSampleRate)
            }
        }
    }
}

internal object AudioTrackFormatInspector {
    fun inspect(fileDescriptor: FileDescriptor): DecodedAudioFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(fileDescriptor)
            (0 until extractor.trackCount)
                .asSequence()
                .map(extractor::getTrackFormat)
                .firstOrNull { format ->
                    format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                ?.let { format ->
                    DecodedAudioFormat(
                        sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE),
                        channelCount = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT),
                    )
                }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? {
        return if (containsKey(key)) getInteger(key) else null
    }
}
