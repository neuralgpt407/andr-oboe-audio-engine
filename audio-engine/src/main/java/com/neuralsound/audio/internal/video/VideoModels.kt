package com.neuralsound.audio.internal.video

import android.net.Uri
import androidx.media3.common.Player
import com.neuralsound.audio.MixerRequest

internal data class VideoPresenterState(
    val player: Player? = null,
    val hasVideo: Boolean = false,
    val firstFrameReady: Boolean = false,
    val aspectRatio: Float? = null,
    val revision: Long = 0L,
)

internal data class VideoPresenterRequest(
    val audio: MixerRequest,
    val videoUri: Uri? = null,
)
