package app.aaps.plugins.aps.openAPSAIMI.ml

/**
 * Factual, read-only SMB learner state for support/dashboard presentation.
 *
 * No status is inferred from timestamps: [lastAttempt] and [waiting] retain their distinct source
 * meanings, and a missing model file has no modification timestamp.
 */
data class AimiSmbDashboardSnapshot(
    val lastAttempt: TrainingResult?,
    val waiting: TrainingResult?,
    val lastAttemptAtMs: Long,
    val lastTrainedAtMs: Long,
    val circuitOpen: Boolean,
    val modelLoaded: Boolean,
    val modelFileExists: Boolean,
    val modelFileModifiedAtMs: Long?,
    val modelFileSizeBytes: Long? = null,
)
