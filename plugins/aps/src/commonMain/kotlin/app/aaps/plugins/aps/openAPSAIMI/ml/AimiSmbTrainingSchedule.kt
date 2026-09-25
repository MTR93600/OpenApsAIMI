package app.aaps.plugins.aps.openAPSAIMI.ml

import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * When the SMB trainer may run another attempt.
 *
 * Extracted from `AimiSmbTrainer.shouldAttempt` on `origin/dev_OAPSAIMI` @
 * `6a6561caabed433fe8b7d22c295809cf54077855` (unchanged through tip
 * `6598201d26e0bc95524e1c5edb1af9a538f1654e`). The Android trainer still owns the coroutine, the
 * model file and the log lines. This object is the decision and the three counters that decision
 * reads, so the same numbers run on every target.
 *
 * Same shape as the basal gate in `BasalMlTrainingCoordinator`: a minimum of new rows, otherwise a
 * stale-attempt bypass. The SMB window is 24h and the clock is the last attempt, not the last
 * publish. No threshold here was chosen for this port.
 */
internal object AimiSmbTrainingSchedule {

    /** Less than this since the last attempt never runs, bootstrap included. */
    const val TRAIN_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6h

    const val MIN_NEW_ROWS_TO_RETRAIN = 200

    /**
     * If the last training ATTEMPT (successful or not) is older than this, attempt again even when
     * [MIN_NEW_ROWS_TO_RETRAIN] has not been reached.
     *
     * A user whose CGM reads every 5 min (not every 1 min, like the maintainer's) writes about 288
     * rows/day, so reaching 200 new rows can take most of a day even on a healthy corpus — and the
     * in-memory attempt counter used to reset to 0 on every app restart, so a phone that restarts a
     * few times a day could go without ever reaching 200 again. 24h is long enough to never fire
     * during normal operation for a fast-CGM user, and short enough that a stalled trainer is never
     * silently stuck for more than a day. Mirrors `BasalMlTrainingCoordinator.STALE_TRAINING_MS`.
     */
    const val STALE_ATTEMPT_MS = 24L * 60 * 60 * 1000L // 24h

    /** Smallest number of post-filter samples a training attempt needs to run at all. */
    const val MIN_TRAINING_SAMPLES = 10

    /**
     * File holding [Counters.lastAttemptMs] / [Counters.lastTrainMs] / [Counters.rowsAtLastTrain],
     * next to the SMB weights. Not a preference key.
     */
    const val STATE_FILE_NAME = "smb_ml_training_state.json"

    /**
     * A persisted or in-memory timestamp more than this far in the future is treated as invalid (clock
     * was wrong once, or a backup was restored from another device) and reset to 0, in both
     * [shouldAttempt] and [decodeCounters].
     *
     * Without this, `now - lastAttemptMs` is negative and stays below [TRAIN_INTERVAL_MS] forever — no
     * attempt until the real clock catches up to the bad value, possibly months, and now that
     * [Counters.lastAttemptMs] is persisted, one bad clock reading becomes permanent across restarts. A
     * small positive tolerance (not exactly 0) allows for ordinary clock drift between the moment a
     * value was written and the moment it is compared.
     */
    const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60 * 1000 // 5 min

    /** The three counters the reference writes to [STATE_FILE_NAME]. */
    data class Counters(
        val lastAttemptMs: Long,
        val lastTrainMs: Long,
        val rowsAtLastTrain: Long,
    )

    /** Decision returned by [shouldAttempt]: whether to run, why, and the row counter corrected for a shrunk CSV. */
    data class TrainingDecision(
        val attempt: Boolean,
        val reason: String,
        val effectiveRowsAtLastTrain: Long,
    )

