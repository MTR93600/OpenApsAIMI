package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.utils.AndroidAimiStorage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import java.io.File

/**
 * Training telemetry of the SMB trainer, ported from `AimiSmbTrainingStateTest` on
 * `origin/dev_OAPSAIMI` @ `6598201d26e0bc95524e1c5edb1af9a538f1654e`.
 *
 * These are the readings a dashboard trusts: what the last real attempt was, when it ran, and — when a
 * candidate was refused — which gate refused it. The KMP port kept `loadModel` and `maybeTrainAsync` and
 * dropped all of that, so this file locks it back down.
 *
 * The reference's pure `shouldAttempt` cases are NOT repeated here: they already live in commonTest as
 * `AimiSmbTrainingScheduleTest`, against the same numbers. What is left is everything that needs files,
 * the circuit breaker, or a real training pass.
 *
 * Storage is a real [AndroidAimiStorage] over a mocked [AimiStorageHelper]: every path below is resolved
 * by the test itself, so the helper is never asked to do anything (same pattern as `AimiSmbCorpusFileTest`).
 */
class AimiSmbTrainingStateTest {

    private val storage: AimiStorage = AndroidAimiStorage(mock<AimiStorageHelper>())

    private fun pathOf(file: File): AimiPath = AimiPath(file.absolutePath)

    /**
     * [AimiSmbTrainer] is a singleton object, so its atomics and circuit breaker are shared by every
     * test in the JVM. Resetting before each case keeps this file self-contained.
     */
    @BeforeEach
    fun resetSharedTrainerState() {
        AimiSmbTrainer.resetForTest()
    }

    // ---- Persistence round trip ----------------------------------------------

