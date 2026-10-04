package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * `latestSmbCached()?.timestamp`, called where the reference calls `latestSmbCached`.
 * The Android shell keeps the cache and the async refresh.
 */
internal fun interface AimiLatestSmbCached {
    fun latestSmbCached(): Long?
}
