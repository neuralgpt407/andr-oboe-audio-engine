package com.neuralsound.audio.internal

internal object OutputRoutePolicy {
    fun availableDeviceId(
        nativeDeviceId: Int,
        availableDeviceIds: Set<Int>,
    ): Int? {
        return nativeDeviceId.takeIf { it > 0 && it in availableDeviceIds }
    }
}
