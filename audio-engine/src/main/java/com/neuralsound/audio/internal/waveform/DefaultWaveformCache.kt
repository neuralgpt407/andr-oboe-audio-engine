package com.neuralsound.audio.internal.waveform

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.waveform.Waveform
import com.neuralsound.audio.waveform.WaveformAnalyzer
import com.neuralsound.audio.waveform.WaveformCache
import com.neuralsound.audio.waveform.WaveformCacheOptions
import com.neuralsound.audio.waveform.WaveformLoad
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * [WaveformCache]: memory LRU over a disk cache keyed by content fingerprint, with one shared
 * analysis per key. Raw URIs never appear in file names, errors or logs.
 */
internal class DefaultWaveformCache(
    private val cacheDir: File,
    private val contentResolver: ContentResolver,
    private val options: WaveformCacheOptions = WaveformCacheOptions(),
    private val analyzer: WaveformAnalyzer,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WaveformCache {
    private class Flight(val deferred: Deferred<AudioResult<Waveform>>) {
        var requesters = 0
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val flightLock = Any()
    private val flights = HashMap<String, Flight>()
    private val diskLock = Any()

    private val memory = object : LinkedHashMap<String, Waveform>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Waveform>?): Boolean =
            size > options.memoryEntries.coerceAtLeast(1)
    }

    override suspend fun get(uri: Uri, levels: Int): AudioResult<Waveform> {
        if (levels !in 1..WaveformAnalyzer.MAX_LEVELS) {
            return AudioResult.Err(
                AudioError.InvalidConfiguration("levels must be between 1 and ${WaveformAnalyzer.MAX_LEVELS}"),
            )
        }
        val version = analyzer.algorithmVersion
        val descriptor = withContext(ioDispatcher) { open(uri) }
            ?: return AudioResult.Err(AudioError.FileError)
        var handedOff = false
        try {
            val key = withContext(ioDispatcher) { keyFor(uri, descriptor, version, levels) }
            lookup(key, version, levels)?.let { return AudioResult.Ok(it) }
            val flight = joinFlight(key, descriptor, version, levels)
            if (flight.second) handedOff = true
            return try {
                flight.first.deferred.await()
            } finally {
                leave(key, flight.first)
            }
        } finally {
            if (!handedOff) closeQuietly(descriptor)
        }
    }

    override fun load(uri: Uri, levels: Int): Flow<WaveformLoad> = flow {
        emit(WaveformLoad.Loading)
        emit(
            when (val result = get(uri, levels)) {
                is AudioResult.Ok -> WaveformLoad.Ready(result.value)
                is AudioResult.Err -> WaveformLoad.Failed(result.error)
            },
        )
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            synchronized(memory) { memory.clear() }
            synchronized(diskLock) {
                cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
            }
        }
    }

    // ---- single flight -------------------------------------------------------------------

    /** Requesters currently waiting on an analysis; for tests. */
    internal fun waitingRequesters(): Int = synchronized(flightLock) { flights.values.sumOf { it.requesters } }

    /** Returns the flight and whether it took ownership of [descriptor]. */
    private fun joinFlight(
        key: String,
        descriptor: ParcelFileDescriptor,
        version: Int,
        levels: Int,
    ): Pair<Flight, Boolean> = synchronized(flightLock) {
        val existing = flights[key]
        if (existing != null) {
            existing.requesters++
            return existing to false
        }
        val deferred = scope.async {
            try {
                analyzeAndStore(key, descriptor, version, levels)
            } finally {
                closeQuietly(descriptor)
            }
        }
        val flight = Flight(deferred).also { it.requesters = 1 }
        flights[key] = flight
        deferred.invokeOnCompletion {
            synchronized(flightLock) { if (flights[key] === flight) flights.remove(key) }
            // Cancelled before the body ran: the finally above never executed.
            closeQuietly(descriptor)
        }
        flight to true
    }

    private fun leave(key: String, flight: Flight) {
        val cancel = synchronized(flightLock) {
            flight.requesters--
            val last = flight.requesters <= 0 && flight.deferred.isActive
            if (last && flights[key] === flight) flights.remove(key)
            last
        }
        if (cancel) flight.deferred.cancel()
    }

    private suspend fun analyzeAndStore(
        key: String,
        descriptor: ParcelFileDescriptor,
        version: Int,
        levels: Int,
    ): AudioResult<Waveform> {
        val result = try {
            analyzer.analyze(descriptor, levels)
        } catch (error: CancellationException) {
            coroutineContext.ensureActive()
            AudioResult.Err(AudioError.Native("waveform:cancelled"))
        } catch (_: Exception) {
            AudioResult.Err(AudioError.Native("waveform:failed"))
        }
        if (result is AudioResult.Ok) {
            val waveform = result.value
            if (waveform.algorithmVersion == version) {
                synchronized(memory) { memory[key] = waveform }
                withContext(NonCancellableIo) { writeDisk(key, waveform) }
            }
        }
        return result
    }

    // ---- lookup --------------------------------------------------------------------------

    private suspend fun lookup(key: String, version: Int, levels: Int): Waveform? {
        synchronized(memory) { memory[key] }?.let { return it }
        return withContext(ioDispatcher) { readDisk(key, version) }?.also {
            synchronized(memory) { memory[key] = it }
        }
    }

    // ---- keys ----------------------------------------------------------------------------

    private fun keyFor(uri: Uri, descriptor: ParcelFileDescriptor, version: Int, levels: Int): String {
        val identity = contentFingerprint(descriptor)?.let { "fp:$it" }
            ?: "uri:$uri:${descriptor.statSize}:${lastModified(uri)}"
        return "${sha256Hex(identity)}-v$version-l$levels"
    }

    private fun lastModified(uri: Uri): Long = try {
        when (uri.scheme) {
            "file" -> uri.path?.let { File(it).lastModified() } ?: 0L
            else -> contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            } ?: 0L
        }
    } catch (_: Exception) {
        0L
    }

    private fun open(uri: Uri): ParcelFileDescriptor? = try {
        contentResolver.openFileDescriptor(uri, "r")
    } catch (_: Exception) {
        null
    }

    // ---- disk ----------------------------------------------------------------------------

    private fun fileFor(key: String) = File(cacheDir, "$key$EXTENSION")

    private fun readDisk(key: String, version: Int): Waveform? {
        val file = fileFor(key)
        synchronized(diskLock) {
            if (!file.isFile) return null
            val levels = try {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    if (input.readInt() != MAGIC || input.readInt() != version) return@use null
                    val count = input.readInt()
                    if (count !in 1..WaveformAnalyzer.MAX_LEVELS || file.length() != HEADER_BYTES + 4L * count) {
                        return@use null
                    }
                    val values = FloatArray(count)
                    for (i in 0 until count) {
                        val value = input.readFloat()
                        if (!value.isFinite() || value !in 0f..1f) return@use null
                        values[i] = value
                    }
                    values
                }
            } catch (_: Exception) {
                null
            }
            if (levels == null) {
                runCatching { file.delete() }
                return null
            }
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            return Waveform(levels, version)
        }
    }

    private fun writeDisk(key: String, waveform: Waveform) {
        runCatching {
            synchronized(diskLock) {
                if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return
                val tmp = File(cacheDir, "$key.tmp")
                DataOutputStream(FileOutputStream(tmp).buffered()).use { out ->
                    out.writeInt(MAGIC)
                    out.writeInt(waveform.algorithmVersion)
                    out.writeInt(waveform.levels.size)
                    waveform.levels.forEach { out.writeFloat(it) }
                }
                if (!tmp.renameTo(fileFor(key))) {
                    tmp.delete()
                    return
                }
                evict()
            }
        }
    }

    private fun evict() {
        val files = cacheDir.listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) } ?: return
        var total = files.sumOf { it.length() }
        if (total <= options.maxDiskBytes) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= options.maxDiskBytes) break
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        runCatching { descriptor.close() }
    }

    private companion object {
        const val MAGIC = 0x4E574631 // "NWF1"
        const val HEADER_BYTES = 12L
        const val EXTENSION = ".nwf"
    }
}

private val NonCancellableIo = NonCancellable + Dispatchers.IO
