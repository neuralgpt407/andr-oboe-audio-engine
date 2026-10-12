package com.neuralsound.audio.internal.recorder

import com.neuralsound.audio.AudioError
import com.neuralsound.audio.AudioResult
import com.neuralsound.audio.recorder.DraftProblem
import com.neuralsound.audio.recorder.DraftRequest
import com.neuralsound.audio.recorder.DraftStatus
import com.neuralsound.audio.recorder.MicMeter
import com.neuralsound.audio.recorder.Recorder
import com.neuralsound.audio.recorder.RecorderEvent
import com.neuralsound.audio.recorder.RecorderState
import com.neuralsound.audio.recorder.RecordingDraft
import com.neuralsound.audio.recorder.Take
import com.neuralsound.audio.recorder.TakeMode
import com.neuralsound.audio.recorder.TakeRequest
import com.neuralsound.audio.recorder.TakeResult
import com.neuralsound.audio.recorder.WaveformBuckets
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-15..FR-18 / S5-AS1..AS5 / S5-AS8 / EC-2..EC-4, plus the applicable OND RecorderManager cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRecordingDraftTest {
    private val dir: File = Files.createTempDirectory("drafts").toFile()
    private val draftFile = File(dir, "draft.wav")
    private val workFile = File(dir, "draft.wav.work")
    private val rate = 44_100L

    // -------------------------------------------------------------------------------
    // Fakes
    // -------------------------------------------------------------------------------

    private class FakeTake(val recorder: FakeRecorder, val request: TakeRequest) : Take {
        override val file: File = request.file
        val waveformFlow = MutableSharedFlow<WaveformBuckets>(extraBufferCapacity = 64)
        override val waveform: Flow<WaveformBuckets> = waveformFlow.asSharedFlow()
        val written = MutableStateFlow(0L)
        override val writtenFrames: StateFlow<Long> = written
        var finished = false

        override fun pause(): AudioResult<Unit> = AudioResult.Done
        override fun resume(): AudioResult<Unit> = AudioResult.Done

        /** Writes the capture into the file the way the native writer finalizes it. */
        fun finalize(frames: Long): TakeResult {
            finished = true
            recorder.active = null
            recorder.writeCapture(request, frames)
            val total = if (request.file.isFile) readSamples(request.file).size.toLong() else 0L
            return TakeResult(request.file, request.startFrame, frames, Recorder.msForFrames(total))
        }

        override suspend fun stop(): AudioResult<TakeResult> {
            if (finished) return AudioResult.Err(AudioError.InvalidState)
            recorder.stopError?.let { error ->
                finalize(recorder.nextFrames)
                return AudioResult.Err(error)
            }
            val result = finalize(recorder.nextFrames)
            if (recorder.corruptOnStop) request.file.writeBytes(ByteArray(10))
            return AudioResult.Ok(result)
        }
    }

    private class FakeRecorder : Recorder {
        override val state: StateFlow<RecorderState> = MutableStateFlow(RecorderState())
        val eventFlow = MutableSharedFlow<RecorderEvent>(extraBufferCapacity = 16)
        override val events: Flow<RecorderEvent> = eventFlow.asSharedFlow()
        val meterFlow = MutableStateFlow(MicMeter())
        override val meter: StateFlow<MicMeter> = meterFlow

        val requests = mutableListOf<TakeRequest>()
        var active: FakeTake? = null
        var nextFrames = 0L
        var fill: Short = 1_000
        var startError: AudioError? = null
        var stopError: AudioError? = null
        var corruptOnStop = false

        override suspend fun openMic(): AudioResult<Unit> = AudioResult.Done
        override suspend fun closeMic() = Unit
        override suspend fun release() = Unit
        override suspend fun openDraft(request: DraftRequest): AudioResult<RecordingDraft> = error("unused")

        override suspend fun record(request: TakeRequest): AudioResult<Take> {
            startError?.let { return AudioResult.Err(it) }
            if (active != null) return AudioResult.Err(AudioError.InvalidState)
            if (request.mode == TakeMode.PUNCH_IN && !request.file.isFile) return AudioResult.Err(AudioError.FileError)
            requests += request
            return AudioResult.Ok(FakeTake(this, request).also { active = it })
        }

        fun writeCapture(request: TakeRequest, frames: Long) {
            val existing = if (request.mode == TakeMode.PUNCH_IN) readSamples(request.file) else ShortArray(0)
            val end = (request.startFrame + frames).toInt()
            val merged = existing.copyOf(maxOf(existing.size, end))
            for (i in request.startFrame.toInt() until end) merged[i] = fill
            request.file.writeBytes(WavTestHelper.wavBytes(merged))
        }

        /** The input device disappears: the recorder finalizes the take and emits InputLost. */
        fun loseInput(partialFrames: Long) {
            val take = checkNotNull(active)
            val result = take.finalize(partialFrames)
            eventFlow.tryEmit(RecorderEvent.InputLost(result))
        }
    }

    private companion object {
        fun readSamples(file: File): ShortArray {
            val bytes = file.readBytes()
            return ShortArray((bytes.size - 44) / 2) { i ->
                ((bytes[44 + 2 * i].toInt() and 0xff) or (bytes[45 + 2 * i].toInt() shl 8)).toShort()
            }
        }
    }

    private fun TestScope.open(
        recorder: FakeRecorder = FakeRecorder(),
        permission: Boolean = true,
        promoter: (File, File) -> Unit = { from, to -> AtomicFilePromoter.promote(from, to) },
        file: File = draftFile,
    ): Pair<FakeRecorder, DefaultRecordingDraft> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return recorder to DefaultRecordingDraft(
            request = DraftRequest(file),
            recorder = recorder,
            hasRecordPermission = { permission },
            dispatcher = dispatcher,
            promoter = promoter,
        )
    }

    private suspend fun DefaultRecordingDraft.opened(): DefaultRecordingDraft {
        assertTrue(initialize().isOk)
        return this
    }

    private suspend fun recordAndPause(recorder: FakeRecorder, draft: RecordingDraft, frames: Long, fill: Short = 1_000) {
        recorder.nextFrames = frames
        recorder.fill = fill
        assertTrue(draft.record().isOk)
        assertTrue(draft.pause().isOk)
    }

    // -------------------------------------------------------------------------------
    // S5-AS1 / FR-15
    // -------------------------------------------------------------------------------

    @Test
    fun recordPauseRecordPauseGivesOneSixSecondDraftWithOrderedStatuses() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        val statuses = mutableListOf<DraftStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            draft.state.collect { if (statuses.lastOrNull() != it.status) statuses += it.status }
        }

        recordAndPause(recorder, draft, 3 * rate)
        assertEquals(3 * rate, draft.state.value.totalFrames)
        recordAndPause(recorder, draft, 3 * rate, fill = 2_000)

        assertEquals(6 * rate, draft.state.value.totalFrames)
        assertEquals(6 * rate, draft.state.value.cursorFrame)
        assertEquals(
            listOf(DraftStatus.EMPTY, DraftStatus.RECORDING, DraftStatus.PAUSED, DraftStatus.RECORDING, DraftStatus.PAUSED),
            statuses,
        )
        // First take is NEW at 0, the resume appends at the exact end.
        assertEquals(listOf(0L to TakeMode.NEW, 3 * rate to TakeMode.PUNCH_IN), recorder.requests.map { it.startFrame to it.mode })
        val samples = readSamples(draftFile)
        assertEquals(6 * rate.toInt(), samples.size)
        assertEquals(1_000.toShort(), samples[0])
        assertEquals(2_000.toShort(), samples[samples.size - 1])
        assertFalse(workFile.exists())
        assertNotNull(WavInspector.inspect(draftFile))
        assertTrue(draft.state.value.waveform.levels.isNotEmpty())
    }

    @Test
    fun liveWaveformCursorAndLevelFollowTheTake() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recorder.nextFrames = rate
        draft.record()
        advanceUntilIdle()
        val take = checkNotNull(recorder.active)

        take.waveformFlow.tryEmit(WaveformBuckets(0, floatArrayOf(0.1f, 0.2f)))
        take.waveformFlow.tryEmit(WaveformBuckets(2, floatArrayOf(0.3f)))
        take.written.value = 4_000L
        recorder.meterFlow.value = MicMeter(peak = 0.6f, rms = 0.2f)
        advanceUntilIdle()

        val live = draft.state.value
        assertEquals(DraftStatus.RECORDING, live.status)
        assertEquals(0L, live.liveWaveform.startFrame)
        assertEquals(listOf(0.1f, 0.2f, 0.3f), live.liveWaveform.levels.toList())
        assertEquals(4_000L, live.cursorFrame)
        assertEquals(4_000L, live.totalFrames)
        assertEquals(0.6f, live.inputLevel, 0f)

        draft.pause()
        val paused = draft.state.value
        assertEquals(0, paused.liveWaveform.levels.size)
        assertEquals(0f, paused.inputLevel, 0f)
    }

    // -------------------------------------------------------------------------------
    // S5-AS2 / FR-16 re-record
    // -------------------------------------------------------------------------------

    @Test
    fun rerecordKeepsAudioBeforeAndAfterTheOverwrittenSpan() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 10 * rate, fill = 1_000)
        val before = readSamples(draftFile)

        recorder.nextFrames = 2 * rate
        recorder.fill = 7_000
        assertTrue(draft.rerecordFrom(4 * rate).isOk)
        assertEquals(4 * rate, draft.state.value.cursorFrame)
        assertEquals(10 * rate, draft.state.value.totalFrames)
        assertTrue(draft.pause().isOk)

        val after = readSamples(draftFile)
        assertEquals(before.size, after.size)
        val from = (4 * rate).toInt()
        val to = (6 * rate).toInt()
        assertTrue(before.copyOfRange(0, from).contentEquals(after.copyOfRange(0, from)))
        assertTrue(before.copyOfRange(to, before.size).contentEquals(after.copyOfRange(to, after.size)))
        assertTrue(after.copyOfRange(from, to).all { it == 7_000.toShort() })
        assertEquals(6 * rate, draft.state.value.cursorFrame)
        assertEquals(10 * rate, draft.state.value.totalFrames)
        assertFalse(workFile.exists())
    }

    @Test
    fun rerecordFrameIsClampedToTheDraftRange() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, rate)

        recorder.nextFrames = 100
        assertTrue(draft.rerecordFrom(99 * rate).isOk)
        assertTrue(draft.pause().isOk)
        assertTrue(draft.rerecordFrom(-5).isOk)
        assertTrue(draft.pause().isOk)

        assertEquals(listOf(rate, 0L), recorder.requests.drop(1).map { it.startFrame })
        // The first clamped edit extended the draft from its exact end.
        assertEquals(rate + 100, draft.state.value.totalFrames)
    }

    // -------------------------------------------------------------------------------
    // S5-AS3 / FR-16 failures
    // -------------------------------------------------------------------------------

    @Test
    fun inputLossDuringAnEditKeepsTheLastGoodDraftAndAllowsResumeThenCommit() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 3 * rate)
        val good = draftFile.readBytes()
        val goodWaveform = draft.state.value.waveform

        recorder.nextFrames = rate
        assertTrue(draft.rerecordFrom(rate).isOk)
        recorder.loseInput(partialFrames = 500)
        advanceUntilIdle()

        val state = draft.state.value
        assertEquals(DraftStatus.PAUSED, state.status)
        assertEquals(DraftProblem.INPUT_LOST, state.problem)
        assertTrue(state.recoverable)
        assertEquals(3 * rate, state.totalFrames)
        assertEquals(goodWaveform, state.waveform)
        assertTrue(good.contentEquals(draftFile.readBytes()))
        assertFalse(workFile.exists())

        recorder.nextFrames = rate
        assertTrue(draft.resume().isOk)
        assertNull(draft.state.value.problem)
        assertTrue(draft.pause().isOk)
        val destination = File(dir, "final.wav")
        val committed = draft.commit(destination)
        assertEquals(4 * rate, (committed as AudioResult.Ok).value.frames)
        assertEquals(4 * rate.toInt(), readSamples(destination).size)
    }

    @Test
    fun writerOverflowFailureOnPauseRollsBackToTheLastGoodDraft() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, rate)
        val good = draftFile.readBytes()

        recorder.nextFrames = rate
        assertTrue(draft.resume().isOk)
        recorder.stopError = AudioError.WriterOverflow
        assertEquals(AudioResult.Err(AudioError.WriterOverflow), draft.pause())

        assertEquals(DraftProblem.OVERFLOW, draft.state.value.problem)
        assertTrue(draft.state.value.recoverable)
        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
        assertTrue(good.contentEquals(draftFile.readBytes()))
        assertFalse(workFile.exists())
    }

    @Test
    fun asynchronousRecorderFailureEventRollsBackInsteadOfDestroyingTheDraft() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, rate)
        val good = draftFile.readBytes()

        assertTrue(draft.resume().isOk)
        recorder.active!!.finalize(100)
        recorder.eventFlow.tryEmit(RecorderEvent.Failed(AudioError.WriterOverflow))
        advanceUntilIdle()

        assertEquals(DraftProblem.OVERFLOW, draft.state.value.problem)
        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
        assertTrue(good.contentEquals(draftFile.readBytes()))
        assertFalse(workFile.exists())
    }

    @Test
    fun takeStartFailureIsRecoverableAndKeepsTheDraft() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, rate)
        val good = draftFile.readBytes()

        recorder.startError = AudioError.MicUnavailable
        assertEquals(AudioResult.Err(AudioError.MicUnavailable), draft.resume())
        assertEquals(DraftProblem.MIC_UNAVAILABLE, draft.state.value.problem)
        assertTrue(draft.state.value.recoverable)
        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
        assertTrue(good.contentEquals(draftFile.readBytes()))
        assertFalse(workFile.exists())

        recorder.startError = null
        assertTrue(draft.resume().isOk)
        assertNull(draft.state.value.problem)
    }

    @Test
    fun invalidWorkingCopyIsRejectedAndTheDraftKeptIntact() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, rate)
        val good = draftFile.readBytes()

        recorder.nextFrames = 50
        recorder.corruptOnStop = true
        assertTrue(draft.resume().isOk)

        assertEquals(AudioResult.Err(AudioError.FileError), draft.pause())

        assertEquals(DraftProblem.INVALID_TAKE, draft.state.value.problem)
        assertTrue(draft.state.value.recoverable)
        assertTrue(good.contentEquals(draftFile.readBytes()))
        assertFalse(workFile.exists())
    }

    @Test
    fun initialTakeThatLosesInputKeepsTheCapturedAudio() = runTest {
        val (recorder, draft) = open()
        draft.opened()

        assertTrue(draft.record().isOk)
        recorder.loseInput(partialFrames = 2 * rate)
        advanceUntilIdle()

        val state = draft.state.value
        assertEquals(DraftStatus.PAUSED, state.status)
        assertEquals(DraftProblem.INPUT_LOST, state.problem)
        assertTrue(state.recoverable)
        assertEquals(2 * rate, state.totalFrames)
        assertEquals(2 * rate.toInt(), readSamples(draftFile).size)
    }

    // -------------------------------------------------------------------------------
    // S5-AS4 / FR-17 commit
    // -------------------------------------------------------------------------------

    @Test
    fun commitMovesTheDraftAndReportsTheTakeResult() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 3 * rate)
        val destination = File(dir, "out/final.wav")
        val draftBytes = draftFile.readBytes()

        val result = (draft.commit(destination) as AudioResult.Ok).value

        assertEquals(TakeResultShape(destination, 0L, 3 * rate, 3_000L), TakeResultShape(result.file, result.startFrame, result.frames, result.fileDurationMs))
        assertTrue(draftBytes.contentEquals(destination.readBytes()))
        assertEquals(DraftStatus.COMMITTED, draft.state.value.status)
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.record())
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.pause())
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.commit(File(dir, "again.wav")))
    }

    private data class TakeResultShape(val file: File, val start: Long, val frames: Long, val ms: Long)

    @Test
    fun commitWhileRecordingPausesFirst() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recorder.nextFrames = 2 * rate
        draft.record()

        val result = (draft.commit(File(dir, "final.wav")) as AudioResult.Ok).value

        assertEquals(2 * rate, result.frames)
        assertEquals(DraftStatus.COMMITTED, draft.state.value.status)
    }

    @Test
    fun interruptedCommitLeavesTheOldDestinationAndLaterRetrySucceeds() = runTest {
        val destination = File(dir, "final.wav")
        val oldBytes = WavTestHelper.wavBytes(ShortArray(500) { 9 })
        destination.writeBytes(oldBytes)
        var failing = false
        // Rename-level failure: the final move into place breaks after the original was backed up.
        val promoter: (File, File) -> Unit = { from, to ->
            AtomicFilePromoter.promote(from, to) { a, b ->
                if (failing && (a == draftFile || a.name.endsWith(".tmp")) && b == destination) false else a.renameTo(b)
            }
        }
        val (recorder, draft) = open(promoter = promoter)
        draft.opened()
        recordAndPause(recorder, draft, rate)
        val draftBytes = draftFile.readBytes()

        failing = true
        assertEquals(AudioResult.Err(AudioError.FileError), draft.commit(destination))

        assertTrue(oldBytes.contentEquals(destination.readBytes())) // old, complete
        assertTrue(draftBytes.contentEquals(draftFile.readBytes()))
        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
        assertEquals(DraftProblem.WRITER_FAILURE, draft.state.value.problem)
        assertTrue(draft.state.value.recoverable)

        failing = false
        assertTrue(draft.commit(destination).isOk)
        assertTrue(draftBytes.contentEquals(destination.readBytes())) // new, complete
    }

    @Test
    fun commitToThePromoterThrowingIOExceptionNeverCreatesAPartialDestination() = runTest {
        val destination = File(dir, "fresh.wav")
        var commitPhase = false
        val (recorder, draft) = open(promoter = { from, to ->
            if (commitPhase) throw IOException("disk full")
            AtomicFilePromoter.promote(from, to)
        })
        draft.opened()
        recordAndPause(recorder, draft, rate)

        commitPhase = true
        assertEquals(AudioResult.Err(AudioError.FileError), draft.commit(destination))
        assertFalse(destination.exists())
        assertTrue(draftFile.isFile)
    }

    // -------------------------------------------------------------------------------
    // S5-AS5 / FR-18 reopen
    // -------------------------------------------------------------------------------

    @Test
    fun reopeningAValidDraftRebuildsWaveformAndTotals() = runTest {
        draftFile.writeBytes(WavTestHelper.wavBytes(ShortArray(rate.toInt()) { 8_000 }))
        val (_, draft) = open()
        draft.opened()

        val state = draft.state.value
        assertEquals(DraftStatus.PAUSED, state.status)
        assertEquals(rate, state.totalFrames)
        assertEquals(rate, state.cursorFrame)
        assertEquals(0L, state.waveform.startFrame)
        // 44_100 frames at 32 ms per bucket (1411.2 frames) is 31.25 buckets, so 32 with the partial one.
        assertEquals(32, state.waveform.levels.size)
        assertEquals(8_000f / 32_768f, state.waveform.levels[0], 1e-4f)
    }

    @Test
    fun reopenedDraftCanBeExtended() = runTest {
        draftFile.writeBytes(WavTestHelper.wavBytes(ShortArray(1_000) { 5 }))
        val (recorder, draft) = open()
        draft.opened()

        recordAndPause(recorder, draft, 500)

        assertEquals(1_500L, draft.state.value.totalFrames)
        assertEquals(1_000L, recorder.requests.single().startFrame)
    }

    @Test
    fun invalidDraftFileFailsOpenWithInvalidTake() = runTest {
        draftFile.writeBytes(ByteArray(100) { 3 })
        val (_, draft) = open()

        assertEquals(AudioResult.Err(AudioError.FileError), draft.initialize())

        assertEquals(DraftStatus.FAILED, draft.state.value.status)
        assertEquals(DraftProblem.INVALID_TAKE, draft.state.value.problem)
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.record())
    }

    @Test
    fun wrongSampleRateDraftIsInvalid() = runTest {
        draftFile.writeBytes(WavTestHelper.wavBytes(ShortArray(1_000), sampleRate = 48_000))
        val (_, draft) = open()
        assertFalse(draft.initialize().isOk)
        assertEquals(DraftProblem.INVALID_TAKE, draft.state.value.problem)
    }

    // -------------------------------------------------------------------------------
    // S5-AS8 empty take
    // -------------------------------------------------------------------------------

    @Test
    fun emptyTakeIsReportedAndNothingIsWritten() = runTest {
        val (recorder, draft) = open()
        draft.opened()

        recorder.nextFrames = 0
        assertTrue(draft.record().isOk)
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.pause())

        assertEquals(DraftStatus.EMPTY, draft.state.value.status)
        assertEquals(DraftProblem.EMPTY_TAKE, draft.state.value.problem)
        assertFalse(draftFile.exists())
        assertFalse(workFile.exists())
    }

    @Test
    fun commitOfAnEmptyDraftCreatesNoFile() = runTest {
        val (_, draft) = open()
        draft.opened()
        val destination = File(dir, "never.wav")

        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.commit(destination))

        assertFalse(destination.exists())
        assertEquals(DraftProblem.EMPTY_TAKE, draft.state.value.problem)
        assertEquals(DraftStatus.EMPTY, draft.state.value.status)
    }

    // -------------------------------------------------------------------------------
    // EC-2 / EC-4 / lifecycle
    // -------------------------------------------------------------------------------

    @Test
    fun missingPermissionFailsBeforeTouchingTheMic() = runTest {
        val (recorder, draft) = open(permission = false)
        draft.opened()

        assertEquals(AudioResult.Err(AudioError.PermissionDenied), draft.record())

        assertTrue(recorder.requests.isEmpty())
        assertEquals(DraftProblem.PERMISSION_DENIED, draft.state.value.problem)
        assertEquals(DraftStatus.EMPTY, draft.state.value.status)
        assertFalse(workFile.exists())
    }

    @Test
    fun leftoverWorkingAndBackupFilesAreCleanedOnOpen() = runTest {
        workFile.writeBytes(ByteArray(64))
        File(dir, "draft.wav.bak").writeBytes(WavTestHelper.wavBytes(ShortArray(2_000) { 4 }))
        File(dir, "draft.wav.tmp").writeBytes(ByteArray(8))
        val (_, draft) = open()
        draft.opened()

        assertFalse(workFile.exists())
        assertFalse(File(dir, "draft.wav.tmp").exists())
        assertFalse(File(dir, "draft.wav.bak").exists())
        // An interrupted promotion left only the backup: it is restored as the draft.
        assertEquals(2_000L, draft.state.value.totalFrames)
        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
    }

    @Test
    fun staleWorkingCopyDoesNotLeakIntoANewEdit() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)
        workFile.writeBytes(ByteArray(33)) // stale leftover

        recorder.nextFrames = 10
        assertTrue(draft.resume().isOk)
        assertTrue(draft.pause().isOk)

        assertEquals(1_010L, draft.state.value.totalFrames)
    }

    @Test
    fun duplicateResumeWhileRecordingOpensOnlyOneTake() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)

        assertTrue(draft.resume().isOk)
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.resume())
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.rerecordFrom(0))
        assertEquals(2, recorder.requests.size)
    }

    @Test
    fun repeatedEditsAlwaysStartFromTheLatestPromotedDraft() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000, fill = 1)
        recordAndPause(recorder, draft, 500, fill = 2)
        recordAndPause(recorder, draft, 250, fill = 3)

        val samples = readSamples(draftFile)
        assertEquals(1_750, samples.size)
        assertEquals(listOf<Short>(1, 2, 3), listOf(samples[0], samples[1_000], samples[1_749]))
        assertEquals(listOf(0L, 1_000L, 1_500L), recorder.requests.map { it.startFrame })
    }

    @Test
    fun zeroFrameEditPromotesTheUnchangedDraft() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)
        val good = draftFile.readBytes()

        recorder.nextFrames = 0
        assertTrue(draft.resume().isOk)
        assertTrue(draft.pause().isOk)

        assertEquals(DraftStatus.PAUSED, draft.state.value.status)
        assertNull(draft.state.value.problem)
        assertEquals(1_000L, draft.state.value.totalFrames)
        assertTrue(good.contentEquals(draftFile.readBytes()))
    }

    @Test
    fun discardDeletesTheDraftAndItsCopies() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)
        recorder.nextFrames = 100
        assertTrue(draft.resume().isOk)

        draft.discard()

        assertEquals(DraftStatus.DISCARDED, draft.state.value.status)
        assertFalse(draftFile.exists())
        assertFalse(workFile.exists())
        assertNull(recorder.active)
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.record())
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.commit(File(dir, "x.wav")))
    }

    @Test
    fun releasingTheRecorderFailsTheDraftButKeepsTheFile() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)
        recorder.nextFrames = 100
        assertTrue(draft.resume().isOk)

        draft.onRecorderReleased()

        assertEquals(DraftStatus.FAILED, draft.state.value.status)
        assertTrue(draftFile.isFile)
        assertFalse(workFile.exists())
        assertEquals(AudioResult.Err(AudioError.InvalidState), draft.record())
    }

    @Test
    fun committingOverTheDraftFileItselfMovesNothing() = runTest {
        val (recorder, draft) = open()
        draft.opened()
        recordAndPause(recorder, draft, 1_000)

        val result = draft.commit(draftFile)

        assertEquals(1_000L, (result as AudioResult.Ok).value.frames)
        assertTrue(draftFile.isFile)
    }
}
