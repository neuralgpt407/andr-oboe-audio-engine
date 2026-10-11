package com.neuralsound.audio.internal.video

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal fun interface ExoPlayerFactory {
    fun create(context: Context): Player
}

internal object ExoPlayerFactories {
    private val productionFactory = ExoPlayerFactory { context ->
        ExoPlayer.Builder(context).build()
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
