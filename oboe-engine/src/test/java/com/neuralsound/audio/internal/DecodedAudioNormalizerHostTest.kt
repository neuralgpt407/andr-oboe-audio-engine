package com.neuralsound.audio.internal

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedAudioNormalizerHostTest {
    @Test
    fun decodedAudioNormalizerHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/decodedAudioNormalizerHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "DecodedAudioNormalizerHostTest")
        val dspDir = File(moduleDir, "src/main/cpp/dsp")
        val resamplerDir = File(moduleDir, "src/main/cpp/third_party/oboe-resampler")

        val command = buildList {
            add("c++")
            add("-std=c++17")
            add("-DRESAMPLER_OUTER_NAMESPACE=neuralsound_dsp")
            add("-I${dspDir.absolutePath}")
            add("-I${resamplerDir.absolutePath}")
            add(File(moduleDir, "src/test/cpp/dsp/DecodedAudioNormalizerHostTest.cpp").absolutePath)
            add(File(dspDir, "DecodedAudioNormalizer.cpp").absolutePath)
            add(File(dspDir, "SampleRateConverter.cpp").absolutePath)
            listOf(
                "IntegerRatio.cpp",
                "LinearResampler.cpp",
                "MultiChannelResampler.cpp",
                "PolyphaseResampler.cpp",
                "PolyphaseResamplerMono.cpp",
                "PolyphaseResamplerStereo.cpp",
                "SincResampler.cpp",
                "SincResamplerStereo.cpp",
            ).forEach { source ->
                add(File(resamplerDir, source).absolutePath)
            }
            add("-o")
            add(binary.absolutePath)
        }

        val compile = runProcess(command, moduleDir)
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: DecodedAudioNormalizer"))
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
            return ProcessResult(-1, "$output\nprocess timed out")
        }
        return ProcessResult(process.exitValue(), output)
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
