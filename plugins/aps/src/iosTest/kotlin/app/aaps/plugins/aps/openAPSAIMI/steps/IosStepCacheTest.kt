package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.plugins.aps.openAPSAIMI.StepService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Pins the iOS step cache. No test here talks to HealthKit, because the simulator has no real
 * health data. They cover the cache, the windows and the unknown state only.
 */
class IosStepCacheTest {

    private val startMs = 1_800_000_000_000L
    private var now = startMs
    private val cache = IosStepCache { now }
    private val key get() = IosStepBuckets.keyOf(now)

    private val original = StepService.cache

    @AfterTest
    fun restore() {
        StepService.cache = original
        StepService.refreshRequest = null
    }

    private fun feed(vararg pairs: Pair<Int, Int>) {
        cache.update(pairs.associate { (back, steps) -> (key - back) to steps }, now)
    }

    @Test
    fun `never refreshed is unknown and not available`() {
        assertEquals(IosStepDataStatus.NEVER_REFRESHED, cache.status())
        assertFalse(cache.hasData())
        assertEquals(null, cache.lastRefreshMs())
        assertEquals(0, cache.singleBucket(1))
        assertEquals(0, cache.windowSum(34))
    }

    @Test
    fun `getters follow the data and are not a constant zero`() {
        feed(1 to 120, 2 to 30, 3 to 7, 5 to 100, 11 to 1000, 33 to 5000, 34 to 99999)
        assertTrue(cache.hasData())
        assertEquals(120, cache.singleBucket(1))
        assertEquals(30, cache.singleBucket(2))
        assertEquals(7, cache.singleBucket(3))
        // 6 buckets: current and 5 back, so 1,2,3,5 count and 11 does not.
        assertEquals(120 + 30 + 7 + 100, cache.windowSum(6))
        assertEquals(120 + 30 + 7 + 100 + 1000, cache.windowSum(12))
        // 34 buckets reach back to 33 but not 34.
        assertEquals(120 + 30 + 7 + 100 + 1000 + 5000, cache.windowSum(34))
    }

    @Test
    fun `answers change when the data changes`() {
        feed(1 to 10)
        val first = cache.singleBucket(1)
        feed(1 to 400)
        assertNotEquals(first, cache.singleBucket(1))
        assertEquals(400, cache.singleBucket(1))
    }

    @Test
    fun `old buckets leave the window as time moves`() {
        feed(1 to 50)
        val oldBuckets = mapOf(key - 1 to 50)
        now += 8 * 60_000L
        assertEquals(50, cache.windowSum(6))
        // 40 minutes later, a good refresh that still holds the old bucket: it is outside 6 buckets.
        now += 32 * 60_000L
        cache.update(oldBuckets, now)
        assertEquals(0, cache.windowSum(6))
        assertEquals(50, cache.windowSum(12))
    }

    @Test
    fun `a stale cache is unknown and not available`() {
        feed(1 to 80)
        assertEquals(IosStepDataStatus.AVAILABLE, cache.status())
        now += IosStepCache.MAX_AGE_MS + 1
        assertEquals(IosStepDataStatus.STALE, cache.status())
        assertFalse(cache.hasData())
        assertEquals(0, cache.singleBucket(1))
    }

    @Test
    fun `an empty good refresh is a real zero and not unknown`() {
        cache.update(emptyMap(), now)
        assertEquals(IosStepDataStatus.AVAILABLE, cache.status())
        assertTrue(cache.hasData())
        assertEquals(0, cache.windowSum(12))
    }

    @Test
    fun `clear goes back to never refreshed`() {
        feed(1 to 80)
        cache.clear()
        assertEquals(IosStepDataStatus.NEVER_REFRESHED, cache.status())
    }

    @Test
    fun `bucket keys match the android rounding`() {
        val five = IosStepBuckets.BUCKET_MS
        assertEquals(10L, IosStepBuckets.keyOf(10 * five))
        assertEquals(10L, IosStepBuckets.keyOf(10 * five + five / 2 - 1))
        assertEquals(11L, IosStepBuckets.keyOf(10 * five + five / 2))
        assertEquals(10L, IosStepBuckets.keyOf(IosStepBuckets.startOfKeyMs(10)))
        assertEquals(9L, IosStepBuckets.keyOf(IosStepBuckets.startOfKeyMs(10) - 1))
    }

    @Test
    fun `step service getters read the cache and expose the status`() {
        StepService.cache = cache
        assertEquals(IosStepDataStatus.NEVER_REFRESHED, StepService.dataStatus)
        assertEquals(0, StepService.getRecentStepCount5Min())
        feed(1 to 120, 2 to 30, 3 to 7, 8 to 200, 20 to 3000)
        assertEquals(IosStepDataStatus.AVAILABLE, StepService.dataStatus)
        assertEquals(120, StepService.getRecentStepCount5Min())
        assertEquals(30, StepService.getRecentStepCount10Min())
        assertEquals(7, StepService.getRecentStepCount15Min())
        assertEquals(157, StepService.getRecentStepCount30Min())
        assertEquals(357, StepService.getRecentStepCount60Min())
        assertEquals(3357, StepService.getRecentStepCount180Min())
    }

    @Test
    fun `a getter asks for a refresh only when the cache is unusable`() {
        StepService.cache = cache
        var asked = 0
        StepService.refreshRequest = { asked++ }
        StepService.getRecentStepCount5Min()
        assertEquals(1, asked)
        feed(1 to 5)
        StepService.getRecentStepCount5Min()
        assertEquals(1, asked)
    }

    @Test
    fun `a failed refresh keeps unknown and a good one makes it available`() = runBlocking {
        val logs = mutableListOf<String>()
        var answer: Map<Long, Int>? = null
        val source = object : IosStepBucketSource {
            override suspend fun fetchStepBuckets(nowMs: Long): Map<Long, Int>? = answer
        }
        refreshStepCache(source, cache, now) { _, text -> logs += text }
        assertEquals(IosStepDataStatus.NEVER_REFRESHED, cache.status())
        assertTrue(logs.single().contains("means unknown"))

        answer = mapOf(key - 1 to 42)
        refreshStepCache(source, cache, now) { _, text -> logs += text }
        assertEquals(IosStepDataStatus.AVAILABLE, cache.status())
        assertEquals(42, cache.singleBucket(1))

        // A later failure must not renew the time of the old data.
        answer = null
        now += IosStepCache.MAX_AGE_MS + 1
        refreshStepCache(source, cache, now) { _, text -> logs += text }
        assertEquals(IosStepDataStatus.STALE, cache.status())
    }
}
