package app.aaps.database.daos

import app.aaps.database.TemporaryBasalQueryBounds
import app.aaps.database.entities.TemporaryBasal

internal suspend fun TemporaryBasalDao.getTemporaryBasalActiveAtBounded(timestamp: Long): TemporaryBasal? =
    getTemporaryBasalActiveAt(
        timestamp = timestamp,
        earliestTimestamp = TemporaryBasalQueryBounds.earliestTimestampForActiveAt(timestamp),
        maxReasonableDurationMs = TemporaryBasalQueryBounds.MAX_REASONABLE_DURATION_MS
    )

internal suspend fun TemporaryBasalDao.getTemporaryBasalActiveAtLegacyBounded(timestamp: Long): TemporaryBasal? =
    getTemporaryBasalActiveAtLegacy(
        timestamp = timestamp,
        earliestTimestamp = TemporaryBasalQueryBounds.earliestTimestampForActiveAt(timestamp),
        maxReasonableDurationMs = TemporaryBasalQueryBounds.MAX_REASONABLE_DURATION_MS
    )
