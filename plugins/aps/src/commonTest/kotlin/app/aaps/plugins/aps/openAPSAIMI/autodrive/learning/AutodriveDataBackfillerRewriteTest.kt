package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.TherapyNotePersistence
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where [AutodriveDataBackfiller] puts the training corpus, and what it puts in it.
 *
 * The backfiller used to hold a `java.io.File` and rename one file over another itself. It now asks
 * [AimiStorage] to do the rewrite, and the two things that could break silently in that move are
 * pinned here:
 *
 * - **the path**. The corpus is the training data. A file written one directory to the side is not
 *   an error anyone sees; it is training data that quietly stops arriving. So the test names the
 *   file it expects and asserts on that exact name, not on "whatever the backfiller wrote".
 * - **the content**. Same header, same rows, same order, and a line break after the last row, which
 *   is what `AutodriveDataLake` assumes when it appends the next one.
 *
 * In `commonTest`, so the same assertions run on the JVM, on Android and on the iOS simulator.
 */
class AutodriveDataBackfillerRewriteTest {

    private val storage = InMemoryAimiStorage()

    /** The one file the corpus lives in. Written out by hand: the point is to pin the name. */
    private val corpusPath = storage.file("autodrive_dataset.csv")

    /** The scratch file the rewrite builds in. It must never survive a finished pass. */
    private val scratchPath = storage.file("autodrive_dataset_tmp.csv")

    private fun backfiller(readings: List<GV> = emptyList()) = AutodriveDataBackfiller(
        aapsLogger = NoOpAapsLogger,
        storage = storage,
        persistenceLayer = TherapyNotePersistence(emptyList(), readings),
    )

    /** A row of the current layout, with the outcome columns already filled. */
    private fun labelledRow(timestampMs: Long, bg: Double): String {
        val cols = MutableList(AutodriveDatasetSchema.COLUMN_COUNT) { "0" }
        cols[AutodriveDatasetSchema.IDX_TIMESTAMP] = timestampMs.toString()
        cols[AutodriveDatasetSchema.IDX_DATE] = "2026-01-01 00:00:00"
        cols[AutodriveDatasetSchema.IDX_BG] = bg.toString()
        cols[AutodriveDatasetSchema.IDX_FUTURE_BG] = "120.0"
        cols[AutodriveDatasetSchema.IDX_ENGAGED] = "1"
        cols[AutodriveDatasetSchema.IDX_SCHEMA_VERSION] = AutodriveDatasetSchema.CURRENT_VERSION.toString()
        return cols.joinToString(",")
    }

    /** A row of the current layout whose outcome columns are still waiting for the CGM window. */
    private fun unlabelledRow(timestampMs: Long, bg: Double): String {
        val cols = labelledRow(timestampMs, bg).split(",").toMutableList()
        cols[AutodriveDatasetSchema.IDX_FUTURE_BG] = ""
        cols[AutodriveDatasetSchema.IDX_HYPO] = ""
        cols[AutodriveDatasetSchema.IDX_HYPER] = ""
        return cols.joinToString(",")
    }

    private fun seed(text: String) {
        storage.writeText(corpusPath, text)
    }

    private fun corpusLines(): List<String> = storage.readLines(corpusPath)

    // ---- the path -------------------------------------------------------------------------------

    /**
     * The rewrite lands on `autodrive_dataset.csv` in the AIMI directory, and nowhere else.
     *
     * The seeded file carries the old 18-column header, which is enough on its own to make the pass
     * rewrite the file.
     */
    @Test
    fun `rewrite lands on the corpus file and leaves no scratch file`() {
        val now = aimiWallClockMs()
        seed("Timestamp_Epoch,Date\n" + labelledRow(now, 120.0) + "\n")

        runBlocking { backfiller().processPendingLines() }

        assertTrue(storage.exists(corpusPath), "the corpus was not written to $corpusPath")
        // The seed carries the old header, so a pass that really reached this file replaced it. Without
        // this the test would also pass when the backfiller wrote nothing at all.
        assertEquals(AutodriveDatasetSchema.HEADER, corpusLines().first(), "the rewrite did not reach $corpusPath")
        assertFalse(storage.exists(scratchPath), "the scratch file $scratchPath survived the pass")
        assertEquals(setOf(corpusPath.value), storage.files.keys, "the pass touched more than the corpus file")
    }

