package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Finds last night in the sleep a health store gives back, the same way on both phones.
 *
 * Both readers ask for 48 hours of sleep, so they get two nights and sometimes a nap. They used to
 * add all of it up, and that sum was taken as "last night": about 13 h for two normal nights. With
 * that number the sleep debt was always 0, a short night (under 5.5 h) could almost never be seen,
 * and the duration part of the sleep score was always full.
 *
 * The rule here:
 * 1. Samples that overlap, or that are less than [SESSION_BRIDGE_MS] apart, are one session. Two
 *    apps that write the same night are then not counted twice, and a short waking stays inside
 *    the night, as a Health Connect session span keeps it.
 * 2. Sessions less than [NIGHT_GAP_MS] apart are one night. A night broken by a long waking is
 *    still one night, but the long waking is not counted as sleep.
 * 3. Last night is the newest night that has ended and holds at least [MIN_NIGHT_MS] of sleep. A
 *    nap is not a night, and a night that is still running does not count until it ends: half a
 *    night must not read as a short night at 3 in the morning.
 * 4. Without such a night, the longest finished one is used, then a running one, so sleep that
 *    exists is never dropped.
 */
object SleepNights {

    /** A waking shorter than this stays inside the session. */
    const val SESSION_BRIDGE_MS = 60L * 60L * 1000L

    /** Sessions closer than this are the same night. */
    const val NIGHT_GAP_MS = 3L * 60L * 60L * 1000L

    /** Less sleep than this is a nap, not a night. */
    const val MIN_NIGHT_MS = 3L * 60L * 60L * 1000L

    data class Span(val startMs: Long, val endMs: Long)

    /** One night: from the first sleep to the last, and how long of that was spent asleep. */
    data class Night(val startMs: Long, val endMs: Long, val asleepMs: Long) {

        val durationHours: Double get() = asleepMs / 3_600_000.0
    }

    /** Last night, or null when there is no sleep at all. See the rule above. */
    fun lastNight(spans: List<Span>, nowMs: Long): Night? {
        val nights = nights(mergeSessions(spans))
        if (nights.isEmpty()) return null
        val finished = nights.filter { it.endMs <= nowMs }
        return finished.lastOrNull { it.asleepMs >= MIN_NIGHT_MS }
            ?: finished.maxByOrNull { it.asleepMs }
            ?: nights.last()
    }

    /** Glues overlapping and nearly touching samples into sessions, oldest first. */
    fun mergeSessions(spans: List<Span>): List<Span> {
        val ordered = spans.filter { it.endMs > it.startMs }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return emptyList()
        val sessions = mutableListOf<Span>()
        var start = ordered.first().startMs
        var end = ordered.first().endMs
        for (span in ordered.drop(1)) {
            if (span.startMs <= end + SESSION_BRIDGE_MS) {
                if (span.endMs > end) end = span.endMs
            } else {
                sessions += Span(start, end)
                start = span.startMs
                end = span.endMs
            }
        }
        sessions += Span(start, end)
        return sessions
    }

    /** Groups sessions into nights, oldest first. [sessions] must come from [mergeSessions]. */
    private fun nights(sessions: List<Span>): List<Night> {
        val nights = mutableListOf<Night>()
        for (session in sessions) {
            val last = nights.lastOrNull()
            if (last != null && session.startMs - last.endMs < NIGHT_GAP_MS) {
                nights[nights.lastIndex] = Night(last.startMs, session.endMs, last.asleepMs + (session.endMs - session.startMs))
            } else {
                nights += Night(session.startMs, session.endMs, session.endMs - session.startMs)
            }
        }
        return nights
    }
}
