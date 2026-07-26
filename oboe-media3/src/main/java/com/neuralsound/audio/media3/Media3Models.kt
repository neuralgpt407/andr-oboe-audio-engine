package com.neuralsound.audio.media3

import androidx.media3.common.Player

data class Media3VideoState(
    val player: Player? = null,
    val hasVideo: Boolean = false,
    val firstFrameReady: Boolean = false,
    val aspectRatio: Float? = null,
    val revision: Long = 0L,
)
