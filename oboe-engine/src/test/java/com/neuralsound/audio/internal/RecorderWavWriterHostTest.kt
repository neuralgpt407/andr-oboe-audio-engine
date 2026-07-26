package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RecorderWavWriterHostTest {
    @Test
    fun pcm16WavWriterHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/recorderHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "Pcm16WavWriterHostTest")
        val testSource = File(moduleDir, "src/test/cpp/recorder/Pcm16WavWriterHostTest.cpp")
        val headerSource = File(moduleDir, "src/main/cpp/recorder/Pcm16WavHeader.cpp")
        val writerSource = File(moduleDir, "src/main/cpp/recorder/Pcm16WavWriter.cpp")

        val compile = runProcess(
            listOf(
                "c++",
                "-std=c++17",
                testSource.absolutePath,
                headerSource.absolutePath,
                writerSource.absolutePath,
                "-o",
                binary.absolutePath,
            ),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: Pcm16WavWriter"))
    }

    @Test
    fun recorderWaveformAccumulatorHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/recorderHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "RecorderWaveformAccumulatorHostTest")
        val testSource = File(moduleDir, "src/test/cpp/recorder/RecorderWaveformAccumulatorHostTest.cpp")
        val accumulatorSource = File(moduleDir, "src/main/cpp/recorder/RecorderWaveformAccumulator.cpp")

        val compile = runProcess(
            listOf(
                "c++",
                "-std=c++17",
                testSource.absolutePath,
                accumulatorSource.absolutePath,
                "-o",
                binary.absolutePath,
            ),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: RecorderWaveformAccumulator"))
    }

    @Test
    fun recorderRingIntegrityHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/recorderHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "RecorderRingIntegrityHostTest")
        val testSource = File(moduleDir, "src/test/cpp/recorder/RecorderRingIntegrityHostTest.cpp")

        val compile = runProcess(
            listOf("c++", "-std=c++17", testSource.absolutePath, "-o", binary.absolutePath),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)
        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: Recorder ring"))
    }

    @Test
    fun recorderStaleCallbackFenceHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/recorderHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "RecorderCallbackFenceHostTest")
        val testSource = File(moduleDir, "src/test/cpp/recorder/RecorderCallbackFenceHostTest.cpp")
        val compile = runProcess(
            listOf("c++", "-std=c++17", "-pthread", testSource.absolutePath, "-o", binary.absolutePath),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)
        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: Recorder stale-callback"))
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
