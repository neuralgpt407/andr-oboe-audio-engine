package com.neuralsound.audio.internal

import java.util.concurrent.atomic.AtomicBoolean

internal class MixerPlaybackCommandExecutor(
    private val isPlaying: () -> Boolean,
    private val isNativePlaying: () -> Boolean,
    private val publishPlaying: (Boolean) -> Unit,
    private val nativePlay: () -> Boolean,
    private val nativePause: () -> Unit,
) {
    private val desiredPlaying = AtomicBoolean(false)

    @Synchronized
    fun play(
        isPrepared: Boolean,
        isEngineActive: Boolean,
        forceNativeStart: Boolean = false,
    ): MixerPlaybackResult {
        desiredPlaying.set(true)
        return startPlayback(isPrepared, isEngineActive, forceNativeStart)
    }

    @Synchronized
    fun resumeDesiredPlayback(
        isPrepared: Boolean,
        isEngineActive: Boolean,
        forceNativeStart: Boolean = true,
    ): MixerPlaybackResult? {
        if (!desiredPlaying.get()) return null
        return startPlayback(isPrepared, isEngineActive, forceNativeStart)
    }

    private fun startPlayback(
        isPrepared: Boolean,
        isEngineActive: Boolean,
        forceNativeStart: Boolean,
    ): MixerPlaybackResult {
        if (!isPrepared) {
            return MixerPlaybackResult.Failure(MixerPlaybackFailureReason.NOT_PREPARED)
        }
        if (!isEngineActive) {
            return MixerPlaybackResult.Failure(MixerPlaybackFailureReason.ENGINE_INACTIVE)
        }
        if (isPlaying() && !forceNativeStart && isNativePlaying()) {
            return MixerPlaybackResult.Success
        }

        if (!nativePlay()) {
            publishPlaying(false)
            return MixerPlaybackResult.Failure(MixerPlaybackFailureReason.NATIVE_START_FAILED)
        }

        publishPlaying(true)
        return MixerPlaybackResult.Success
    }

    @Synchronized
    fun pause(): MixerPlaybackResult {
        desiredPlaying.set(false)
        if (!isPlaying()) return MixerPlaybackResult.Success

        nativePause()
        publishPlaying(false)
        return MixerPlaybackResult.Success
    }

    fun requestPlayIntent() {
        desiredPlaying.set(true)
    }

    fun desiresPlayback(): Boolean = desiredPlaying.get()

    fun clearPlaybackIntent() {
        desiredPlaying.set(false)
    }

    @Synchronized
    fun reconcilePlayingState(isEngineActive: Boolean): Boolean {
        val nativePlaying = isEngineActive && isNativePlaying()
        if (isPlaying() != nativePlaying) publishPlaying(nativePlaying)
        return nativePlaying
    }
}
