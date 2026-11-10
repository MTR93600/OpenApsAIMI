package app.aaps.core.interfaces.workflow

/**
 * Guards the expensive [PrepareGraphDataWorker] path triggered by [REASON_ON_EVENT_APP_INITIALIZED].
 * After a process restart, replaying every historical autosens bucket can block the UI for minutes.
 */
object AppInitCalculationPolicy {

    const val REASON_ON_EVENT_APP_INITIALIZED = "onEventAppInitialized"

    /** Let Compose/dashboard attach before the main IOB/autosens worker runs. */
    const val DEFER_MS = 5_000L

    /**
     * On cold start, only (re)compute the most recent bucket window (~8 h at 5-min resolution).
     * Older gaps are filled on later BG/history calculations.
     */
    const val WARM_START_MAX_BUCKETS_TO_COMPUTE = 100

    fun isAppInitReason(reason: String): Boolean = reason == REASON_ON_EVENT_APP_INITIALIZED

    fun warmStartOldestBucketIndex(bucketedSize: Int, reason: String): Int {
        if (!isAppInitReason(reason)) return 0
        if (bucketedSize < 4) return 0
        return maxOf(0, bucketedSize - 4 - WARM_START_MAX_BUCKETS_TO_COMPUTE)
    }
}
