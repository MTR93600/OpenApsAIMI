package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The corpus a real install carries was written by three different builds.
 *
 * Rows written before the outcome labels came from CGM keep a `Hypo_Occurred = 0` that means "the
 * old pass gave up at a coverage gap", and they have no `Engaged` field, which the trainer used to
 * read as `1.0`. Measured on the 17 068-row production corpus: **every** row is of that kind, and
 * **73.2 %** of the labelled ones have an outcome window the CSV does not cover continuously. So the
 * `Engaged` feature was a perfect stand-in for "labelled by the broken method", and fitting a weight
 * to it meant fitting a weight to the bug.
 */
class AutodriveDatasetSchemaMigrationTest {

    @TempDir
    lateinit var dir: File

    private val aapsLogger = mockk<AAPSLogger>(relaxed = true)
    private val persistenceLayer = mockk<PersistenceLayer>()
    private val storage = mockk<AimiStorageHelper>()

    private fun backfiller(): AutodriveDataBackfiller {
        every { storage.getAimiFile(any<String>()) } answers { File(dir, firstArg<String>()) }
        return AutodriveDataBackfiller(mockk<Context>(relaxed = true), aapsLogger, storage, persistenceLayer)
    }

    private fun datasetFile() = File(dir, AutodriveDataLake.FILE_NAME)

    /** CGM values every 5 minutes over [count] samples, all at [value]. */
    private fun readings(startMs: Long, count: Int, value: Double) = (0 until count).map { i ->
        GV(
            timestamp = startMs + i * 300_000L, value = value, raw = null,
            noise = null, trendArrow = TrendArrow.FLAT, sourceSensor = SourceSensor.UNKNOWN,
        )
    }

    private fun stubGlucose(list: List<GV>) {
        coEvery { persistenceLayer.getBgReadingsDataFromTimeToTime(any(), any(), any()) } returns list
    }

    private val legacyHeader =
        "Timestamp_Epoch,Date,BG_Current,BG_Velocity,IOB_Net,COB,Estimated_SI,Estimated_Ra,Patient_Weight," +
            "Physio_Mask,MPC_Raw_SMB,MPC_Raw_TBR,CBF_Safe_SMB,CBF_Safe_TBR,CBF_Intervention," +
            "Future_BG_45m,Hypo_Occurred,Hyper_Occurred"

    /** An 18-column row: no `Engaged`, outcome columns filled by the censored pass. */
    private fun legacyRow(ts: Long, bg: Double, futureBg: String, hypo: String) =
        "$ts,2026-06-01 10:00:00,$bg,0.0,1.5,0.0,0.1,0.5,70.0,0,0.0,0.8,0.0,0.8,0,$futureBg,$hypo,0"

    @Test
    fun `the stale 18-column header is replaced, not echoed back`() = runBlocking {
        val now = System.currentTimeMillis()
        val ts = now - 3 * 3_600_000L
        datasetFile().writeText(legacyHeader + "\n" + legacyRow(ts, 150.0, "150.0", "0") + "\n")
        stubGlucose(readings(ts, 13, 150.0))

        backfiller().processPendingLines()

        val lines = datasetFile().readLines()
        assertThat(lines[0]).isEqualTo(AutodriveDatasetSchema.HEADER)
        assertThat(lines[0].split(",")).hasSize(AutodriveDatasetSchema.COLUMN_COUNT)
    }

    @Test
    fun `a legacy row loses its censored label and is re-derived from CGM`() = runBlocking {
        val now = System.currentTimeMillis()
        val ts = now - 3 * 3_600_000L
        // The old pass wrote "no hypo". The CGM says otherwise.
        datasetFile().writeText(legacyHeader + "\n" + legacyRow(ts, 150.0, "150.0", "0") + "\n")
        stubGlucose(readings(ts, 13, 60.0))

        backfiller().processPendingLines()

        val cols = datasetFile().readLines()[1].split(",")
        assertThat(cols).hasSize(AutodriveDatasetSchema.COLUMN_COUNT)
        assertThat(cols[AutodriveDatasetSchema.IDX_HYPO]).isEqualTo("1")
        assertThat(cols[AutodriveDatasetSchema.IDX_SCHEMA_VERSION])
            .isEqualTo(AutodriveDatasetSchema.CURRENT_VERSION.toString())
        // Pre-Engaged rows were only written on engaged ticks, so 1 is the truthful value.
        assertThat(cols[AutodriveDatasetSchema.IDX_ENGAGED]).isEqualTo("1")
    }

