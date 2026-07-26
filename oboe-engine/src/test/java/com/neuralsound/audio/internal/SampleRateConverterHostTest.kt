package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class SampleRateConverterHostTest {
    @Test
    fun sampleRateConverterHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/dspHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "SampleRateConverterHostTest")

        val dspInclude = File(moduleDir, "src/main/cpp/dsp")
        val resamplerInclude = File(moduleDir, "src/main/cpp/third_party/oboe-resampler")

        val testSource = File(moduleDir, "src/test/cpp/dsp/SampleRateConverterHostTest.cpp")
        val wrapperSource = File(moduleDir, "src/main/cpp/dsp/SampleRateConverter.cpp")
        val resamplerSources = listOf(
            "IntegerRatio.cpp",
            "LinearResampler.cpp",
            "MultiChannelResampler.cpp",
            "PolyphaseResampler.cpp",
            "PolyphaseResamplerMono.cpp",
            "PolyphaseResamplerStereo.cpp",
            "SincResampler.cpp",
            "SincResamplerStereo.cpp",
        ).map { File(resamplerInclude, it) }

        val command = buildList {
            add("c++")
            add("-std=c++17")
            // Isolate the vendored resampler's symbols from those inside liboboe.so.
            add("-DRESAMPLER_OUTER_NAMESPACE=neuralsound_dsp")
            add("-I${dspInclude.absolutePath}")
            add("-I${resamplerInclude.absolutePath}")
            add(testSource.absolutePath)
            add(wrapperSource.absolutePath)
            resamplerSources.forEach { add(it.absolutePath) }
            add("-o")
            add(binary.absolutePath)
        }

        val compile = runProcess(command, moduleDir)
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: SampleRateConverter"))
    }

    private fun runProcess(command: List<String>, workingDir: File): ProcessResult {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ProcessResult(-1, output + "\nprocess timed out")
        }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
