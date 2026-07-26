package com.neuralsound.audio.media3

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.MixerSession
import com.neuralsound.audio.MixerState
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
import kotlinx.coroutines.withContext

class Media3MixerSession internal constructor(
    context: Context,
    val mixer: MixerSession,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _videoState = MutableStateFlow(Media3VideoState())
    val videoState: StateFlow<Media3VideoState> = _videoState.asStateFlow()
    val mixerState: StateFlow<MixerState> = mixer.state

    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null

    init {
        scope.launch {
            mixer.state.collect { audioState ->
                withContext(Dispatchers.Main.immediate) {
                    synchronizePlayer(audioState)
                }
            }
        }
    }

    suspend fun prepare(request: Media3MixerRequest): AudioResult {
        check(!closed.get()) { "Media3MixerSession is closed" }
        prepareVideo(request.videoUri)
        val result = mixer.prepare(request.audio)
        if (result is AudioResult.Failure) {
            releaseVideo()
        }
        return result
    }

    fun notifyVideoSurfaceRecreated() {
        _videoState.update { it.copy(revision = it.revision + 1L) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        mixer.close()
        val resources = detachVideo()
        mainHandler.post {
            resources.release()
        }
    }

    private suspend fun prepareVideo(uri: android.net.Uri?) {
        withContext(Dispatchers.Main.immediate) {
            releaseVideoOnMain()
            if (uri == null) {
                _videoState.update { it.copy(firstFrameReady = true) }
                return@withContext
            }
            val newPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(uri))
                volume = 0f
                prepare()
            }
            val newListener = createListener()
            newPlayer.addListener(newListener)
            player = newPlayer
            listener = newListener
            _videoState.value = Media3VideoState(
                player = newPlayer,
                hasVideo = true,
                revision = _videoState.value.revision + 1L,
            )
        }
    }

    private fun synchronizePlayer(audioState: MixerState) {
        val currentPlayer = player ?: return
        currentPlayer.playWhenReady = audioState.isPlaying
        if (currentPlayer.playbackParameters.speed != audioState.effects.tempo) {
            currentPlayer.playbackParameters = PlaybackParameters(audioState.effects.tempo, 1f)
        }
        if (
            Media3SyncPolicy.shouldSeek(
                audioPositionMs = audioState.positionMs,
                videoPositionMs = currentPlayer.currentPosition,
                videoReady = currentPlayer.playbackState == Player.STATE_READY,
            )
        ) {
            currentPlayer.seekTo(audioState.positionMs)
        }
    }

    private suspend fun releaseVideo() {
        withContext(Dispatchers.Main.immediate) {
            releaseVideoOnMain()
        }
    }

    private fun releaseVideoOnMain() {
        detachVideo().release()
    }

    private fun detachVideo(): VideoResources {
        val resources = VideoResources(player, listener)
        player = null
        listener = null
        _videoState.value = Media3VideoState(revision = _videoState.value.revision + 1L)
        return resources
    }

    private fun createListener(): Player.Listener {
        return object : Player.Listener {
            override fun onRenderedFirstFrame() {
                _videoState.update { it.copy(firstFrameReady = true) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _videoState.update {
                    it.copy(
                        aspectRatio = Media3SyncPolicy.aspectRatio(
                            width = videoSize.width,
                            height = videoSize.height,
                            pixelWidthHeightRatio = videoSize.pixelWidthHeightRatio,
                        )
                    )
                }
            }
        }
    }

    private data class VideoResources(
        val player: ExoPlayer?,
        val listener: Player.Listener?,
    ) {
        fun release() {
            listener?.let { player?.removeListener(it) }
            player?.release()
        }
    }
}
