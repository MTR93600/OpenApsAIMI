package app.aaps.database

import java.util.concurrent.TimeUnit

/**
 * Bounds for [app.aaps.database.daos.TemporaryBasalDao.getTemporaryBasalActiveAt] so SQLite
 * does not scan/sort the full history when resolving the active temp basal.
 */
object TemporaryBasalQueryBounds {

    /**
     * How far back to search for an active TBR at [timestamp] (ms).
     *
     * It must be at least as long as the longest real temp basal, or that temp basal is not found while it
     * runs. Medtrum stores a pump suspend as a 0 % temp basal of 4800 minutes (80 hours) and cuts it at the
     * resume. One day was too short: the suspend was not found, so the resume could not cut it, and the IOB
     * code (which reads temp basals without this bound) saw zero basal for up to 80 hours. 4 days covers it.
     * A shorter window than the old 7 days means fewer rows are read for every lookup.
     */
    val MAX_ACTIVE_LOOKBACK_MS: Long = TimeUnit.DAYS.toMillis(4)

    /** Ignore rows with corrupt/overlong duration (ms). Same length as [MAX_ACTIVE_LOOKBACK_MS], see there. */
    val MAX_REASONABLE_DURATION_MS: Long = TimeUnit.DAYS.toMillis(4)

    fun earliestTimestampForActiveAt(timestamp: Long): Long =
        (timestamp - MAX_ACTIVE_LOOKBACK_MS).coerceAtLeast(0L)
}
