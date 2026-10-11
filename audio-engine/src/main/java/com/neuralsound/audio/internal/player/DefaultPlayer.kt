package com.neuralsound.audio.internal.player

import android.net.Uri
import android.util.Log
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.MixerAppendResult
import com.neuralsound.audio.internal.MixerCompletion
import com.neuralsound.audio.internal.MixerController
import com.neuralsound.audio.internal.MixerPlaybackFailureReason
import com.neuralsound.audio.internal.MixerPlaybackResult
import com.neuralsound.audio.internal.MixerPreparationResult
import com.neuralsound.audio.internal.OutputRoutePolicy
import com.neuralsound.audio.player.AudioRoute
import com.neuralsound.audio.player.CompletionReason
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.Player
import com.neuralsound.audio.player.PlayerEvent
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSnapshot
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerState
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackSettings
import com.neuralsound.audio.player.TrackState
import com.neuralsound.audio.player.VideoController
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [Player] over the native mixer controller.
 *
 * Every command runs in order on one command coroutine, so callers on the main thread never
 * block on native work and commands never interleave. [release] bypasses the queue so it can
 * interrupt a long load; pending calls then complete with [AudioError.Released].
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultPlayer(
    private val controller: MixerController,
    private val options: PlayerOptions,
    private val nativeAvailable: Boolean,
    commandDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val releaseDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Player {
    private val released = AtomicBoolean(false)
    private val pendingPitchTempo = AtomicReference<PitchTempo?>(null)
    private val scope = CoroutineScope(SupervisorJob() + commandDispatcher)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val pending = mutableSetOf<CompletableDeferred<AudioResult<*>>>()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PlayerEvent> = _events.asSharedFlow()

    override val video: VideoController = NoVideoController

    /** Loaded tracks in order, with the settings the app asked for (clamped). */
    private val tracks = linkedMapOf<TrackId, Track>()
    private var videoUri: Uri? = null

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Log.e(TAG, "Player command failed", error)
                }
            }
        }
        observeController()
    }

    override suspend fun load(source: PlayerSource): AudioResult<Unit> = call {
        loadNow(source)
    }

    override suspend fun appendTrack(track: Track): AudioResult<Unit> = call {
        appendNow(track)
    }

    override fun play() = enqueue { playNow() }

    override fun pause() = enqueue {
        if (controller.isPrepared() && controller.pause() == MixerPlaybackResult.Success) {
            updateLive { it.copy(status = PlayerStatus.PAUSED) }
        }
    }

    override fun togglePlayPause() {
        if (_state.value.isPlaying) pause() else play()
    }

    override fun seekTo(positionMs: Long) = enqueue { seekNow(positionMs) }

    override fun seekToFraction(fraction: Float) = enqueue {
        val duration = controller.totalDuration.value
        if (duration > 0L) seekNow((duration * fraction.coerceIn(0f, 1f)).toLong())
    }

    override fun seekBy(deltaMs: Long) = enqueue {
        seekNow((controller.currentPosition.value + deltaMs).coerceAtLeast(0L))
    }

    override fun seekAndPlay(positionMs: Long) = enqueue {
        if (seekNow(positionMs)) playNow()
    }

    override fun setRange(range: PlaybackRange?, loop: Boolean) = enqueue {
        controller.setPlaybackRange(LegacyMapping.toInternal(range))
        controller.setLooping(loop)
        updateLive {
            it.copy(range = LegacyMapping.toPublic(controller.currentPlaybackRange()) ?: range, loop = loop)
        }
    }

    /**
     * Slider drags call this many times per second; only the latest value is applied, and the
     * controller additionally debounces the native tempo change.
     */
    override fun setPitchTempo(pitchTempo: PitchTempo) {
        if (released.get()) return
        if (pendingPitchTempo.getAndSet(pitchTempo.clamped()) != null) return
        enqueue {
            val latest = pendingPitchTempo.getAndSet(null) ?: return@enqueue
            controller.setTempo(latest.tempo)
            controller.setPitchSemitones(latest.semitones)
            updateLive { it.copy(pitchTempo = latest) }
        }
    }

    override fun updateTrack(id: TrackId, transform: (TrackSettings) -> TrackSettings) = enqueue {
        val current = tracks[id] ?: return@enqueue
        val next = transform(current.settings).clamped()
        if (next == current.settings) return@enqueue
        if (next.volume != current.settings.volume) controller.setVolume(id, next.volume)
        if (next.muted != current.settings.muted) controller.setMuted(id, next.muted)
        if (next.balance != current.settings.balance) controller.setChannelGain(id, LegacyMapping.toInternal(next.balance))
        if (next.offsetMs != current.settings.offsetMs) controller.setTrackOffset(id, next.offsetMs)
        if (next.boostDb != current.settings.boostDb) controller.setSourceBoostDb(id, next.boostDb)
        tracks[id] = current.copy(settings = next)
        publishTracks()
    }

    override suspend fun detach(): PlayerSnapshot? {
        val snapshot = call { AudioResult.Ok(snapshotOrNull()) }.getOrNull()
        release()
        return snapshot
    }

    override suspend fun restore(snapshot: PlayerSnapshot): AudioResult<Unit> = load(
        snapshot.source.copy(startAtMs = snapshot.positionMs, autoPlay = snapshot.wasPlaying),
    )

    override suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        _state.update { it.copy(status = PlayerStatus.RELEASED) }
        synchronized(pending) {
            pending.forEach { it.complete(AudioResult.Err(AudioError.Released)) }
            pending.clear()
        }
        commands.close()
        withContext(releaseDispatcher) { controller.closeNow() }
        scope.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // Commands (run on the command coroutine)
    // ---------------------------------------------------------------------------------------

    private suspend fun loadNow(source: PlayerSource): AudioResult<Unit> {
        if (!nativeAvailable) return AudioResult.Err(AudioError.Unavailable)
        PlayerOptionsPolicy.problem(options)?.let { return AudioResult.Err(AudioError.InvalidConfiguration(it)) }
        if (source.tracks.size > options.maxTracks) {
            return AudioResult.Err(AudioError.TrackLimitExceeded(options.maxTracks))
        }
        val requested = source.tracks.map { it.copy(settings = it.settings.clamped()) }
        val pitchTempo = source.pitchTempo.clamped()
        tracks.clear()
        requested.forEach { tracks[it.id] = it }
        videoUri = source.videoUri
        _state.update {
            if (it.status == PlayerStatus.RELEASED) it else PlayerState(
                status = PlayerStatus.LOADING,
                positionMs = source.startAtMs,
                tracks = trackStates(),
                pitchTempo = pitchTempo,
                range = source.range,
                loop = source.loop,
            )
        }
        controller.setPlaybackRange(LegacyMapping.toInternal(source.range))
        val result = try {
            controller.preparePlayers(
                tracks = requested.associate { it.id to it.uri },
                startPositionMs = source.startAtMs,
                looping = source.loop,
                effects = LegacyMapping.toInternal(pitchTempo),
                trackOffsetsMs = requested.associate { it.id to it.settings.offsetMs },
                initialVolumes = requested.associate { it.id to it.settings.volume },
                initialChannelGains = requested.associate { it.id to LegacyMapping.toInternal(it.settings.balance) },
            )
        } catch (cancellation: CancellationException) {
            if (released.get()) return AudioResult.Err(AudioError.Released)
            throw cancellation
        }
        if (released.get()) return AudioResult.Err(AudioError.Released)
        return when (result) {
            MixerPreparationResult.Success -> {
                requested.forEach { applyPostJoinSettings(it) }
                updateLive {
                    it.copy(
                        status = PlayerStatus.READY,
                        durationMs = controller.totalDuration.value,
                        positionMs = controller.currentPosition.value,
                        range = LegacyMapping.toPublic(controller.currentPlaybackRange()),
                        route = currentRoute(),
                        error = null,
                    )
                }
                if (source.autoPlay) playNow()
                AudioResult.Done
            }
            is MixerPreparationResult.Failure -> {
                val error = result.failure?.let(::toPublic) ?: AudioError.Native("load")
                fail(error)
                AudioResult.Err(error)
            }
        }
    }

    private fun appendNow(track: Track): AudioResult<Unit> {
        if (!controller.isPrepared()) return AudioResult.Err(AudioError.NotLoaded)
        if (track.id !in tracks && tracks.size >= options.maxTracks) {
            return AudioResult.Err(AudioError.TrackLimitExceeded(options.maxTracks))
        }
        val clamped = track.copy(settings = track.settings.clamped())
        val result = controller.appendTrack(
            type = clamped.id,
            uri = clamped.uri,
            initialVolume = clamped.settings.volume,
            muted = clamped.settings.muted,
            initialChannelGain = LegacyMapping.toInternal(clamped.settings.balance),
            initialOffsetMs = clamped.settings.offsetMs,
        )
        return when (result) {
            MixerAppendResult.Success -> {
                applyPostJoinSettings(clamped)
                tracks[clamped.id] = clamped
                updateLive { it.copy(tracks = trackStates(), durationMs = controller.totalDuration.value) }
                AudioResult.Done
            }
            is MixerAppendResult.Failure -> {
                val error = LegacyMapping.toPublic(result.failure, { uri ->
                    clamped.id.takeIf { clamped.uri.toString() == uri } ?: trackIdForUri(uri)
                }, nativeAvailable)
                if (error is AudioError.TrackFailed) _events.tryEmit(PlayerEvent.TrackFailed(error.trackId, error.reason))
                AudioResult.Err(error)
            }
        }
    }

    /** Settings the controller does not take at prepare/append time. */
    private fun applyPostJoinSettings(track: Track) {
        controller.setMuted(track.id, track.settings.muted)
        if (track.settings.boostDb != 0) controller.setSourceBoostDb(track.id, track.settings.boostDb)
    }

    private fun playNow() {
        if (!controller.isPrepared()) return
        when (val result = controller.play()) {
            MixerPlaybackResult.Success -> updateLive { it.copy(status = PlayerStatus.PLAYING, error = null) }
            is MixerPlaybackResult.Failure -> if (result.reason == MixerPlaybackFailureReason.NATIVE_START_FAILED) {
                fail(AudioError.Native("play"))
            }
        }
    }

    private fun seekNow(positionMs: Long): Boolean = controller.seekToMs(positionMs.coerceAtLeast(0L))

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    private fun observeController() {
        scope.launch {
            controller.currentPosition.collect { position -> updateLive { it.copy(positionMs = position) } }
        }
        scope.launch {
            controller.totalDuration.collect { duration ->
                updateLive { if (duration > 0L) it.copy(durationMs = duration) else it }
            }
        }
        scope.launch {
            controller.isPlaying.collect { playing ->
                updateLive {
                    when (it.status) {
                        PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.READY, PlayerStatus.ENDED ->
                            if (playing) it.copy(status = PlayerStatus.PLAYING)
                            else if (it.status == PlayerStatus.PLAYING) it.copy(status = PlayerStatus.PAUSED)
                            else it
                        else -> it
                    }
                }
            }
        }
        scope.launch {
            controller.routeRevision.collect {
                val route = currentRoute() ?: return@collect
                val changed = _state.value.route != route
                updateLive { it.copy(route = route) }
                if (changed) _events.tryEmit(PlayerEvent.RouteChanged(route))
            }
        }
        scope.launch {
            controller.runtimeFailure.collect { failure -> if (failure != null) fail(toPublic(failure)) }
        }
        scope.launch {
            controller.completions.collect { completion ->
                var ended = false
                updateLive {
                    ended = true
                    it.copy(status = PlayerStatus.ENDED, positionMs = controller.currentPosition.value)
                }
                if (ended) {
                    val reason = when (completion) {
                        MixerCompletion.END_OF_MEDIA -> CompletionReason.END_OF_MEDIA
                        MixerCompletion.END_OF_RANGE -> CompletionReason.END_OF_RANGE
                    }
                    _events.tryEmit(PlayerEvent.Completed(reason, controller.currentPosition.value))
                }
            }
        }
    }

    /** Updates state unless the player is idle, failed or released. */
    private inline fun updateLive(crossinline transform: (PlayerState) -> PlayerState) {
        _state.update { if (it.status in INACTIVE) it else transform(it) }
    }

    private fun publishTracks() = updateLive { it.copy(tracks = trackStates()) }

    private fun trackStates(): Map<TrackId, TrackState> =
        tracks.mapValuesTo(linkedMapOf()) { (_, track) -> TrackState(settings = track.settings) }

    private fun currentRoute(): AudioRoute? {
        if (!controller.isPrepared()) return null
        val deviceId = controller.currentRoutedOutputDeviceId()
        val deviceType = controller.currentRoutedOutputDeviceType(deviceId)
        return AudioRoute(
            kind = OutputRoutePolicy.routeKind(deviceType),
            deviceId = deviceId,
            deviceType = deviceType,
            topologyRevision = controller.currentOutputTopologyRevision(),
        )
    }

    private fun fail(error: AudioError) {
        var failed = false
        _state.update {
            if (it.status == PlayerStatus.RELEASED || it.status == PlayerStatus.FAILED) it
            else {
                failed = true
                it.copy(status = PlayerStatus.FAILED, positionMs = 0L, durationMs = 0L, route = null, error = error)
            }
        }
        if (!failed) return
        if (error is AudioError.TrackFailed) _events.tryEmit(PlayerEvent.TrackFailed(error.trackId, error.reason))
        _events.tryEmit(PlayerEvent.Failed(error))
    }

    private fun toPublic(failure: com.neuralsound.audio.internal.legacy.AudioFailure): AudioError =
        LegacyMapping.toPublic(failure, ::trackIdForUri, nativeAvailable)

    private fun trackIdForUri(uri: String): TrackId? = tracks.values.firstOrNull { it.uri.toString() == uri }?.id

    private fun snapshotOrNull(): PlayerSnapshot? {
        val current = _state.value
        if (tracks.isEmpty() || current.status !in SNAPSHOT_STATUSES) return null
        return PlayerSnapshot(
            source = PlayerSource(
                tracks = tracks.values.toList(),
                videoUri = videoUri,
                range = current.range,
                loop = current.loop,
                pitchTempo = current.pitchTempo,
            ),
            positionMs = current.positionMs,
            wasPlaying = current.isPlaying,
        )
    }

    // ---------------------------------------------------------------------------------------
    // Command queue
    // ---------------------------------------------------------------------------------------

    private fun enqueue(block: suspend () -> Unit) {
        if (released.get()) return
        commands.trySend { if (!released.get()) block() }
    }

    private suspend fun <T> call(block: suspend () -> AudioResult<T>): AudioResult<T> {
        if (released.get()) return AudioResult.Err(AudioError.Released)
        val result = CompletableDeferred<AudioResult<*>>()
        synchronized(pending) { pending += result }
        val sent = commands.trySend {
            try {
                result.complete(if (released.get()) AudioResult.Err(AudioError.Released) else block())
            } catch (cancellation: CancellationException) {
                result.complete(AudioResult.Err(AudioError.Released))
                throw cancellation
            } catch (error: Throwable) {
                Log.e(TAG, "Player call failed", error)
                result.complete(AudioResult.Err(AudioError.Native("player")))
            } finally {
                synchronized(pending) { pending -= result }
            }
        }.isSuccess
        if (!sent) return AudioResult.Err(AudioError.Released)
        @Suppress("UNCHECKED_CAST")
        return result.await() as AudioResult<T>
    }

    private companion object {
        const val TAG = "NeuralSoundPlayer"
        const val EVENT_BUFFER = 64
        val INACTIVE = setOf(PlayerStatus.IDLE, PlayerStatus.FAILED, PlayerStatus.RELEASED)
        val SNAPSHOT_STATUSES = setOf(PlayerStatus.READY, PlayerStatus.PLAYING, PlayerStatus.PAUSED, PlayerStatus.ENDED)
    }
}

/** Placeholder until the video presenter is wired into the player (FEAT-0001 Task 24). */
internal object NoVideoController : VideoController {
    override fun attachPresentation() = Unit
    override fun suspendPresentation() = Unit
    override fun detachPresentation() = Unit
    override fun notifySurfaceRecreated() = Unit
}

/** Validation of [PlayerOptions]; an invalid option fails [Player.load] with `InvalidConfiguration`. */
internal object PlayerOptionsPolicy {
    fun problem(options: PlayerOptions): String? = when {
        options.maxTracks !in 1..PlayerOptions.MAX_SUPPORTED_TRACKS ->
            "maxTracks must be between 1 and ${PlayerOptions.MAX_SUPPORTED_TRACKS}, was ${options.maxTracks}"
        options.focus is FocusPolicy.Duck && (options.focus as FocusPolicy.Duck).level !in 0f..1f ->
            "Duck level must be between 0 and 1"
        else -> null
    }
}
