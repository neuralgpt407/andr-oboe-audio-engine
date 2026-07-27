package com.neuralsound.audio.internal

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformAnalysisHostTest {
    @Test
    fun waveformAnalysisHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/waveformHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "WaveformAnalysisHostTest")
        val waveformDir = File(moduleDir, "src/main/cpp/waveform")

        val compile = runProcess(
            listOf(
                "c++",
                "-std=c++17",
                "-pthread",
                "-I${waveformDir.absolutePath}",
                File(
                    moduleDir,
                    "src/test/cpp/waveform/WaveformAnalysisHostTest.cpp",
                ).absolutePath,
                File(waveformDir, "WaveformAnalysisCore.cpp").absolutePath,
                File(waveformDir, "WaveformRmsAccumulator.cpp").absolutePath,
                "-o",
                binary.absolutePath,
            ),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: WaveformAnalysis"))
    }

    private fun runProcess(command: List<String>, workingDir: File): ProcessResult {
        val outputFile = File.createTempFile("waveform-host-", ".log", workingDir)
        return try {
            val process = ProcessBuilder(command)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(outputFile)
                .start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            val output = outputFile.readText()
            if (!finished) {
                ProcessResult(-1, "$output\nprocess timed out")
            } else {
                ProcessResult(process.exitValue(), output)
            }
        } finally {
            outputFile.delete()
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
    )
}
