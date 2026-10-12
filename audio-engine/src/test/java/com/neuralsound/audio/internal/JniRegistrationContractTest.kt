package com.neuralsound.audio.internal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every method registered in jni_bridge.cpp must exist on the JVM under that exact name.
 * Kotlin mangles `internal` member names, so an `internal external fun` without @JvmName
 * fails RegisterNatives and aborts the process on first library load (device-only crash).
 */
class JniRegistrationContractTest {
    private val bridge = File(HostTestSupport.moduleDir, "src/main/cpp/jni/jni_bridge.cpp").readText()
    private val sources = File(HostTestSupport.moduleDir, "src/main/java").walkTopDown().filter { it.extension == "kt" }
        .associate { it.nameWithoutExtension to it.readText() }

    @Test
    fun everyRegisteredNativeHasAnUnmangledKotlinDeclaration() {
        val tables = Regex("""JNINativeMethod\s+(\w+)\[\]\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL).findAll(bridge).toList()
        val classes = Regex("""registerRequiredNativeMethods\(\s*env,\s*"([^"]+)",\s*(\w+)""").findAll(bridge)
            .associate { it.groupValues[2] to it.groupValues[1].substringAfterLast('/') }
        assertEquals("three JNI registration tables", 3, tables.size)
        val problems = mutableListOf<String>()
        tables.forEach { table ->
            val className = classes[table.groupValues[1]] ?: return@forEach
            val source = sources[className] ?: sources.values.firstOrNull { it.contains("object $className") || it.contains("class $className") }
                ?: run { problems.add("no Kotlin source for $className"); return@forEach }
            Regex("""\{\s*"(\w+)"""").findAll(table.groupValues[2]).forEach { match ->
                val name = match.groupValues[1]
                val declared = Regex("""(private|@JvmName\("$name"\)\s*(internal\s+)?)\s*external\s+fun\s+$name\b""").containsMatchIn(source) ||
                    Regex("""private\s+external\s+fun\s+$name\b""").containsMatchIn(source)
                if (!declared) problems += "$className.$name"
            }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