    @Test
    fun training_state_survives_a_reload_from_disk(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 15, smbGiven = "0.3000")

        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }

        val attemptBefore = AimiSmbTrainer.lastAttemptAtMs()
        val resultBefore = AimiSmbTrainer.lastResult()
        assertThat(attemptBefore).isGreaterThan(0L)
        assertThat(resultBefore).isNotNull()

        // Simulate an app restart: every in-memory atomic goes back to its just-started default.
        AimiSmbTrainer.resetForTest()
        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(0L)
        assertThat(AimiSmbTrainer.lastResult()).isNull()

        AimiSmbTrainer.loadPersistedState(storage, pathOf(dir))

        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(attemptBefore)
        assertThat(AimiSmbTrainer.lastResult()).isEqualTo(resultBefore)
    }

    @Test
    fun the_row_counter_and_last_training_time_survive_a_reload(@TempDir dir: File) {
        AimiSmbTrainer.setPersistedStateForTest(
            lastAttemptMs = 1_700_000_000_000L,
            lastTrainMs = 1_699_000_000_000L,
            rowsAtLastTrain = 1234L,
        )
        AimiSmbTrainer.persistState(storage, pathOf(dir))

        AimiSmbTrainer.resetForTest()
        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(0L)

        AimiSmbTrainer.loadPersistedState(storage, pathOf(dir))

        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(1234L)
        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(1_700_000_000_000L)
        assertThat(AimiSmbTrainer.lastTrainedAtMs()).isEqualTo(1_699_000_000_000L)
    }

    @Test
    fun a_shrunk_csv_resets_the_counter_on_disk_even_when_the_attempt_is_skipped(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 15, smbGiven = "0.3000")
        // The nightly bad-day job cut the file: the counter now claims more rows than the file holds.
        // The attempt itself is not due (the last one was a minute ago), but the correction must still
        // reach the disk, or the negative count comes back at the next restart.
        AimiSmbTrainer.setPersistedStateForTest(
            lastAttemptMs = aimiWallClockMs() - 60_000L,
            rowsAtLastTrain = 2000L,
        )

        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }

        AimiSmbTrainer.resetForTest()
        AimiSmbTrainer.loadPersistedState(storage, pathOf(dir))
        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(0L)
    }

    // ---- Waiting status is separate from the last real result ------------------

    @Test
    fun a_skipped_attempt_reports_waiting_and_does_not_overwrite_the_last_real_result(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")

        // First: a real attempt, which leaves a persisted result.
        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }
        val realResult = AimiSmbTrainer.lastResult()
        assertThat(realResult).isNotNull()

        // Then: a tick that is not due. It must not replace the reason above.
        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }

        assertThat(AimiSmbTrainer.lastResult()).isEqualTo(realResult)
        val waiting = AimiSmbTrainer.currentWaitingStatus()
        assertThat(waiting).isNotNull()
        assertThat(waiting!!.outcome).isEqualTo(TrainingOutcome.SKIPPED_NOT_DUE)
        assertThat(waiting.gateDetail).isNotEmpty()
    }

    @Test
    fun a_missing_csv_is_a_real_result_not_a_waiting_state(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")

        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }

        val result = AimiSmbTrainer.lastResult()
        assertThat(result).isNotNull()
        assertThat(result!!.outcome).isEqualTo(TrainingOutcome.SKIPPED_NO_CSV)
        assertThat(AimiSmbTrainer.currentWaitingStatus()).isNull()
    }

    // ---- Gate-rejection result --------------------------------------------------

    @Test
    fun a_candidate_rejected_by_the_liveness_gates_records_why(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        // Every row asks the model to learn the SAME label. The candidate can then only publish a model
        // that answers (almost) the same value for every input, so it must be rejected — deterministically,
        // unlike a real training run whose gate failure would depend on the random weight init.
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")

        runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }

        val result = AimiSmbTrainer.lastResult()
        assertThat(result).isNotNull()
        assertThat(result!!.outcome).isEqualTo(TrainingOutcome.REJECTED_BY_GATES)
        assertThat(result.samplesAfterFilter).isAtLeast(10)
        // The measured value against its threshold, not just "something was written": a person reading
        // the dashboard must see WHICH gate refused the model.
        assertThat(result.gateDetail).isNotEmpty()
        assertThat(result.gateDetail.lowercase()).contains("discard")
    }

    // ---- A rejected candidate must not switch off the model in service ----------

    @Test
    fun a_rejected_candidate_does_not_open_the_breaker_while_a_model_is_in_service(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")
        // An incumbent is loaded. It passed these same gates when it was published, so a candidate that
        // fails them says nothing about the incumbent and must not take it out of service.
        AimiSmbTrainer.setModelForTest(AimiNeuralNetwork(AimiSmbTrainer.INPUT_SIZE, 4, 1, TrainingConfig(epochs = 1)))

        repeat(3) {
            AimiSmbTrainer.setPersistedStateForTest()
            runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }
        }

        assertThat(AimiSmbTrainer.lastResult()!!.outcome).isEqualTo(TrainingOutcome.REJECTED_BY_GATES)
        assertThat(AimiSmbTrainer.isCircuitOpenNow()).isFalse()
    }

    @Test
    fun a_rejected_candidate_still_opens_the_breaker_when_no_model_is_in_service(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")
        AimiSmbTrainer.setModelForTest(null)

        repeat(3) {
            AimiSmbTrainer.setPersistedStateForTest()
            runBlocking { AimiSmbTrainer.trainNow(storage, pathOf(dir), pathOf(csvFile)) }
        }

        // Nothing is in service, so repeated failures are worth pausing for.
        assertThat(AimiSmbTrainer.isCircuitOpenNow()).isTrue()
    }

    // ---- Row builder ------------------------------------------------------------

    /** Full rows matching `SmbRefinementFeatureSchema.trainingCsvColumnNames`, all sharing one label. */
    private fun writeCorpus(file: File, rows: Int, smbGiven: String) {
        val header = SmbRefinementFeatureSchema.trainingCsvHeaderLine()
        val body = (1..rows).joinToString("\n") { i -> row(bg = (100 + i).toString(), smbGiven = smbGiven) }
        file.writeText(header + "\n" + body + "\n")
    }

    /** One row in the current 39 column shape (see `AimiSmbCorpusFileTest.currentShapeRow`). */
    private fun row(bg: String, smbGiven: String): String = listOf(
        "09/07/2026 12:30",
        bg, "1.2", "9.0", "3.5", "2.9", "1.7", "0.8", "0.7", "0.9", "1.0",
        "0.4100", "0.9293", "0.9100", "0.6400",
        "0.9000", "0.1800", "0.8400",
        "0.8300", "0.1200", "0.7900",
        "2", "4", "3", "1", "3",
        "0.20", "0.10", "",
        "0.15", smbGiven, "55", "7.0",
        "0.1500", "0.0000", "governed", "harmonia", "148.0", "0.2000",
    ).joinToString(",")
}