    /**
     * Whether a training attempt should run now, given only in-memory numbers — no file I/O, so it is
     * fully unit-testable.
     *
     * Order of the gates:
     * 1. Rate limit: less than [TRAIN_INTERVAL_MS] since the last ATTEMPT never runs, bootstrap or not —
     *    point 1 of the spec keeps this gate for the very first training too.
     * 2. Bootstrap: no model available yet (`!modelAvailable`) always attempts once the rate limit clears,
     *    regardless of new rows.
     * 3. Enough new rows ([MIN_NEW_ROWS_TO_RETRAIN]) always attempts — unchanged from before.
     * 4. Otherwise, a last attempt older than [STALE_ATTEMPT_MS] (24h) attempts anyway, so a slow-CGM user
     *    is never stuck waiting for 200 new rows that take a full day to accumulate.
     * 5. Otherwise, skip.
     *
     * [rowsAtLastTrain] is corrected to 0 first when [totalRows] fell below it (the CSV shrank — the
     * nightly `automateDeletionIfBadDay` trims it on a bad TIR day), so `totalRows - rowsAtLastTrain` can
     * never go negative. The caller must persist [TrainingDecision.effectiveRowsAtLastTrain] even when
     * the decision is to skip, so the correction is not lost.
     *
     * [lastAttemptMs] more than [CLOCK_SKEW_TOLERANCE_MS] in the future (a bad clock reading, or a
     * restored backup from another device) is treated as 0 ("never attempted"), so a single bad
     * timestamp cannot freeze training until the real clock catches up to it — see
     * [CLOCK_SKEW_TOLERANCE_MS].
     */
    fun shouldAttempt(
        nowMs: Long,
        lastAttemptMs: Long,
        rowsAtLastTrain: Long,
        totalRows: Long,
        modelAvailable: Boolean,
    ): TrainingDecision {
        val safeLastAttemptMs = sanitizeTimestamp(lastAttemptMs, nowMs)
        val correctedRows = if (totalRows < rowsAtLastTrain) 0L else rowsAtLastTrain

        if (nowMs - safeLastAttemptMs < TRAIN_INTERVAL_MS) {
            return TrainingDecision(
                attempt = false,
                reason = "rate limit: last attempt too recent",
                effectiveRowsAtLastTrain = correctedRows,
            )
        }
        if (!modelAvailable) {
            return TrainingDecision(
                attempt = true,
                reason = "bootstrap: no model available yet",
                effectiveRowsAtLastTrain = correctedRows,
            )
        }

        val newRows = totalRows - correctedRows
        if (newRows >= MIN_NEW_ROWS_TO_RETRAIN) {
            return TrainingDecision(
                attempt = true,
                reason = "$newRows new rows since last train",
                effectiveRowsAtLastTrain = correctedRows,
            )
        }
        if (nowMs - safeLastAttemptMs > STALE_ATTEMPT_MS) {
            return TrainingDecision(
                attempt = true,
                reason = "last attempt older than 24h ($newRows new rows)",
                effectiveRowsAtLastTrain = correctedRows,
            )
        }
        return TrainingDecision(
            attempt = false,
            reason = "only $newRows new rows (need $MIN_NEW_ROWS_TO_RETRAIN), last attempt not stale",
            effectiveRowsAtLastTrain = correctedRows,
        )
    }

    /** [ms] clamped to 0 when it lies more than [CLOCK_SKEW_TOLERANCE_MS] past [nowMs]. */
    fun sanitizeTimestamp(ms: Long, nowMs: Long): Long =
        if (ms > nowMs + CLOCK_SKEW_TOLERANCE_MS) 0L else ms

    fun encodeCounters(counters: Counters): String = buildJsonObject {
        put("lastAttemptMs", counters.lastAttemptMs)
        put("lastTrainMs", counters.lastTrainMs)
        put("rowsAtLastTrain", counters.rowsAtLastTrain)
    }.toString()

    /**
     * Reads the three counters. A timestamp more than [CLOCK_SKEW_TOLERANCE_MS] past [nowMs] comes back
     * as 0. Unreadable text comes back as null, which the trainer treats as "leave memory as it is".
     */
    fun decodeCounters(text: String, nowMs: Long): Counters? {
        val json = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return Counters(
            lastAttemptMs = sanitizeTimestamp(json.optLongCompat("lastAttemptMs", 0L), nowMs),
            lastTrainMs = sanitizeTimestamp(json.optLongCompat("lastTrainMs", 0L), nowMs),
            rowsAtLastTrain = json.optLongCompat("rowsAtLastTrain", 0L),
        )
    }
}