    // ---- the content ----------------------------------------------------------------------------

    /** The header is rewritten from the schema, and the rows keep their order below it. */
    @Test
    fun `rewrite keeps the rows in file order under the canonical header`() {
        val now = aimiWallClockMs()
        val first = labelledRow(now - 3 * 60_000L, 100.0)
        val second = labelledRow(now - 2 * 60_000L, 110.0)
        val third = labelledRow(now - 60_000L, 120.0)
        seed("Timestamp_Epoch,Date\n$first\n$second\n$third\n")

        runBlocking { backfiller().processPendingLines() }

        assertEquals(
            listOf(AutodriveDatasetSchema.HEADER, first, second, third),
            corpusLines(),
        )
    }

    /**
     * The file ends with a line break.
     *
     * `AutodriveDataLake` appends the next row straight onto the end of this file. Without the final
     * break the next tick's row would be glued onto the last one and both would be lost.
     */
    @Test
    fun `rewrite ends the file with a line break`() {
        val now = aimiWallClockMs()
        seed("Timestamp_Epoch,Date\n" + labelledRow(now, 120.0) + "\n")

        runBlocking { backfiller().processPendingLines() }

        assertTrue(storage.readText(corpusPath)!!.endsWith("\n"), "the corpus does not end with a line break")
    }

    /** Rows older than the retention window are dropped, the rest stay. */
    @Test
    fun `rewrite drops rows past the retention window`() {
        val now = aimiWallClockMs()
        val old = labelledRow(now - 61L * 24 * 60 * 60 * 1000L, 100.0)
        val recent = labelledRow(now - 60_000L, 120.0)
        seed(AutodriveDatasetSchema.HEADER + "\n$old\n$recent\n")

        runBlocking { backfiller().processPendingLines() }

        assertEquals(listOf(AutodriveDatasetSchema.HEADER, recent), corpusLines())
    }

    /** A row whose outcome window has passed is labelled from the CGM history and written back. */
    @Test
    fun `rewrite fills the outcome columns from the glucose history`() {
        val now = aimiWallClockMs()
        val rowTime = now - 2 * 60 * 60 * 1000L
        seed(AutodriveDatasetSchema.HEADER + "\n" + unlabelledRow(rowTime, 120.0) + "\n")

        val readings = listOf(
            glucose(rowTime + 30 * 60 * 1000L, 65.0),
            glucose(rowTime + 50 * 60 * 1000L, 190.0),
        )
        val filled = runBlocking { backfiller(readings).processPendingLines() }

        assertEquals(1, filled)
        val cols = corpusLines()[1].split(",")
        assertEquals("190.0", cols[AutodriveDatasetSchema.IDX_FUTURE_BG])
        assertEquals("1", cols[AutodriveDatasetSchema.IDX_HYPO])
        assertEquals("1", cols[AutodriveDatasetSchema.IDX_HYPER])
    }

    // ---- the training gate ----------------------------------------------------------------------

    /** The gate reads the corpus file and counts only the rows whose outcome is known. */
    @Test
    fun `training gate counts the labelled rows of the corpus file`() {
        val now = aimiWallClockMs()
        seed(
            AutodriveDatasetSchema.HEADER + "\n" +
                labelledRow(now - 2 * 60_000L, 100.0) + "\n" +
                unlabelledRow(now - 60_000L, 110.0) + "\n" +
                labelledRow(now, 120.0) + "\n"
        )

        assertTrue(backfiller().isDatasetReadyForTraining(minimumValidLines = 2))
        assertFalse(backfiller().isDatasetReadyForTraining(minimumValidLines = 3))
    }

    private fun glucose(timestamp: Long, value: Double) = GV(
        timestamp = timestamp,
        value = value,
        raw = value,
        noise = 0.0,
        trendArrow = TrendArrow.FLAT,
        sourceSensor = SourceSensor.UNKNOWN,
    )
}
