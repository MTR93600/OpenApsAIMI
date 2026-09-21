package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What [AimiAppendGuard.beforeAppend] did, so a caller can log or ignore it. */
enum class AimiAppendOutcome {

    /** The file name is not in [AimiRetentionPolicy]. Nothing was checked or touched. */
    NOT_MANAGED,

    /** The file is under its hard cap (or was not due for a check yet). Nothing was touched. */
    UNDER_CAP,

    /** The file was over its hard cap and has just been renamed aside to its `.overflow` file. */
    ROTATED,

    /**
     * The file is over its hard cap again, but its `.overflow` file already exists from an earlier
     * rotation that the janitor has not drained yet. The cap is NOT enforced this time: the live
     * file is left alone and keeps growing, on purpose, so a live rotation can never overwrite and
     * lose the still-unarchived one.
     */
    OVERFLOW_ALREADY_PRESENT,

    /** The per-file lock was held by someone else (normally the janitor). Nothing was touched. */
    SKIPPED_BUSY,
}

/**
 * Stops a managed file growing without limit when the daily janitor does not run — but only up to
 * its first hard cap.
 *
 * This is the only part of the retention code that runs on the decision path, so it must be almost
 * free. It counts the bytes it has been told about and only calls `File.length()` once per megabyte,
 * which is one syscall per megabyte written instead of one per line.
 *
 * Over the cap it renames the file aside, which is O(1) and touches no content. The next janitor
 * pass archives the `.overflow` file and deletes it. Nothing is ever compressed here.
 *
 * **This buys exactly one cap per file, not an unlimited bound.** Once `<name>.overflow` exists,
 * this guard refuses to touch the file again (see [AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT]) so
 * that it can never overwrite and lose an archive the janitor has not drained yet. That means: if
 * the janitor keeps running, the live file is capped forever, one rotation at a time. If the janitor
 * never runs, the file rotates once, then grows without limit again, silently, from the guard's point
 * of view — the returned [AimiAppendOutcome] is the only signal of this; callers that care should
 * watch for it.
 */
object AimiAppendGuard {

    private const val STAT_EVERY_BYTES = 1024L * 1024

    private val pending = ConcurrentHashMap<String, AtomicLong>()
    private val stats = AtomicLong(0)

    /** How many times the file size was actually read. Test only. */
    val statCount: Long get() = stats.get()

    /** Clears the byte counters. Test only. */
    fun resetForTest() {
        pending.clear()
        stats.set(0)
    }

    /**
     * Called just before [addedBytes] are appended to [file]. Returns what it did; never throws and
     * never blocks (if the file is locked by the janitor right now, the check is simply skipped).
     *
     * [addedBytes] is normally the number of bytes about to be written, and is added to a per-file
     * running total; the file is only stat'd once that total reaches one megabyte, to keep this cheap
     * on the decision path. A value of zero or less is treated as "check now regardless of the
     * running total" (used by tests, and by any caller that wants an on-demand check) rather than
     * being added to the counter — a negative value is never subtracted, so the counter can never be
     * pushed backwards by a bad caller.
     */
    @JvmOverloads
    fun beforeAppend(file: File, addedBytes: Int, capOverride: Long? = null): AimiAppendOutcome {
        val rule = AimiRetentionPolicy.ruleFor(file.name) ?: return AimiAppendOutcome.NOT_MANAGED
        val cap = capOverride ?: rule.hardCapBytes
        val counter = pending.computeIfAbsent(file.absolutePath) { AtomicLong(0) }
        val forceCheckNow = addedBytes <= 0
        if (!forceCheckNow) {
            val total = counter.addAndGet(addedBytes.toLong())
            if (total < STAT_EVERY_BYTES) return AimiAppendOutcome.UNDER_CAP
        }
        counter.set(0)
        return runCatching {
            stats.incrementAndGet()
            if (file.length() <= cap) return@runCatching AimiAppendOutcome.UNDER_CAP
            // The rename below only moves this one file aside. The janitor must sweep the directory
            // this file actually lives in to drain the `.overflow` it creates; writers can use a
            // directory the worker does not know about (cross-task coupling, see Task 10).
            //
            // The overflow.exists() check and the rename must happen under the SAME lock acquisition.
            // Two overlapping callers could otherwise both read "overflow does not exist" before
            // either of them renames, and the second one's renameTo would then silently replace the
            // first one's still-undrained overflow file - POSIX rename(2) clobbers its destination.
            // That is unreachable today because one thread writes each managed file, but the guard
            // must not depend on that.
            val overflow = File(file.path + AimiRetentionPolicy.OVERFLOW_SUFFIX)
            AimiFileLock.tryWithFile(file) {
                when {
                    overflow.exists()        -> AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT
                    file.renameTo(overflow)  -> AimiAppendOutcome.ROTATED
                    else                      -> AimiAppendOutcome.SKIPPED_BUSY
                }
            } ?: AimiAppendOutcome.SKIPPED_BUSY
        }.getOrDefault(AimiAppendOutcome.SKIPPED_BUSY)
    }
}
