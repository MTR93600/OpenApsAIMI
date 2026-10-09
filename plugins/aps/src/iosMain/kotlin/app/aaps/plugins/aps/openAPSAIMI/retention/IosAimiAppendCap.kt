package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.tryWithLock
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiLocalFiles
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.aimiLocalFiles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiAppendCap]: the same guard as [AimiAppendGuard], on iOS files.
 *
 * The Android guard is built on `java.io.File` and a non-blocking file lock, neither of which
 * exists on iOS, so the logic is ported rather than shared: count the bytes it has been told
 * about, stat the file once per megabyte, and rename it aside to `<name>.overflow` the moment it
 * passes its hard cap from [AimiRetentionPolicy]. The next retention pass archives the overflow
 * file and deletes it.
 *
 * The guarantees are the guard's own: never throws, never blocks. A lock that cannot be taken at
 * once (the janitor is mid-rotation) skips the check instead of stalling a dosing tick, and an
 * overflow file the janitor has not drained yet is never overwritten - the live file is left alone
 * and keeps growing, which is exactly one cap per file between janitor runs.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiAppendCap @Inject constructor() : AimiAppendCap {

    // Not a constructor parameter: there is no Metro binding for AimiLocalFiles, and this is the
    // same module that owns the expect/actual, so the platform file access is read directly.
    private val io: AimiLocalFiles = aimiLocalFiles()

    private val lock = AapsLock()

    /** Bytes told about per path since the last stat. Only touched under [lock]. */
    private val pending = mutableMapOf<String, Long>()

    override fun beforeAppend(path: AimiPath, bytes: Int) {
        runCatching {
            val fileName = path.value.substringAfterLast('/')
            val rule = AimiRetentionPolicy.ruleFor(fileName) ?: return
            val cap = rule.hardCapBytes
            val key = path.value
            val forceCheckNow = bytes <= 0
            // The hot path only bumps a counter; the file is stat'd once per megabyte.
            val due = lock.tryWithLock {
                if (!forceCheckNow) {
                    val total = (pending[key] ?: 0L) + bytes
                    if (total < STAT_EVERY_BYTES) {
                        pending[key] = total
                        return@tryWithLock false
                    }
                }
                pending[key] = 0L
                true
            } ?: return
            if (!due) return
            // Short critical section under one lock: the overflow-exists check and the rename
            // must not interleave, or two callers could both rotate and lose an archive.
            lock.tryWithLock {
                if (io.length(key) <= cap) return@tryWithLock
                val overflow = key + AimiRetentionPolicy.OVERFLOW_SUFFIX
                if (io.exists(overflow)) return@tryWithLock
                io.rename(key, overflow)
            }
        }
    }

    companion object {

        private const val STAT_EVERY_BYTES = 1024L * 1024
    }
}
