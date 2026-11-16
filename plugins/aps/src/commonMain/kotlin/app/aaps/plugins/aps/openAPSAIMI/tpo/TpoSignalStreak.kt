package app.aaps.plugins.aps.openAPSAIMI.tpo

/**
 * A TPO session starts only when the same pack has been proposed for [MIN_PERSISTENCE_MS] in a row.
 *
 * A session changes the settings for up to 45 min, so a signal that lasts one tick must not start
 * one. Case 2026-11-16: the rebound guard was on for a single 5 min tick (the tick before and the
 * tick after were normal) and that tick started a STRONG post-hypo session.
 *
 * Time based, not tick based, so a 1 min CGM and a 5 min CGM wait the same time. With a 5 min CGM
 * it means three ticks in a row (0, 5 and 10 min).
 *
 * The streak lives in memory only. After a restart it starts again from zero, which can only delay
 * a session, never start one.
 *
 * Pure: it reads no clock.
 *
 * @property packId the pack seen on the last tick, or `null` when no pack was proposed.
 * @property firstSeenMs when this pack was first seen without a break.
 * @property lastSeenMs when this pack was last seen.
 */
internal data class TpoSignalStreak(
    val packId: TpoPackId? = null,
    val firstSeenMs: Long = 0L,
    val lastSeenMs: Long = 0L,
) {

    /** The streak after one more tick. [proposedPackId] is `null` when the tick proposed nothing. */
    fun observe(proposedPackId: TpoPackId?, nowMs: Long): TpoSignalStreak = when {
        proposedPackId == null -> TpoSignalStreak()
        proposedPackId == packId && nowMs - lastSeenMs in 0..MAX_GAP_MS -> copy(lastSeenMs = nowMs)
        else -> TpoSignalStreak(packId = proposedPackId, firstSeenMs = nowMs, lastSeenMs = nowMs)
    }

    /** True when the pack has lasted long enough to start a session. */
    fun isConfirmed(nowMs: Long): Boolean =
        packId != null && nowMs - firstSeenMs >= MIN_PERSISTENCE_MS

    companion object {

        /** How long the same pack must be proposed before a session starts. */
        const val MIN_PERSISTENCE_MS = 10L * 60L * 1000L

        /**
         * Longest gap between two ticks that still counts as "in a row". A little over one 5 min
         * CGM interval, so one late reading does not reset the streak, two do.
         */
        const val MAX_GAP_MS = 7L * 60L * 1000L
    }
}
