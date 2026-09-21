package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises access to one telemetry file, the same way `AutodriveDatasetLock` does for the
 * Autodrive dataset, but keyed by path so one lock per managed file is enough.
 *
 * The janitor rewrites a file by moving a temporary copy over it. [withFile] only guarantees mutual
 * exclusion between lock holders: it stops two janitor passes, or a janitor pass and any other
 * writer that also takes this lock, from touching the file at the same time. It does NOT, by itself,
 * stop a line from being lost. A writer that never takes this lock at all can still append in the
 * microseconds between the janitor's catch-up read and the move, and that line is then dropped when
 * the move replaces the file. This is accepted: at most one telemetry line, at most once per pass.
 *
 * Writers called from the decision path are meant to take [tryWithFile] instead and give up at once
 * if the lock is busy: a lost telemetry line is cheap, a delayed dose is not. Wiring an actual writer
 * to do so is a later task.
 */
internal object AimiFileLock {

    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /**
     * Keyed by canonical path, not [File.getAbsolutePath]. On Android, `/sdcard` is a symlink to
     * `/storage/emulated/0`, and [AimiRetentionWorker] now reaches the same real directory through
     * more than one spelling (`storageHelper.getAimiDirectory()` can itself resolve to either one).
     * A writer and the janitor that reach the same file through two different spellings must take
     * the SAME lock, or the whole point of this lock - stopping them from touching the file at the
     * same time - is defeated. [File.getCanonicalPath] can throw [IOException] (for example on a
     * broken symlink); [File.getAbsolutePath] is used only as that fallback, never as the normal key.
     */
    private fun lockFor(file: File): ReentrantLock {
        val key = try {
            file.canonicalPath
        } catch (e: IOException) {
            file.absolutePath
        }
        return locks.computeIfAbsent(key) { ReentrantLock() }
    }

    /** Runs [block] with exclusive access to [file], waiting for it if necessary. */
    fun <T> withFile(file: File, block: () -> T): T {
        val lock = lockFor(file)
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Runs [block] only if [file] is free right now, and returns `null` otherwise. Never waits. */
    fun <T : Any> tryWithFile(file: File, block: () -> T): T? {
        val lock = lockFor(file)
        if (!lock.tryLock()) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