    @Test
    fun `a legacy row with no CGM coverage stays unlabelled rather than keeping a wrong label`() = runBlocking {
        val now = System.currentTimeMillis()
        val ts = now - 3 * 3_600_000L
        datasetFile().writeText(legacyHeader + "\n" + legacyRow(ts, 150.0, "150.0", "0") + "\n")
        stubGlucose(emptyList())

        backfiller().processPendingLines()

        val cols = datasetFile().readLines()[1].split(",")
        assertThat(cols[AutodriveDatasetSchema.IDX_FUTURE_BG]).isEmpty()
        assertThat(cols[AutodriveDatasetSchema.IDX_HYPO]).isEmpty()
        assertThat(cols[AutodriveDatasetSchema.IDX_SCHEMA_VERSION])
            .isEqualTo(AutodriveDatasetSchema.CURRENT_VERSION.toString())
    }

    @Test
    fun `a 19-column row keeps its CGM-derived label and only gains the version stamp`() = runBlocking {
        val now = System.currentTimeMillis()
        val ts = now - 3 * 3_600_000L
        val row = legacyRow(ts, 150.0, "150.0", "1") + ",0"
        datasetFile().writeText(legacyHeader + ",Engaged\n" + row + "\n")
        // Contradicting CGM: it must not be consulted, the row is already labelled.
        stubGlucose(readings(ts, 13, 200.0))

        backfiller().processPendingLines()

        val cols = datasetFile().readLines()[1].split(",")
        assertThat(cols[AutodriveDatasetSchema.IDX_HYPO]).isEqualTo("1")
        assertThat(cols[AutodriveDatasetSchema.IDX_ENGAGED]).isEqualTo("0")
        assertThat(cols[AutodriveDatasetSchema.IDX_SCHEMA_VERSION])
            .isEqualTo(AutodriveDatasetSchema.CURRENT_VERSION.toString())
    }

    @Test
    fun `a row already on the current schema is left alone`() = runBlocking {
        val now = System.currentTimeMillis()
        val ts = now - 3 * 3_600_000L
        val row = legacyRow(ts, 150.0, "150.0", "1") + ",0," + AutodriveDatasetSchema.CURRENT_VERSION
        datasetFile().writeText(AutodriveDatasetSchema.HEADER + "\n" + row + "\n")
        stubGlucose(readings(ts, 13, 200.0))

        backfiller().processPendingLines()

        assertThat(datasetFile().readLines()[1]).isEqualTo(row)
    }

    @Test
    fun `the trainer refuses rows written before the labels came from CGM`() {
        val now = System.currentTimeMillis()
        every { storage.getAimiFile(any<String>()) } answers { File(dir, firstArg<String>()) }
        every { storage.saveFileSafe(any(), any()) } answers {
            firstArg<File>().writeText(secondArg()); true
        }
        // Enough legacy rows to clear every volume gate, all of them unusable.
        val rows = (0 until 4000).joinToString("\n") { i ->
            legacyRow(now - (4000L - i) * 300_000L, 150.0, "150.0", if (i % 20 == 0) "1" else "0")
        }
        datasetFile().writeText(legacyHeader + "\n" + rows + "\n")

        val trainer = AutodriveNeuralTrainer(mockk<Context>(relaxed = true), aapsLogger, storage)
        val installed = trainer.trainAttentionWeights()

        assertThat(installed).isFalse()
        assertThat(File(dir, AutodriveNeuralTrainer.WEIGHTS_FILE_NAME).exists()).isFalse()
        assertThat(trainer.lastReport?.rows).isEqualTo(0)
    }
}
