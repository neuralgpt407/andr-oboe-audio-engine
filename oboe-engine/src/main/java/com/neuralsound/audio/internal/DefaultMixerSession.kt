package com.neuralsound.audio.internal

import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.AudioRoute
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerSession
import com.neuralsound.audio.MixerState
import com.neuralsound.audio.MixerStatus
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.TrackMix
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class DefaultMixerSession(
    private val controller: MixerController,
) : MixerSession {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(MixerState())
    override val state: StateFlow<MixerState> = _state.asStateFlow()

    init {
        scope.launch {
            controller.currentPosition.collect { positionMs ->
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES) current
                    else current.copy(positionMs = positionMs)
                }
            }
        }
        scope.launch {
            controller.totalDuration.collect { durationMs ->
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES) current
                    else current.copy(durationMs = durationMs)
                }
            }
        }
        scope.launch {
            controller.isPlaying.collect { playing ->
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES || current.status == MixerStatus.PREPARING) {
                        current
                    } else {
                        current.copy(status = if (playing) MixerStatus.PLAYING else MixerStatus.PAUSED)
                    }
                }
            }
        }
        scope.launch {
            controller.tempoSpeed.collect { tempo ->
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES) current
                    else current.copy(effects = current.effects.copy(tempo = tempo))
                }
            }
        }
        scope.launch {
            controller.pitchSemitones.collect { pitch ->
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES) current
                    else current.copy(effects = current.effects.copy(pitchSemitones = pitch))
                }
            }
        }
        scope.launch {
            controller.routeRevision.collect {
                _state.update { current ->
                    if (current.status in TERMINAL_STATUSES) current
                    else current.copy(route = currentRoute())
                }
            }
        }
        scope.launch {
            controller.runtimeFailure.collect { failure ->
                if (failure != null && _state.value.status !in TERMINAL_STATUSES) {
                    fail(failure)
                }
            }
        }
    }

    override suspend fun prepare(request: MixerRequest): AudioResult {
        releasedFailure()?.let { return it }
        _state.value = MixerState(
            status = MixerStatus.PREPARING,
            positionMs = request.startPositionMs,
            playbackRange = request.playbackRange,
            looping = request.looping,
            effects = request.effects,
            tracks = request.tracks.associate { track ->
                track.id to com.neuralsound.audio.MixerTrackState(track.mix, track.offsetMs)
            },
        )
        controller.setPlaybackRange(request.playbackRange)

        val tracks = linkedMapOf<TrackId, android.net.Uri>()
        request.tracks.forEach { tracks[it.id] = it.uri }
        val result = controller.preparePlayers(
            tracks = tracks,
            startPositionMs = request.startPositionMs,
            looping = request.looping,
            effects = request.effects,
            trackOffsetsMs = request.tracks.associate { it.id to it.offsetMs },
            initialVolumes = request.tracks.associate { it.id to it.mix.volume },
            initialChannelGains = request.tracks.associate { it.id to it.mix.channelGain },
        )
        return when (result) {
            MixerPreparationResult.Success -> {
                request.tracks.forEach { track ->
                    controller.setMuted(track.id, track.mix.muted)
                }
                _state.update {
                    it.copy(
                        status = MixerStatus.READY,
                        durationMs = controller.totalDuration.value,
                        playbackRange = controller.currentPlaybackRange(),
                        tracks = controller.snapshotTracks(),
                        route = currentRoute(),
                        failure = null,
                    )
                }
                if (request.autoPlay) play() else AudioResult.Success
            }

            is MixerPreparationResult.Failure -> fail(
                result.failure ?: AudioFailure.NativeOperationFailed(
                    operation = "prepare",
                    detail = result.message,
                )
            )
        }
    }

    override fun appendTrack(track: MixerTrack): AudioResult {
        readyFailure()?.let { return it }
        val appendResult = controller.appendTrack(
            type = track.id,
            uri = track.uri,
            initialVolume = track.mix.volume,
            muted = track.mix.muted,
            initialChannelGain = track.mix.channelGain,
        )
        if (appendResult is MixerAppendResult.Failure) {
            return AudioResult.Failure(appendResult.failure)
        }
        controller.setTrackOffset(track.id, track.offsetMs)
        publishRuntimeState()
        return AudioResult.Success
    }

    override fun play(): AudioResult {
        readyFailure()?.let { return it }
        return controller.play().toAudioResult().also { result ->
            if (result == AudioResult.Success) {
                _state.update { it.copy(status = MixerStatus.PLAYING, failure = null) }
            }
        }
    }

    override fun pause(): AudioResult {
        readyFailure()?.let { return it }
        return controller.pause().toAudioResult().also { result ->
            if (result == AudioResult.Success) {
                _state.update { it.copy(status = MixerStatus.PAUSED, failure = null) }
            }
        }
    }

    override fun seekTo(positionMs: Long): AudioResult {
        readyFailure()?.let { return it }
        if (!controller.seekToMs(positionMs.coerceAtLeast(0L))) {
            return AudioResult.Failure(AudioFailure.NotPrepared)
        }
        return AudioResult.Success
    }

    override fun setPlaybackRange(range: PlaybackRange?): AudioResult {
        releasedFailure()?.let { return it }
        controller.setPlaybackRange(range)
        _state.update { it.copy(playbackRange = controller.currentPlaybackRange()) }
        return AudioResult.Success
    }

    override fun setLooping(looping: Boolean): AudioResult {
        releasedFailure()?.let { return it }
        controller.setLooping(looping)
        _state.update { it.copy(looping = looping) }
        return AudioResult.Success
    }

    override fun updateTrackMix(trackId: TrackId, mix: TrackMix): AudioResult {
        readyFailure()?.let { return it }
        if (trackId !in state.value.tracks) {
            return AudioResult.Failure(
                AudioFailure.NativeOperationFailed("updateTrackMix", "Unknown track ID: $trackId")
            )
        }
        controller.setVolume(trackId, mix.volume)
        controller.setMuted(trackId, mix.muted)
        controller.setChannelGain(trackId, mix.channelGain)
        publishRuntimeState()
        return AudioResult.Success
    }

    override fun setTrackOffset(trackId: TrackId, offsetMs: Long): AudioResult {
        readyFailure()?.let { return it }
        if (trackId !in state.value.tracks) {
            return AudioResult.Failure(
                AudioFailure.NativeOperationFailed("setTrackOffset", "Unknown track ID: $trackId")
            )
        }
        controller.setTrackOffset(trackId, offsetMs)
        publishRuntimeState()
        return AudioResult.Success
    }

    override fun setEffects(effects: PlaybackEffects): AudioResult {
        releasedFailure()?.let { return it }
        controller.setTempo(effects.tempo)
        controller.setPitchSemitones(effects.pitchSemitones)
        _state.update { it.copy(effects = effects) }
        return AudioResult.Success
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        controller.closeNow()
        _state.value = _state.value.copy(status = MixerStatus.RELEASED)
    }

    private fun publishRuntimeState() {
        _state.update {
            it.copy(
                tracks = controller.snapshotTracks(),
                route = currentRoute(),
            )
        }
    }

    private fun currentRoute(): AudioRoute? {
        if (!controller.isPrepared()) return null
        val deviceId = controller.currentRoutedOutputDeviceId()
        return AudioRoute(
            deviceId = deviceId,
            deviceType = controller.currentRoutedOutputDeviceType(deviceId),
            topologyRevision = controller.currentOutputTopologyRevision(),
        )
    }

    private fun readyFailure(): AudioResult.Failure? {
        releasedFailure()?.let { return it }
        val current = _state.value
        if (current.status == MixerStatus.FAILED) {
            return AudioResult.Failure(
                current.failure ?: AudioFailure.NativeOperationFailed("mixer")
            )
        }
        return if (controller.isPrepared()) null else AudioResult.Failure(AudioFailure.NotPrepared)
    }

    private fun releasedFailure(): AudioResult.Failure? {
        return if (closed.get()) AudioResult.Failure(AudioFailure.Released) else null
    }

    private fun fail(failure: AudioFailure): AudioResult.Failure {
        _state.update {
            it.copy(
                status = MixerStatus.FAILED,
                positionMs = 0L,
                durationMs = 0L,
                playbackRange = null,
                tracks = emptyMap(),
                route = null,
                failure = failure,
            )
        }
        return AudioResult.Failure(failure)
    }

    private fun MixerPlaybackResult.toAudioResult(): AudioResult {
        return when (this) {
            MixerPlaybackResult.Success -> AudioResult.Success
            is MixerPlaybackResult.Failure -> AudioResult.Failure(
                when (reason) {
                    MixerPlaybackFailureReason.NOT_PREPARED -> AudioFailure.NotPrepared
                    MixerPlaybackFailureReason.ENGINE_INACTIVE -> AudioFailure.EngineInactive
                    MixerPlaybackFailureReason.NATIVE_START_FAILED ->
                        AudioFailure.NativeOperationFailed(operation = "play")
                }
            )
        }
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            MixerStatus.IDLE,
            MixerStatus.FAILED,
            MixerStatus.RELEASED,
        )
    }
}
