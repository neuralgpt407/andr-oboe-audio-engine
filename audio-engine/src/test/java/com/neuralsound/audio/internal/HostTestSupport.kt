package com.neuralsound.audio.internal

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Compiles a plain C++ host test with the host `c++` and asserts it prints its PASS marker. */
internal object HostTestSupport {
    val moduleDir: File = (File("audio-engine").takeIf { it.exists() } ?: File(".")).canonicalFile
    private val cppDir = File(moduleDir, "src/main/cpp")

    fun compileAndRun(
        name: String,
        testSource: String,
        sources: List<String>,
        includes: List<String>,
        passMarker: String,
        defines: List<String> = emptyList(),
    ) {
        val binary = File(File(moduleDir, "build/hostTests").also { it.mkdirs() }, name)
        val command = buildList {
            add("c++")
            add("-std=c++17")
            add("-O1")
            defines.forEach { add("-D$it") }
            includes.forEach { add("-I${File(cppDir, it).absolutePath}") }
            add(File(moduleDir, "src/test/cpp/$testSource").absolutePath)
            sources.forEach { add(File(cppDir, it).absolutePath) }
            add("-o")
            add(binary.absolutePath)
        }
        val compile = run(command)
        assertEquals(compile.second, 0, compile.first)
        val result = run(listOf(binary.absolutePath))
        assertEquals(result.second, 0, result.first)
        assertTrue(result.second, result.second.contains(passMarker))
    }

    private fun run(command: List<String>): Pair<Int, String> {
        val process = ProcessBuilder(command).directory(moduleDir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return -1 to "$output\nprocess timed out"
        }
        return process.exitValue() to output
    }
}
