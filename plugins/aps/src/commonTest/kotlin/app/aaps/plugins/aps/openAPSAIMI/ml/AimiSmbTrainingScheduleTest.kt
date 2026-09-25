package app.aaps.plugins.aps.openAPSAIMI.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the SMB trainer schedule from `origin/dev_OAPSAIMI` @ `6a6561caab`
 * (`AimiSmbTrainer.shouldAttempt`). The clock is the `nowMs` argument: nothing here reads the wall clock.
 *
 * Pure decision only. File reload, gate-rejection reports and the circuit-breaker coupling stay on the
 * Android trainer.
 */
class AimiSmbTrainingScheduleTest {

    /** Frozen clock. Far from the epoch so `now - 0` clears both the 6h rate limit and the 24h stale gate. */
    private val now = 100_000_000_000L

    @Test
    fun constants_match_the_reference_schedule() {
        assertEquals(6L * 60 * 60 * 1000L, AimiSmbTrainingSchedule.TRAIN_INTERVAL_MS)
        assertEquals(200, AimiSmbTrainingSchedule.MIN_NEW_ROWS_TO_RETRAIN)
        assertEquals(24L * 60 * 60 * 1000L, AimiSmbTrainingSchedule.STALE_ATTEMPT_MS)
        assertEquals(10, AimiSmbTrainingSchedule.MIN_TRAINING_SAMPLES)
        assertEquals(5L * 60 * 1000L, AimiSmbTrainingSchedule.CLOCK_SKEW_TOLERANCE_MS)
        assertEquals("smb_ml_training_state.json", AimiSmbTrainingSchedule.STATE_FILE_NAME)
    }

    @Test
    fun just_before_24h_with_a_model_and_199_new_rows_skips() {
        val stale = AimiSmbTrainingSchedule.STALE_ATTEMPT_MS
        val atThreshold = decision(
            lastAttemptMs = now - stale,
            rowsAtLastTrain = 1_000L,
            totalRows = 1_199L,
            modelAvailable = true,
        )
        val oneMsBefore = decision(
            lastAttemptMs = now - (stale - 1L),
            rowsAtLastTrain = 1_000L,
            totalRows = 1_199L,
            modelAvailable = true,
        )

        assertFalse(atThreshold.attempt)
        assertFalse(oneMsBefore.attempt)
        assertEquals(
            "only 199 new rows (need 200), last attempt not stale",
            atThreshold.reason,
        )
    }

    @Test
    fun just_after_24h_with_a_model_and_199_new_rows_attempts() {
        val decision = decision(
            lastAttemptMs = now - (AimiSmbTrainingSchedule.STALE_ATTEMPT_MS + 1L),
            rowsAtLastTrain = 1_000L,
            totalRows = 1_199L,
            modelAvailable = true,
        )

        assertTrue(decision.attempt)
        assertEquals("last attempt older than 24h (199 new rows)", decision.reason)
    }

    @Test
    fun never_trained_attempts_once_the_rate_limit_is_clear() {
        val noModel = decision(
            lastAttemptMs = 0L,
            rowsAtLastTrain = 0L,
            totalRows = 30L,
            modelAvailable = false,
        )
        val modelButNeverAttempted = decision(
            lastAttemptMs = 0L,
            rowsAtLastTrain = 1_000L,
            totalRows = 1_030L,
            modelAvailable = true,
        )

        assertTrue(noModel.attempt)
        assertEquals("bootstrap: no model available yet", noModel.reason)
        assertTrue(modelButNeverAttempted.attempt)
        assertEquals("last attempt older than 24h (30 new rows)", modelButNeverAttempted.reason)
    }

    @Test
    fun frozen_clock_treats_a_future_last_attempt_past_the_tolerance_as_never_attempted() {
        val beyondTolerance = decision(
            lastAttemptMs = now + AimiSmbTrainingSchedule.CLOCK_SKEW_TOLERANCE_MS + 1L,
            rowsAtLastTrain = 0L,
            totalRows = 5L,
            modelAvailable = false,
        )
        val insideTolerance = decision(
            lastAttemptMs = now + AimiSmbTrainingSchedule.CLOCK_SKEW_TOLERANCE_MS,
            rowsAtLastTrain = 0L,
            totalRows = 100_000L,
            modelAvailable = false,
        )

        assertTrue(beyondTolerance.attempt)
        assertEquals("bootstrap: no model available yet", beyondTolerance.reason)
        assertFalse(insideTolerance.attempt)
        assertEquals("rate limit: last attempt too recent", insideTolerance.reason)
    }

