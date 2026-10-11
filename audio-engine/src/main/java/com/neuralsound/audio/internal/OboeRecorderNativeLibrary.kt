package com.neuralsound.audio.internal

internal object OboeRecorderNativeLibrary {
    @Volatile
    private var cachedAvailability: Boolean? = null

    fun isAvailable(): Boolean {
        cachedAvailability?.let { return it }
        return synchronized(this) {
            cachedAvailability ?: run {
                val loaded = runCatching {
                    System.loadLibrary("neuralsound_audio_engine")
                }.isSuccess
                cachedAvailability = loaded
                loaded
            }
        }
    }
}
