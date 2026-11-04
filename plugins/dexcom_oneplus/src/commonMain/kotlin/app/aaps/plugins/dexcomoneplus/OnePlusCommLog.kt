package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock

/**
 * The newest driver lines, kept in memory, for the log screen of a phone that has no log file.
 *
 * On Android the driver writes through slf4j into `AndroidAPS.log`, and the log screen reads that
 * file back. iPhone has no such file yet: its sink prints to standard output, which only Xcode can
 * read. So the iOS sink also hands every line to this buffer, and the log screen reads it here.
 *
 * Two limits keep it small: lines older than [MAX_AGE_MS] (24 h) are dropped, and never more than
 * [MAX_LINES] are kept. The buffer is lost when the app stops; that is said on the screen.
 *
 * It is a log and nothing else: no driver decision reads it.
 */
class OnePlusCommLog(
    private val maxLines: Int = MAX_LINES,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {

    /** One kept line. */
    data class Line(val atMs: Long, val level: String, val text: String)

    private val lock = AapsLock()
    private val lines = ArrayDeque<Line>()

    /** Keeps [text], written at [atMs] with [level], and drops what is now too old or too many. */
    fun add(atMs: Long, level: String, text: String) {
        lock.withLock {
            lines.addLast(Line(atMs, level, text))
            prune(atMs)
        }
    }

    /** The kept lines, oldest first, as they are at [nowMs]. */
    fun lines(nowMs: Long): List<Line> = lock.withLock {
        prune(nowMs)
        lines.toList()
    }

    /** Forgets every line. */
    fun clear() {
        lock.withLock { lines.clear() }
    }

    private fun prune(nowMs: Long) {
        while (lines.size > maxLines) lines.removeFirst()
        while (lines.isNotEmpty() && nowMs - lines.first().atMs > maxAgeMs) lines.removeFirst()
    }

    companion object {

        /** How long a line is kept: one day. */
        const val MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L

        /** How many lines are kept at most, so a busy day cannot fill the memory. */
        const val MAX_LINES: Int = 3000

        /** The one buffer the iOS sink writes to. */
        val shared: OnePlusCommLog = OnePlusCommLog()
    }
}
