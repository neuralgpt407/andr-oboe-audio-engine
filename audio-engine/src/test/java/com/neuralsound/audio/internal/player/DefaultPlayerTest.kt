package com.neuralsound.audio.internal.player

import android.net.Uri
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.MixerAppendResult
import com.neuralsound.audio.internal.MixerCompletion
import com.neuralsound.audio.internal.MixerController
import com.neuralsound.audio.internal.MixerPlaybackResult
import com.neuralsound.audio.internal.MixerPreparationResult
import com.neuralsound.audio.internal.legacy.AudioFailure
import com.neuralsound.audio.internal.legacy.ChannelGain as InternalChannelGain
import com.neuralsound.audio.internal.legacy.MixerTrackState
import com.neuralsound.audio.internal.legacy.PlaybackEffects
import com.neuralsound.audio.internal.legacy.PlaybackRange as InternalPlaybackRange
import com.neuralsound.audio.internal.legacy.TrackMix
import com.neuralsound.audio.player.ChannelGain
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.PlayerEvent
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultPlayerTest {
    private val original = Track(TrackId("original"), Uri.parse("content://media/original-48000"))
    private val denoised = Track(
        TrackId("denoised"),
        Uri.parse("content://media/denoised-44100"),
        TrackSettings(muted = true, offsetMs = 275L),
    )

    private fun TestScope.player(controller: FakeMixerController): DefaultPlayer {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DefaultPlayer(controller, PlayerOptions(), nativeAvailable = true, dispatcher, dispatcher)
    }

    @Test
    fun loadPublishesOneReadyTimelineWithClampedSettings() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)

        val result = player.load(
            PlayerSource(
                tracks = listOf(original.copy(settings = TrackSettings(volume = 3f, muted = true, boostDb = 40))),
                startAtMs = 1_500L,
                pitchTempo = PitchTempo(semitones = 30, tempo = 9f),
            ),
        )
        advanceUntilIdle()

        assertEquals(AudioResult.Done, result)
        val state = player.state.value
        assertEquals(PlayerStatus.READY, state.status)
        assertEquals(10_000L, state.durationMs)
        assertEquals(1_500L, state.positionMs)
        assertEquals(TrackSettings(volume = 1f, muted = true, boostDb = 18), state.tracks.getValue(original.id).settings)
        assertEquals(PitchTempo(semitones = 10, tempo = 2f), state.pitchTempo)
        assertEquals(PlaybackEffects(tempo = 2f, pitchSemitones = 10), controller.preparedEffects)
        assertEquals(1f, controller.preparedVolumes.getValue(original.id))
        assertTrue(controller.snapshotTracks().getValue(original.id).mix.muted)
        player.release()
    }

    @Test
    fun appendJoinsTheLoadedTimelineWithItsOffset() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))

        assertEquals(AudioResult.Done, player.appendTrack(denoised))
        advanceUntilIdle()

        assertEquals(275L, controller.offsetObservedDuringAppend)
        assertEquals(listOf(original.id, denoised.id), player.state.value.tracks.keys.toList())
        assertTrue(player.state.value.tracks.getValue(denoised.id).settings.muted)
        player.release()
    }

    @Test
    fun appendBeforeLoadIsRejected() = runTest {
        val player = player(FakeMixerController())
        assertEquals(AudioResult.Err(AudioError.NotLoaded), player.appendTrack(denoised))
        player.release()
    }

    @Test
    fun updateTrackSendsOnlyTheChangedSettings() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))
        controller.calls.clear()

        player.updateTrack(original.id) { it.copy(muted = true) }
        player.updateTrack(original.id) { it.copy(balance = ChannelGain.balance(-0.5f), offsetMs = 120L) }
        player.updateTrack(original.id) { it }
        player.updateTrack(TrackId("unknown")) { it.copy(volume = 0f) }
        advanceUntilIdle()

        assertEquals(listOf("mute:original=true", "gain:original=1.0/0.5", "offset:original=120"), controller.calls)
        val settings = player.state.value.tracks.getValue(original.id).settings
        assertTrue(settings.muted)
        assertEquals(120L, settings.offsetMs)
        player.release()
    }

    @Test
    fun transportTargetsResolveAgainstTheTimeline() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))

        player.seekToFraction(0.5f)
        advanceUntilIdle()
        assertEquals(5_000L, player.state.value.positionMs)

        player.seekBy(-20_000L)
        advanceUntilIdle()
        assertEquals(0L, player.state.value.positionMs)

        player.seekBy(2_500L)
        advanceUntilIdle()
        assertEquals(2_500L, player.state.value.positionMs)

        player.seekAndPlay(7_000L)
        advanceUntilIdle()
        assertEquals(7_000L, player.state.value.positionMs)
        assertEquals(PlayerStatus.PLAYING, player.state.value.status)

        player.togglePlayPause()
        advanceUntilIdle()
        assertEquals(PlayerStatus.PAUSED, player.state.value.status)
        player.release()
    }

    @Test
    fun rangeAndLoopAreForwardedAndPublished() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))

        player.setRange(PlaybackRange(1_000L, 4_000L), loop = true)
        advanceUntilIdle()

        assertEquals(InternalPlaybackRange(1_000L, 4_000L), controller.currentPlaybackRange())
        assertTrue(controller.loopingEnabled)
        assertEquals(PlaybackRange(1_000L, 4_000L), player.state.value.range)
        assertTrue(player.state.value.loop)
        player.release()
    }

    @Test
    fun pitchTempoIsClampedBeforeReachingTheEngine() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))

        player.setPitchTempo(PitchTempo(semitones = -14, tempo = 0.1f))
        advanceUntilIdle()

        assertEquals(0.5f, controller.tempoSpeed.value)
        assertEquals(-10, controller.pitchSemitones.value)
        assertEquals(PitchTempo(semitones = -10, tempo = 0.5f), player.state.value.pitchTempo)
        player.release()
    }

    @Test
    fun aBurstOfPitchTempoChangesAppliesOnlyTheLatestValue() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))
        controller.calls.clear()

        repeat(20) { step -> player.setPitchTempo(PitchTempo(semitones = step % 5, tempo = if (step == 19) 1.25f else 0.5f + step * 0.05f)) }
        advanceUntilIdle()

        assertEquals(listOf("tempo:1.25", "pitch:4"), controller.calls)
        assertEquals(PitchTempo(semitones = 4, tempo = 1.25f), player.state.value.pitchTempo)

        player.setPitchTempo(PitchTempo(semitones = 1, tempo = 2f))
        advanceUntilIdle()
        assertEquals(listOf("tempo:1.25", "pitch:4", "tempo:2.0", "pitch:1"), controller.calls)
        player.release()
    }

    @Test
    fun loadFailureNamesTheTrackWithoutItsUri() = runTest {
        val controller = FakeMixerController(
            prepareResult = MixerPreparationResult.Failure(
                message = "open failed",
                failure = AudioFailure.SourceUnavailable(denoised.uri.toString()),
            ),
        )
        val player = player(controller)
        val events = mutableListOf<PlayerEvent>()
        val collector = launch { player.events.toList(events) }
        advanceUntilIdle()

        val result = player.load(PlayerSource(tracks = listOf(original, denoised)))
        advanceUntilIdle()

        val expected = AudioError.TrackFailed(denoised.id, TrackFailureReason.UNREADABLE_SOURCE)
        assertEquals(AudioResult.Err(expected), result)
        assertEquals(PlayerStatus.FAILED, player.state.value.status)
        assertEquals(expected, player.state.value.error)
        assertEquals(listOf(PlayerEvent.TrackFailed(denoised.id, TrackFailureReason.UNREADABLE_SOURCE), PlayerEvent.Failed(expected)), events)
        assertFalse(player.state.value.toString().contains("content://"))
        collector.cancel()
        player.release()
    }

    @Test
    fun runtimeFailureMovesThePlayerToFailed() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))

        controller.runtimeFailure.value = AudioFailure.NativeOperationFailed("normalizeTrack")
        advanceUntilIdle()
        controller.currentPosition.value = 9_500L
        advanceUntilIdle()

        assertEquals(PlayerStatus.FAILED, player.state.value.status)
        assertEquals(AudioError.Native("normalizeTrack"), player.state.value.error)
        assertEquals(0L, player.state.value.positionMs)
        player.release()
    }

    @Test
    fun releaseDuringLoadCompletesTheLoadAsReleased() = runTest {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val controller = FakeMixerController(beforePrepareResult = { entered.complete(Unit); finish.await() })
        val player = player(controller)

        val load = async(start = CoroutineStart.UNDISPATCHED) { player.load(PlayerSource(tracks = listOf(original))) }
        entered.await()
        player.release()
        finish.complete(Unit)

        assertEquals(AudioResult.Err(AudioError.Released), load.await())
        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
        assertTrue(controller.closed)
    }

    @Test
    fun callsAfterReleaseFailAndCommandsAreIgnored() = runTest {
        val controller = FakeMixerController()
        val player = player(controller)
        player.load(PlayerSource(tracks = listOf(original)))
        player.release()
        player.release()
        controller.calls.clear()

        player.play()
        player.seekTo(1_000L)
        advanceUntilIdle()

        assertEquals(AudioResult.Err(AudioError.Released), player.load(PlayerSource(tracks = listOf(original))))
        assertEquals(AudioResult.Err(AudioError.Released), player.appendTrack(denoised))
        assertTrue(controller.calls.isEmpty())
        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
    }

    @Test
    fun detachHandsTheSessionToAnotherPlayer() = runTest {
        val first = FakeMixerController()
        val player = player(first)
        player.load(PlayerSource(tracks = listOf(original, denoised)))
        player.setRange(PlaybackRange(1_000L, 9_000L), loop = true)
        player.updateTrack(original.id) { it.copy(volume = 0.5f) }
        player.seekAndPlay(4_200L)
        advanceUntilIdle()

        val snapshot = player.detach()!!
        assertEquals(PlayerStatus.RELEASED, player.state.value.status)
        assertEquals(4_200L, snapshot.positionMs)
        assertTrue(snapshot.wasPlaying)

        val second = FakeMixerController()
        val next = player(second)
        assertEquals(AudioResult.Done, next.restore(snapshot))
        advanceUntilIdle()

        assertEquals(4_200L, second.preparedStartMs)
        assertEquals(0.5f, second.preparedVolumes.getValue(original.id))
        assertEquals(PlaybackRange(1_000L, 9_000L), next.state.value.range)
        assertTrue(next.state.value.loop)
        assertEquals(PlayerStatus.PLAYING, next.state.value.status)
        next.release()
    }

    @Test
    fun detachWithoutLoadReturnsNull() = runTest {
        val player = player(FakeMixerController())
        assertNull(player.detach())
    }

    internal class FakeMixerController(
        private val prepareResult: MixerPreparationResult = MixerPreparationResult.Success,
        private val beforePrepareResult: suspend () -> Unit = {},
    ) : MixerController {
        override val routeRevision: StateFlow<Long> = MutableStateFlow(0L)
        override val isPlaying = MutableStateFlow(false)
        override val currentPosition = MutableStateFlow(0L)
        override val totalDuration = MutableStateFlow(0L)
        override val tempoSpeed = MutableStateFlow(1f)
        override val pitchSemitones = MutableStateFlow(0)
        override val runtimeFailure = MutableStateFlow<AudioFailure?>(null)
        override val completions = MutableSharedFlow<MixerCompletion>(extraBufferCapacity = 8)

        /** Mirrors the native controller: playback stops at the end and completion is signalled. */
        fun finishAt(positionMs: Long, completion: MixerCompletion) {
            currentPosition.value = positionMs
            isPlaying.value = false
            completions.tryEmit(completion)
        }

        val calls = mutableListOf<String>()
        var prepared = false
        var closed = false
        var loopingEnabled = false
        var preparedEffects: PlaybackEffects? = null
        var preparedVolumes: Map<TrackId, Float> = emptyMap()
        var preparedStartMs = -1L
        var offsetObservedDuringAppend: Long? = null
        private var range: InternalPlaybackRange? = null
        private val tracks = linkedMapOf<TrackId, MixerTrackState>()

        override fun currentRoutedOutputDeviceId(): Int? = null
        override fun currentRoutedOutputDeviceType(deviceId: Int?): Int? = null
        override fun currentOutputTopologyRevision(): Long = 0L
        override fun setPlaybackRange(range: InternalPlaybackRange?) { this.range = range }

        override suspend fun preparePlayers(
            tracks: Map<TrackId, Uri>,
            startPositionMs: Long,
            looping: Boolean,
            effects: PlaybackEffects,
            trackOffsetsMs: Map<TrackId, Long>,
            initialVolumes: Map<TrackId, Float>,
            initialChannelGains: Map<TrackId, InternalChannelGain>,
        ): MixerPreparationResult {
            beforePrepareResult()
            if (prepareResult != MixerPreparationResult.Success) return prepareResult
            prepared = true
            this.loopingEnabled = looping
            preparedEffects = effects
            preparedVolumes = initialVolumes
            preparedStartMs = startPositionMs
            tempoSpeed.value = effects.tempo
            pitchSemitones.value = effects.pitchSemitones
            currentPosition.value = startPositionMs
            totalDuration.value = 10_000L
            this.tracks.clear()
            tracks.keys.forEach { id ->
                this.tracks[id] = MixerTrackState(
                    mix = TrackMix(volume = initialVolumes.getValue(id), channelGain = initialChannelGains.getValue(id)),
                    offsetMs = trackOffsetsMs[id] ?: 0L,
                )
            }
            return MixerPreparationResult.Success
        }

        override fun appendTrack(
            type: TrackId,
            uri: Uri,
            initialVolume: Float,
            muted: Boolean,
            initialChannelGain: InternalChannelGain,
            initialOffsetMs: Long,
        ): MixerAppendResult {
            tracks[type] = MixerTrackState(TrackMix(initialVolume, muted, initialChannelGain), initialOffsetMs)
            offsetObservedDuringAppend = initialOffsetMs
            return MixerAppendResult.Success
        }

        override fun play(): MixerPlaybackResult {
            calls += "play"
            isPlaying.value = true
            return MixerPlaybackResult.Success
        }

        override fun pause(): MixerPlaybackResult {
            calls += "pause"
            isPlaying.value = false
            return MixerPlaybackResult.Success
        }

        override fun setVolume(type: TrackId, volume: Float) {
            calls += "volume:$type=$volume"
            update(type) { it.copy(mix = it.mix.copy(volume = volume)) }
        }

        override fun setChannelGain(type: TrackId, channelGain: InternalChannelGain) {
            calls += "gain:$type=${channelGain.left}/${channelGain.right}"
            update(type) { it.copy(mix = it.mix.copy(channelGain = channelGain)) }
        }

        override fun setMuted(type: TrackId, muted: Boolean) {
            calls += "mute:$type=$muted"
            update(type) { it.copy(mix = it.mix.copy(muted = muted)) }
        }

        override fun setTrackOffset(type: TrackId, offsetMs: Long) {
            calls += "offset:$type=$offsetMs"
            update(type) { it.copy(offsetMs = offsetMs) }
        }

        override fun setTempo(speed: Float) {
            calls += "tempo:$speed"
            tempoSpeed.value = speed
        }

        override fun setPitchSemitones(semitones: Int) {
            calls += "pitch:$semitones"
            pitchSemitones.value = semitones
        }
        override fun setLooping(looping: Boolean) { this.loopingEnabled = looping }

        override fun seekToMs(positionMs: Long): Boolean {
            calls += "seek:$positionMs"
            currentPosition.value = positionMs.coerceAtMost(totalDuration.value)
            return prepared
        }

        override fun snapshotTracks(): Map<TrackId, MixerTrackState> = tracks.toMap()
        override fun currentPlaybackRange(): InternalPlaybackRange? = range
        override fun isPrepared(): Boolean = prepared && !closed

        override fun closeNow() {
            closed = true
            prepared = false
            tracks.clear()
        }

        private fun update(type: TrackId, transform: (MixerTrackState) -> MixerTrackState) {
            tracks[type]?.let { tracks[type] = transform(it) }
        }
    }
}
