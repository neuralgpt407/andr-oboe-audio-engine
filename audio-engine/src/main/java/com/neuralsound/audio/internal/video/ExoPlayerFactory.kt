package com.neuralsound.audio.internal.video

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal fun interface ExoPlayerFactory {
    fun create(context: Context): Player
}

internal object ExoPlayerFactories {
    /**
     * Video-only player: the audio track is not selected, so Media3 runs on its standalone
     * clock. With a (muted) audio renderer its clock waits for an AudioTrack to be rebuilt
     * after every seek, which left the video hundreds of milliseconds behind each correction.
     */
    private val productionFactory = ExoPlayerFactory { context ->
        ExoPlayer.Builder(context).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
    }

    @Volatile
    private var installedFactory = productionFactory

    fun create(context: Context): Player = installedFactory.create(context)

    fun installForTesting(factory: ExoPlayerFactory) {
        check(installedFactory === productionFactory) {
            "A Media3 test player factory is already installed"
        }
        installedFactory = factory
    }

    fun resetForTesting() {
        installedFactory = productionFactory
    }
}
