package com.neuralsound.audio.internal.recorder

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilePromoterTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun f(name: String, text: String? = null) =
        File(tmp.root, name).also { if (text != null) it.writeText(text) }

    @Test fun promotesWhenDestinationMissing() {
        val work = f("d.wav.work", "new"); val dest = f("d.wav")
        AtomicFilePromoter.promote(work, dest)
        assertEquals("new", dest.readText()); assertFalse(work.exists())
    }

    @Test fun promoteOverExistingLeavesNoBackup() {
        val work = f("d.wav.work", "new"); val dest = f("d.wav", "old")
        AtomicFilePromoter.promote(work, dest)
        assertEquals("new", dest.readText())
        assertFalse(work.exists()); assertFalse(f("d.wav.bak").exists())
    }

    @Test fun failureAfterBackupRestoresOriginal() {
        val work = f("d.wav.work", "new"); val dest = f("d.wav", "old")
        var calls = 0
        try {
            AtomicFilePromoter.promote(work, dest) { from, to ->
                calls++
                if (calls == 2) throw IOException("boom") else from.renameTo(to)
            }
            fail("expected IOException")
        } catch (_: IOException) {}
        assertEquals("old", dest.readText())
        assertFalse(f("d.wav.bak").exists())
        assertTrue(work.exists())
    }

    @Test fun failedRenameFallsBackToCopyThenSucceeds() {
        val work = f("d.wav.work", "new"); val dest = f("d.wav", "old")
        var calls = 0
        AtomicFilePromoter.promote(work, dest) { from, to ->
            calls++
            if (work == from && to == dest) false else from.renameTo(to)
        }
        assertEquals("new", dest.readText()); assertFalse(f("d.wav.tmp").exists()); assertFalse(f("d.wav.bak").exists())
    }

    @Test fun copyFallbackFailureRestoresOriginal() {
        val work = f("d.wav.work", "new"); val dest = f("d.wav", "old")
        try {
            AtomicFilePromoter.promote(work, dest) { from, to ->
                if (to == dest && from != File(tmp.root, "d.wav.bak")) false else from.renameTo(to)
            }
            fail("expected IOException")
        } catch (_: IOException) {}
        assertEquals("old", dest.readText()); assertFalse(f("d.wav.tmp").exists())
    }

    @Test fun missingWorkingCopyThrowsAndKeepsDestination() {
        val dest = f("d.wav", "old")
        try { AtomicFilePromoter.promote(f("nope"), dest); fail() } catch (_: IOException) {}
        assertEquals("old", dest.readText())
    }

    @Test fun cleanupRestoresBackupWhenDestinationMissing() {
        f("d.wav.bak", "orig")
        AtomicFilePromoter.cleanupLeftovers(f("d.wav"))
        assertEquals("orig", f("d.wav").readText()); assertFalse(f("d.wav.bak").exists())
    }

    @Test fun cleanupDeletesStaleTmpWorkAndRedundantBackup() {
        val dest = f("d.wav", "cur")
        f("d.wav.tmp", "x"); f("d.wav.work", "y"); f("d.wav.bak", "z")
        AtomicFilePromoter.cleanupLeftovers(dest)
        assertEquals("cur", dest.readText())
        assertFalse(f("d.wav.tmp").exists()); assertFalse(f("d.wav.work").exists()); assertFalse(f("d.wav.bak").exists())
    }
}
