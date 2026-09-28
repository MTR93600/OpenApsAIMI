package app.aaps.plugins.aps.openAPSAIMI.ml

/**
 * Why a training attempt of the SMB refinement model ended.
 *
 * Restored from `AimiSmbTrainer.TrainingOutcome` on `origin/dev_OAPSAIMI` @
 * `6598201d26e0bc95524e1c5edb1af9a538f1654e`, where it was nested inside the Android trainer object.
 * It is plain data with no platform call in it, so it lives in commonMain: a dashboard on any target
 * can read it, and `commonTest` can lock it.
 */
enum class TrainingOutcome {

    /** A candidate passed every gate and was published. */
    TRAINED,

    /** Not due yet: rate limit, and neither enough new rows nor a stale-enough last attempt. */
    SKIPPED_NOT_DUE,

    /** No CSV file to read. */
    SKIPPED_NO_CSV,

    /** The stored CSV header does not match the schema — refused instead of training on the wrong column. */
    REFUSED_HEADER,

    /** Fewer rows survived the quality filter than `AimiSmbTrainingSchedule.MIN_TRAINING_SAMPLES` asks for. */
    TOO_FEW_SAMPLES,

    /** A candidate was trained but did not pass the liveness / accuracy gates, so nothing was published. */
    REJECTED_BY_GATES,

    /** The failure circuit breaker is open; no attempt was made. */
    CIRCUIT_OPEN,

    /** An exception was thrown while training. */
    ERROR,
}

/**
 * Snapshot of one SMB training attempt: what happened, and — for a result the user or a developer
 * needs to explain — enough numbers to explain it without reading logcat.
 *
 * Restored from `AimiSmbTrainer.TrainingResult` on `origin/dev_OAPSAIMI` @
 * `6598201d26e0bc95524e1c5edb1af9a538f1654e`. Same fields, same meanings; only the place changed.
 *
 * @param atMs when the attempt ran, in epoch milliseconds.
 * @param outcome why the attempt ended.
 * @param totalRows how many data rows the training CSV held.
 * @param samplesAfterFilter how many rows survived the quality filter and reached the trainer.
 * @param rowsRejectedByFilter how many rows the quality filter dropped.
 * @param gateDetail for [TrainingOutcome.REJECTED_BY_GATES] and [TrainingOutcome.REFUSED_HEADER], the
 *   measured value against its threshold (for example "spread 0.021 < 0.050"), taken from the same
 *   message the shared training pipeline already logs. Empty when not applicable.
 */
data class TrainingResult(
    val atMs: Long,
    val outcome: TrainingOutcome,
    val totalRows: Long = 0L,
    val samplesAfterFilter: Int = 0,
    val rowsRejectedByFilter: Long = 0L,
    val gateDetail: String = "",
)
