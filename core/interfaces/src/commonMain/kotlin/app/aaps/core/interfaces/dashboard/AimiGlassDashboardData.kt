package app.aaps.core.interfaces.dashboard

/**
 * Read-only AIMI values published for dashboard presentation.
 *
 * Trajectory endpoints are in mg/dL. Null means the AIMI publisher has no finite endpoint; callers
 * must keep it unavailable rather than substituting a calculated or zero value.
 */
data class AimiGlassDashboardData(
    val trajectory: Trajectory? = null,
    val physio: Physio? = null,
    /**
     * Text summary of the latest AIMI patient state. Present whenever AIMI has published a patient
     * state, also when it has no live physio digest ([physio] is then null).
     */
    val physioPresentation: Physio.Presentation? = null,
    val training: Training? = null,
    val keyFactors: KeyFactors? = null,
    val safety: Safety? = null,
    val contextScreenAvailable: Boolean = false,
) {

    data class KeyFactors(
        val targetBgMgdl: Double?,
        val maxIobUnits: Double?,
        val maxSmbUnits: Double?,
        val isfMgdlPerUnit: Double?,
        val stableBgMinutes: Double?,
    )

    data class Safety(
        val tdd7DaysPerHour: Double?,
        val lowTirLastHourPercent: Double?,
        val lowTirTodayPercent: Double?,
        val stepsLast5Minutes: Int?,
        /**
         * Steps since local midnight as the platform health store totals them (Health Connect on
         * Android). Null when there is no such total on this platform, it is not allowed, or the read
         * failed. Never a stand-in 0.
         */
        val stepsToday: StepsToday? = null,
    )

    /** A step total for the day that starts at [dayStartMs] (local midnight), up to the read time. */
    data class StepsToday(
        val dayStartMs: Long,
        val steps: Long,
    )

    data class Trajectory(
        val hybridTerminalMgdl: Double?,
        val iobTerminalMgdl: Double?,
        val cobTerminalMgdl: Double?,
        val uamTerminalMgdl: Double?,
        /** Epoch time when the AIMI loop published these curves; null means freshness is unknown. */
        val publishedAtMs: Long? = null,
        /** True when publication time is unknown or outside the documented freshness window. */
        val isStale: Boolean = true,
    )

    data class Physio(
        val stepsLast15m: Int,
        val stepsLast60m: Int,
        val hrNowBpm: Int,
        val hrAvg15mBpm: Int,
        val rhrRestingBpm: Int,
        val hrvRmssd: Double,
        val activityState: String,
        val sleepDebtMinutes: Int,
        val asleepLiveConfidence: Double,
        val asleepLiveSource: String,
        val snapshotAgeMs: Long,
        val hrSampleAgeMs: Long?,
        val source: String,
        val confidence: Double,
        val thermalHypothesis: String,
        val thermalDeltaVsBaselineC: Double,
        val thermalInflammationIndex: Double,
        val thermalNarrative: String,
    ) {
        data class Presentation(
            val modeHeadline: String,
            val intentSummary: String,
            val thermalSummary: String,
            val updatedSummary: String,
        )
    }

    /**
     * Read-only model/training metadata. Timestamps are epoch milliseconds and sizes are bytes;
     * null means unavailable.
     */
    data class Training(
        val basal: BasalTraining?,
        val smb: SmbTraining?,
    )

    data class ModelFile(
        val exists: Boolean,
        val modifiedAtMs: Long?,
        val sizeBytes: Long? = null,
    )

    data class BasalTraining(
        val lastTrainedAtMs: Long,
        val sampleCount: Int,
        val circuitOpen: Boolean,
        val modelFile: ModelFile,
    )

    data class SmbTraining(
        val lastAttempt: SmbAttempt?,
        val waiting: SmbWaiting?,
        val lastTrainedAtMs: Long,
        val circuitOpen: Boolean,
        val modelLoaded: Boolean,
        val modelFile: ModelFile,
    )

    data class SmbAttempt(
        val atMs: Long,
        val outcome: SmbOutcome,
        val totalRows: Long,
        val samplesAfterFilter: Int,
        val rowsRejectedByFilter: Long,
        val detail: String,
    )

    enum class SmbOutcome {
        TRAINED,
        NO_CSV,
        REFUSED_HEADER,
        TOO_FEW_SAMPLES,
        REJECTED_BY_GATES,
        ERROR,
        WAITING_NOT_DUE,
        WAITING_CIRCUIT_OPEN,
    }

    enum class SmbWaiting {
        NOT_DUE,
        CIRCUIT_OPEN,
    }
}
