package com.neuralsound.audio.internal

import android.media.AudioDeviceInfo
import com.neuralsound.audio.player.RouteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OutputRoutePolicyTest {
    @Test
    fun `removed native route is not published as active`() {
        assertNull(
            OutputRoutePolicy.availableDeviceId(
                nativeDeviceId = 12,
                availableDeviceIds = setOf(4, 8),
            )
        )
    }

    @Test
    fun `available native route is preserved`() {
        assertEquals(
            12,
            OutputRoutePolicy.availableDeviceId(
                nativeDeviceId = 12,
                availableDeviceIds = setOf(4, 12),
            )
        )
    }

    @Test
    fun `device types map to route kinds used for recording and monitoring decisions`() {
        val expected = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to RouteKind.SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to RouteKind.SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to RouteKind.WIRED,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES to RouteKind.WIRED,
            AudioDeviceInfo.TYPE_USB_HEADSET to RouteKind.USB,
            AudioDeviceInfo.TYPE_USB_DEVICE to RouteKind.USB,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to RouteKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to RouteKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_BLE_HEADSET to RouteKind.BLUETOOTH,
            AudioDeviceInfo.TYPE_HDMI to RouteKind.OTHER,
        )
        expected.forEach { (type, kind) -> assertEquals("type $type", kind, OutputRoutePolicy.routeKind(type)) }
        assertEquals(RouteKind.UNKNOWN, OutputRoutePolicy.routeKind(null))
    }

    @Test
    fun `only private low-latency outputs allow monitoring`() {
        assertTrue(RouteKind.WIRED.allowsMonitoring)
        assertTrue(RouteKind.USB.allowsMonitoring)
        listOf(RouteKind.SPEAKER, RouteKind.BLUETOOTH, RouteKind.OTHER, RouteKind.UNKNOWN).forEach {
            assertFalse(it.name, it.allowsMonitoring)
        }
    }
}
