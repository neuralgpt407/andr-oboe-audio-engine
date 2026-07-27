package com.neuralsound.audio.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.neuralsound.audio.AudioFailure
import com.neuralsound.audio.ChannelGain
import com.neuralsound.audio.MixerTrackState as PublicMixerTrackState
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackMix
import com.neuralsound.audio.TrackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private class MixerPreparationException(
    val failure: AudioFailure,
    message: String,
) : IllegalStateException(message)

internal class NativeMixerController(
    context: Context
) {
    private val context = context.applicationContext
    private val tag = "OboeNativeMixerController"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Audio focus state
    @Volatile private var pausedByFocus = false
    @Volatile private var isDucked = false
    private val focusRequest: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributesCompat.Builder()
                    .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                    .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
            .setWillPauseWhenDucked(false)
            .build()
    }

    // Headphone unplug receiver
    @Volatile private var noisyReceiverRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
            }
        }
    }

    // Output device changes (BT/wired connect & disconnect). AAudio only
    // delivers ErrorDisconnected to a started stream, so a paused stream must
    // be told explicitly that its device went away or it silently fails on
    // the next play.
    @Volatile private var deviceCallbackRegistered = false
    private val outputTopologyRevision = AtomicLong(0L)
    private val _routeRevision = MutableStateFlow(0L)
    val routeRevision: StateFlow<Long> = _routeRevision.asStateFlow()
    private var knownOutputDeviceIds: Set<Int>? = null
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onOutputDevicesChanged(addedDevices)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onOutputDevicesChanged(removedDevices)
        }
    }

    @Volatile
    private var lifecycleJob: Job? = null

    private val trackStates = mutableMapOf<TrackId, MixerTrackState>()
    private val trackTypeOrder = mutableListOf<TrackId>()
    private val isSeeking = AtomicBoolean(false)
    private val isInitialized = AtomicBoolean(false)
    @Volatile private var isLooping = false
    @Volatile private var activePlaybackRange: PlaybackRange? = null
    @Volatile private var queuedSeekRequest: PendingSeekRequest? = null
    private var positionUpdateJob: Job? = null
    private var tempoApplyJob: Job? = null
    private var routeMonitorJob: Job? = null
    private val engine = EngineHandle()
    private var durationMs: Long = 0L
    @Volatile
    private var mixerSampleRate: Int = 0
    private var lastNativeTempoSpeed: Float = PlaybackEffects.DEFAULT_TEMPO

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val playbackCommands by lazy {
        MixerPlaybackCommandExecutor(
            isPlaying = { _isPlaying.value },
            isNativePlaying = { engine.use(false) { handle -> nativeIsPlaying(handle) } },
            publishPlaying = { _isPlaying.value = it },
            nativePlay = { engine.use(false) { handle -> nativePlay(handle) } },
            nativePause = { engine.use { handle -> nativePause(handle) } },
        )
    }

    private val _isSyncing = MutableStateFlow(false)

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _totalDuration = MutableStateFlow(0L)
    val totalDuration: StateFlow<Long> = _totalDuration.asStateFlow()

    private val _tempoSpeed = MutableStateFlow(PlaybackEffects.DEFAULT_TEMPO)
    val tempoSpeed: StateFlow<Float> = _tempoSpeed.asStateFlow()

    private val _pitchSemitones = MutableStateFlow(PlaybackEffects.DEFAULT_PITCH_SEMITONES)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    fun currentRoutedOutputDeviceId(): Int? {
        val nativeDeviceId = engine.use(0) { handle -> nativeGetOutputDeviceId(handle) }
        val availableDeviceIds = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .asSequence()
            .filter(AudioDeviceInfo::isSink)
            .map(AudioDeviceInfo::getId)
            .toSet()
        return OutputRoutePolicy.availableDeviceId(nativeDeviceId, availableDeviceIds)
    }

    fun currentRoutedOutputDeviceType(deviceId: Int? = currentRoutedOutputDeviceId()): Int? {
        deviceId ?: return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { device -> device.isSink && device.id == deviceId }
            ?.type
    }

    fun currentOutputTopologyRevision(): Long = outputTopologyRevision.get()

    fun setPlaybackRange(range: PlaybackRange?) {
        val normalizedRange = if (range != null && durationMs > 0L) {
            range.clampTo(durationMs)
        } else {
            range
        }
        activePlaybackRange = normalizedRange
        normalizedRange?.takeIf { durationMs > 0L }?.let {
            val current = _currentPosition.value
            if (current < it.startMs || current > it.endMs) {
                seekToPosition(it.startMs)
            }
        }
    }

    suspend fun preparePlayers(
        tracks: Map<TrackId, Uri>,
        startPositionMs: Long,
        looping: Boolean = false,
        effects: PlaybackEffects = PlaybackEffects(),
        trackOffsetsMs: Map<TrackId, Long> = emptyMap(),
        initialVolumes: Map<TrackId, Float> = emptyMap(),
        initialChannelGains: Map<TrackId, ChannelGain> = emptyMap(),
    ): MixerPreparationResult {
        if (tracks.isEmpty()) {
            return MixerPreparationResult.Failure("no tracks were supplied")
        }
        if (!nativeLibraryAvailable) {
            return MixerPreparationResult.Failure("native library is not available")
        }

        val previous = lifecycleJob
        val preparation = scope.async {
            runCatching { previous?.join() }
            initializePlayersInternal(
                tracks = tracks,
                initialVolumes = initialVolumes,
                initialChannelGains = initialChannelGains,
                autoPlay = false,
                startPositionMs = startPositionMs,
                looping = looping,
                effects = effects,
                trackOffsetsMs = trackOffsetsMs,
                forceSeek = true,
            )
        }
        lifecycleJob = preparation
        return preparation.await()
    }

    private suspend fun initializePlayersInternal(
        tracks: Map<TrackId, Uri>,
        initialVolumes: Map<TrackId, Float>,
        initialChannelGains: Map<TrackId, ChannelGain>,
        autoPlay: Boolean,
        startPositionMs: Long,
        looping: Boolean,
        effects: PlaybackEffects,
        trackOffsetsMs: Map<TrackId, Long>,
        forceSeek: Boolean,
    ): MixerPreparationResult {
        releaseResourcesImmediate(resetPlaybackState = startPositionMs <= 0L)
        isLooping = looping
        _tempoSpeed.value = effects.tempo
        _pitchSemitones.value = effects.pitchSemitones

        val openedFds = mutableListOf<android.os.ParcelFileDescriptor>()
        return try {
                isSeeking.set(false)
                synchronized(this@NativeMixerController) {
                    queuedSeekRequest = null
                }
                _isSyncing.value = true

                val newStates = linkedMapOf<TrackId, MixerTrackState>()
                val trackFormats = mutableListOf<Pair<TrackId, DecodedAudioFormat>>()
                val fds = IntArray(tracks.size)
                val durations = LongArray(tracks.size)
                tracks.entries.forEachIndexed { index, (type, uri) ->
                    val pfd = try {
                        context.contentResolver.openFileDescriptor(uri, "r")
                            ?: throw MixerPreparationException(
                                failure = AudioFailure.SourceUnavailable(uri.toString()),
                                message = "Unable to open $uri",
                            )
                    } catch (exception: MixerPreparationException) {
                        throw exception
                    } catch (exception: Exception) {
                        throw MixerPreparationException(
                            failure = AudioFailure.SourceUnavailable(uri.toString()),
                            message = exception.message ?: "Unable to open $uri",
                        )
                    }
                    openedFds += pfd
                    fds[index] = pfd.fd
                    durations[index] = 0L
                    val format = runCatching {
                        AudioTrackFormatInspector.inspect(pfd.fileDescriptor)
                    }.getOrNull() ?: DecodedAudioFormat(
                        sampleRate = null,
                        channelCount = null,
                    )
                    trackFormats += type to format
                    newStates[type] = MixerTrackState(
                        type = type,
                        uri = uri,
                        initialVolume = initialVolumes[type] ?: 0.8f,
                        initialChannelGain = initialChannelGains[type] ?: ChannelGain.Center,
                        initialOffsetMs = trackOffsetsMs[type] ?: 0L,
                    )
                }
                MixerTrackFormatPolicy.findProblem(trackFormats)?.let { problem ->
                    throw MixerPreparationException(
                        failure = AudioFailure.UnsupportedTrackFormat(
                            trackId = problem.trackId,
                            sampleRate = problem.format.sampleRate,
                            channelCount = problem.format.channelCount,
                            requiredSampleRate = problem.requiredSampleRate,
                        ),
                        message = "Track ${problem.trackId} must be mono/stereo and use the mixer sample rate",
                    )
                }
                mixerSampleRate = checkNotNull(trackFormats.first().second.sampleRate)

                val handle = nativeCreate()
                if (handle == 0L || !nativeInitializeTracks(handle, fds, durations, tracks.size)) {
                    if (handle != 0L) nativeRelease(handle)
                    throw IllegalStateException("Native Oboe engine initialization failed")
                }
                // Publish so release/seek paths see a live engine. The rest of
                // setup runs on the local handle: the lifecycleJob chain
                // serialises init against release, and isInitialized is still
                // false, so no other thread acts on the engine until we finish.
                engine.set(handle)
                openedFds.forEach { it.close() }
                openedFds.clear()

                synchronized(this@NativeMixerController) {
                    trackTypeOrder.clear()
                    trackTypeOrder.addAll(tracks.keys)
                    trackStates.clear()
                    trackStates.putAll(newStates)
                }

                durationMs = nativeGetDurationMs(handle)
                _totalDuration.value = durationMs
                activePlaybackRange = activePlaybackRange?.takeIf { durationMs > 0L }?.clampTo(durationMs)
                val clampedStart = PlaybackRangePolicy.seekTarget(startPositionMs, activePlaybackRange, durationMs)
                _currentPosition.value = clampedStart
                newStates.values.forEachIndexed { index, state ->
                    nativeSetVolume(handle, index, state.volume.value)
                    nativeSetMute(handle, index, state.isMuted.value)
                    nativeSetChannelGain(
                        handle,
                        index,
                        state.channelGain.value.left,
                        state.channelGain.value.right,
                    )
                    nativeSetTrackOffset(
                        handle,
                        index,
                        trackOffsetsMs[state.type] ?: 0L,
                    )
                }
                tempoApplyJob?.cancel()
                nativeSetTempo(handle, _tempoSpeed.value)
                lastNativeTempoSpeed = _tempoSpeed.value
                nativeSetPitchSemitones(handle, _pitchSemitones.value)

                if (forceSeek || clampedStart > 0L) {
                    nativeSeekTo(handle, clampedStart)
                }

                startPositionUpdateLoop()
                registerDeviceCallback()
                startRouteMonitor()
                isInitialized.set(true)
                _isSyncing.value = false

                if (autoPlay) {
                    play()
                }
                MixerPreparationPolicy.result(
                    initialized = true,
                    offsetsApplied = true,
                    seekCompleted = !forceSeek || clampedStart >= 0L,
                )
        } catch (e: Exception) {
            Log.e(tag, "Error initializing Oboe mixer", e)
            openedFds.forEach { runCatching { it.close() } }
            releaseResourcesImmediate()
            _isSyncing.value = false
            MixerPreparationResult.Failure(
                message = e.message ?: "native mixer initialization failed",
                failure = (e as? MixerPreparationException)?.failure,
            )
        }
    }

    fun appendTrack(
        type: TrackId,
        uri: Uri,
        initialVolume: Float,
        muted: Boolean,
        initialChannelGain: ChannelGain = ChannelGain.Center,
    ): MixerAppendResult {
        if (!nativeLibraryAvailable || !engine.isActive || !isInitialized.get()) {
            return MixerAppendResult.Failure(AudioFailure.EngineInactive)
        }
        when (
            synchronized(this) {
                AppendTrackPolicy.decide(
                    existingUriString = trackStates[type]?.uri?.toString(),
                    requestedUriString = uri.toString()
                )
            }
        ) {
            AppendTrackDecision.APPEND -> Unit
            AppendTrackDecision.ALREADY_APPENDED -> return MixerAppendResult.Success
            AppendTrackDecision.CONFLICTING_TRACK -> {
                Log.w(tag, "Rejecting duplicate appended track type=$type uri=$uri")
                return MixerAppendResult.Failure(
                    AudioFailure.NativeOperationFailed(
                        operation = "appendTrack",
                        detail = "Track ID $type is already bound to another URI",
                    )
                )
            }
        }
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: return MixerAppendResult.Failure(AudioFailure.SourceUnavailable(uri.toString()))
        } catch (e: Exception) {
            Log.e(tag, "Unable to open appended track uri=$uri", e)
            return MixerAppendResult.Failure(AudioFailure.SourceUnavailable(uri.toString()))
        }

        return pfd.use {
            val format = runCatching {
                AudioTrackFormatInspector.inspect(it.fileDescriptor)
            }.getOrNull() ?: DecodedAudioFormat(
                sampleRate = null,
                channelCount = null,
            )
            if (
                format.sampleRate == null ||
                format.sampleRate <= 0 ||
                format.channelCount == null ||
                format.channelCount !in 1..2 ||
                mixerSampleRate <= 0 ||
                format.sampleRate != mixerSampleRate
            ) {
                return@use MixerAppendResult.Failure(
                    AudioFailure.UnsupportedTrackFormat(
                        trackId = type,
                        sampleRate = format.sampleRate,
                        channelCount = format.channelCount,
                        requiredSampleRate = mixerSampleRate.takeIf { rate -> rate > 0 },
                    )
                )
            }
            val appended = engine.use(false) { handle ->
                nativeAppendTrack(
                    handle = handle,
                    fd = it.fd,
                    volume = initialVolume,
                    muted = muted,
                    leftGain = initialChannelGain.left,
                    rightGain = initialChannelGain.right,
                )
            }
            if (appended) {
                synchronized(this) {
                    trackTypeOrder.add(type)
                    trackStates[type] = MixerTrackState(
                        type = type,
                        uri = uri,
                        initialVolume = initialVolume,
                        initialMuted = muted,
                        initialChannelGain = initialChannelGain,
                    )
                }
                durationMs = engine.use(durationMs) { handle -> nativeGetDurationMs(handle) }
                _totalDuration.value = durationMs
            }
            if (appended) {
                MixerAppendResult.Success
            } else {
                MixerAppendResult.Failure(
                    AudioFailure.NativeOperationFailed(operation = "appendTrack")
                )
            }
        }
    }

    fun play(): MixerPlaybackResult {
        if (!isInitialized.get() || !engine.isActive) {
            return playbackCommands.play(
                isPrepared = isInitialized.get(),
                isEngineActive = engine.isActive,
            )
        }
        if (_isSyncing.value || isSeeking.get()) {
            playbackCommands.requestPlayIntent()
            return MixerPlaybackResult.Success
        }
        val playStart = PlaybackRangePolicy.playStartPosition(
            currentPositionMs = _currentPosition.value,
            activeRange = activePlaybackRange,
            durationMs = durationMs,
        )
        if (isInitialized.get() && engine.isActive && playStart != _currentPosition.value) {
            playbackCommands.requestPlayIntent()
            seekToPosition(playStart)
            return MixerPlaybackResult.Success
        }
        return startPlayback(explicitPlayIntent = true)
    }

    fun pause(): MixerPlaybackResult = pausePlayback()

    fun setVolume(type: TrackId, volume: Float) {
        val state = synchronized(this) { trackStates[type] } ?: return
        state.setVolume(volume)
        trackIndexOf(type)?.let { index ->
            engine.use { handle ->
                nativeSetVolume(handle, index, state.volume.value)
                nativeSetMute(handle, index, state.isMuted.value)
            }
        }
    }

    fun setChannelGain(type: TrackId, channelGain: ChannelGain) {
        val state = synchronized(this) { trackStates[type] } ?: return
        state.setChannelGain(channelGain)
        trackIndexOf(type)?.let { index ->
            engine.use { handle ->
                nativeSetChannelGain(
                    handle,
                    index,
                    state.channelGain.value.left,
                    state.channelGain.value.right,
                )
            }
        }
    }

    fun setMuted(type: TrackId, muted: Boolean) {
        val state = synchronized(this) { trackStates[type] } ?: return
        state.setMuted(muted)
        trackIndexOf(type)?.let { index ->
            engine.use { handle -> nativeSetMute(handle, index, muted) }
        }
    }

    fun setTrackOffset(type: TrackId, offsetMs: Long) {
        val state = synchronized(this) { trackStates[type] } ?: return
        state.setOffset(offsetMs)
        trackIndexOf(type)?.let { index ->
            engine.use { handle -> nativeSetTrackOffset(handle, index, offsetMs) }
        }
    }

    fun setTempo(speed: Float) {
        val clampedSpeed = speed.coerceIn(
            PlaybackEffects.MIN_TEMPO,
            PlaybackEffects.MAX_TEMPO
        )

        if (abs(clampedSpeed - _tempoSpeed.value) < TEMPO_NO_OP_EPSILON) {
            return
        }

        _tempoSpeed.value = clampedSpeed
        scheduleNativeTempoApply(clampedSpeed)
    }

    fun setPitchSemitones(semitones: Int) {
        val clampedSemitones = semitones.coerceIn(
            PlaybackEffects.MIN_PITCH_SEMITONES,
            PlaybackEffects.MAX_PITCH_SEMITONES
        )

        if (clampedSemitones == _pitchSemitones.value) {
            return
        }

        _pitchSemitones.value = clampedSemitones
        engine.use { handle -> nativeSetPitchSemitones(handle, clampedSemitones) }
    }

    fun setLooping(looping: Boolean) {
        isLooping = looping
    }

    fun seekToMs(positionMs: Long): Boolean {
        if (!isInitialized.get() || durationMs <= 0L) return false
        seekToPosition(
            PlaybackRangePolicy.seekTarget(positionMs, activePlaybackRange, durationMs)
        )
        return true
    }

    fun snapshotTracks(): Map<TrackId, PublicMixerTrackState> = synchronized(this) {
        trackStates.mapValues { (_, state) ->
            PublicMixerTrackState(
                mix = TrackMix(
                    volume = state.volume.value,
                    muted = state.isMuted.value,
                    channelGain = state.channelGain.value,
                ),
                offsetMs = state.offsetMs.value,
            )
        }
    }

    fun currentPlaybackRange(): PlaybackRange? = activePlaybackRange

    fun isPrepared(): Boolean = isInitialized.get() && engine.isActive

    fun closeNow() {
        lifecycleJob?.cancel()
        releaseResourcesImmediate()
        scope.cancel()
    }

    private fun startPlayback(
        explicitPlayIntent: Boolean = false,
        forceNativeStart: Boolean = false,
    ): MixerPlaybackResult {
        requestAudioFocus()
        val result = if (explicitPlayIntent) {
            playbackCommands.play(
                isPrepared = isInitialized.get(),
                isEngineActive = engine.isActive,
                forceNativeStart = forceNativeStart,
            )
        } else {
            playbackCommands.resumeDesiredPlayback(
                isPrepared = isInitialized.get(),
                isEngineActive = engine.isActive,
                forceNativeStart = forceNativeStart,
            )
        }
        if (result == null) {
            unregisterNoisyReceiver()
            abandonAudioFocus()
            return MixerPlaybackResult.Success
        }
        if (
            result is MixerPlaybackResult.Success &&
            playbackCommands.desiresPlayback() &&
            _isPlaying.value
        ) {
            registerNoisyReceiver()
            publishRouteUpdate()
        } else {
            unregisterNoisyReceiver()
            abandonAudioFocus()
        }
        return result
    }

    private fun pausePlayback(fromFocusLoss: Boolean = false): MixerPlaybackResult {
        val result = playbackCommands.pause()
        if (fromFocusLoss) {
            // Keep the focus request held — abandoning it means AUDIOFOCUS_GAIN
            // is never delivered when the call ends, so auto-resume would be
            // impossible. Keep the noisy receiver too: unplugging headphones
            // during the call must cancel auto-resume (no speaker blast).
            pausedByFocus = true
        } else {
            pausedByFocus = false
            unregisterNoisyReceiver()
            abandonAudioFocus()
        }
        return result
    }

    private fun seekToPosition(targetMs: Long, playAfterSeek: Boolean = false) {
        if (!isInitialized.get() || !engine.isActive) return
        if (_isSyncing.value && !isSeeking.get()) return
        if (playAfterSeek) playbackCommands.requestPlayIntent()
        if (!isSeeking.compareAndSet(false, true)) {
            queueSeekRequest(targetMs, playAfterSeek)
            return
        }
        _isSyncing.value = true
        scope.launch {
            try {
                // Hold the shared lock for the full native seek so the engine
                // cannot be released (deleted) mid-call. This closes the
                // use-after-free that crashed in OboeAudioEngine::seekTo.
                engine.use { handle -> nativeSeekTo(handle, targetMs) }
                _currentPosition.value = targetMs
            } catch (e: Exception) {
                Log.e(tag, "Seek error", e)
            } finally {
                finishSeek()
            }
        }
    }

    private fun queueSeekRequest(targetMs: Long, playAfterSeek: Boolean) {
        synchronized(this) {
            queuedSeekRequest = queuedSeekRequest?.replacedBy(targetMs, playAfterSeek)
                ?: PendingSeekRequest(targetMs, playAfterSeek)
        }
    }

    private fun finishSeek() {
        _isSyncing.value = false
        isSeeking.set(false)
        val queuedRequest = synchronized(this) {
            queuedSeekRequest.also { queuedSeekRequest = null }
        }
        if (queuedRequest != null) {
            seekToPosition(
                targetMs = queuedRequest.targetMs,
                playAfterSeek = false,
            )
        } else if (playbackCommands.desiresPlayback()) {
            startPlayback(forceNativeStart = true)
        }
    }

    private fun startPositionUpdateLoop() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                if (!isSeeking.get()) {
                    val pos = engine.use(-1L) { handle -> nativeGetPositionMs(handle) }
                    if (pos >= 0L) {
                        _currentPosition.value = pos
                        if (
                            _isPlaying.value &&
                            durationMs > 0L &&
                            PlaybackRangePolicy.shouldLoopAtPosition(pos, activePlaybackRange, durationMs)
                        ) {
                            handlePlaybackComplete()
                        }
                    }
                }
                delay(40)
            }
        }
    }

    private fun handlePlaybackComplete() {
        if (
            PlaybackRangePolicy.shouldLoopOnCompletion(
                activeRange = activePlaybackRange,
                explicitLooping = isLooping,
            )
        ) {
            seekToPosition(PlaybackRangePolicy.loopStart(activePlaybackRange))
        } else {
            pausePlayback()
            val endMs = activePlaybackRange?.endMs ?: durationMs
            _currentPosition.value = endMs
        }
    }

    private fun scheduleNativeTempoApply(speed: Float) {
        tempoApplyJob?.cancel()
        tempoApplyJob = scope.launch {
            delay(TEMPO_APPLY_DEBOUNCE_MS)
            applyNativeTempoIfNeeded(speed)
        }
    }

    private fun applyNativeTempoIfNeeded(speed: Float) {
        if (!engine.isActive) return

        val clampedSpeed = speed.coerceIn(
            PlaybackEffects.MIN_TEMPO,
            PlaybackEffects.MAX_TEMPO
        )
        val isDefaultReset = abs(clampedSpeed - PlaybackEffects.DEFAULT_TEMPO) < TEMPO_NO_OP_EPSILON &&
            abs(lastNativeTempoSpeed - PlaybackEffects.DEFAULT_TEMPO) >= TEMPO_NO_OP_EPSILON
        val shouldApply = isDefaultReset || abs(clampedSpeed - lastNativeTempoSpeed) >= TEMPO_NATIVE_APPLY_THRESHOLD
        if (!shouldApply) return

        val applied = engine.use(false) { handle ->
            nativeSetTempo(handle, clampedSpeed)
            true
        }
        if (applied) {
            lastNativeTempoSpeed = clampedSpeed
        }
    }

    private fun releaseResourcesImmediate(resetPlaybackState: Boolean = true) {
        isInitialized.set(false)
        isLooping = false
        synchronized(this) {
            queuedSeekRequest = null
        }
        tempoApplyJob?.cancel()
        tempoApplyJob = null
        routeMonitorJob?.cancel()
        routeMonitorJob = null
        positionUpdateJob?.cancel()
        positionUpdateJob = null
        // Exclusive lock: blocks until any in-flight native call (seek/play/
        // pause/etc.) finishes before deleting the engine, preventing the
        // use-after-free crash.
        engine.release { handle -> nativeRelease(handle) }
        mixerSampleRate = 0
        synchronized(this) {
            trackStates.clear()
            trackTypeOrder.clear()
        }
        if (resetPlaybackState) {
            durationMs = 0L
            lastNativeTempoSpeed = PlaybackEffects.DEFAULT_TEMPO
        }
        unregisterNoisyReceiver()
        unregisterDeviceCallback()
        abandonAudioFocus()
        pausedByFocus = false
        isDucked = false
        if (resetPlaybackState) {
            playbackCommands.clearPlaybackIntent()
            resetStates()
        }
    }

    private fun resetStates() {
        _isPlaying.value = false
        _isSyncing.value = false
        _currentPosition.value = 0L
        _totalDuration.value = 0L
        _tempoSpeed.value = PlaybackEffects.DEFAULT_TEMPO
        _pitchSemitones.value = PlaybackEffects.DEFAULT_PITCH_SEMITONES
    }

    private fun trackIndexOf(type: TrackId): Int? = synchronized(this) {
        trackTypeOrder.indexOf(type).takeIf { it >= 0 }
    }

    // -------------------------------------------------------------------------
    // Audio focus
    // -------------------------------------------------------------------------

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (isDucked) {
                    isDucked = false
                    restoreDuckVolume()
                }
                // Auto-resume only when the pause was caused by focus loss
                // (call/assistant). A user pause never sets pausedByFocus.
                if (pausedByFocus) {
                    pausedByFocus = false
                    if (isInitialized.get() && !_isPlaying.value) play()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Permanent loss (another music app) or transient loss (phone
                // call, Google Assistant). Un-duck first so the stored volumes
                // are correct when playback resumes. A permanent loss never
                // gets a GAIN afterwards, so auto-resume simply never fires.
                if (isDucked) {
                    isDucked = false
                    restoreDuckVolume()
                }
                if (isInitialized.get() && _isPlaying.value) {
                    pausePlayback(fromFocusLoss = true)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Notification, brief system sound — just duck volume.
                if (!isDucked) {
                    isDucked = true
                    applyDuckVolume()
                }
            }
        }
    }

    private fun requestAudioFocus() {
        pausedByFocus = false
        AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)
    }

    private fun abandonAudioFocus() {
        AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
    }

    private fun applyDuckVolume() {
        engine.use { handle ->
            synchronized(this) { trackTypeOrder.toList() }.forEachIndexed { index, type ->
                val savedVolume = synchronized(this) { trackStates[type] }?.volume?.value ?: return@forEachIndexed
                nativeSetVolume(handle, index, savedVolume * DUCK_FACTOR)
            }
        }
    }

    private fun restoreDuckVolume() {
        engine.use { handle ->
            synchronized(this) { trackTypeOrder.toList() }.forEachIndexed { index, type ->
                val state = synchronized(this) { trackStates[type] } ?: return@forEachIndexed
                nativeSetVolume(handle, index, state.volume.value)
                nativeSetMute(handle, index, state.isMuted.value)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Headphone unplug (becoming noisy)
    // -------------------------------------------------------------------------

    private fun registerNoisyReceiver() {
        if (noisyReceiverRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        ContextCompat.registerReceiver(
            context,
            noisyReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        noisyReceiverRegistered = true
    }

    private fun unregisterNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        try {
            context.unregisterReceiver(noisyReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(tag, "Noisy receiver already unregistered", e)
        }
        noisyReceiverRegistered = false
    }

    // -------------------------------------------------------------------------
    // Output device changes
    // -------------------------------------------------------------------------

    private fun onOutputDevicesChanged(devices: Array<out AudioDeviceInfo>) {
        if (devices.none { it.isSink }) return
        val topologyChanged = refreshOutputTopologyRevision()
        if (!topologyChanged || !isInitialized.get()) return
        // Off the main thread: engine.use can block on the handle lock while
        // a release is in flight.
        scope.launch {
            engine.use { handle -> nativeOnDeviceChanged(handle) }
            publishRouteUpdate()
        }
    }

    private fun startRouteMonitor() {
        routeMonitorJob?.cancel()
        routeMonitorJob = scope.launch {
            var lastDeviceId = currentRoutedOutputDeviceId()
            while (isActive) {
                delay(ROUTE_MONITOR_INTERVAL_MS)
                val currentDeviceId = currentRoutedOutputDeviceId()
                if (currentDeviceId != lastDeviceId) {
                    lastDeviceId = currentDeviceId
                    publishRouteUpdate()
                }
            }
        }
    }

    private fun publishRouteUpdate() {
        _routeRevision.update { sequence -> sequence + 1L }
    }

    private fun registerDeviceCallback() {
        if (deviceCallbackRegistered) return
        refreshOutputTopologyRevision()
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        deviceCallbackRegistered = true
    }

    private fun refreshOutputTopologyRevision(): Boolean {
        val currentIds = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .asSequence()
            .filter(AudioDeviceInfo::isSink)
            .map(AudioDeviceInfo::getId)
            .toSet()
        return synchronized(this) {
            val previousIds = knownOutputDeviceIds
            knownOutputDeviceIds = currentIds
            when {
                previousIds == null -> false
                previousIds == currentIds -> false
                else -> {
                    outputTopologyRevision.incrementAndGet()
                    true
                }
            }
        }
    }

    private fun unregisterDeviceCallback() {
        if (!deviceCallbackRegistered) return
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        deviceCallbackRegistered = false
    }

    private external fun nativeCreate(): Long
    private external fun nativeInitializeTracks(handle: Long, fds: IntArray, durations: LongArray, count: Int): Boolean
    private external fun nativeAppendTrack(
        handle: Long,
        fd: Int,
        volume: Float,
        muted: Boolean,
        leftGain: Float,
        rightGain: Float,
    ): Boolean
    private external fun nativePlay(handle: Long): Boolean
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativePause(handle: Long)
    private external fun nativeSeekTo(handle: Long, ms: Long)
    private external fun nativeOnDeviceChanged(handle: Long)
    private external fun nativeSetVolume(handle: Long, trackIndex: Int, volume: Float)
    private external fun nativeSetMute(handle: Long, trackIndex: Int, muted: Boolean)
    private external fun nativeSetChannelGain(handle: Long, trackIndex: Int, leftGain: Float, rightGain: Float)
    private external fun nativeSetTrackOffset(handle: Long, trackIndex: Int, offsetMs: Long)
    private external fun nativeSetTempo(handle: Long, speed: Float)
    private external fun nativeSetPitchSemitones(handle: Long, semitones: Int)
    private external fun nativeGetPositionMs(handle: Long): Long
    private external fun nativeGetDurationMs(handle: Long): Long
    private external fun nativeGetOutputDeviceId(handle: Long): Int
    private external fun nativeRelease(handle: Long)

    companion object {
        private const val TEMPO_APPLY_DEBOUNCE_MS = 75L
        private const val TEMPO_NO_OP_EPSILON = 0.0001f
        private const val TEMPO_NATIVE_APPLY_THRESHOLD = 0.01f
        private const val ROUTE_MONITOR_INTERVAL_MS = 250L
        /** Volume multiplier applied during AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK (notifications). */
        private const val DUCK_FACTOR = 0.2f

        internal val nativeLibraryAvailable: Boolean

        init {
            nativeLibraryAvailable = try {
                System.loadLibrary("neuralsound_audio_engine")
                true
            } catch (e: UnsatisfiedLinkError) {
                Log.e("NativeMixerController", "Failed to load libneuralsound_audio_engine.so", e)
                false
            }
        }
    }
}
