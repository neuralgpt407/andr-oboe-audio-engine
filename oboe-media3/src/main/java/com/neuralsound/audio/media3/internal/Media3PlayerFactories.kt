package com.neuralsound.audio.media3.internal

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

internal fun interface Media3PlayerFactory {
    fun create(context: Context): Player
}

internal object Media3PlayerFactories {
    private val productionFactory = Media3PlayerFactory { context ->
        ExoPlayer.Builder(context).build()
    }

    @Volatile
    private var installedFactory = productionFactory

    fun create(context: Context): Player = installedFactory.create(context)

    fun installForTesting(factory: Media3PlayerFactory) {
        check(installedFactory === productionFactory) {
            "A Media3 test player factory is already installed"
        }
        installedFactory = factory
    }

    fun resetForTesting() {
        installedFactory = productionFactory
    }
}
