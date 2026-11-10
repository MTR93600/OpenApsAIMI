package app.aaps.database

import java.util.concurrent.TimeUnit

/**
 * Bounds for resolving the active [app.aaps.database.entities.EffectiveProfileSwitch] so SQLite
 * does not sort/scan the full EPS history (rows are large due to embedded profile blocks).
 */
object EffectiveProfileSwitchQueryBounds {

    /** How far back to search before falling back to an id-only unbounded query (ms). */
    val MAX_ACTIVE_LOOKBACK_MS: Long = TimeUnit.DAYS.toMillis(180)

    fun earliestTimestampForActiveAt(timestamp: Long): Long =
        (timestamp - MAX_ACTIVE_LOOKBACK_MS).coerceAtLeast(0L)
}
