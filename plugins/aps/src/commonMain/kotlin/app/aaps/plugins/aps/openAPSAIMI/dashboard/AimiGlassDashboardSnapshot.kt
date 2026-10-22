package app.aaps.plugins.aps.openAPSAIMI.dashboard

import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbTrainer
import app.aaps.plugins.aps.openAPSAIMI.ml.TrainingOutcome
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

/**
 * Read-only, unit-explicit subset of AIMI runtime data used by the GLASS dashboard.
 *
 * A null terminal means that the corresponding published trajectory has no finite last point.
 * It is deliberately not converted to zero or to a made-up display value here.
 */
data class AimiGlassDashboardSnapshot(
    val hybridTerminalMgdl: Double?,
    val iobTerminalMgdl: Double?,
    val cobTerminalMgdl: Double?,
    val uamTerminalMgdl: Double?,
    val physio: PhysioLiveDigest?,
    val trajectoryPublishedAtMs: Long?,
    val trajectoryIsStale: Boolean,
) {
    data class PhysioPresentation(
        val modeHeadline: String,
        val intentSummary: String,
        val thermalSummary: String,
        val updatedSummary: String,
    )

    companion object {

        /**
         * Matches the existing Overview calculation freshness rule (older than nine minutes is
         * outdated). Forecasts older than that are not presented as current; the UI shows its
         * existing no-recent-prediction state instead.
         */
        const val TRAJECTORY_STALE_AFTER_MS: Long = 9L * 60L * 1000L

        fun from(
            curves: AdvancedPredictionCurves?,
            physio: PhysioLiveDigest?,
            trajectoryPublishedAtMs: Long? = null,
            nowEpochMs: Long = aimiWallClockMs(),
        ): AimiGlassDashboardSnapshot =
            AimiGlassDashboardSnapshot(
                hybridTerminalMgdl = curves?.hybrid?.finiteLast(),
                iobTerminalMgdl = curves?.iob?.finiteLast(),
                cobTerminalMgdl = curves?.cob?.finiteLast(),
                uamTerminalMgdl = curves?.uam?.finiteLast(),
                physio = physio,
                trajectoryPublishedAtMs = trajectoryPublishedAtMs,
                trajectoryIsStale = curves != null && (
                    trajectoryPublishedAtMs == null ||
                        nowEpochMs < trajectoryPublishedAtMs ||
                        nowEpochMs - trajectoryPublishedAtMs > TRAJECTORY_STALE_AFTER_MS
                    ),
            )

        private fun List<Double>.finiteLast(): Double? = lastOrNull()?.takeIf(Double::isFinite)
    }

    fun toSharedData(
        physioPresentation: PhysioPresentation? = null,
        basalTraining: BasalMlDashboardSnapshot? = null,
        smbTraining: AimiSmbDashboardSnapshot? = null,
        keyFactors: AimiGlassDashboardData.KeyFactors? = null,
    ): AimiGlassDashboardData =
        AimiGlassDashboardData(
            trajectory = if (
                hybridTerminalMgdl == null && iobTerminalMgdl == null &&
                cobTerminalMgdl == null && uamTerminalMgdl == null
            ) {
                null
            } else {
                AimiGlassDashboardData.Trajectory(
                    hybridTerminalMgdl = hybridTerminalMgdl,
                    iobTerminalMgdl = iobTerminalMgdl,
                    cobTerminalMgdl = cobTerminalMgdl,
                    uamTerminalMgdl = uamTerminalMgdl,
                    publishedAtMs = trajectoryPublishedAtMs,
                    isStale = trajectoryIsStale,
                )
            },
            physio = physio?.let {
                AimiGlassDashboardData.Physio(
                    stepsLast15m = it.stepsLast15m,
                    stepsLast60m = it.stepsLast60m,
                    hrNowBpm = it.hrNowBpm,
                    hrAvg15mBpm = it.hrAvg15mBpm,
                    rhrRestingBpm = it.rhrRestingBpm,
                    hrvRmssd = it.hrvRmssd,
                    activityState = it.activityState,
                    sleepDebtMinutes = it.sleepDebtMinutes,
                    asleepLiveConfidence = it.asleepLiveConfidence,
                    asleepLiveSource = it.asleepLiveSource,
                    snapshotAgeMs = it.snapshotAgeMs,
                    hrSampleAgeMs = it.hrSampleAgeMs,
                    source = it.source,
                    confidence = it.confidence,
                    thermalHypothesis = it.thermalHypothesis,
                    thermalDeltaVsBaselineC = it.thermalDeltaVsBaselineC,
                    thermalInflammationIndex = it.thermalInflammationIndex,
                    thermalNarrative = it.thermalNarrative,
                    presentation = physioPresentation?.let { presentation ->
                        AimiGlassDashboardData.Physio.Presentation(
                            modeHeadline = presentation.modeHeadline,
                            intentSummary = presentation.intentSummary,
                            thermalSummary = presentation.thermalSummary,
                            updatedSummary = presentation.updatedSummary,
                        )
                    },
                )
            },
            training = if (basalTraining == null && smbTraining == null) {
                null
            } else {
                AimiGlassDashboardData.Training(
                    basal = basalTraining?.let {
                        AimiGlassDashboardData.BasalTraining(
                            lastTrainedAtMs = it.lastTrainedAtMs,
                            sampleCount = it.sampleCount,
                            circuitOpen = it.circuitOpen,
                            modelFile = AimiGlassDashboardData.ModelFile(
                                exists = it.modelFileExists,
                                modifiedAtMs = it.modelFileModifiedAtMs,
                                sizeBytes = it.modelFileSizeBytes,
                            ),
                        )
                    },
                    smb = smbTraining?.let {
                        AimiGlassDashboardData.SmbTraining(
                            lastAttempt = it.lastAttempt?.let { attempt ->
                                AimiGlassDashboardData.SmbAttempt(
                                    atMs = attempt.atMs,
                                    outcome = attempt.outcome.toSharedOutcome(),
                                    totalRows = attempt.totalRows,
                                    samplesAfterFilter = attempt.samplesAfterFilter,
                                    rowsRejectedByFilter = attempt.rowsRejectedByFilter,
                                    detail = attempt.gateDetail,
                                )
                            },
                            waiting = it.waiting?.outcome?.let { outcome ->
                                when (outcome) {
                                    TrainingOutcome.SKIPPED_NOT_DUE -> AimiGlassDashboardData.SmbWaiting.NOT_DUE
                                    TrainingOutcome.CIRCUIT_OPEN -> AimiGlassDashboardData.SmbWaiting.CIRCUIT_OPEN
                                    else -> null
                                }
                            },
                            lastTrainedAtMs = it.lastTrainedAtMs,
                            circuitOpen = it.circuitOpen,
                            modelLoaded = it.modelLoaded,
                            modelFile = AimiGlassDashboardData.ModelFile(
                                exists = it.modelFileExists,
                                modifiedAtMs = it.modelFileModifiedAtMs,
                                sizeBytes = it.modelFileSizeBytes,
                            ),
                        )
                    },
                )
            },
            keyFactors = keyFactors,
        )

    private fun TrainingOutcome.toSharedOutcome(): AimiGlassDashboardData.SmbOutcome = when (this) {
        TrainingOutcome.TRAINED -> AimiGlassDashboardData.SmbOutcome.TRAINED
        TrainingOutcome.SKIPPED_NO_CSV -> AimiGlassDashboardData.SmbOutcome.NO_CSV
        TrainingOutcome.REFUSED_HEADER -> AimiGlassDashboardData.SmbOutcome.REFUSED_HEADER
        TrainingOutcome.TOO_FEW_SAMPLES -> AimiGlassDashboardData.SmbOutcome.TOO_FEW_SAMPLES
        TrainingOutcome.REJECTED_BY_GATES -> AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES
        TrainingOutcome.ERROR -> AimiGlassDashboardData.SmbOutcome.ERROR
        TrainingOutcome.SKIPPED_NOT_DUE -> AimiGlassDashboardData.SmbOutcome.WAITING_NOT_DUE
        TrainingOutcome.CIRCUIT_OPEN -> AimiGlassDashboardData.SmbOutcome.WAITING_CIRCUIT_OPEN
    }

}

