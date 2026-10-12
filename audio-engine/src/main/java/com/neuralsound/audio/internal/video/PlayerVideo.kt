package com.neuralsound.audio.internal.video

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.neuralsound.audio.player.PlayerState
import com.neuralsound.audio.player.VideoController
import com.neuralsound.audio.player.VideoState
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Player-side view of the optional video presentation. */
internal interface VideoPort : VideoController {
    /** Replaces the video source (null = audio only). Presentation is attached automatically. */
    suspend fun setSource(uri: Uri?)

    suspend fun release()
}

internal object NoVideoPort : VideoPort {
    override suspend fun setSource(uri: Uri?) = Unit
    override suspend fun release() = Unit
    override fun attachPresentation() = Unit
    override fun suspendPresentation() = Unit
    override fun detachPresentation() = Unit
    override fun notifySurfaceRecreated() = Unit
}

/**
 * Muted Media3 video slaved to the native audio clock (FEAT-0001 FR-26).
 *
 * The video follows play/pause and tempo from [audio]; drift is corrected by [VideoSyncPolicy]
 * (75 ms dead-band, hard seek above 450 ms, released below 250 ms). Suspending stops decoding
 * while audio continues; re-attaching seeks to the audio position before frames are shown.
 * All Media3 calls run on [mainDispatcher].
 */
internal class PlayerVideo(
    context: Context,
    private val audio: StateFlow<PlayerState>,
    private val publish: ((VideoState) -> VideoState) -> Unit,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    syncIntervalMs: Long = SYNC_INTERVAL_MS,
) : VideoPort {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val policy = VideoSyncPolicy()
    private val sync = VideoSyncController(
        scope = scope,
        sampleIntervalMs = syncIntervalMs,
        ineligibility = { ineligibility() },
        synchronize = { synchronizeOnce() },
    )

    private var uri: Uri? = null
    private var player: Player? = null
    private var listener: Player.Listener? = null
    private var followJob: Job? = null
    private var suspended = false
    private var hardCorrections = 0L
    private var lastAudioPositionMs = 0L
    private var generation = 0L

    override suspend fun setSource(uri: Uri?) = withContext(mainDispatcher) {
        releasePlayer()
        this@PlayerVideo.uri = uri
        suspended = false
        publish { VideoState(hasVideo = uri != null, revision = it.revision + 1) }
        if (uri != null) attachNow()
    }

    override fun attachPresentation() {
        scope.launch { attachNow() }
    }

    override fun suspendPresentation() {
        scope.launch {
            val current = player ?: return@launch
            suspended = true
            stopFollowing()
            current.playWhenReady = false
        }
    }

    override fun detachPresentation() {
        scope.launch { releasePlayer() }
    }

    override fun notifySurfaceRecreated() {
        publish { it.copy(revision = it.revision + 1) }
    }

    override suspend fun release() {
        withContext(mainDispatcher) { releasePlayer() }
        scope.cancel()
    }

    private fun attachNow() {
        val source = uri ?: return
        val current = player ?: createPlayer(source)
        suspended = false
        // Show the frame at the authoritative audio position before resuming.
        current.seekTo(audio.value.positionMs)
        policy.reset(clock())
        startFollowing(current)
    }

    private fun createPlayer(source: Uri): Player {
        val created = ExoPlayerFactories.create(applicationContext)
        val newListener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                publish { it.copy(firstFrameReady = true) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                publish {
                    it.copy(aspectRatio = videoAspectRatio(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio))
                }
            }
        }
        created.addListener(newListener)
        created.setMediaItem(MediaItem.fromUri(source))
        created.volume = 0f
        created.prepare()
        player = created
        listener = newListener
        generation += 1
        publish { it.copy(player = created, hasVideo = true, firstFrameReady = false, revision = it.revision + 1) }
        return created
    }

    private fun startFollowing(target: Player) {
        stopFollowing()
        lastAudioPositionMs = audio.value.positionMs
        followJob = scope.launch {
            audio.collect { state -> follow(target, state) }
        }
        sync.start(generation)
    }

    private fun stopFollowing() {
        followJob?.cancel()
        followJob = null
        sync.stop()
    }

    private fun follow(target: Player, state: PlayerState) {
        if (target.playWhenReady != state.isPlaying) target.playWhenReady = state.isPlaying
        val tempo = state.pitchTempo.tempo
        if (target.playbackParameters.speed != tempo) {
            target.playbackParameters = PlaybackParameters(tempo, 1f)
            policy.reset(clock(), tempoGraceMs = TEMPO_GRACE_MS)
        }
        val jump = abs(state.positionMs - lastAudioPositionMs) > DISCONTINUITY_MS
        lastAudioPositionMs = state.positionMs
        // Seeks and scrubbing while paused move the video immediately; steady drift is the policy's job.
        if (jump || (!state.isPlaying && abs(state.positionMs - target.currentPosition) > POLICY_DEADBAND_MS)) {
            target.seekTo(state.positionMs)
            policy.reset(clock())
        }
    }

    private fun ineligibility(): VideoSyncSkipReason? {
        val current = player ?: return VideoSyncSkipReason.NO_PLAYER
        return when {
            suspended -> VideoSyncSkipReason.PAUSED
            !audio.value.isPlaying -> VideoSyncSkipReason.NOT_PLAYING
            current.playbackState != Player.STATE_READY -> VideoSyncSkipReason.NOT_READY
            else -> null
        }
    }

    private fun synchronizeOnce() {
        val current = player ?: return
        val state = audio.value
        val decision = policy.decide(
            VideoSyncSample(
                audioPositionMs = state.positionMs,
                videoPositionMs = current.currentPosition,
                durationMs = state.durationMs,
                elapsedRealtimeMs = clock(),
            ),
        )
        val action = decision.action
        if (action is VideoSyncAction.HardSeek) {
            current.seekTo(action.targetPositionMs)
            hardCorrections += 1
        }
        publish { it.copy(sync = it.sync.copy(lastDriftMs = decision.driftMs, hardCorrections = hardCorrections)) }
    }

    private fun releasePlayer() {
        stopFollowing()
        val current = player ?: return
        listener?.let(current::removeListener)
        current.release()
        player = null
        listener = null
        publish { it.copy(player = null, firstFrameReady = false, revision = it.revision + 1) }
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 100L
        const val TEMPO_GRACE_MS = 500L
        const val DISCONTINUITY_MS = 1_000L
        const val POLICY_DEADBAND_MS = 75L
    }
}
