package com.neuralsound.audio.internal

import android.net.Uri
import com.neuralsound.audio.AudioError
import com.neuralsound.audio.TrackFailureReason
import com.neuralsound.audio.TrackId
import com.neuralsound.audio.internal.legacy.AudioFailure
import com.neuralsound.audio.internal.player.LegacyMapping
import com.neuralsound.audio.player.PlayerEvent
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** FR-17 / NFR-3: no media URI or path reaches public errors or SDK log calls. */
@RunWith(RobolectricTestRunner::class)
class NoUriLeakTest {
    private val secretUri = Uri.parse("content://com.example.media/private/song-of-user.mp3")

    @Test
    fun publicErrorsAndEventsDescribeTracksWithoutTheirSource() {
        val id = TrackId("vocal")
        val internal = listOf(
            AudioFailure.TrackFailed(id, TrackFailureReason.UNREADABLE_SOURCE),
            AudioFailure.SourceUnavailable(secretUri.toString()),
            AudioFailure.UnsupportedTrackFormat(id, 48_000, 6, 44_100),
            AudioFailure.NativeOperationFailed("prepare", "detail ${secretUri.lastPathSegment}"),
        )
        internal.forEach { failure ->
            val public = LegacyMapping.toPublic(failure, { uri -> id.takeIf { uri == secretUri.toString() } })
            val rendered = listOf(public.toString(), PlayerEvent.Failed(public).toString())
            rendered.forEach { text ->
                assertFalse(text, text.contains("content://"))
                assertFalse(text, text.contains("song-of-user"))
            }
        }
        assertEquals(
            AudioError.TrackFailed(id, TrackFailureReason.UNREADABLE_SOURCE),
            LegacyMapping.toPublic(AudioFailure.SourceUnavailable(secretUri.toString()), { id }),
        )
    }

    @Test
    fun sdkSourcesNeverLogAUriOrPath() {
        val sources = File(HostTestSupport.moduleDir, "src/main/java").walkTopDown().filter { it.extension == "kt" }
        val offenders = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val isLog = Regex("""Log\.[vdiwe]\(""").containsMatchIn(line)
                val leaks = Regex("""\$\{?(uri|source|path|file|descriptor)\b""").containsMatchIn(line)
                if (isLog && leaks) "${file.name}:${index + 1}: ${line.trim()}" else null
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
