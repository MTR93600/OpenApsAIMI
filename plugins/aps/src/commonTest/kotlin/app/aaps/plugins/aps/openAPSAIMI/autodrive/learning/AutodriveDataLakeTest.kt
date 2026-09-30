package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What [AutodriveDataLake] actually puts in `autodrive_dataset.csv`.
 *
 * Two things are pinned here, and they are the two the multiplatform port could have broken
 * silently:
 *
 * - the **date column**, which used to be written with the device's own calendar
 * - the **carry-forward buffer**, which is a `kotlin.collections.ArrayDeque` now and was a
 *   `java.util.ArrayDeque` before. Order and eviction have to be identical, because the buffer holds
 *   training rows that the classifier later learns from in time order.
 *
 * In `commonTest`, so the same assertions run on the JVM, on Android and on the iOS simulator.
 */
@OptIn(ExperimentalAtomicApi::class)
class AutodriveDataLakeTest {

    private val storage = InMemoryAimiStorage()
    private val lake = AutodriveDataLake(NoOpAapsLogger, storage)
    private val path get() = storage.file(AutodriveDataLake.FILE_NAME)

    private fun state(bg: Double = 120.0) = AutoDriveState(
        bg = bg,
        bgVelocity = 0.5,
        iob = 1.0,
        physiologicalStressMask = doubleArrayOf(),
    )

    private fun rows(): List<String> = storage.readLines(path).drop(1)

    private fun columnsOfSingleRow(): List<String> {
        val written = rows()
        assertEquals(1, written.size, "expected exactly one row, got $written")
        return written.single().split(",")
    }

    // ---- the date column ------------------------------------------------------------------------

    /**
     * The corpus date is the Gregorian wall clock, whatever calendar the phone is set to.
     *
     * The old writer was `SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())`, and
     * `getDefault()` chooses the **calendar**, not just the wording: on a Thai device the same
     * instant came out as `2569-…` and on a Saudi one in Arabic-Indic digits. The expectation below
     * is built from `kotlinx.datetime` directly rather than from the production formatter, so it
     * checks the value rather than agreeing with the code under test.
     */
    @Test
    fun dateColumnIsTheGregorianWallClockOfTheTimestamp() {
        val epochMs = 1790000000000L

        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = epochMs)

        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
        // `LocalDate.toString()` is ISO-8601, so always `YYYY-MM-DD` on the proleptic Gregorian
        // calendar. The time is built from the plain Int fields, because the ISO time form drops the
        // seconds when they are zero and the corpus column never does.
        val expected = buildString {
            append(local.date.toString()); append(' ')
            append(local.hour.toString().padStart(2, '0')); append(':')
            append(local.minute.toString().padStart(2, '0')); append(':')
            append(local.second.toString().padStart(2, '0'))
        }
        assertEquals(expected, columnsOfSingleRow()[AutodriveDatasetSchema.IDX_DATE])
    }

    /** ASCII digits only. A device that renders Arabic-Indic digits must not reach the corpus. */
    @Test
    fun dateColumnIsAsciiDigitsAndSeparators() {
        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1790000000000L)

        val date = columnsOfSingleRow()[AutodriveDatasetSchema.IDX_DATE]
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$""").matches(date),
            "the date column must be plain ASCII yyyy-MM-dd HH:mm:ss but was '$date'",
        )
    }

    /** Column 0 is what every reader actually keys off, so it stays the raw epoch. */
    @Test
    fun timestampColumnStaysTheRawEpoch() {
        val epochMs = 1790000000000L

        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = epochMs)

        assertEquals(epochMs.toString(), columnsOfSingleRow()[AutodriveDatasetSchema.IDX_TIMESTAMP])
    }

    @Test
    fun theHeaderIsWrittenOnceOnFirstUse() {
        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1L)
        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 2L)

        val lines = storage.readLines(path)
        assertEquals(AutodriveDatasetSchema.HEADER, lines.first())
        assertEquals(3, lines.size)
    }

    // ---- the carry-forward buffer ---------------------------------------------------------------

    /**
     * Runs [whileBusy] while another thread holds the dataset, so every write inside it is deferred.
     *
     * A real second thread is the only way: the lock is reentrant, so taking it on this thread would
     * let the data lake's own attempt straight through and nothing would be carried forward.
     */
    private fun withDatasetHeldElsewhere(whileBusy: () -> Unit) = runBlocking {
        val held = CompletableDeferred<Unit>()
        val release = AtomicBoolean(false)
        val holder = launch(Dispatchers.Default) {
            AutodriveDatasetLock.withDataset {
                held.complete(Unit)
                @Suppress("ControlFlowWithEmptyBody")
                while (!release.load()) {
                    // Holding the dataset, exactly as the backfiller does across its transaction.
                }
            }
        }
        withTimeout(10.seconds) { held.await() }
        try {
            whileBusy()
        } finally {
            release.store(true)
            holder.join()
        }
    }

    /**
     * Carried rows are appended oldest first, ahead of the row that finally got through.
     *
     * This is the `pollFirst` to `removeFirstOrNull` substitution. Both take the **head**, so the
     * buffer stays first-in-first-out; a version that took the tail would reverse the corpus and the
     * chronological holdout split in the trainer would silently stop being chronological.
     */
    @Test
    fun deferredRowsAreAppendedOldestFirst() {
        withDatasetHeldElsewhere {
            lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 1000L)
            lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 2000L)
            lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 3000L)
        }
        assertEquals(3, lake.deferredRowCount)
        assertEquals(3, lake.contendedWriteCount)
        assertEquals(0, storage.readLines(path).size, "nothing may be written while the dataset is busy")

        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 4000L)

        assertEquals(
            listOf("1000", "2000", "3000", "4000"),
            rows().map { it.split(",")[AutodriveDatasetSchema.IDX_TIMESTAMP] },
        )
        assertEquals(0, lake.deferredRowCount, "the buffer is emptied once the bytes are out")
    }

    /**
     * Past the cap the **oldest** row is dropped, not the newest.
     *
     * The newest row describes the physiology closest to now, so evicting from the head is the
     * deliberate choice. `java.util.ArrayDeque.pollFirst` and `kotlin.collections.ArrayDeque`'s
     * `removeFirstOrNull` agree on that, and this is what would catch it if they did not.
     */
    @Test
    fun theOldestRowIsDroppedWhenTheBufferIsFull() {
        val overflow = 2
        val total = AutodriveDataLake.MAX_DEFERRED_ROWS + overflow
        withDatasetHeldElsewhere {
            repeat(total) { i ->
                lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = (i + 1) * 1000L)
            }
        }
        assertEquals(overflow, lake.droppedRowCount)
        assertEquals(AutodriveDataLake.MAX_DEFERRED_ROWS, lake.deferredRowCount)

        lake.recordSnapshot(state(), null, null, engaged = true, currentTimestamp = 999_000L)

        val written = rows().map { it.split(",")[AutodriveDatasetSchema.IDX_TIMESTAMP] }
        // The first `overflow` timestamps are gone; the rest survive in order, newest row last.
        val expected = (overflow + 1..total).map { (it * 1000L).toString() } + "999000"
        assertEquals(expected, written)
    }
}
