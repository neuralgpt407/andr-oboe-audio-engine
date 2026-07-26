package com.neuralsound.audio.internal

import android.net.Uri
import com.neuralsound.audio.ChannelGain
import com.neuralsound.audio.TrackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class MixerTrackState(
    val type: TrackId,
    val uri: Uri,
    private val initialVolume: Float = 1f,
    private val initialMuted: Boolean = false,
    private val initialChannelGain: ChannelGain = ChannelGain.Center,
    initialOffsetMs: Long = 0L,
) {
    private val _volume = MutableStateFlow(initialVolume.coerceIn(0f, 1f))
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _isMuted = MutableStateFlow(initialMuted)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _channelGain = MutableStateFlow(initialChannelGain)
    val channelGain: StateFlow<ChannelGain> = _channelGain.asStateFlow()

    private val _offsetMs = MutableStateFlow(initialOffsetMs)
    val offsetMs: StateFlow<Long> = _offsetMs.asStateFlow()

    val effectiveVolume: Float
        get() = if (_isMuted.value) 0f else _volume.value

    fun setVolume(value: Float) {
        val safeVolume = value.coerceIn(0f, 1f)
        _volume.value = safeVolume
        if (safeVolume > 0f && _isMuted.value) {
            _isMuted.value = false
        }
        if (safeVolume == 0f) {
            _isMuted.value = true
        }
    }

    fun toggleMute() {
        _isMuted.value = !_isMuted.value
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setChannelGain(gain: ChannelGain) {
        _channelGain.value = gain
    }

    fun setOffset(offsetMs: Long) {
        _offsetMs.value = offsetMs
    }
}
