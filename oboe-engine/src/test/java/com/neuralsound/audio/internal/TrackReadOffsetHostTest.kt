package com.neuralsound.audio.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class TrackReadOffsetHostTest {
    @Test
    fun trackReadOffsetHostContractPasses() {
        val moduleDir = (File("oboe-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
        val outputDir = File(moduleDir, "build/trackReadOffsetHostTest").also { it.mkdirs() }
        val binary = File(outputDir, "TrackReadOffsetHostTest")
        val testSource = File(moduleDir, "src/test/cpp/engine/TrackReadOffsetHostTest.cpp")
        val includeDir = File(moduleDir, "src/main/cpp/engine")

        val compile = runProcess(
            listOf(
                "c++",
                "-std=c++17",
                "-I${includeDir.absolutePath}",
                testSource.absolutePath,
                "-o",
                binary.absolutePath,
            ),
            moduleDir,
        )
        assertEquals(compile.output, 0, compile.exitCode)

        val run = runProcess(listOf(binary.absolutePath), moduleDir)
        assertEquals(run.output, 0, run.exitCode)
        assertTrue(run.output, run.output.contains("PASS: Track read offsets"))
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
