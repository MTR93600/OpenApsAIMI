package app.aaps.plugins.aps.openAPSAIMI.ISF

import java.util.TreeMap

/**
 * Small time-keyed store for the dynamic ISF values the loop computes.
 *
 * ## Why the key is only a time
 *
 * The store this class replaces was keyed on `bucketStart + glucose`, and it read the newest value
 * as "the last key". Keys are kept sorted, so that read returned the entry of the **highest glucose
 * of the newest bucket**, not the most recent entry. While glucose was falling, the value stayed
 * pinned to the one computed at the peak of the bucket. A time and a glucose must never be added
 * into one scalar that is then used for ordering.
 *
 * Here the key is the start of the time bucket only, so "last key" means "most recent". The exact
 * sample time is kept inside [Sample], so the age of a value is measured from when it was written,
 * not from the start of its bucket. The glucose it was computed for is kept as plain data and never
 * takes part in ordering.
 *
 * Writes are ordered, not "last writer wins": inside one bucket, an older write never replaces a
 * newer one. Two writers really do aim at the same bucket out of order at start up.
 *
 * Old entries are dropped one by one when they fall out of the retention window. The store is never
 * emptied in one go: a wholesale `clear()` made the very next read fall back to the static profile
 * ISF, which is a different quantity.
 *
 * This class is plain Kotlin on purpose. It uses no Android collection, so it can be unit tested
 * on the JVM.
 *
 * See `docs/adr/0003-dynisf-cache-read-path.md`.
 *
 * @param bucketMs width of one time bucket. Two writes inside the same bucket share a key, so the
 *   later one replaces the earlier one.
 * @param retentionMs how long a sample is kept before it is evicted.
 */
class DynIsfCache(
    private val bucketMs: Long = 5 * 60 * 1000L,
    private val retentionMs: Long = 24 * 60 * 60 * 1000L,
) {

    /**
     * One stored sensitivity value.
     *
     * @param isfMgdl the sensitivity, mg/dL per U.
     * @param glucoseMgdl the glucose this value was computed for, when it is known.
     * @param atMs the exact time the value was written. Ages are measured from this, not from the
     *   bucket start.
     */
    data class Sample(val isfMgdl: Double, val glucoseMgdl: Double?, val atMs: Long)

    private val samples = TreeMap<Long, Sample>()

    /**
     * Stores one value and drops what is older than the retention window.
     *
     * A value that is not finite or not above zero is ignored: it cannot be a sensitivity.
     */
    @Synchronized
    fun put(atMs: Long, isfMgdl: Double, glucoseMgdl: Double?) {
        if (!isfMgdl.isFinite() || isfMgdl <= 0.0) return
        // Math.floorMod, not %: a negative time would give a negative remainder with %, and the
        // resulting key would sort after a later time.
        val key = atMs - Math.floorMod(atMs, bucketMs)
        // Two writers can aim at the same bucket out of order. At start up the warm up reloads the
        // database history on a background scope while the first live tick already computes a fresh
        // value. With plain "last writer wins", a history row landing in the same bucket would erase
        // that fresh value. Only a write that is at least as recent may replace what is there.
        val existing = samples[key]
        if (existing == null || existing.atMs <= atMs) {
            samples[key] = Sample(isfMgdl = isfMgdl, glucoseMgdl = glucoseMgdl, atMs = atMs)
        }
        // Eviction runs on every call, accepted or refused, and its window is measured from the
        // newest sample the store holds rather than from this call's time. A late write then cannot
        // drop entries that are still inside the window, and cannot leave the store unbounded
        // either.
        val newestAtMs = samples.lastEntry()?.value?.atMs ?: return
        val oldestKept = newestAtMs - retentionMs
        while (samples.isNotEmpty() && samples.firstKey() < oldestKept) {
            samples.pollFirstEntry()
        }
    }

    /** The most recent sample, or `null` when nothing is stored. Never chosen by glucose. */
    @Synchronized
    fun newest(): Sample? = samples.lastEntry()?.value

    /**
     * Mean sensitivity of the samples written in `[fromMs, toMs]`.
     *
     * Returns `null` when the window holds no sample.
     */
    @Synchronized
    fun averageSince(fromMs: Long, toMs: Long): Double? {
        var count = 0
        var sum = 0.0
        for (sample in samples.values) {
            if (sample.atMs in fromMs..toMs) {
                count++
                sum += sample.isfMgdl
            }
        }
        return if (count == 0) null else sum / count
    }

    /** How many samples are stored. */
    @Synchronized
    fun size(): Int = samples.size

    /** True when nothing is stored. */
    @Synchronized
    fun isEmpty(): Boolean = samples.isEmpty()
}
