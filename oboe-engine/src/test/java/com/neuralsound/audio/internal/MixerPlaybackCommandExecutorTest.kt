package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixerPlaybackCommandExecutorTest {

    @Test
    fun `failed native start does not publish playing`() {
        var playing = false
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { playing },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                false
            },
            nativePause = {},
        )

        val result = executor.play(isPrepared = true, isEngineActive = true)

        assertEquals(
            MixerPlaybackResult.Failure(MixerPlaybackFailureReason.NATIVE_START_FAILED),
            result,
        )
        assertFalse(playing)
        assertEquals(1, nativePlayCalls)
    }

    @Test
    fun `repeated play and pause commands are idempotent`() {
        var playing = false
        var nativePlayCalls = 0
        var nativePauseCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { playing },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                true
            },
            nativePause = { nativePauseCalls += 1 },
        )

        assertEquals(MixerPlaybackResult.Success, executor.play(true, true))
        assertEquals(MixerPlaybackResult.Success, executor.play(true, true))
        assertTrue(playing)
        assertEquals(1, nativePlayCalls)

        assertEquals(MixerPlaybackResult.Success, executor.pause())
        assertEquals(MixerPlaybackResult.Success, executor.pause())
        assertFalse(playing)
        assertEquals(1, nativePauseCalls)
    }

    @Test
    fun `play reports preparation and engine availability failures without invoking native`() {
        var playing = false
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { playing },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                true
            },
            nativePause = {},
        )

        assertEquals(
            MixerPlaybackResult.Failure(MixerPlaybackFailureReason.NOT_PREPARED),
            executor.play(isPrepared = false, isEngineActive = true),
        )
        assertEquals(
            MixerPlaybackResult.Failure(MixerPlaybackFailureReason.ENGINE_INACTIVE),
            executor.play(isPrepared = true, isEngineActive = false),
        )
        assertEquals(0, nativePlayCalls)
        assertFalse(playing)
    }

    @Test
    fun `forced resume retries native start and clears stale playing state on failure`() {
        var playing = true
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { playing },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                false
            },
            nativePause = {},
        )

        val result = executor.play(
            isPrepared = true,
            isEngineActive = true,
            forceNativeStart = true,
        )

        assertEquals(
            MixerPlaybackResult.Failure(MixerPlaybackFailureReason.NATIVE_START_FAILED),
            result,
        )
        assertEquals(1, nativePlayCalls)
        assertFalse(playing)
    }

    @Test
    fun `stale published playing state retries when native restart has stopped`() {
        var publishedPlaying = true
        var nativePlaying = false
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { publishedPlaying },
            isNativePlaying = { nativePlaying },
            publishPlaying = { publishedPlaying = it },
            nativePlay = {
                nativePlayCalls += 1
                nativePlaying = true
                true
            },
            nativePause = {},
        )

        val result = executor.play(isPrepared = true, isEngineActive = true)

        assertEquals(MixerPlaybackResult.Success, result)
        assertEquals(1, nativePlayCalls)
        assertTrue(publishedPlaying)
        assertTrue(nativePlaying)
    }

    @Test
    fun `toggle decision reconciles stale published state after native restart failure`() {
        var publishedPlaying = true
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { publishedPlaying },
            isNativePlaying = { false },
            publishPlaying = { publishedPlaying = it },
            nativePlay = { true },
            nativePause = {},
        )

        val actuallyPlaying = executor.reconcilePlayingState(isEngineActive = true)

        assertFalse(actuallyPlaying)
        assertFalse(publishedPlaying)
    }

    @Test
    fun `queued autoplay followed by pause does not resume`() {
        var playing = true
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { playing },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                true
            },
            nativePause = {},
        )

        executor.requestPlayIntent()
        executor.pause()
        val result = executor.resumeDesiredPlayback(true, true)

        assertEquals(null, result)
        assertEquals(0, nativePlayCalls)
        assertFalse(playing)
    }

    @Test
    fun `pause then play during seek resumes latest play intent`() {
        var playing = true
        var nativePlaying = false
        var nativePlayCalls = 0
        val executor = MixerPlaybackCommandExecutor(
            isPlaying = { playing },
            isNativePlaying = { nativePlaying },
            publishPlaying = { playing = it },
            nativePlay = {
                nativePlayCalls += 1
                nativePlaying = true
                true
            },
            nativePause = { nativePlaying = false },
        )

        executor.pause()
        executor.requestPlayIntent()
        val result = executor.resumeDesiredPlayback(true, true)

        assertEquals(MixerPlaybackResult.Success, result)
        assertEquals(1, nativePlayCalls)
        assertTrue(playing)
        assertTrue(nativePlaying)
    }
}
