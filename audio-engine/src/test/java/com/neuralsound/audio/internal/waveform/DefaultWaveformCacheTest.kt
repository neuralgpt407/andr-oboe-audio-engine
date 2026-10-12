package com.neuralsound.audio.internal.waveform

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.waveform.Waveform
import com.neuralsound.audio.waveform.WaveformAnalyzer
import com.neuralsound.audio.waveform.WaveformCacheOptions
import com.neuralsound.audio.waveform.WaveformLoad
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DefaultWaveformCacheTest {
    private class FakeAnalyzer(override var algorithmVersion: Int = 3) : WaveformAnalyzer {
        val calls = AtomicInteger()
        val started = CompletableDeferred<Unit>()
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failure: AudioError? = null
        @Volatile var cancelled = false

        override suspend fun analyze(descriptor: ParcelFileDescriptor, maxLevels: Int): AudioResult<Waveform> {
            calls.incrementAndGet()
            started.complete(Unit)
            try {
                gate?.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            }
            failure?.let { return AudioResult.Err(it) }
            return AudioResult.Ok(Waveform(FloatArray(maxLevels) { (it % 10) / 10f }, algorithmVersion))
        }
    }

    private lateinit var root: File
    private lateinit var cacheDir: File
    private val analyzer = FakeAnalyzer()
    private val resolver get() = RuntimeEnvironment.getApplication().contentResolver

    @Before
    fun setUp() {
        root = File.createTempFile("wfc", "").also { it.delete(); it.mkdirs() }
        cacheDir = File(root, "cache")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun newCache(
        options: WaveformCacheOptions = WaveformCacheOptions(),
        fake: WaveformAnalyzer = analyzer,
    ) = DefaultWaveformCache(cacheDir, resolver, options, fake)

    private fun audioFile(name: String, seed: Long = 1, size: Int = 50_000): File =
        File(root, name).apply { writeBytes(ByteArray(size).also { Random(seed).nextBytes(it) }) }

    private fun uri(file: File): Uri = Uri.fromFile(file)

    private fun nwf() = cacheDir.listFiles { f -> f.name.endsWith(".nwf") }.orEmpty()

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    // ---- S4-AS1 -------------------------------------------------------------------------

    @Test
    fun sameFileTwiceAnalysesOnce() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        assertTrue(cache.get(uri(file)).isOk)
        assertTrue(cache.get(uri(file)).isOk)
        assertEquals(1, analyzer.calls.get())
    }

    @Test
    fun byteIdenticalCopyAtAnotherPathReusesTheEntry() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        val copy = File(root, "sub/copy.wav").apply { parentFile!!.mkdirs(); writeBytes(file.readBytes()) }
        val first = cache.get(uri(file)).getOrNull()
        val second = cache.get(uri(copy)).getOrNull()
        assertEquals(first, second)
        assertEquals(1, analyzer.calls.get())
    }

    @Test
    fun newCacheInstanceOverTheSameDirReusesDiskEntry() = runBlocking {
        val file = audioFile("a.wav")
        val first = newCache().get(uri(file)).getOrNull()
        val second = newCache().get(uri(file)).getOrNull()
        assertEquals(first, second)
        assertEquals(1, analyzer.calls.get())
    }

    @Test
    fun differentContentIsAnalysedSeparately() = runBlocking {
        val cache = newCache()
        cache.get(uri(audioFile("a.wav", seed = 1)))
        cache.get(uri(audioFile("b.wav", seed = 2)))
        assertEquals(2, analyzer.calls.get())
    }

    // ---- S4-AS2 -------------------------------------------------------------------------

    @Test
    fun differentLevelsGetSeparateEntries() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        val big = cache.get(uri(file), 512).getOrNull()!!
        val small = cache.get(uri(file), 64).getOrNull()!!
        assertEquals(512, big.levels.size)
        assertEquals(64, small.levels.size)
        assertEquals(2, analyzer.calls.get())
        cache.get(uri(file), 512)
        cache.get(uri(file), 64)
        assertEquals(2, analyzer.calls.get())
        assertEquals(2, nwf().size)
    }

    @Test
    fun newAlgorithmVersionTriggersNewAnalysis() = runBlocking {
        val file = audioFile("a.wav")
        newCache().get(uri(file))
        val v4 = FakeAnalyzer(algorithmVersion = 4)
        val result = newCache(fake = v4).get(uri(file)).getOrNull()!!
        assertEquals(1, v4.calls.get())
        assertEquals(4, result.algorithmVersion)
    }

    @Test
    fun invalidLevelsAreRejected() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        assertTrue(cache.get(uri(file), 0).errorOrNull() is AudioError.InvalidConfiguration)
        assertTrue(cache.get(uri(file), WaveformAnalyzer.MAX_LEVELS + 1).errorOrNull() is AudioError.InvalidConfiguration)
        assertEquals(0, analyzer.calls.get())
    }

    // ---- S4-AS3 -------------------------------------------------------------------------

    @Test
    fun concurrentGetsShareOneAnalysis() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        analyzer.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { cache.get(uri(file)) }
        val b = async(Dispatchers.Default) { cache.get(uri(file)) }
        analyzer.started.await()
        waitFor { cache.waitingRequesters() == 2 }
        analyzer.gate!!.complete(Unit)
        assertTrue(a.await().isOk)
        assertEquals(a.await().getOrNull(), b.await().getOrNull())
        assertEquals(1, analyzer.calls.get())
    }

    @Test
    fun cancellingOneRequesterLetsTheOtherComplete() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        analyzer.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { cache.get(uri(file)) }
        val b = async(Dispatchers.Default) { cache.get(uri(file)) }
        analyzer.started.await()
        waitFor { cache.waitingRequesters() == 2 }
        a.cancelAndJoin()
        waitFor { cache.waitingRequesters() == 1 }
        assertFalse(analyzer.cancelled)
        analyzer.gate!!.complete(Unit)
        assertTrue(b.await().isOk)
        assertEquals(1, analyzer.calls.get())
    }

    @Test
    fun cancellingAllRequestersCancelsTheAnalysis() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        analyzer.gate = CompletableDeferred()
        val a = async(Dispatchers.Default) { cache.get(uri(file)) }
        val b = async(Dispatchers.Default) { cache.get(uri(file)) }
        analyzer.started.await()
        waitFor { cache.waitingRequesters() == 2 }
        a.cancelAndJoin()
        b.cancelAndJoin()
        waitFor { analyzer.cancelled }
        assertEquals(0, cache.waitingRequesters())
        assertEquals(0, nwf().size)
    }

    @Test
    fun cancelledAnalysisIsRestartedByTheNextRequest() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        analyzer.gate = CompletableDeferred()
        val a = launch(Dispatchers.Default) { cache.get(uri(file)) }
        analyzer.started.await()
        waitFor { cache.waitingRequesters() == 1 }
        a.cancelAndJoin()
        waitFor { analyzer.cancelled }
        analyzer.gate = null
        assertTrue(cache.get(uri(file)).isOk)
        assertEquals(2, analyzer.calls.get())
    }

    // ---- S4-AS4 / failures --------------------------------------------------------------

    @Test
    fun missingFileIsATypedErrorWithoutAnalysis() = runBlocking {
        val cache = newCache()
        val missing = File(root, "secret-missing-name.wav")
        val error = cache.get(uri(missing)).errorOrNull()
        assertEquals(AudioError.FileError, error)
        assertFalse(error.toString().contains("secret-missing-name"))
        assertEquals(0, analyzer.calls.get())
    }

    @Test
    fun unreadableUriIsATypedError() = runBlocking {
        val cache = newCache()
        val error = cache.get(Uri.parse("content://com.example.nobody/audio/1")).errorOrNull()
        assertEquals(AudioError.FileError, error)
    }

    @Test
    fun analyzerFailureIsReturnedNotCachedAndRetried() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        analyzer.failure = AudioError.Native("waveform:corrupt-media")
        assertEquals(AudioError.Native("waveform:corrupt-media"), cache.get(uri(file)).errorOrNull())
        assertEquals(0, nwf().size)
        analyzer.failure = null
        assertTrue(cache.get(uri(file)).isOk)
        assertEquals(2, analyzer.calls.get())
    }

    // ---- EC-10 / eviction ---------------------------------------------------------------

    @Test
    fun corruptDiskEntryIsDeletedAndReanalysed() = runBlocking {
        val file = audioFile("a.wav")
        newCache().get(uri(file))
        val entry = nwf().single()
        entry.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(newCache().get(uri(file)).isOk)
        assertEquals(2, analyzer.calls.get())
        assertTrue(entry.length() > 3)
    }

    @Test
    fun truncatedDiskEntryIsReanalysed() = runBlocking {
        val file = audioFile("a.wav")
        newCache().get(uri(file))
        val entry = nwf().single()
        entry.writeBytes(entry.readBytes().copyOf(entry.length().toInt() - 4))
        assertTrue(newCache().get(uri(file)).isOk)
        assertEquals(2, analyzer.calls.get())
    }

    @Test
    fun evictionKeepsDiskUnderTheLimitAndDropsOldestFirst() = runBlocking {
        val entryBytes = 12L + 4L * 100
        val cache = newCache(WaveformCacheOptions(maxDiskBytes = entryBytes * 2 + 10))
        val files = (1..4).map { audioFile("f$it.wav", seed = it.toLong()) }
        for (f in files) {
            cache.get(uri(f), 100)
            // Keep modification times distinct on coarse filesystem timestamps.
            Thread.sleep(20)
        }
        val files2 = nwf()
        assertTrue(files2.sumOf { it.length() } <= entryBytes * 2 + 10)
        assertEquals(2, files2.size)
        // Newest entry survived on disk: a new instance hits it without analysing.
        val before = analyzer.calls.get()
        newCache().get(uri(files.last()), 100)
        assertEquals(before, analyzer.calls.get())
    }

    @Test
    fun memoryLruEvictsBeyondLimit() = runBlocking {
        val cache = newCache(WaveformCacheOptions(memoryEntries = 1))
        val a = audioFile("a.wav", 1)
        val b = audioFile("b.wav", 2)
        cache.get(uri(a))
        cache.get(uri(b))
        cache.get(uri(a)) // from disk, not re-analysed
        assertEquals(2, analyzer.calls.get())
    }

    @Test
    fun clearRemovesMemoryAndDisk() = runBlocking {
        val cache = newCache()
        val file = audioFile("a.wav")
        cache.get(uri(file))
        cache.clear()
        assertEquals(0, nwf().size)
        cache.get(uri(file))
        assertEquals(2, analyzer.calls.get())
    }

    @Test
    fun diskFileNamesNeverContainTheUri() = runBlocking {
        val cache = newCache()
        cache.get(uri(audioFile("my-private-song.wav")))
        assertTrue(cacheDir.list().orEmpty().none { it.contains("my-private-song") })
    }

    // ---- load() -------------------------------------------------------------------------

    @Test
    fun loadEmitsLoadingThenReady() = runBlocking {
        val cache = newCache()
        val states = cache.load(uri(audioFile("a.wav"))).toList()
        assertEquals(2, states.size)
        assertEquals(WaveformLoad.Loading, states[0])
        assertTrue(states[1] is WaveformLoad.Ready)
    }

    @Test
    fun loadEmitsFailedForMissingFile() = runBlocking {
        val cache = newCache()
        val states = cache.load(uri(File(root, "nope.wav"))).toList()
        assertEquals(listOf(WaveformLoad.Loading, WaveformLoad.Failed(AudioError.FileError)), states)
    }
}
