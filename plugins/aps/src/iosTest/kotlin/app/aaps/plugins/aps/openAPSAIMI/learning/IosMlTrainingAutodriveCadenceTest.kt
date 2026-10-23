package app.aaps.plugins.aps.openAPSAIMI.learning

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Autodrive jobs run inside the hourly training task, on Android's 6 h and 24 h cadence. */
class IosMlTrainingAutodriveCadenceTest {

    private val hour = 60L * 60L * 1000L

    @Test
    fun cadenceMatchesAndroidWorkers() {
        assertEquals(6 * hour, IosMlTrainingScheduler.BACKFILL_INTERVAL_MS)
        assertEquals(24 * hour, IosMlTrainingScheduler.ATTENTION_INTERVAL_MS)
    }

    @Test
    fun aJobThatNeverRanIsDue() {
        assertTrue(IosMlTrainingScheduler.isDue(null, 1_000L, 6 * hour))
    }

    @Test
    fun aJobIsDueOnlyAfterItsInterval() {
        val last = 100 * hour
        assertFalse(IosMlTrainingScheduler.isDue(last, last + 5 * hour, 6 * hour))
        assertTrue(IosMlTrainingScheduler.isDue(last, last + 6 * hour, 6 * hour))
    }

    @Test
    fun aClockThatMovedBackMakesTheJobDue() {
        assertTrue(IosMlTrainingScheduler.isDue(100 * hour, 99 * hour, 24 * hour))
    }
}
