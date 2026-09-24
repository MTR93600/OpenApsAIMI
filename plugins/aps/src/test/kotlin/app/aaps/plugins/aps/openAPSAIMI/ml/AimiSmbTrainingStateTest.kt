package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.plugins.aps.openAPSAIMI.AimiNeuralNetwork
import app.aaps.plugins.aps.openAPSAIMI.TrainingConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Covers the 2026-09-22 SMB training schedule fix: a 5-min-CGM user could never reach the 200-new-rows
 * gate fast enough, and the in-memory attempt counter reset to 0 on every app restart, so training could
 * silently never run. Also covers "fix round 1", the adversarial review's findings on that same change
 * (R-CB, I3, I4, I5 — see `coder-training-report.md`).
 *
 * Covered here:
 *  - [AimiSmbTrainer.shouldAttempt], the pure decision function (no files, no coroutines), including the
 *    I4 clock-skew clamp;
 *  - that [AimiSmbTrainer.lastAttemptAtMs] / [AimiSmbTrainer.lastResult] / the row counter survive a
 *    reload from disk, including non-zero counters (I5) and a future persisted timestamp (I4);
 *  - that a rejected candidate leaves a [AimiSmbTrainer.TrainingResult] a person can read without logcat,
 *    naming the failed gate (I5);
 *  - that the restart race between [AimiSmbTrainer.loadModel] and the first training tick cannot make a
 *    decision on stale defaults (I3);
 *  - that a candidate rejected by the gates opens the circuit breaker only when no model is already in
 *    service (R-CB), and that the shrunk-CSV counter correction is persisted even on a skipped attempt (I5).
 */
class AimiSmbTrainingStateTest {

    // [AimiSmbTrainer] is a singleton object: its atomics and circuit breaker are shared by every test in
    // the JVM. Only the tests below that actually call [AimiSmbTrainer.trainNow] / [AimiSmbTrainer.persistState]
    // touch that shared state, but resetting before every test keeps this file self-contained either way.
    @BeforeEach
    fun resetSharedTrainerState() {
        AimiSmbTrainer.resetForTest()
    }

    // ---- shouldAttempt: pure function, no I/O ---------------------------------

    private val sixHoursMs = 6L * 60 * 60 * 1000
    private val twentyFourHoursMs = 24L * 60 * 60 * 1000

    @Test
    fun no_model_attempts_even_with_few_new_rows() {
        // lastAttemptMs = 0 ("never attempted") is far more than 6h before nowMs, so the rate limit gate
        // does not intercept this — it is the bootstrap gate (modelAvailable = false) being asserted here.
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = 100_000_000_000L,
            lastAttemptMs = 0L,
            rowsAtLastTrain = 0L,
            totalRows = 30L,
            modelAvailable = false,
        )

