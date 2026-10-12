package com.neuralsound.audio.player

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.testing.TestLifecycleOwner
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

private class FakePlayer(initial: PlayerStatus = PlayerStatus.IDLE) : Player {
    val calls = mutableListOf<String>()
    val status = MutableStateFlow(PlayerState(status = initial))
    override val state: StateFlow<PlayerState> get() = status
    override val events: Flow<PlayerEvent> = emptyFlow()
    override val mix: StateFlow<PlayerMix> get() = TODO()
    override val clock: PlaybackClock get() = TODO()
    override val video: VideoController get() = TODO()

    fun setStatus(s: PlayerStatus) { status.value = PlayerState(status = s) }

    override fun play() { calls += "play"; setStatus(PlayerStatus.PLAYING) }
    override fun pause() { calls += "pause"; setStatus(PlayerStatus.PAUSED) }
    override suspend fun release() { calls += "release" }

    override suspend fun load(source: PlayerSource): AudioResult<Unit> = TODO()
    override suspend fun appendTrack(track: Track): AudioResult<Unit> = TODO()
    override fun togglePlayPause() = TODO()
    override fun seekTo(positionMs: Long) = TODO()
    override fun seekToFraction(fraction: Float) = TODO()
    override fun seekBy(deltaMs: Long) = TODO()
    override fun seekAndPlay(positionMs: Long) = TODO()
    override fun setRange(range: PlaybackRange?, loop: Boolean) = TODO()
    override fun setPitchTempo(pitchTempo: PitchTempo) = TODO()
    override fun updateTrack(id: TrackId, transform: (TrackSettings) -> TrackSettings) = TODO()
    override suspend fun applyMix(mix: PlayerMix): AudioResult<MixApplyResult> = TODO()
    override fun setSolo(id: TrackId, solo: Boolean) = TODO()
    override suspend fun detach(): PlayerSnapshot? = TODO()
    override suspend fun restore(snapshot: PlayerSnapshot): AudioResult<Unit> = TODO()
    override fun diagnostics(reset: Boolean): CallbackStats? = TODO()
}

/** An Activity that is also a LifecycleOwner, reporting ON_DESTROY from onDestroy. */
class LifecycleTestActivity : Activity(), LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
    }
    override fun onDestroy() {
        super.onDestroy()
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

@RunWith(RobolectricTestRunner::class)
class PlayerLifecycleTest {
    private lateinit var process: TestLifecycleOwner
    private lateinit var screen: TestLifecycleOwner

    @Before fun setUp() {
        process = TestLifecycleOwner(Lifecycle.State.RESUMED, Dispatchers.Unconfined)
        screen = TestLifecycleOwner(Lifecycle.State.RESUMED, Dispatchers.Unconfined)
        PlayerLifecycleDefaults.processLifecycle = { process.lifecycle }
    }

    @After fun tearDown() {
        PlayerLifecycleDefaults.processLifecycle = { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle }
    }

    private fun background() { process.currentState = Lifecycle.State.CREATED }
    private fun foreground() { process.currentState = Lifecycle.State.RESUMED }

    @Test fun backgroundPausesAPlayingPlayer() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen)
        background()
        assertEquals(listOf("pause"), p.calls)
    }

    @Test fun foregroundDoesNotResumeByDefault() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen)
        background(); foreground()
        assertEquals(listOf("pause"), p.calls)
    }

    @Test fun resumesInForegroundWhenAskedAndPausedByBinding() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen, LifecycleBinding(resumeInForeground = true))
        background(); foreground()
        assertEquals(listOf("pause", "play"), p.calls)
    }

    @Test fun doesNotResumeIfUserPlayedInBetween() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen, LifecycleBinding(resumeInForeground = true))
        background()
        p.setStatus(PlayerStatus.PLAYING) // user pressed play while backgrounded
        p.setStatus(PlayerStatus.PAUSED)  // ...and paused again
        foreground()
        assertEquals(listOf("pause"), p.calls)
    }

    @Test fun notPlayingPlayerIsNeitherPausedNorResumed() {
        val p = FakePlayer(PlayerStatus.PAUSED)
        p.bindTo(screen, LifecycleBinding(resumeInForeground = true))
        background(); foreground()
        assertTrue(p.calls.isEmpty())
    }

    @Test fun pauseInBackgroundCanBeDisabled() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen, LifecycleBinding(pauseInBackground = false))
        background()
        assertTrue(p.calls.isEmpty())
    }

    @Test fun destroyReleases() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen)
        screen.currentState = Lifecycle.State.DESTROYED
        assertEquals(listOf("release"), p.calls)
    }

    @Test fun releaseOnDestroyCanBeDisabled() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        p.bindTo(screen, LifecycleBinding(releaseOnDestroy = false))
        screen.currentState = Lifecycle.State.DESTROYED
        assertTrue(p.calls.isEmpty())
    }

    @Test fun rotationDoesNotReleaseButFinishingDoes() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        val controller = Robolectric.buildActivity(LifecycleTestActivity::class.java).create()
        p.bindTo(controller.get())
        controller.recreate()
        assertFalse("rotation must not release", p.calls.contains("release"))

        val q = FakePlayer(PlayerStatus.PLAYING)
        val c2 = Robolectric.buildActivity(LifecycleTestActivity::class.java).create()
        q.bindTo(c2.get())
        c2.destroy()
        assertEquals(listOf("release"), q.calls)
    }

    @Test fun closingTheBindingStopsReactions() {
        val p = FakePlayer(PlayerStatus.PLAYING)
        val binding = p.bindTo(screen)
        binding.close()
        binding.close()
        background()
        screen.currentState = Lifecycle.State.DESTROYED
        assertTrue(p.calls.isEmpty())
    }

    @Test fun releaseWithReleasesWhenScopeIsCancelled() {
        val p = FakePlayer()
        val job = Job()
        p.releaseWith(CoroutineScope(job))
        assertTrue(p.calls.isEmpty())
        job.cancel()
        assertEquals(listOf("release"), p.calls)
    }

    @Test fun releaseWithRequiresAJob() {
        val p = FakePlayer()
        val noJob = object : CoroutineScope {
            override val coroutineContext = kotlin.coroutines.EmptyCoroutineContext
        }
        assertThrows(IllegalArgumentException::class.java) { p.releaseWith(noJob) }
    }
}
