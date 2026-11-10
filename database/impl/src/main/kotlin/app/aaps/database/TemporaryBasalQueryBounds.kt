package app.aaps.database

import java.util.concurrent.TimeUnit

/**
 * Bounds for [app.aaps.database.daos.TemporaryBasalDao.getTemporaryBasalActiveAt] so SQLite
 * does not scan/sort the full history when resolving the active temp basal.
 */
object TemporaryBasalQueryBounds {

    /**
     * How far back to search for an active TBR at [timestamp] (ms).
     * A temp basal never runs longer than 24 hours, so one day is enough. A shorter window means fewer
     * rows are read for every lookup (this query is called very often by the IOB/COB and AIMI code).
     */
    val MAX_ACTIVE_LOOKBACK_MS: Long = TimeUnit.DAYS.toMillis(1)

    /** Ignore rows with corrupt/overlong duration (ms). */
    val MAX_REASONABLE_DURATION_MS: Long = TimeUnit.DAYS.toMillis(1)

    fun earliestTimestampForActiveAt(timestamp: Long): Long =
        (timestamp - MAX_ACTIVE_LOOKBACK_MS).coerceAtLeast(0L)
}
