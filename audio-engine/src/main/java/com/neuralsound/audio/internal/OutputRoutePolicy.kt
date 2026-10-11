package com.neuralsound.audio.internal

import android.media.AudioDeviceInfo
import com.neuralsound.audio.player.RouteKind

internal object OutputRoutePolicy {
    fun availableDeviceId(
        nativeDeviceId: Int,
        availableDeviceIds: Set<Int>,
    ): Int? {
        return nativeDeviceId.takeIf { it > 0 && it in availableDeviceIds }
    }

    fun routeKind(deviceType: Int?): RouteKind = when (deviceType) {
        null -> RouteKind.UNKNOWN
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> RouteKind.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG -> RouteKind.WIRED
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> RouteKind.BLUETOOTH
        else -> RouteKind.OTHER
    }
}
