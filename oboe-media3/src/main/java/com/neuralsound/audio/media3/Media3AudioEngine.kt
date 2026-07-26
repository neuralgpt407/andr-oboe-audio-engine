package com.neuralsound.audio.media3

import android.content.Context
import android.net.Uri
import com.neuralsound.audio.AudioEngine

class Media3AudioEngine(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioEngine = AudioEngine(applicationContext)

    fun createSession(): Media3MixerSession {
        return Media3MixerSession(
            context = applicationContext,
            mixer = audioEngine.createMixerSession(),
        )
    }
}

data class Media3MixerRequest(
    val audio: com.neuralsound.audio.MixerRequest,
    val videoUri: Uri? = null,
)
