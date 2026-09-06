package app.aaps.plugins.aps.openAPSAIMI.ISF

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the read rule of [DynIsfCache].
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `dd9979ca4d`. These are non-regression locks, not a
 * red-before/green-after proof. The rule they replace lived inline in `OpenAPSAIMIPlugin` on an
 * `android.util.LongSparseArray`, which returns default values under `unitTests.isReturnDefaultValues`,
 * so the old behaviour could not be tested at all. Each test below says what the old rule would have
 * answered.
 *
 * See `docs/adr/0003-dynisf-cache-read-path.md`.
 */
class DynIsfCacheTest {

    private val minute = 60 * 1000L
    private val hour = 60 * minute

    /** A time that is a whole multiple of both the 5-minute and the 30-minute bucket. */
    private val tenAm = 999_999_900_000L

    /**
     * T1 — the incident of 2026-09-01. The old key was `bucketStart + glucose`, so inside one bucket
     * the entry with the highest glucose sorted last and won the read. Here the 180 mg/dL sample is
     * older but would have been served.
     */
    @Test
    fun `newest returns the latest sample even when an earlier one had a higher glucose`() {
        val cache = DynIsfCache()

        cache.put(atMs = tenAm, isfMgdl = 20.0, glucoseMgdl = 180.0)
        cache.put(atMs = tenAm + 20 * minute, isfMgdl = 40.0, glucoseMgdl = 120.0)

        assertEquals(40.0, cache.newest()?.isfMgdl)
        assertEquals(120.0, cache.newest()?.glucoseMgdl)
    }

    /**
     * T2 — the age must come from the sample time. The old code recovered a 30-minute bucket start
     * from the key, so a value written at 10:29 and read at 10:31 was reported as 31 minutes old.
     */
    @Test
    fun `age is measured from the sample time not from a bucket start`() {
        val cache = DynIsfCache(bucketMs = 30 * minute)

        cache.put(atMs = tenAm + 29 * minute, isfMgdl = 25.0, glucoseMgdl = 140.0)
        val readAt = tenAm + 31 * minute

        val sample = cache.newest()
        assertNotNull(sample)
        assertEquals(2 * minute, readAt - sample!!.atMs)
    }

    /** T3 — a fresh write after a long silence must win over the warm-up history. */
    @Test
    fun `a sample written after a long silence supersedes the warm up history`() {
        val cache = DynIsfCache()

        cache.put(atMs = tenAm, isfMgdl = 9.0, glucoseMgdl = 260.0)
        cache.put(atMs = tenAm + 4 * hour, isfMgdl = 33.0, glucoseMgdl = 110.0)

        assertEquals(33.0, cache.newest()?.isfMgdl)
        assertEquals(tenAm + 4 * hour, cache.newest()?.atMs)
    }

    /**
     * T4 — retention drops the oldest entries one by one. The old code called `clear()` above 1000
     * entries, and the next read then fell back to the static profile ISF.
     */
    @Test
    fun `retention evicts old samples instead of emptying the store`() {
        val cache = DynIsfCache(bucketMs = 5 * minute, retentionMs = 24 * hour)
        val step = 48 * hour / 400

        for (i in 0 until 400) {
            cache.put(atMs = tenAm + i * step, isfMgdl = 20.0 + i % 7, glucoseMgdl = 120.0)
            assertTrue(cache.newest() != null, "store must never be empty after a write")
        }

        // At most one entry per 5-minute bucket of the 24-hour window, plus the newest one.
        assertTrue(cache.size() <= 24 * 60 / 5 + 1, "size was ${cache.size()}")
        assertTrue(cache.size() < 400)
        assertEquals(tenAm + 399 * step, cache.newest()?.atMs)
    }

    /** T5 — the 24-hour average must only see the window it was asked for. */
    @Test
    fun `averageSince ignores samples outside the window`() {
        val cache = DynIsfCache()

        cache.put(atMs = tenAm, isfMgdl = 10.0, glucoseMgdl = 200.0)
        cache.put(atMs = tenAm + 1 * hour, isfMgdl = 20.0, glucoseMgdl = 150.0)
        cache.put(atMs = tenAm + 2 * hour, isfMgdl = 30.0, glucoseMgdl = 120.0)

        val average = cache.averageSince(fromMs = tenAm + 1 * hour, toMs = tenAm + 2 * hour)

        assertEquals(25.0, average)
        assertNull(cache.averageSince(fromMs = tenAm + 10 * hour, toMs = tenAm + 11 * hour))
    }

    /**
     * T6 — `Math.floorMod` keeps the buckets right across the epoch. With `%` the remainder of a
     * negative time is negative, so a sample one minute before the epoch and a sample one minute
     * after it both land on bucket 0 and the second write erases the first.
     */
    @Test
    fun `keys stay ordered for a timestamp before the epoch`() {
        val cache = DynIsfCache()

        cache.put(atMs = -1 * minute, isfMgdl = 10.0, glucoseMgdl = 200.0)
        cache.put(atMs = 1 * minute, isfMgdl = 20.0, glucoseMgdl = 100.0)

        assertEquals(2, cache.size())
        assertEquals(20.0, cache.newest()?.isfMgdl)

        // Same two samples, written in the other order: the later time must still win.
        val reversed = DynIsfCache()
        reversed.put(atMs = 1 * minute, isfMgdl = 20.0, glucoseMgdl = 100.0)
        reversed.put(atMs = -1 * minute, isfMgdl = 10.0, glucoseMgdl = 200.0)

        assertEquals(2, reversed.size())
        assertEquals(20.0, reversed.newest()?.isfMgdl)
    }

    /**
     * T7 — two writers can aim at the same bucket out of order. At start up the warm up reloads the
     * database history on a background scope while the first live tick already computes a fresh
     * value. If the last history row falls in the same 5-minute bucket as that fresh value, a plain
     * "last writer wins" would let the older row erase the newer one.
     */
    @Test
    fun `an older write does not replace a newer one in the same bucket`() {
        val cache = DynIsfCache()

        cache.put(atMs = tenAm + 3 * minute, isfMgdl = 40.0, glucoseMgdl = 120.0)
        cache.put(atMs = tenAm + 1 * minute, isfMgdl = 20.0, glucoseMgdl = 180.0)

        assertEquals(40.0, cache.newest()?.isfMgdl)
        assertEquals(tenAm + 3 * minute, cache.newest()?.atMs)
    }
}
