package com.neuralsound.audio.internal

import android.net.Uri
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.ChannelGain
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerStatus
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.MixerTrackState
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.TrackMix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultMixerSessionNormalizationBehaviorTest {
    private val original = MixerTrack(
        id = TrackId("original"),
        uri = Uri.parse("file:///original-48000.wav"),
    )
    private val denoised = MixerTrack(
        id = TrackId("denoised"),
        uri = Uri.parse("file:///denoised-44100.wav"),
        mix = TrackMix(muted = true),
    )

    @Test
    fun mixedRateWorkflowRemainsOnePublicSessionTimeline() = runBlocking {
        val controller = FakeMixerController()
        val session = DefaultMixerSession(controller)

        assertEquals(
            AudioResult.Success,
            session.prepare(MixerRequest(tracks = listOf(original))),
        )
        assertEquals(AudioResult.Success, session.appendTrack(denoised))
        assertEquals(
            AudioResult.Success,
            session.updateTrackMix(original.id, TrackMix(muted = true)),
        )
        assertEquals(
            AudioResult.Success,
            session.updateTrackMix(denoised.id, TrackMix(muted = false)),
        )
        assertEquals(AudioResult.Success, session.seekTo(9_900L))
        assertEquals(
            AudioResult.Success,
            session.setEffects(PlaybackEffects(tempo = 1.25f, pitchSemitones = 2)),
        )

        assertEquals(MixerStatus.READY, session.state.value.status)
        assertEquals(10_000L, session.state.value.durationMs)
        assertEquals(setOf(original.id, denoised.id), session.state.value.tracks.keys)
        assertTrue(session.state.value.tracks.getValue(original.id).mix.muted)
        assertFalse(session.state.value.tracks.getValue(denoised.id).mix.muted)
        assertEquals(PlaybackEffects(tempo = 1.25f, pitchSemitones = 2), session.state.value.effects)

        assertEquals(AudioResult.Success, session.play())
        controller.finishPlaybackAt(10_000L)
        withTimeout(2_000L) {
            while (
                session.state.value.status != MixerStatus.PAUSED ||
                session.state.value.positionMs != 10_000L
            ) {
                delay(10L)
            }
        }
        assertEquals(10_000L, session.state.value.durationMs)

        session.close()
        session.close()
        assertEquals(MixerStatus.RELEASED, session.state.value.status)
    }

    @Test
    fun progressiveAppendAppliesOffsetBeforePublishingTheTrack() = runBlocking {
        val controller = FakeMixerController()
        val session = DefaultMixerSession(controller)
        val offsetTrack = denoised.copy(offsetMs = 275L)

        assertEquals(
            AudioResult.Success,
            session.prepare(MixerRequest(tracks = listOf(original))),
        )
        assertEquals(AudioResult.Success, session.appendTrack(offsetTrack))

        assertEquals(275L, controller.offsetObservedDuringAppend)
        assertEquals(275L, session.state.value.tracks.getValue(denoised.id).offsetMs)
        session.close()
    }

    @Test
    fun typedPreparationFailureDoesNotPublishPartiallyPreparedTracks() = runBlocking {
        val failure = AudioFailure.NativeOperationFailed(
            operation = "decodeTrack",
            detail = "Track original could not be decoded",
        )
        val controller = FakeMixerController(
            prepareResult = MixerPreparationResult.Failure(
                message = "decoder failed",
                failure = failure,
            )
        )
        val session = DefaultMixerSession(controller)

        assertEquals(
            AudioResult.Failure(failure),
            session.prepare(MixerRequest(tracks = listOf(original))),
        )
        assertEquals(MixerStatus.FAILED, session.state.value.status)
        assertEquals(failure, session.state.value.failure)
        assertTrue(session.state.value.tracks.isEmpty())
        assertEquals(0L, session.state.value.durationMs)

        session.close()
    }

    @Test
    fun asynchronousResamplerFailureTransitionsThePublicSessionToFailed() = runBlocking {
        val controller = FakeMixerController()
        val session = DefaultMixerSession(controller)
        val failure = AudioFailure.NativeOperationFailed(
            operation = "normalizeTrack",
            detail = "Track original could not be normalized to 44100 Hz",
        )

        assertEquals(
            AudioResult.Success,
            session.prepare(MixerRequest(tracks = listOf(original))),
        )
        controller.publishRuntimeFailure(failure)
        withTimeout(2_000L) {
            while (session.state.value.status != MixerStatus.FAILED) {
                delay(10L)
            }
        }

        assertEquals(failure, session.state.value.failure)
        assertTrue(session.state.value.tracks.isEmpty())
        controller.currentPosition.value = 9_500L
        controller.totalDuration.value = 10_000L
        delay(100L)
        assertEquals(0L, session.state.value.positionMs)
        assertEquals(0L, session.state.value.durationMs)
        assertEquals(AudioResult.Failure(failure), session.play())
        assertEquals(MixerStatus.FAILED, session.state.value.status)
        assertEquals(failure, session.state.value.failure)
        session.close()
    }

    @Test
    fun closeDuringSuccessfulPrepareReturnsReleasedAndKeepsReleasedState() = runBlocking {
        val prepareEntered = CompletableDeferred<Unit>()
        val allowPrepareToFinish = CompletableDeferred<Unit>()
        val controller = FakeMixerController(
            beforePrepareResult = {
                prepareEntered.complete(Unit)
                allowPrepareToFinish.await()
            },
        )
        val session = DefaultMixerSession(controller)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            session.prepare(MixerRequest(tracks = listOf(original)))
        }

        prepareEntered.await()
        session.close()
        allowPrepareToFinish.complete(Unit)

        assertEquals(AudioResult.Failure(AudioFailure.Released), result.await())
        assertEquals(MixerStatus.RELEASED, session.state.value.status)
    }

    @Test
    fun closeDuringFailedPrepareReturnsReleasedAndKeepsReleasedState() = runBlocking {
        val prepareEntered = CompletableDeferred<Unit>()
        val allowPrepareToFinish = CompletableDeferred<Unit>()
        val controller = FakeMixerController(
            prepareResult = MixerPreparationResult.Failure("decoder failed"),
            beforePrepareResult = {
                prepareEntered.complete(Unit)
                allowPrepareToFinish.await()
            },
        )
        val session = DefaultMixerSession(controller)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            session.prepare(MixerRequest(tracks = listOf(original)))
        }

        prepareEntered.await()
        session.close()
        allowPrepareToFinish.complete(Unit)

        assertEquals(AudioResult.Failure(AudioFailure.Released), result.await())
        assertEquals(MixerStatus.RELEASED, session.state.value.status)
    }

    @Test
    fun closeMapsControllerPreparationCancellationToReleased() = runBlocking {
        val prepareEntered = CompletableDeferred<Unit>()
        val allowCancellation = CompletableDeferred<Unit>()
        val controller = FakeMixerController(
            beforePrepareResult = {
                prepareEntered.complete(Unit)
                allowCancellation.await()
                throw CancellationException("controller closed")
            },
        )
        val session = DefaultMixerSession(controller)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            session.prepare(MixerRequest(tracks = listOf(original)))
        }

        prepareEntered.await()
        session.close()
        allowCancellation.complete(Unit)

        assertEquals(AudioResult.Failure(AudioFailure.Released), result.await())
        assertEquals(MixerStatus.RELEASED, session.state.value.status)
    }

    private class FakeMixerController(
        private var prepareResult: MixerPreparationResult = MixerPreparationResult.Success,
        private val beforePrepareResult: suspend () -> Unit = {},
    ) : MixerController {
        override val routeRevision: StateFlow<Long> = MutableStateFlow(0L)
        override val isPlaying: MutableStateFlow<Boolean> = MutableStateFlow(false)
        override val currentPosition: MutableStateFlow<Long> = MutableStateFlow(0L)
        override val totalDuration: MutableStateFlow<Long> = MutableStateFlow(0L)
        override val tempoSpeed: MutableStateFlow<Float> = MutableStateFlow(1f)
        override val pitchSemitones: MutableStateFlow<Int> = MutableStateFlow(0)
        override val runtimeFailure: MutableStateFlow<AudioFailure?> = MutableStateFlow(null)

        private var prepared = false
        private var playbackRange: PlaybackRange? = null
        private val tracks = linkedMapOf<TrackId, MixerTrackState>()
        var offsetObservedDuringAppend: Long? = null
            private set

        override fun currentRoutedOutputDeviceId(): Int? = null
        override fun currentRoutedOutputDeviceType(deviceId: Int?): Int? = null
        override fun currentOutputTopologyRevision(): Long = 0L

        override fun setPlaybackRange(range: PlaybackRange?) {
            playbackRange = range
        }

        override suspend fun preparePlayers(
            tracks: Map<TrackId, Uri>,
            startPositionMs: Long,
            looping: Boolean,
            effects: PlaybackEffects,
            trackOffsetsMs: Map<TrackId, Long>,
            initialVolumes: Map<TrackId, Float>,
            initialChannelGains: Map<TrackId, ChannelGain>,
        ): MixerPreparationResult {
            beforePrepareResult()
            if (prepareResult != MixerPreparationResult.Success) {
                prepared = false
                return prepareResult
            }
            prepared = true
            currentPosition.value = startPositionMs
            totalDuration.value = 10_000L
            this.tracks.clear()
            tracks.keys.forEach { id ->
                this.tracks[id] = MixerTrackState(
                    mix = TrackMix(
                        volume = initialVolumes[id] ?: 0.8f,
                        channelGain = initialChannelGains[id] ?: ChannelGain.Center,
                    ),
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
            initialChannelGain: ChannelGain,
            initialOffsetMs: Long,
        ): MixerAppendResult {
            tracks[type] = MixerTrackState(
                mix = TrackMix(
                    volume = initialVolume,
                    muted = muted,
                    channelGain = initialChannelGain,
                ),
                offsetMs = initialOffsetMs,
            )
            offsetObservedDuringAppend = tracks.getValue(type).offsetMs
            return MixerAppendResult.Success
        }

        override fun play(): MixerPlaybackResult {
            isPlaying.value = true
            return MixerPlaybackResult.Success
        }

        override fun pause(): MixerPlaybackResult {
            isPlaying.value = false
            return MixerPlaybackResult.Success
        }

        override fun setVolume(type: TrackId, volume: Float) {
            updateMix(type) { it.copy(volume = volume) }
        }

        override fun setChannelGain(type: TrackId, channelGain: ChannelGain) {
            updateMix(type) { it.copy(channelGain = channelGain) }
        }

        override fun setMuted(type: TrackId, muted: Boolean) {
            updateMix(type) { it.copy(muted = muted) }
        }

        override fun setTrackOffset(type: TrackId, offsetMs: Long) {
            tracks[type] = tracks.getValue(type).copy(offsetMs = offsetMs)
        }

        override fun setTempo(speed: Float) {
            tempoSpeed.value = speed
        }

        override fun setPitchSemitones(semitones: Int) {
            pitchSemitones.value = semitones
        }

        override fun setLooping(looping: Boolean) = Unit

        override fun seekToMs(positionMs: Long): Boolean {
            currentPosition.value = positionMs
            return prepared
        }

        override fun snapshotTracks(): Map<TrackId, MixerTrackState> = tracks.toMap()
        override fun currentPlaybackRange(): PlaybackRange? = playbackRange
        override fun isPrepared(): Boolean = prepared

        override fun closeNow() {
            prepared = false
            tracks.clear()
        }

        fun publishRuntimeFailure(failure: AudioFailure) {
            runtimeFailure.value = failure
        }

        fun finishPlaybackAt(positionMs: Long) {
            currentPosition.value = positionMs
            isPlaying.value = false
        }

        private fun updateMix(type: TrackId, transform: (TrackMix) -> TrackMix) {
            val state = tracks.getValue(type)
            tracks[type] = state.copy(mix = transform(state.mix))
        }
    }
}
