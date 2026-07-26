package com.neuralsound.audio.sample

import android.content.Context
import android.net.Uri
import com.neuralsound.audio.AudioEngine
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.RecorderSession
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.media3.Media3AudioEngine
import com.neuralsound.audio.media3.Media3MixerRequest
import com.neuralsound.audio.media3.Media3MixerSession

class SampleSessions(context: Context) : AutoCloseable {
    private val audioEngine = AudioEngine(context)
    private val media3Engine = Media3AudioEngine(context)

    val mixer: Media3MixerSession = media3Engine.createSession()
    val recorder: RecorderSession = audioEngine.createRecorderSession()

    suspend fun prepareTwoTrackMix(
        vocalUri: Uri,
        instrumentalUri: Uri,
        videoUri: Uri? = null,
    ): AudioResult {
        return mixer.prepare(
            Media3MixerRequest(
                audio = MixerRequest(
                    tracks = listOf(
                        MixerTrack(TrackId("vocal"), vocalUri),
                        MixerTrack(TrackId("instrumental"), instrumentalUri),
                    )
                ),
                videoUri = videoUri,
            )
        )
    }

    override fun close() {
        recorder.close()
        mixer.close()
    }
}
