package com.neuralsound.audio.internal

import org.junit.Test

/** FR-11 / FR-12 / S2-AS1..AS3: source boost, lookahead limiter and attenuation ramp host contracts. */
class OutputSignalChainHostTest {
    private val dspSources = listOf("dsp/SourceBoost.cpp", "dsp/StereoLookaheadLimiter.cpp")

    @Test
    fun sourceBoostAndLimiterContractPasses() = HostTestSupport.compileAndRun(
        name = "SourceBoostAndLimiterDspHostTest",
        testSource = "dsp/SourceBoostAndLimiterDspHostTest.cpp",
        sources = dspSources,
        includes = listOf("dsp"),
        passMarker = "PASS: Source boost and limiter DSP host contract",
    )

    @Test
    fun outputAttenuationRampContractPasses() = HostTestSupport.compileAndRun(
        name = "OutputAttenuationRampHostTest",
        testSource = "dsp/OutputAttenuationRampHostTest.cpp",
        sources = dspSources + "dsp/OutputAttenuationRamp.cpp",
        includes = listOf("dsp"),
        passMarker = "PASS: Output attenuation ramp host contract",
    )

    @Test
    fun limiterMatchesTheExportAlimiterEnvelope() = HostTestSupport.compileAndRun(
        name = "LimiterReferenceHostTest",
        testSource = "dsp/LimiterReferenceHostTest.cpp",
        sources = listOf("dsp/StereoLookaheadLimiter.cpp"),
        includes = listOf("dsp"),
        passMarker = "PASS: Limiter matches alimiter reference envelope",
    )
}
