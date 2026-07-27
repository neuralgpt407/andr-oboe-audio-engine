package com.neuralsound.audio

import android.content.Context
import com.neuralsound.audio.internal.DefaultMixerSession
import com.neuralsound.audio.internal.NativeMixerController
import com.neuralsound.audio.internal.NativeRecorderSession

class AudioEngine(context: Context) {
    private val applicationContext = context.applicationContext

    val isNativeAvailable: Boolean
        get() = NativeMixerController.nativeLibraryAvailable

    fun createMixerSession(): MixerSession {
        return DefaultMixerSession(NativeMixerController(applicationContext))
    }

    fun createRecorderSession(): RecorderSession {
        return NativeRecorderSession()
    }

    fun createFramePreciseRecorderSession(): FramePreciseRecorderSession {
        return NativeRecorderSession()
    }
}
