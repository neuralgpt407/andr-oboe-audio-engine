package com.neuralsound.audio.internal

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-12 / S2-AS4: production code limits the mix before the duck ramp and safety clamp. */
class OboeOutputSignalOrderContractTest {
    private val source = File(HostTestSupport.moduleDir, "src/main/cpp/engine/OboeAudioEngine.cpp").readText()

    @Test
    fun outputIsLimitedBeforeCallbackAttenuationAndClamp() {
        val callback = source.section("oboe::DataCallbackResult OboeAudioEngine::onAudioReady", "OboeAudioEngine::startRenderThread")
        val read = callback.indexOf("outputFifo_.read")
        val attenuation = callback.indexOf("applyPostLimiterOutputAttenuation")
        val clamp = callback.indexOf("std::clamp(value, -kSafetyCeiling, kSafetyCeiling)")
        assertTrue("callback reads limited output before attenuating", read in 0 until attenuation)
        assertTrue("safety clamp runs after the attenuation ramp", attenuation in 0 until clamp)
    }

    @Test
    fun everyRenderPathWritesLimiterOutputOnly() {
        val renderer = source.section("int OboeAudioEngine::renderNextChunk", "bool OboeAudioEngine::waitForRenderPrebuffer")
        assertTrue(renderer.contains("limiter_.process"))
        assertTrue(renderer.contains("limiter_.drain"))
        assertTrue(renderer.contains("outputFifo_.write(limitedBuffer_"))
        assertFalse(renderer.contains("outputFifo_.write(mixBuffer_"))
        assertFalse(renderer.contains("outputFifo_.write(processedBuffer_"))
    }

    @Test
    fun sourceBoostIsAppliedPerTrackBeforeTheMix() {
        val mixer = source.section("int OboeAudioEngine::mixSourceFrames", "bool OboeAudioEngine::openStream")
        assertTrue(mixer.contains("sourceBoosts_[i].nextLinearGain()"))
        assertTrue(mixer.contains("sourceBoostGain *"))
    }

    private fun String.section(start: String, end: String): String {
        val startIndex = indexOf(start)
        val endIndex = indexOf(end, startIndex + start.length)
        require(startIndex >= 0 && endIndex > startIndex) { "Unable to locate $start .. $end" }
        return substring(startIndex, endIndex)
    }
}
