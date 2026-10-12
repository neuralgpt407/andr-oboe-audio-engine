package com.neuralsound.audio.internal.recorder

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/** Atomic-ish replacement of a destination file by a working copy, with rollback and crash recovery. */
internal object AtomicFilePromoter {
    private const val BAK = ".bak"
    private const val TMP = ".tmp"
    private const val WORK = ".work" // conventional working-copy name: <dest>.work

    /** Replaces [destination] with [workingCopy]; on failure the original is restored and [IOException] thrown. */
    fun promote(workingCopy: File, destination: File) =
        promote(workingCopy, destination) { from, to -> from.renameTo(to) }

    internal fun promote(workingCopy: File, destination: File, renamer: (File, File) -> Boolean) {
        if (!workingCopy.isFile) throw IOException("Working copy does not exist: $workingCopy")
        val backup = backupOf(destination)
        var backedUp = false
        try {
            if (backup.exists() && !backup.delete()) throw IOException("Cannot clear stale backup $backup")
            if (destination.exists()) {
                if (!renamer(destination, backup)) throw IOException("Cannot back up $destination")
                backedUp = true
            }
            if (!renamer(workingCopy, destination)) {
                copyAcrossDirs(workingCopy, destination, renamer)
            }
            if (backedUp) backup.delete()
        } catch (failure: Throwable) {
            if (backedUp) {
                destination.delete()
                if (!renamer(backup, destination)) {
                    throw IOException("Unable to restore $destination from backup after failed promotion", failure)
                }
            }
            throw failure as? IOException ?: IOException("Promotion failed", failure)
        }
    }

    private fun copyAcrossDirs(src: File, dest: File, renamer: (File, File) -> Boolean) {
        val tmp = File(dest.parentFile, dest.name + TMP)
        try {
            FileInputStream(src).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!renamer(tmp, dest)) throw IOException("Cannot rename $tmp to $dest")
            src.delete()
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Crash recovery: restore `<dest>.bak` if the destination is missing, then drop stale `.tmp`/`.bak` leftovers. */
    fun cleanupLeftovers(destination: File) {
        val backup = backupOf(destination)
        if (!destination.exists() && backup.exists()) {
            if (!backup.renameTo(destination)) throw IOException("Unable to restore $destination from $backup")
        }
        if (destination.exists() && backup.exists()) backup.delete()
        File(destination.parentFile, destination.name + TMP).takeIf { it.exists() }?.delete()
        File(destination.parentFile, destination.name + WORK).takeIf { it.exists() }?.delete()
    }

    private fun backupOf(destination: File) = File(destination.parentFile, destination.name + BAK)
}
