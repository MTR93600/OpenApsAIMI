package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.steps.IosStepCache
import app.aaps.plugins.aps.openAPSAIMI.steps.IosStepDataStatus
import kotlin.concurrent.Volatile

/**
 * iOS step counts, read from a cache that `IosStepsLifecycle` fills from HealthKit.
 *
 * The getters never wait for HealthKit. They only read the in-memory cache, like the Android
 * version reads its live sensor buckets. The windows are the same as on Android: the 5, 10 and 15
 * minute getters answer one 5 minute bucket each, and the 30, 60 and 180 minute getters add up
 * 6, 12 and 34 buckets.
 *
 * **When there is no data, the getters return `null`.** That is the case before the first refresh,
 * when HealthKit is not available or a query failed, when the last good refresh is older than
 * ten minutes (`NEVER_REFRESHED` and `STALE`), and when HealthKit has held no step sample for 24
 * hours (`NO_STEP_SOURCE`, which is what a refused read looks like). When the status is
 * `AVAILABLE` the getters return the real count, and that includes a real 0. The engine reads
 * `null` as "unknown" and does not take a step-driven decision from it. [dataStatus] shows the
 * state for logs and tests.
 */
actual object StepService {

    @Volatile
    internal var cache: IosStepCache = IosStepCache { aimiWallClockMs() }

    /**
     * Asks for a refresh without waiting for it. Set by `IosStepsLifecycle` while it runs.
     * Called by a getter when the cache is not usable, so the next tick has an answer.
     */
    @Volatile
    internal var refreshRequest: (() -> Unit)? = null

    /** Whether the getters answer real readings. When it is not `AVAILABLE`, every getter answers `null`. */
    val dataStatus: IosStepDataStatus
        get() = cache.status()

    actual fun getRecentStepCount5Min(): Int? = read { singleBucket(1) }
    actual fun getRecentStepCount10Min(): Int? = read { singleBucket(2) }
    actual fun getRecentStepCount15Min(): Int? = read { singleBucket(3) }
    actual fun getRecentStepCount30Min(): Int? = read { windowSum(6) }
    actual fun getRecentStepCount60Min(): Int? = read { windowSum(12) }
    actual fun getRecentStepCount180Min(): Int? = read { windowSum(34) }

    private inline fun read(block: IosStepCache.() -> Int?): Int? {
        val current = cache
        if (!current.hasData()) refreshRequest?.invoke()
        return current.block()
    }
}
