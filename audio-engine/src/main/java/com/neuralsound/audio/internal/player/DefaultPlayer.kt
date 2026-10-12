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
import com.neuralsound.audio.internal.PlaybackRangePolicy
import com.neuralsound.audio.player.AudioRoute
import com.neuralsound.audio.player.CallbackStats
import com.neuralsound.audio.player.CompletionReason
import com.neuralsound.audio.player.FocusPolicy
import com.neuralsound.audio.player.PitchTempo
import com.neuralsound.audio.player.PlaybackClock
import com.neuralsound.audio.player.PlaybackRange
import com.neuralsound.audio.player.Player
import com.neuralsound.audio.player.MixApplyResult
import com.neuralsound.audio.player.PlayerEvent
import com.neuralsound.audio.player.PlayerMix
import com.neuralsound.audio.player.PlayerOptions
import com.neuralsound.audio.player.PlayerSnapshot
import com.neuralsound.audio.player.PlayerSource
import com.neuralsound.audio.player.PlayerState
import com.neuralsound.audio.player.PlayerStatus
import com.neuralsound.audio.player.Track
import com.neuralsound.audio.player.TrackMix
import com.neuralsound.audio.player.TrackSettings
import com.neuralsound.audio.player.TrackState
import com.neuralsound.audio.internal.video.NoVideoPort
import com.neuralsound.audio.internal.video.VideoPort
import com.neuralsound.audio.player.VideoController
import com.neuralsound.audio.player.VideoState
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    videoFactory: (StateFlow<PlayerState>, ((VideoState) -> VideoState) -> Unit) -> VideoPort = { _, _ -> NoVideoPort },
    nanoTime: () -> Long = System::nanoTime,
) : Player {
    private val released = AtomicBoolean(false)
    private class PitchSlot(val value: PitchTempo, val barrier: Long)

    /** Latest coalesced pitch/tempo, guarded by [pitchLock]; never coalesced across a [barrier] bump. */
    private var pitchSlot: PitchSlot? = null
    private val pitchLock = Any()

    /** Bumped on the caller's thread by commands that set pitch/tempo themselves (load, applyMix). */
    private val barrier = java.util.concurrent.atomic.AtomicLong(0L)
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

    private val playbackClock = DefaultPlaybackClock(
        scope = scope,
        syncing = controller.isSyncing,
        enginePosition = { controller.currentPosition.value },
        onSeekSettled = { position -> updateLive { it.copy(positionMs = position) } },
        nanoTime = nanoTime,
    )
    override val clock: PlaybackClock
        get() = playbackClock

    private val videoPort: VideoPort = videoFactory(_state.asStateFlow()) { transform ->
        _state.update { if (it.status == PlayerStatus.RELEASED) it else it.copy(video = transform(it.video)) }
    }
    override val video: VideoController
        get() = videoPort

    /** Loaded tracks in order, with the settings the app asked for (clamped). */
    private val tracks = linkedMapOf<TrackId, Track>()
    private var videoUri: Uri? = null

    /** Tracks whose solo is on (only ever contains loaded track ids). */
    private val soloed = linkedSetOf<TrackId>()

    /** Mix settings for ids that are not loaded yet; applied (and removed) when that id is appended. */
    private val pendingMix = linkedMapOf<TrackId, TrackMix>()

    /** Last mute value pushed to the controller per track, so only changes are sent. */
    private val engineMuted = hashMapOf<TrackId, Boolean>()

    /** Pitch/tempo/range/loop from an [applyMix] issued before [load]; consumed by the next load. */
    private var stagedGlobals: PlayerMix? = null

    private val _mix = MutableStateFlow(PlayerMix())
    override val mix: StateFlow<PlayerMix> = _mix.asStateFlow()

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

    override suspend fun load(source: PlayerSource): AudioResult<Unit> {
        barrier.incrementAndGet()
        return loadQueued(source)
    }

    private suspend fun loadQueued(source: PlayerSource): AudioResult<Unit> = call {
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
        seekNow((_state.value.positionMs + deltaMs).coerceAtLeast(0L))
    }

    override fun seekAndPlay(positionMs: Long) = enqueue {
        if (seekNow(positionMs)) playNow()
    }

    override fun setRange(range: PlaybackRange?, loop: Boolean) = enqueue { applyRangeNow(range, loop) }

    private fun applyRangeNow(range: PlaybackRange?, loop: Boolean) {
        val positionBefore = controller.currentPosition.value
        controller.setPlaybackRange(LegacyMapping.toInternal(range))
        controller.setLooping(loop)
        // The controller seeks to the range start when the position is outside the range.
        val applied = controller.currentPlaybackRange()
        if (applied != null && controller.totalDuration.value > 0L &&
            (positionBefore < applied.startMs || positionBefore > applied.endMs)
        ) {
            markSeek(applied.startMs)
            if (!controller.isSyncing.value) playbackClock.settleSeekNow()
        }
        updateLive {
            it.copy(range = LegacyMapping.toPublic(controller.currentPlaybackRange()) ?: range, loop = loop)
        }
        publishMix()
    }

    /**
     * Slider drags call this many times per second; only the latest value is applied, and the
     * controller additionally debounces the native tempo change.
     */
    override fun setPitchTempo(pitchTempo: PitchTempo) {
        if (released.get()) return
        val stamp = barrier.get()
        val needsCommand = synchronized(pitchLock) {
            val previous = pitchSlot
            pitchSlot = PitchSlot(pitchTempo.clamped(), stamp)
            previous == null || previous.barrier != stamp
        }
        if (!needsCommand) return
        enqueue {
            val latest = synchronized(pitchLock) {
                val slot = pitchSlot
                if (slot != null && slot.barrier == stamp) slot.also { pitchSlot = null } else null
            }?.value ?: return@enqueue
            controller.setTempo(latest.tempo)
            controller.setPitchSemitones(latest.semitones)
            updateLive { it.copy(pitchTempo = latest) }
            publishMix()
        }
    }

    private fun applyPitchTempoNow(pitchTempo: PitchTempo) {
        controller.setTempo(pitchTempo.tempo)
        controller.setPitchSemitones(pitchTempo.semitones)
        updateLive { it.copy(pitchTempo = pitchTempo) }
    }

    override fun updateTrack(id: TrackId, transform: (TrackSettings) -> TrackSettings) = enqueue {
        val current = tracks[id] ?: return@enqueue
        val next = transform(current.settings).clamped()
        if (next == current.settings) return@enqueue
        pushSettings(id, current.settings, next)
        tracks[id] = current.copy(settings = next)
        syncMutes()
        publishTracks()
        publishMix()
    }

    override fun setSolo(id: TrackId, solo: Boolean) = enqueue {
        val staged = pendingMix[id]
        when {
            id in tracks -> {
                if (solo == (id in soloed)) return@enqueue
                if (solo) soloed += id else soloed -= id
                syncMutes()
            }
            staged != null -> pendingMix[id] = staged.copy(solo = solo)
            else -> return@enqueue
        }
        publishMix()
    }

    override suspend fun applyMix(mix: PlayerMix): AudioResult<MixApplyResult> {
        barrier.incrementAndGet()
        return call { AudioResult.Ok(applyMixNow(mix)) }
    }

    /** Sends the changed non-mute settings to the controller; mute goes through [syncMutes]. */
    private fun pushSettings(id: TrackId, from: TrackSettings, to: TrackSettings) {
        if (to.volume != from.volume) controller.setVolume(id, to.volume)
        if (to.balance != from.balance) controller.setChannelGain(id, LegacyMapping.toInternal(to.balance))
        if (to.offsetMs != from.offsetMs) controller.setTrackOffset(id, to.offsetMs)
        if (to.boostDb != from.boostDb) controller.setSourceBoostDb(id, to.boostDb)
    }

    private fun effectiveMuted(id: TrackId, userMuted: Boolean, solos: Set<TrackId> = soloed): Boolean =
        userMuted || (solos.isNotEmpty() && id !in solos)

    /** Pushes the effective mute (user mute, or silenced by another track's solo) of every track that changed. */
    private fun syncMutes() {
        for ((id, track) in tracks) {
            val effective = effectiveMuted(id, track.settings.muted)
            if (engineMuted[id] != effective) {
                controller.setMuted(id, effective)
                engineMuted[id] = effective
            }
        }
    }

    private fun applyMixNow(mix: PlayerMix): MixApplyResult {
        val wanted = mix.tracks.mapValuesTo(linkedMapOf()) { (_, m) ->
            TrackMix.from(m.toTrackSettings().clamped(), m.solo)
        }
        val pitchTempo = mix.pitchTempo.clamped()
        if (!controller.isPrepared()) {
            // Nothing loaded: stage everything for the next load.
            stagedGlobals = PlayerMix(pitchTempo = pitchTempo, range = mix.range, loop = mix.loop)
            pendingMix.clear()
            pendingMix.putAll(wanted)
            publishMix()
            return MixApplyResult(applied = emptySet(), pending = wanted.keys.toSet())
        }
        val applied = linkedSetOf<TrackId>()
        val waiting = linkedSetOf<TrackId>()
        for ((id, m) in wanted) {
            val current = tracks[id]
            if (current == null) {
                pendingMix[id] = m
                waiting += id
                continue
            }
            val next = m.toTrackSettings()
            pushSettings(id, current.settings, next)
            tracks[id] = current.copy(settings = next)
            if (m.solo) soloed += id else soloed -= id
            applied += id
        }
        applyPitchTempoNow(pitchTempo)
        applyRangeNow(mix.range, mix.loop)
        syncMutes()
        publishTracks()
        publishMix()
        return MixApplyResult(applied = applied, pending = waiting)
    }

    private fun publishMix() {
        val state = _state.value
        val live = state.status !in INACTIVE
        val staged = stagedGlobals
        _mix.value = PlayerMix(
            tracks = tracks.mapValuesTo(linkedMapOf()) { (id, track) -> TrackMix.from(track.settings, id in soloed) },
            pitchTempo = if (live) state.pitchTempo else staged?.pitchTempo ?: PitchTempo(),
            range = if (live) state.range else staged?.range,
            loop = if (live) state.loop else staged?.loop ?: false,
        )
    }

    override fun diagnostics(reset: Boolean): CallbackStats? {
        if (released.get()) return null
        val raw = controller.callbackStats(reset) ?: return null
        if (raw.size < 6) return null
        return CallbackStats(
            callbackMaxNs = raw[0],
            callbackP99Ns = raw[1],
            bufferPeriodNs = raw[2],
            renderLoadP99 = raw[3] / 1000f,
            underruns = raw[4],
            callbacks = raw[5],
        )
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
        runCatching { videoPort.release() }
        playbackClock.close()
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
        // A new source starts with no solo and no pending mix, except what applyMix staged before this load.
        val staged = stagedGlobals
        val carried = if (staged != null) pendingMix.toMap() else emptyMap()
        stagedGlobals = null
        pendingMix.clear()
        soloed.clear()
        engineMuted.clear()
        val requested = source.tracks.map {
            val fromMix = carried[it.id]
            it.copy(settings = (fromMix?.toTrackSettings() ?: it.settings).clamped())
        }
        carried.forEach { (id, m) ->
            if (requested.any { it.id == id }) {
                if (m.solo) soloed += id
            } else {
                pendingMix[id] = m
            }
        }
        val pitchTempo = (staged?.pitchTempo ?: source.pitchTempo).clamped()
        val loadRange = if (staged != null) staged.range else source.range
        val loadLoop = if (staged != null) staged.loop else source.loop
        playbackClock.onLoadStarted()
        tracks.clear()
        requested.forEach { tracks[it.id] = it }
        videoUri = source.videoUri
        _state.update {
            if (it.status == PlayerStatus.RELEASED) it else PlayerState(
                status = PlayerStatus.LOADING,
                positionMs = source.startAtMs,
                tracks = trackStates(),
                pitchTempo = pitchTempo,
                range = loadRange,
                loop = loadLoop,
            )
        }
        publishMix()
        controller.setPlaybackRange(LegacyMapping.toInternal(loadRange))
        val result = try {
            controller.preparePlayers(
                tracks = requested.associate { it.id to it.uri },
                startPositionMs = source.startAtMs,
                looping = loadLoop,
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
                publishMix()
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
                publishMix()
                playbackClock.onLoaded(controller.currentPosition.value, controller.isPlaying.value, pitchTempo.tempo)
                videoPort.setSource(source.videoUri)
                if (source.autoPlay) playNow()
                AudioResult.Done
            }
            is MixerPreparationResult.Failure -> {
                videoPort.setSource(null)
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
        val staged = pendingMix[track.id]
        val clamped = track.copy(settings = (staged?.toTrackSettings() ?: track.settings).clamped())
        val solosAfter = if (staged?.solo == true) soloed + clamped.id else soloed
        val result = controller.appendTrack(
            type = clamped.id,
            uri = clamped.uri,
            initialVolume = clamped.settings.volume,
            muted = effectiveMuted(clamped.id, clamped.settings.muted, solosAfter),
            initialChannelGain = LegacyMapping.toInternal(clamped.settings.balance),
            initialOffsetMs = clamped.settings.offsetMs,
        )
        return when (result) {
            MixerAppendResult.Success -> {
                pendingMix.remove(clamped.id)
                if (staged != null) {
                    if (staged.solo) soloed += clamped.id else soloed -= clamped.id
                }
                tracks[clamped.id] = clamped
                applyPostJoinSettings(clamped)
                syncMutes()
                updateLive { it.copy(tracks = trackStates(), durationMs = controller.totalDuration.value) }
                publishMix()
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
        val muted = effectiveMuted(track.id, track.settings.muted)
        controller.setMuted(track.id, muted)
        engineMuted[track.id] = muted
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

    /**
     * Seeks to [positionMs] clamped like the controller does. [PlayerState.positionMs] shows the
     * target at once and the clock reports `seeking` until the controller finishes syncing.
     */
    private fun seekNow(positionMs: Long): Boolean {
        val requested = positionMs.coerceAtLeast(0L)
        val duration = controller.totalDuration.value
        if (duration <= 0L) return controller.seekToMs(requested)
        val target = PlaybackRangePolicy.seekTarget(requested, controller.currentPlaybackRange(), duration)
        markSeek(target)
        val accepted = controller.seekToMs(target)
        if (!accepted || !controller.isSyncing.value) playbackClock.settleSeekNow()
        return accepted
    }

    /** Shows [target] immediately and starts the clock's seek; the caller settles it if the controller is idle. */
    private fun markSeek(target: Long) {
        playbackClock.beginSeek(target)
        updateLive { it.copy(positionMs = target) }
    }

    // ---------------------------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------------------------

    private fun observeController() {
        scope.launch {
            controller.currentPosition.collect { position ->
                playbackClock.onPosition(position)
                updateLive {
                    // Mid-seek the controller may still report the pre-seek position.
                    val target = playbackClock.seekTargetMs
                    if (target != null && position != target) it else it.copy(positionMs = position)
                }
            }
        }
        scope.launch {
            controller.isPlaying.collect { playing -> playbackClock.onPlaying(playing) }
        }
        scope.launch {
            _state.map { it.pitchTempo.tempo }.distinctUntilChanged().collect { playbackClock.onTempo(it) }
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
            controller.focusChanges.collect { _events.tryEmit(PlayerEvent.FocusChanged(it)) }
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