/**
 * Shared observable read-only feed for Glass-specific AIMI metrics.
 *
 * This aggregates read-only runtime snapshots and one profile-values provider. It performs no
 * prediction, calculation-policy, truncation, training, or dosing work.
 */
object AimiGlassDashboardSnapshotFeed {

    private data class TrainingStorage(val storage: AimiStorage, val directory: AimiPath)

    private val trainingStorage = MutableStateFlow<TrainingStorage?>(null)
    private val keyFactorsProvider =
        MutableStateFlow<(() -> AimiGlassDashboardData.KeyFactors)?>(null)

    /** Called by the AIMI plugin at startup with the exact storage location used by its SMB trainer. */
    fun configureTrainingStorage(storage: AimiStorage, directory: AimiPath) {
        trainingStorage.value = TrainingStorage(storage, directory)
    }

    /** Supplies read-only key factors from the active AIMI runtime and profile. */
    fun configureKeyFactorsProvider(provider: () -> AimiGlassDashboardData.KeyFactors) {
        keyFactorsProvider.value = provider
    }

    fun observe(): Flow<AimiGlassDashboardData> = channelFlow {
        val trajectoryJob = launch(start = CoroutineStart.UNDISPATCHED) {
            TrajectoryRuntimeRepository.updates.collect { send(currentSnapshot()) }
        }
        val patientStateJob = launch(start = CoroutineStart.UNDISPATCHED) {
            PatientStateRuntimeRepository.updates.collect { send(currentSnapshot()) }
        }
        val storageJob = launch(start = CoroutineStart.UNDISPATCHED) {
            trainingStorage.collect { send(currentSnapshot()) }
        }
        val smbTrainingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            AimiSmbTrainer.dashboardUpdates.collect { send(currentSnapshot()) }
        }
        val freshnessJob = launch(start = CoroutineStart.UNDISPATCHED) {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                send(currentSnapshot())
            }
        }
        val basalCoordinator = BasalMlTrainingCoordinator.instance
        val basalTrainingJob = basalCoordinator?.let { coordinator ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                coordinator.dashboardUpdates.collect { send(currentSnapshot()) }
            }
        }

        send(currentSnapshot())
        awaitClose {
            trajectoryJob.cancel()
            patientStateJob.cancel()
            storageJob.cancel()
            smbTrainingJob.cancel()
            basalTrainingJob?.cancel()
            freshnessJob.cancel()
        }
    }

    private fun currentSnapshot(): AimiGlassDashboardData {
        val nowEpochMs = aimiWallClockMs()
        val patientRuntime = PatientStateRuntimeRepository.getLatest()
        val presentation = patientRuntime?.let { runtime ->
            PatientStatePresentationBuilder.build(
                runtime,
                nowEpochMs,
            ).let {
                AimiGlassDashboardSnapshot.PhysioPresentation(
                    modeHeadline = it.modeHeadline,
                    intentSummary = it.intentSummary,
                    thermalSummary = it.thermalSummary,
                    updatedSummary = it.updatedSummary,
                )
            }
        }
        val trajectory = TrajectoryRuntimeRepository.getLatestSnapshot()
        return AimiGlassDashboardSnapshot.from(
            curves = trajectory?.curves,
            physio = patientRuntime?.physioLive,
            trajectoryPublishedAtMs = trajectory?.publishedAtMs,
            nowEpochMs = nowEpochMs,
        ).toSharedData(
            physioPresentation = presentation,
            basalTraining = BasalMlTrainingCoordinator.instance?.dashboardSnapshot(),
            smbTraining = trainingStorage.value?.let { configured ->
                AimiSmbTrainer.dashboardSnapshot(configured.storage, configured.directory)
            },
            keyFactors = keyFactorsProvider.value?.invoke(),
        )
    }
}
