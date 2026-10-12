package com.neuralsound.audio.internal.video

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

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
