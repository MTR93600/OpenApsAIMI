package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.HR
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Pins the iOS heart rate sync to the Android Health Connect sync: same switch, same 4 hour
 * cadence, same 24 hour look-back, same row shape. No HealthKit here, the source is a fake.
 */
class IosHeartRateSyncTest {

    private val startMs = 1_800_000_000_000L
    private var now = startMs

    private var enabled = true
    private var storedLastSync = 0L
    private var reading: IosHeartRateReading? = null
    private val asked = mutableListOf<Pair<Long, Long>>()
    private val written = mutableListOf<HR>()
    private var failWrite = false

    private val source = object : IosHeartRateSource {
        override suspend fun fetchNewestHeartRate(sinceMs: Long, nowMs: Long): IosHeartRateReading? {
            asked += sinceMs to nowMs
            return reading
        }
    }

    private val sync = IosHeartRateSync(
        source = source,
        write = { rows ->
            if (failWrite) error("disk full")
            written += rows
        },
        isEnabled = { enabled },
        lastSyncMs = { storedLastSync },
        storeLastSyncMs = { storedLastSync = it },
        log = { _, _ -> },
    )

    @Test
    fun `writes one row shaped like the android health connect row`() = runBlocking {
        reading = IosHeartRateReading(timestampMs = now - 120_000L, beatsPerMinute = 71.0)
        val row = assertNotNull(sync.runIfDue(now))

        assertEquals(listOf(row), written)
        assertEquals(now - 120_000L, row.timestamp)
        assertEquals(71.0, row.beatsPerMinute)
        assertEquals(5L * 60L * 1000L, row.duration)
        assertEquals(UnifiedActivityProviderMTR.HEALTHKIT_DEVICE, row.device)
        assertEquals(0L, row.id)
        // Android reads the last 24 hours.
        assertEquals(listOf((now - 24L * 60L * 60L * 1000L) to now), asked)
        assertEquals(now, storedLastSync)
    }

    @Test
    fun `runs every four hours and not more often`() = runBlocking {
        reading = IosHeartRateReading(now, 70.0)
        sync.runIfDue(now)
        now += IosHeartRateSync.SYNC_INTERVAL_MS - 60_000L
        reading = IosHeartRateReading(now, 80.0)
        assertNull(sync.runIfDue(now))
        assertEquals(1, asked.size)

        now += 60_000L
        assertNotNull(sync.runIfDue(now))
        assertEquals(2, asked.size)
        assertEquals(listOf(70.0, 80.0), written.map { it.beatsPerMinute })
    }

    @Test
    fun `the stored last run time survives a restart`() = runBlocking {
        // Like WorkManager keeping its period: a new process does not run at once.
        storedLastSync = now - 60 * 60_000L
        reading = IosHeartRateReading(now, 70.0)
        assertNull(sync.runIfDue(now))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `switched off means no read and no row`() = runBlocking {
        enabled = false
        reading = IosHeartRateReading(now, 70.0)
        assertNull(sync.runIfDue(now))
        assertTrue(asked.isEmpty())
        assertTrue(written.isEmpty())
    }

    @Test
    fun `no heart rate in healthkit writes nothing and waits for the next period`() = runBlocking {
        // A refused read and a phone with no watch look the same: no sample. No row is invented.
        reading = null
        assertNull(sync.runIfDue(now))
        assertTrue(written.isEmpty())
        assertEquals(now, storedLastSync)
        now += 60_000L
        assertNull(sync.runIfDue(now))
        assertEquals(1, asked.size)
    }

    @Test
    fun `the same sample is not written twice`() = runBlocking {
        reading = IosHeartRateReading(now - 1_000L, 66.0)
        sync.runIfDue(now)
        now += IosHeartRateSync.SYNC_INTERVAL_MS
        assertNull(sync.runIfDue(now))
        assertEquals(1, written.size)
    }

    @Test
    fun `a failed write is not counted as written`() = runBlocking {
        reading = IosHeartRateReading(now - 1_000L, 66.0)
        failWrite = true
        assertNull(sync.runIfDue(now))
        failWrite = false
        now += IosHeartRateSync.SYNC_INTERVAL_MS
        assertNotNull(sync.runIfDue(now))
        assertEquals(1, written.size)
    }

    @Test
    fun `a clock moved back makes the run due`() {
        storedLastSync = now + 60 * 60_000L
        assertTrue(sync.isDue(now))
    }
}
