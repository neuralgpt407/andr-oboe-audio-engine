package com.neuralsound.audio.media3

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.MixerRequest
import com.neuralsound.audio.MixerSession
import com.neuralsound.audio.MixerState
import com.neuralsound.audio.MixerTrack
import com.neuralsound.audio.PlaybackEffects
import com.neuralsound.audio.PlaybackRange
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.TrackMix
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FakeMixerSession(
    private val onPrepare: suspend (Int) -> AudioResult = { AudioResult.Success },
) : MixerSession {
    private val mutableState = MutableStateFlow(MixerState())
    override val state: StateFlow<MixerState> = mutableState
    var prepareCalls = 0
    var closeCalls = 0

    override suspend fun prepare(request: MixerRequest): AudioResult {
        prepareCalls += 1
        return onPrepare(prepareCalls)
    }

    fun publish(state: MixerState) {
        mutableState.value = state
    }

    override fun appendTrack(track: MixerTrack): AudioResult = AudioResult.Success
    override fun play(): AudioResult = AudioResult.Success
    override fun pause(): AudioResult = AudioResult.Success
    override fun seekTo(positionMs: Long): AudioResult = AudioResult.Success
    override fun setPlaybackRange(range: PlaybackRange?): AudioResult = AudioResult.Success
    override fun setLooping(looping: Boolean): AudioResult = AudioResult.Success
    override fun updateTrackMix(trackId: TrackId, mix: TrackMix): AudioResult = AudioResult.Success
    override fun setTrackOffset(trackId: TrackId, offsetMs: Long): AudioResult = AudioResult.Success
    override fun setEffects(effects: PlaybackEffects): AudioResult = AudioResult.Success

    override fun close() {
        closeCalls += 1
    }
}

internal class FakePlayer : InvocationHandler {
    val player: Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
        this,
    ) as Player
    var mediaItem: MediaItem? = null
    var volume = 1f
    var prepareCalls = 0
    var playWhenReady = false
    var playbackParameters = PlaybackParameters.DEFAULT
    var playbackState = Player.STATE_IDLE
    var currentPosition = 0L
    val seekPositions = mutableListOf<Long>()
    val operationOrder = mutableListOf<String>()
    var releaseCalls = 0
    var removeListenerCalls = 0
    var releaseLooper: Looper? = null
    private var listener: Player.Listener? = null

    override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?): Any? {
        val args = arguments.orEmpty()
        return when (method.name) {
            "setMediaItem" -> Unit.also { mediaItem = args[0] as MediaItem }
            "setVolume" -> Unit.also { volume = args[0] as Float }
            "getVolume" -> volume
            "prepare" -> Unit.also {
                prepareCalls += 1
                operationOrder += "prepare"
            }
            "addListener" -> Unit.also {
                listener = args[0] as Player.Listener
                operationOrder += "addListener"
            }
            "removeListener" -> Unit.also {
                if (listener === args[0]) listener = null
                removeListenerCalls += 1
            }
            "setPlayWhenReady" -> Unit.also { playWhenReady = args[0] as Boolean }
            "getPlayWhenReady" -> playWhenReady
            "setPlaybackParameters" -> Unit.also {
                playbackParameters = args[0] as PlaybackParameters
            }
            "getPlaybackParameters" -> playbackParameters
            "getPlaybackState" -> playbackState
            "getCurrentPosition" -> currentPosition
            "seekTo" -> Unit.also { seekPositions += args.last() as Long }
            "release" -> Unit.also {
                releaseCalls += 1
                releaseLooper = Looper.myLooper()
            }
            "getApplicationLooper" -> Looper.getMainLooper()
            "equals" -> proxy === args[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "FakePlayer"
            else -> defaultValue(method.returnType)
        }
    }

    fun renderFirstFrame() {
        checkNotNull(listener).onRenderedFirstFrame()
    }

    fun changeVideoSize(videoSize: VideoSize) {
        checkNotNull(listener).onVideoSizeChanged(videoSize)
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
}
