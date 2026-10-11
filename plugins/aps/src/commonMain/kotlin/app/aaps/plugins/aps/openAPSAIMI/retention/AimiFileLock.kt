package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.tryWithLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

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
 *
 * Keyed by the resolved path, so two spellings of one file share one lock. On Android `/sdcard` is
 * itself a symlink to `/storage/emulated/0`, and the janitor and the writers do not get their paths
 * from the same place: the worker's sweep resolves its own candidates, but the append guard's path
 * comes from `AimiStorageHelper`. Keying on the raw spelling would therefore have left the janitor
 * and a writer holding two different locks for one file, which is the case this lock exists for.
 *
 * When a path cannot be resolved - typically a file that does not exist yet - the raw spelling is
 * the key instead. Two spellings of a file nobody has created yet still take two locks, which is
 * harmless: there is nothing to corrupt until one of them writes it.
 */
internal object AimiFileLock {

    private val mapGuard = AapsLock()
    private val locks = HashMap<String, AapsLock>()

    private fun lockFor(path: AimiPath): AapsLock {
        val key = aimiFsCanonicalPath(path) ?: path.value
        return mapGuard.withLock { locks.getOrPut(key) { AapsLock() } }
    }

    /** Runs [block] with exclusive access to [path], waiting for it if necessary. */
    fun <T> withFile(path: AimiPath, block: () -> T): T = lockFor(path).withLock(block)

    /** Runs [block] only if [path] is free right now, and returns `null` otherwise. Never waits. */
    fun <T : Any> tryWithFile(path: AimiPath, block: () -> T): T? = lockFor(path).tryWithLock(block)
}
