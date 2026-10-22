package app.aaps.plugins.aps.openAPSAIMI.learning

/**
 * Read-only basal learner and weights-file facts for dashboard presentation.
 *
 * These values are the coordinator's live counters and the existing model file metadata; taking
 * the snapshot does not load, train, or publish either model head.
 */
data class BasalMlDashboardSnapshot(
    val lastTrainedAtMs: Long,
    val sampleCount: Int,
    val circuitOpen: Boolean,
    val modelFileExists: Boolean,
    val modelFileModifiedAtMs: Long?,
    val modelFileSizeBytes: Long? = null,
)
