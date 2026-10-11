package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
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
}