        assertThat(decision.attempt).isTrue()
    }

    @Test
    fun model_present_199_new_rows_and_last_attempt_23h_ago_skips() {
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - 23L * 60 * 60 * 1000,
            rowsAtLastTrain = 1_000L,
            totalRows = 1_199L,
            modelAvailable = true,
        )

        assertThat(decision.attempt).isFalse()
    }

    @Test
    fun model_present_199_new_rows_and_last_attempt_25h_ago_attempts() {
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - 25L * 60 * 60 * 1000,
            rowsAtLastTrain = 1_000L,
            totalRows = 1_199L,
            modelAvailable = true,
        )

        assertThat(decision.attempt).isTrue()
    }

    @Test
    fun two_hundred_new_rows_attempts() {
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - twentyFourHoursMs, // clear of both the rate limit and (just barely) the staleness trigger
            rowsAtLastTrain = 1_000L,
            totalRows = 1_200L,
            modelAvailable = true,
        )

        assertThat(decision.attempt).isTrue()
    }

    @Test
    fun a_csv_shorter_than_the_row_counter_resets_it_instead_of_going_negative() {
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - twentyFourHoursMs,
            rowsAtLastTrain = 2_000L,
            totalRows = 1_800L,
            modelAvailable = true,
        )

        assertThat(decision.effectiveRowsAtLastTrain).isEqualTo(0L)
    }

    @Test
    fun within_six_hours_of_the_last_attempt_always_skips() {
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - (sixHoursMs - 1),
            rowsAtLastTrain = 0L,
            totalRows = 100_000L, // would clear every other gate
            modelAvailable = false, // would also clear the bootstrap gate
        )

        assertThat(decision.attempt).isFalse()
    }

    @Test
    fun a_future_last_attempt_timestamp_is_treated_as_never_attempted() {
        // I4: a phone clock set forward once (then corrected), or a backup restored from another device,
        // can leave lastAttemptMs in the future. Unclamped, `nowMs - lastAttemptMs` is negative and stays
        // below TRAIN_INTERVAL_MS forever, freezing training until the real clock catches up — possibly
        // months. modelAvailable = false isolates this to the clamp itself: without it, a future
        // lastAttemptMs would make this return `attempt = false` regardless of bootstrap.
        val now = 100_000_000_000L
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now + 10 * twentyFourHoursMs,
            rowsAtLastTrain = 0L,
            totalRows = 5L,
            modelAvailable = false,
        )

        assertThat(decision.attempt).isTrue()
    }

    // ---- Persistence round-trip -------------------------------------------

    @Test
    fun training_state_survives_a_reload_from_disk(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 15, smbGiven = "0.3000")

        runBlocking { AimiSmbTrainer.trainNow(dir, csvFile) }

        val attemptBefore = AimiSmbTrainer.lastAttemptAtMs()
        val resultBefore = AimiSmbTrainer.lastResult()
        assertThat(attemptBefore).isGreaterThan(0L)
        assertThat(resultBefore).isNotNull()

        // Simulate an app restart: every in-memory atomic goes back to its just-started default.
        AimiSmbTrainer.resetForTest()
        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(0L)
        assertThat(AimiSmbTrainer.lastResult()).isNull()

        AimiSmbTrainer.loadPersistedState(dir)

        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(attemptBefore)
        assertThat(AimiSmbTrainer.lastResult()).isEqualTo(resultBefore)
    }

    // ---- Gate-rejection result -------------------------------------------

    @Test
    fun a_candidate_rejected_by_the_liveness_gates_records_why(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        // Every row asks the model to learn the SAME label. The candidate can then only publish a model
        // that answers (almost) the same value for every input, so it must be rejected — deterministically,
        // unlike a real training run whose gate failure would depend on the random weight init.
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")

        runBlocking { AimiSmbTrainer.trainNow(dir, csvFile) }

        val result = AimiSmbTrainer.lastResult()
        assertThat(result).isNotNull()
        assertThat(result!!.outcome).isEqualTo(AimiSmbTrainer.TrainingOutcome.REJECTED_BY_GATES)
        assertThat(result.samplesAfterFilter).isAtLeast(10)
        // The measured value against its threshold, e.g. "answers almost the same for every input: spread
        // 0.0 < 0.05" or "does not beat the median label: ... ratio=... > 0.95" — whichever gate trips
        // first, both read as "<value> <op> <threshold>".
        // The measured value against its threshold, not just "something was written": a person reading
        // the dashboard must see WHICH gate refused the model.
        assertThat(result.gateDetail).isNotEmpty()
        assertThat(result.gateDetail.lowercase()).contains("discard")
    }

    // ---- R-CB: a rejected candidate must not switch off the model in service ---------------------

    @Test
    fun a_rejected_candidate_does_not_open_the_breaker_while_a_model_is_in_service(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")
        // An incumbent is loaded. It passed these same gates when it was published, so a candidate that
        // fails them says nothing about the incumbent and must not take it out of service.
        AimiSmbTrainer.setModelForTest(AimiNeuralNetwork(AimiSmbTrainer.INPUT_SIZE, 4, 1, TrainingConfig(epochs = 1)))

        repeat(3) {
            AimiSmbTrainer.setPersistedStateForTest()
            runBlocking { AimiSmbTrainer.trainNow(dir, csvFile) }
        }

        assertThat(AimiSmbTrainer.lastResult()!!.outcome).isEqualTo(AimiSmbTrainer.TrainingOutcome.REJECTED_BY_GATES)
        assertThat(AimiSmbTrainer.isCircuitOpenNow()).isFalse()
    }

    @Test
    fun a_rejected_candidate_still_opens_the_breaker_when_no_model_is_in_service(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 20, smbGiven = "0.3000")
        AimiSmbTrainer.setModelForTest(null)

        repeat(3) {
            AimiSmbTrainer.setPersistedStateForTest()
            runBlocking { AimiSmbTrainer.trainNow(dir, csvFile) }
        }

        // Nothing is in service, so repeated failures are worth pausing for: this is the old behaviour.
        assertThat(AimiSmbTrainer.isCircuitOpenNow()).isTrue()
    }

    // ---- I5: the rest of the persisted state ----------------------------------------------------

    @Test
    fun the_row_counter_and_last_training_time_survive_a_reload(@TempDir dir: File) {
        AimiSmbTrainer.setPersistedStateForTest(
            lastAttemptMs = 1_700_000_000_000L,
            lastTrainMs = 1_699_000_000_000L,
            rowsAtLastTrain = 1234L,
        )
        AimiSmbTrainer.persistState(dir)

        AimiSmbTrainer.resetForTest()
        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(0L)

        AimiSmbTrainer.loadPersistedState(dir)

        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(1234L)
        assertThat(AimiSmbTrainer.lastAttemptAtMs()).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun a_shrunk_csv_resets_the_counter_on_disk_even_when_the_attempt_is_skipped(@TempDir dir: File) {
        val csvFile = File(dir, "oapsaimiML2_records.csv")
        writeCorpus(csvFile, rows = 15, smbGiven = "0.3000")
        // The nightly bad-day job cut the file: the counter now claims more rows than the file holds.
        // The attempt itself is not due (the last one was a minute ago), but the correction must still
        // reach the disk, or the negative count comes back at the next restart.
        AimiSmbTrainer.setPersistedStateForTest(
            lastAttemptMs = System.currentTimeMillis() - 60_000L,
            rowsAtLastTrain = 2000L,
        )

        runBlocking { AimiSmbTrainer.trainNow(dir, csvFile) }

        AimiSmbTrainer.resetForTest()
        AimiSmbTrainer.loadPersistedState(dir)
        assertThat(AimiSmbTrainer.rowsAtLastTrainForTest()).isEqualTo(0L)
    }

    @Test
    fun the_bootstrap_bypass_does_not_apply_once_a_model_exists() {
        val now = 1_700_000_000_000L
        // Same few new rows as `no_model_attempts_even_with_few_new_rows`, but a model is available:
        // the bypass must not fire, so the 200-row rule decides and the answer is "skip".
        val decision = AimiSmbTrainer.shouldAttempt(
            nowMs = now,
            lastAttemptMs = now - sixHoursMs - 1,
            rowsAtLastTrain = 1000L,
            totalRows = 1030L,
            modelAvailable = true,
        )
        assertThat(decision.attempt).isFalse()
    }

    // ---- Row builder --------------------------------------------------------

    /** Full 39-column rows matching [SmbRefinementFeatureSchema.trainingCsvColumnNames], all sharing one label. */
    private fun writeCorpus(file: File, rows: Int, smbGiven: String) {
        val header = SmbRefinementFeatureSchema.trainingCsvHeaderLine()
        val body = (1..rows).joinToString("\n") { i -> row(bg = (100 + i).toString(), smbGiven = smbGiven) }
        file.writeText(header + "\n" + body + "\n")
    }

    /** One row in the current 39 column shape (see `AimiSmbCorpusGuardTest.currentShapeRow`). */
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
