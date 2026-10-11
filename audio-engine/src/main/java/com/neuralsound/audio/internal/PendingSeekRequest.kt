package com.neuralsound.audio.internal

internal data class PendingSeekRequest(
    val targetMs: Long,
    val playAfterSeek: Boolean,
) {
    fun replacedBy(targetMs: Long, playAfterSeek: Boolean): PendingSeekRequest {
        return PendingSeekRequest(
            targetMs = targetMs,
            playAfterSeek = this.playAfterSeek || playAfterSeek,
        )
    }
}