    @Test
    fun within_six_hours_of_the_last_attempt_always_skips() {
        val decision = decision(
            lastAttemptMs = now - (AimiSmbTrainingSchedule.TRAIN_INTERVAL_MS - 1L),
            rowsAtLastTrain = 0L,
            totalRows = 100_000L,
            modelAvailable = false,
        )

        assertFalse(decision.attempt)
        assertEquals("rate limit: last attempt too recent", decision.reason)
    }

    @Test
    fun two_hundred_new_rows_attempts_even_when_the_last_attempt_is_not_stale() {
        val decision = decision(
            lastAttemptMs = now - AimiSmbTrainingSchedule.STALE_ATTEMPT_MS,
            rowsAtLastTrain = 1_000L,
            totalRows = 1_200L,
            modelAvailable = true,
        )

        assertTrue(decision.attempt)
        assertEquals("200 new rows since last train", decision.reason)
    }

    @Test
    fun a_csv_shorter_than_the_row_counter_resets_it_instead_of_going_negative() {
        val decision = decision(
            lastAttemptMs = now - AimiSmbTrainingSchedule.STALE_ATTEMPT_MS,
            rowsAtLastTrain = 2_000L,
            totalRows = 1_800L,
            modelAvailable = true,
        )

        assertEquals(0L, decision.effectiveRowsAtLastTrain)
        assertTrue(decision.attempt)
        assertEquals("1800 new rows since last train", decision.reason)
    }

    @Test
    fun gate_rejection_with_a_model_in_service_does_not_count_against_the_breaker() {
        val breaker = TrainingCircuitBreaker(clock = { now })
        repeat(TrainingCircuitBreaker.DEFAULT_MAX_FAILURES) {
            assertFalse(recordGateRejection(breaker, modelInService = true))
        }
        assertFalse(breaker.isOpen(now))
    }

    @Test
    fun gate_rejection_without_a_model_counts_against_the_breaker() {
        val breaker = TrainingCircuitBreaker(clock = { now })
        repeat(TrainingCircuitBreaker.DEFAULT_MAX_FAILURES - 1) {
            assertFalse(recordGateRejection(breaker, modelInService = false))
        }
        assertFalse(breaker.isOpen(now))
        assertTrue(recordGateRejection(breaker, modelInService = false))
        assertTrue(breaker.isOpen(now))
    }

    @Test
    fun persisted_counters_round_trip_and_a_future_timestamp_is_cleared_against_the_frozen_clock() {
        val encoded = AimiSmbTrainingSchedule.encodeCounters(
            AimiSmbTrainingSchedule.Counters(
                lastAttemptMs = now - 3_600_000L,
                lastTrainMs = now - 86_400_000L,
                rowsAtLastTrain = 1_500L,
            ),
        )
        val decoded = AimiSmbTrainingSchedule.decodeCounters(encoded, now)

        assertEquals(now - 3_600_000L, decoded?.lastAttemptMs)
        assertEquals(now - 86_400_000L, decoded?.lastTrainMs)
        assertEquals(1_500L, decoded?.rowsAtLastTrain)

        val future = """{"lastAttemptMs":${now + 86_400_000L},"lastTrainMs":${now + 3_600_000L},"rowsAtLastTrain":40}"""
        val sanitized = AimiSmbTrainingSchedule.decodeCounters(future, now)
        assertEquals(0L, sanitized?.lastAttemptMs)
        assertEquals(0L, sanitized?.lastTrainMs)
        assertEquals(40L, sanitized?.rowsAtLastTrain)

        assertNull(AimiSmbTrainingSchedule.decodeCounters("not-json", now))
    }

    /**
     * Applies the tip R-CB rule to the real breaker: a gate rejection counts only when no model is in service.
     * Returns whether this rejection just tripped the breaker, which is false when it was not counted.
     */
    private fun recordGateRejection(breaker: TrainingCircuitBreaker, modelInService: Boolean): Boolean {
        if (!AimiSmbTrainingSchedule.countGateRejectionAsBreakerFailure(modelInService)) return false
        return breaker.recordFailure()
    }

    private fun decision(
        lastAttemptMs: Long,
        rowsAtLastTrain: Long,
        totalRows: Long,
        modelAvailable: Boolean,
    ): AimiSmbTrainingSchedule.TrainingDecision =
        AimiSmbTrainingSchedule.shouldAttempt(
            nowMs = now,
            lastAttemptMs = lastAttemptMs,
            rowsAtLastTrain = rowsAtLastTrain,
            totalRows = totalRows,
            modelAvailable = modelAvailable,
        )
}
