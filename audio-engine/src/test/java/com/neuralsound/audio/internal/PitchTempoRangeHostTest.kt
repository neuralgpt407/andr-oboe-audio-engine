package com.neuralsound.audio.internal

import org.junit.Test

class PitchTempoRangeHostTest {
    @Test
    fun pitchTempoProcessorHonoursTheFullPublicRange() {
        HostTestSupport.compileAndRun(
            name = "PitchTempoRangeHostTest",
            testSource = "effects/PitchTempoRangeHostTest.cpp",
            sources = listOf("effects/PitchTempoProcessor.cpp", "effects/AudioFifo.cpp"),
            includes = listOf("effects", "third_party", "third_party/signalsmith-stretch"),
            passMarker = "PASS: PitchTempoRange",
        )
    }
}
