package com.neuralsound.audio.internal.recorder

import android.app.Application
import android.app.Notification
import org.robolectric.RuntimeEnvironment
import com.neuralsound.audio.recorder.MicForeground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** FR-19 / S5-AS6 / S5-AS7: the opt-in microphone foreground service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MicForegroundControllerTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    private fun foreground(id: Int = 42, build: () -> Notification = ::notification) =
        MicForeground(id) { build() }

    private fun notification(): Notification =
        Notification.Builder(app, "test").setSmallIcon(android.R.drawable.ic_btn_speak_now).build()

    /** Plays the system: delivers the start command once the controller queued a service start. */
    private fun deliverStart(): MicrophoneForegroundService {
        val intent = shadowOf(app).nextStartedService
        assertNotNull("service was not started", intent)
        return Robolectric.buildService(MicrophoneForegroundService::class.java, intent).create().startCommand(0, 1).get()
    }

    private suspend fun awaitPending() = withTimeout(2_000) {
        while (shadowOf(app).peekNextStartedService() == null) delay(5)
    }

    @Test
    fun promotionStartsTheServiceInForegroundWithTheAppNotificationId() = runBlocking {
        val controller = MicForegroundController(app, timeoutMs = 2_000)
        val result = async(Dispatchers.Default) { controller.promote(foreground(id = 42)) }
        awaitPending()

        val service = deliverStart()

        assertTrue(result.await())
        assertEquals(42, shadowOf(service).lastForegroundNotificationId)
        assertNotNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun aNotificationBuilderThatThrowsMeansNoPromotion() = runBlocking {
        val controller = MicForegroundController(app, timeoutMs = 2_000)
        val result = async(Dispatchers.Default) {
            controller.promote(foreground { throw IllegalStateException("not allowed") })
        }
        awaitPending()

        deliverStart()

        assertFalse(result.await())
        assertNotNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun noServiceResponseTimesOutAndStopsTheService() = runBlocking {
        val controller = MicForegroundController(app, timeoutMs = 50)

        assertFalse(controller.promote(foreground()))

        assertNotNull(shadowOf(app).nextStoppedService)
        assertNull(MicForegroundRegistry.take())
    }

    @Test
    fun stopStopsTheServiceOnceAndIsIdempotent() = runBlocking {
        val controller = MicForegroundController(app, timeoutMs = 2_000)
        val result = async(Dispatchers.Default) { controller.promote(foreground()) }
        awaitPending()
        deliverStart()
        assertTrue(result.await())

        controller.stop()
        controller.stop()

        assertNotNull(shadowOf(app).nextStoppedService)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun stopWithoutPromotionDoesNothing() = runBlocking {
        MicForegroundController(app).stop()

        assertNull(shadowOf(app).nextStoppedService)
    }
}
