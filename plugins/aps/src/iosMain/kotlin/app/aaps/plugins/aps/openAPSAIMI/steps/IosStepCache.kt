package app.aaps.plugins.aps.openAPSAIMI.steps

import kotlin.concurrent.Volatile

/**
 * What the iOS step cache knows right now.
 *
 * The three states are kept apart on purpose. Only [AVAILABLE] lets a step count be a reading.
 * In the other two states the cache answers `null` ("unknown"), never `0`, so the engine can tell
 * "no data" from "the person did not move".
 */
enum class IosStepDataStatus {
    /** No refresh has finished yet since the app started. Getters answer `null` (unknown). */
    NEVER_REFRESHED,

    /** The last good refresh is too old to trust. Getters answer `null` (unknown). */
    STALE,

    /** A recent refresh worked. A 0 from a getter now is real: HealthKit saw no steps. */
    AVAILABLE,
}

/**
 * Time buckets of 5 minutes, keyed the same way as the Android `StepService`.
 *
 * Android keys a bucket with `round(time / 5 min)`, so bucket `k` covers the span from
 * `k - 0.5` to `k + 0.5` times 5 minutes. The same keys are used here, in integer maths, so that a
 * window sum on iOS asks the same question as on Android.
 */
internal object IosStepBuckets {

    const val BUCKET_MS = 5L * 60L * 1000L

    /** Half a bucket. HealthKit buckets are anchored this far before the epoch to match the keys. */
    const val HALF_BUCKET_MS = BUCKET_MS / 2

    /** The longest window a getter reads is 34 buckets back (the 180 minute one). */
    const val BUCKETS_TO_KEEP = 34

    /** Same as `round(timeMs / BUCKET_MS)` for positive times, without floating point. */
    fun keyOf(timeMs: Long): Long = (timeMs + HALF_BUCKET_MS) / BUCKET_MS

    /** Start of the bucket [key], in epoch milliseconds. */
    fun startOfKeyMs(key: Long): Long = key * BUCKET_MS - HALF_BUCKET_MS

    /** First instant a refresh has to read so that the longest window is complete. */
    fun windowStartMs(nowMs: Long): Long = startOfKeyMs(keyOf(nowMs) - BUCKETS_TO_KEEP)
}

/**
 * In-memory step buckets that a background refresh fills and the six `StepService` getters read.
 *
 * The getters are called inside the dosing loop and must never wait for HealthKit, so they only
 * read this cache. Every method here is plain memory work.
 *
 * **Unknown is not zero.** Before the first good refresh, and when the last good refresh is older
 * than [MAX_AGE_MS], [status] is not [IosStepDataStatus.AVAILABLE]. The getters then return `null`.
 * Only when the status is available is a `0` a measurement.
 *
 * @param nowMs the clock, a parameter so a test can move time.
 */
internal class IosStepCache(private val nowMs: () -> Long) {

    private class Snapshot(val buckets: Map<Long, Int>, val refreshedAtMs: Long)

    @Volatile
    private var snapshot: Snapshot? = null

    /** Replaces the cache with the buckets of one good refresh. Buckets with no steps may be left out. */
    fun update(buckets: Map<Long, Int>, refreshedAtMs: Long = nowMs()) {
        snapshot = Snapshot(buckets.toMap(), refreshedAtMs)
    }

    /** Forgets everything. The status goes back to [IosStepDataStatus.NEVER_REFRESHED]. */
    fun clear() {
        snapshot = null
    }

    /** Epoch milliseconds of the last good refresh, or `null` when there has been none. */
    fun lastRefreshMs(): Long? = snapshot?.refreshedAtMs

    fun status(): IosStepDataStatus {
        val current = snapshot ?: return IosStepDataStatus.NEVER_REFRESHED
        return if (nowMs() - current.refreshedAtMs <= MAX_AGE_MS) {
            IosStepDataStatus.AVAILABLE
        } else {
            IosStepDataStatus.STALE
        }
    }

    /** `true` when a getter answer is a real reading, and `false` when the getters answer `null`. */
    fun hasData(): Boolean = status() == IosStepDataStatus.AVAILABLE

    /**
     * Steps in the one bucket that is [bucketsBack] buckets before the current one.
     * Like Android, the 5, 10 and 15 minute getters are single buckets, not running totals.
     * Returns `null` when the status is not available - see the class comment.
     */
    fun singleBucket(bucketsBack: Int): Int? {
        val current = snapshot ?: return null
        if (!hasData()) return null
        return current.buckets[IosStepBuckets.keyOf(nowMs()) - bucketsBack] ?: 0
    }

    /**
     * Steps in the last [bucketCount] buckets, counting the current one. Same rule as Android:
     * a bucket counts when its key is greater than `currentKey - bucketCount`.
     * Returns `null` when the status is not available - see the class comment.
     */
    fun windowSum(bucketCount: Int): Int? {
        val current = snapshot ?: return null
        if (!hasData()) return null
        val oldestExcluded = IosStepBuckets.keyOf(nowMs()) - bucketCount
        var total = 0
        for ((key, steps) in current.buckets) {
            if (key > oldestExcluded) total += steps
        }
        return total
    }

    companion object {

        /**
         * How old the last good refresh may be before the cache is called stale. The refresh runs
         * every minute (see `IosStepsLifecycle`), so 10 minutes means about nine missed refreshes in
         * a row. Short enough that a dead refresh is noticed within one or two loop ticks, long
         * enough that one slow HealthKit answer does not flip the state.
         */
        const val MAX_AGE_MS = 10L * 60L * 1000L
    }
}
