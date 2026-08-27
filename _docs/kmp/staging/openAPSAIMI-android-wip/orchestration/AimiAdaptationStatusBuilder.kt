package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AimiAdaptationEvidenceType
import app.aaps.core.interfaces.aps.AimiAdaptationMetric
import app.aaps.core.interfaces.aps.AimiAdaptationMetricId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleStatus
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationProgress
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.AimiAdaptationStatus
import app.aaps.plugins.aps.openAPSAIMI.NGRResult
import app.aaps.plugins.aps.openAPSAIMI.NGRState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.learning.UnifiedReactivityLearner
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdaptivePkPdStatusSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.DiaGovernorResult
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdUpdateReason
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapPeakGovernorResult
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleInfo

object AimiAdaptationStatusBuilder {

    private const val GOVERNANCE_READY_SAMPLES = 36
    private const val REACTIVITY_READY_SAMPLES = 12

    internal fun basalGovernanceEnabled(
        t3cBrittleModeEnabled: Boolean,
        adaptiveBasalEnabled: Boolean,
    ): Boolean = t3cBrittleModeEnabled || adaptiveBasalEnabled

    data class BuildInput(
        val now: Long,
        val basalGovernanceEnabled: Boolean,
        val basalGovernance: BasalNeuralLearner.GovernanceSnapshot?,
        val unifiedReactivityEnabled: Boolean,
        val unifiedReactivity: UnifiedReactivityLearner.StatusSnapshot?,
        val basalLearnerEnabled: Boolean,
        val basalLearner: BasalLearner.StatusSnapshot?,
        val pkpdEnabled: Boolean,
        val pkpd: AdaptivePkPdStatusSnapshot?,
        val onlineLearnerEnabled: Boolean,
        val onlineLearner: OnlineLearner.StatusSnapshot?,
        val ngrEnabled: Boolean,
        val ngr: NGRResult?,
        val wCycleEnabled: Boolean,
        val wCycle: WCycleInfo?,
        val peakGovernorEnabled: Boolean,
        val peakGovernor: TapPeakGovernorResult? = null,
        val diaGovernorEnabled: Boolean,
        val diaGovernor: DiaGovernorResult? = null,
    )

    fun build(input: BuildInput): AimiAdaptationStatus {
        val modules = listOf(
            basalGovernance(input),
            unifiedReactivity(input),
            basalLearner(input),
            pkpd(input),
            onlineLearner(input),
            ngr(input),
            wCycle(input),
            peakGovernor(input),
            diaGovernor(input),
        )
        return aggregate(input.now, modules)
    }

    fun enrichGovernors(
        status: AimiAdaptationStatus,
        now: Long,
        pkpdEnabled: Boolean,
        peakGovernorEnabled: Boolean,
        peakGovernor: TapPeakGovernorResult?,
        diaGovernorEnabled: Boolean,
        diaGovernor: DiaGovernorResult?,
    ): AimiAdaptationStatus {
        val replacements = listOf(
            peakGovernor(
                enabled = peakGovernorEnabled,
                pkpdEnabled = pkpdEnabled,
                result = peakGovernor,
                now = now,
            ),
            diaGovernor(
                enabled = diaGovernorEnabled,
                pkpdEnabled = pkpdEnabled,
                result = diaGovernor,
                now = now,
            ),
        ).associateBy { it.moduleId }
        return aggregate(
            now,
            status.modules.map { replacements[it.moduleId] ?: it },
        )
    }

    private fun basalGovernance(input: BuildInput): AimiAdaptationModuleStatus {
        val snapshot = input.basalGovernance
        if (!input.basalGovernanceEnabled) return disabled(AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE)
        if (snapshot == null || snapshot.sampleCount == 0) {
            return waiting(AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE, AimiAdaptationReasonCode.WAITING_FOR_SAMPLES)
        }
        val phase = when {
            snapshot.sampleCount < GOVERNANCE_READY_SAMPLES -> AimiAdaptationPhase.LEARNING
            snapshot.action == BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE -> AimiAdaptationPhase.ACTIVE
            else -> AimiAdaptationPhase.READY
        }
        val reason = when {
            snapshot.sampleCount < GOVERNANCE_READY_SAMPLES -> AimiAdaptationReasonCode.WARMUP
            snapshot.action == BasalNeuralLearner.GovernanceAction.HOLD_CONSERVATIVE -> AimiAdaptationReasonCode.SAFETY_HOLD
            else -> AimiAdaptationReasonCode.READY
        }
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE,
            phase = phase,
            reason = reason,
            progress = AimiAdaptationProgress(
                AimiAdaptationEvidenceType.SAMPLES,
                snapshot.sampleCount.coerceAtMost(GOVERNANCE_READY_SAMPLES),
                GOVERNANCE_READY_SAMPLES,
            ),
            metrics = listOf(
                metric(AimiAdaptationMetricId.SAMPLE_COUNT, snapshot.sampleCount),
                metric(AimiAdaptationMetricId.CONFIDENCE, snapshot.confidence),
            ),
            updatedAt = snapshot.timestamp,
        )
    }

    private fun unifiedReactivity(input: BuildInput): AimiAdaptationModuleStatus {
        val snapshot = input.unifiedReactivity
        if (!input.unifiedReactivityEnabled) return disabled(AimiAdaptationModuleId.UNIFIED_REACTIVITY)
        if (snapshot == null || snapshot.last24hSampleCount == 0) {
            return waiting(AimiAdaptationModuleId.UNIFIED_REACTIVITY, AimiAdaptationReasonCode.WAITING_FOR_SAMPLES)
        }
        val ready = snapshot.longAnalysisCount > 0L && snapshot.last24hSampleCount >= REACTIVITY_READY_SAMPLES
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.UNIFIED_REACTIVITY,
            phase = if (ready) AimiAdaptationPhase.ACTIVE else AimiAdaptationPhase.LEARNING,
            reason = if (ready) AimiAdaptationReasonCode.ACTIVE else AimiAdaptationReasonCode.LEARNING_IN_PROGRESS,
            progress = AimiAdaptationProgress(
                AimiAdaptationEvidenceType.SAMPLES,
                snapshot.last24hSampleCount.coerceAtMost(REACTIVITY_READY_SAMPLES),
                REACTIVITY_READY_SAMPLES,
            ),
            metrics = listOfNotNull(
                metric(AimiAdaptationMetricId.SAMPLE_COUNT, snapshot.last24hSampleCount),
                metric(AimiAdaptationMetricId.SHORT_ANALYSIS_COUNT, snapshot.shortAnalysisCount),
                metric(AimiAdaptationMetricId.LONG_ANALYSIS_COUNT, snapshot.longAnalysisCount),
                metric(AimiAdaptationMetricId.GLOBAL_FACTOR, snapshot.globalFactor),
                metric(AimiAdaptationMetricId.SHORT_FACTOR, snapshot.shortTermFactor),
                metric(AimiAdaptationMetricId.COMBINED_MULTIPLIER, snapshot.combinedFactor),
                snapshot.lastAnalysis?.let { metric(AimiAdaptationMetricId.TIR_PERCENT, it.tir70_180) },
                snapshot.lastAnalysis?.let { metric(AimiAdaptationMetricId.CV_PERCENT, it.cv_percent) },
            ),
            updatedAt = snapshot.updatedAt,
        )
    }

    private fun basalLearner(input: BuildInput): AimiAdaptationModuleStatus {
        val snapshot = input.basalLearner
        if (!input.basalLearnerEnabled) return disabled(AimiAdaptationModuleId.BASAL_MULTI_SCALE)
        if (snapshot == null || snapshot.mediumBufferCount == 0) {
            return waiting(AimiAdaptationModuleId.BASAL_MULTI_SCALE, AimiAdaptationReasonCode.WAITING_FOR_SAMPLES)
        }
        val updateCount = snapshot.shortUpdateCount + snapshot.mediumUpdateCount + snapshot.longUpdateCount
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.BASAL_MULTI_SCALE,
            phase = if (updateCount > 0L) AimiAdaptationPhase.ACTIVE else AimiAdaptationPhase.LEARNING,
            reason = if (updateCount > 0L) AimiAdaptationReasonCode.ACTIVE else AimiAdaptationReasonCode.LEARNING_IN_PROGRESS,
            metrics = listOf(
                metric(AimiAdaptationMetricId.SHORT_BUFFER_COUNT, snapshot.shortBufferCount),
                metric(AimiAdaptationMetricId.MEDIUM_BUFFER_COUNT, snapshot.mediumBufferCount),
                metric(AimiAdaptationMetricId.FASTING_SAMPLE_COUNT, snapshot.fastingSampleCount),
                metric(AimiAdaptationMetricId.SHORT_UPDATE_COUNT, snapshot.shortUpdateCount),
                metric(AimiAdaptationMetricId.MEDIUM_UPDATE_COUNT, snapshot.mediumUpdateCount),
                metric(AimiAdaptationMetricId.LONG_UPDATE_COUNT, snapshot.longUpdateCount),
                metric(AimiAdaptationMetricId.SHORT_MULTIPLIER, snapshot.shortTermMultiplier),
                metric(AimiAdaptationMetricId.MEDIUM_MULTIPLIER, snapshot.mediumTermMultiplier),
                metric(AimiAdaptationMetricId.LONG_MULTIPLIER, snapshot.longTermMultiplier),
                metric(AimiAdaptationMetricId.COMBINED_MULTIPLIER, snapshot.combinedMultiplier),
            ),
            updatedAt = snapshot.updatedAt,
        )
    }

    private fun pkpd(input: BuildInput): AimiAdaptationModuleStatus {
        val snapshot = input.pkpd
        if (!input.pkpdEnabled) return disabled(AimiAdaptationModuleId.PKPD)
        if (snapshot == null || snapshot.latestReason == PkPdUpdateReason.NOT_UPDATED) {
            return waiting(AimiAdaptationModuleId.PKPD, AimiAdaptationReasonCode.WAITING_FOR_EVENTS)
        }
        val accepted = snapshot.latestReason == PkPdUpdateReason.ACCEPTED
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.PKPD,
            phase = if (accepted) AimiAdaptationPhase.ACTIVE else AimiAdaptationPhase.WAITING,
            reason = if (accepted) AimiAdaptationReasonCode.ACTIVE else AimiAdaptationReasonCode.WAITING_FOR_EVENTS,
            metrics = listOf(
                metric(AimiAdaptationMetricId.ACCEPTED_UPDATE_COUNT, snapshot.acceptedUpdateCount),
                metric(AimiAdaptationMetricId.DIA_HOURS, snapshot.params.diaHrs),
                metric(AimiAdaptationMetricId.PEAK_MINUTES, snapshot.params.peakMin),
            ),
            updatedAt = snapshot.updatedAt,
        )
    }

    private fun onlineLearner(input: BuildInput): AimiAdaptationModuleStatus {
        val snapshot = input.onlineLearner
        if (!input.onlineLearnerEnabled) return disabled(AimiAdaptationModuleId.ONLINE_LEARNER)
        if (snapshot == null || snapshot.evaluatedFeedbackCount == 0L) {
            return AimiAdaptationModuleStatus(
                moduleId = AimiAdaptationModuleId.ONLINE_LEARNER,
                phase = AimiAdaptationPhase.WAITING,
                reason = AimiAdaptationReasonCode.WAITING_FOR_FEEDBACKS,
                metrics = snapshot?.let {
                    listOf(
                        metric(AimiAdaptationMetricId.PENDING_PREDICTION_COUNT, it.pendingPredictionCount),
                        metric(AimiAdaptationMetricId.SENSITIVITY_FACTOR, it.learnedSensitivityFactor),
                    )
                } ?: emptyList(),
                updatedAt = snapshot?.updatedAt,
            )
        }
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.ONLINE_LEARNER,
            phase = AimiAdaptationPhase.ACTIVE,
            reason = AimiAdaptationReasonCode.ACTIVE,
            metrics = listOfNotNull(
                metric(AimiAdaptationMetricId.PENDING_PREDICTION_COUNT, snapshot.pendingPredictionCount),
                metric(AimiAdaptationMetricId.EVALUATED_FEEDBACK_COUNT, snapshot.evaluatedFeedbackCount),
                metric(AimiAdaptationMetricId.RELEASE_COUNT, snapshot.releaseCount),
                metric(AimiAdaptationMetricId.SENSITIVITY_FACTOR, snapshot.learnedSensitivityFactor),
                snapshot.lastError?.let { metric(AimiAdaptationMetricId.LAST_ERROR, it) },
            ),
            updatedAt = snapshot.updatedAt,
        )
    }

    private fun ngr(input: BuildInput): AimiAdaptationModuleStatus {
        if (!input.ngrEnabled) return disabled(AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE)
        val result = input.ngr
            ?: return waiting(AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE, AimiAdaptationReasonCode.NO_RESULT)
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE,
            phase = if (result.state == NGRState.INACTIVE) AimiAdaptationPhase.READY else AimiAdaptationPhase.ACTIVE,
            reason = if (result.state == NGRState.INACTIVE) AimiAdaptationReasonCode.READY else AimiAdaptationReasonCode.ACTIVE,
            metrics = listOf(
                metric(AimiAdaptationMetricId.SMB_MULTIPLIER, result.smbMultiplier),
                metric(AimiAdaptationMetricId.BASAL_MULTIPLIER, result.basalMultiplier),
                metric(AimiAdaptationMetricId.EXTRA_IOB_HEADROOM_UNITS, result.extraIOBHeadroomU),
            ),
            updatedAt = input.now,
        )
    }

    private fun wCycle(input: BuildInput): AimiAdaptationModuleStatus {
        if (!input.wCycleEnabled) return disabled(AimiAdaptationModuleId.WCYCLE)
        val info = input.wCycle
            ?: return waiting(AimiAdaptationModuleId.WCYCLE, AimiAdaptationReasonCode.WAITING_FOR_OBSERVATIONS)
        return AimiAdaptationModuleStatus(
            moduleId = AimiAdaptationModuleId.WCYCLE,
            phase = if (info.applied) AimiAdaptationPhase.ACTIVE else AimiAdaptationPhase.READY,
            reason = if (info.applied) AimiAdaptationReasonCode.ACTIVE else AimiAdaptationReasonCode.READY,
            metrics = listOf(
                metric(AimiAdaptationMetricId.DAY_IN_CYCLE, info.dayInCycle),
                metric(AimiAdaptationMetricId.BASAL_MULTIPLIER, info.basalMultiplier),
                metric(AimiAdaptationMetricId.SMB_MULTIPLIER, info.smbMultiplier),
                metric(AimiAdaptationMetricId.IC_MULTIPLIER, info.icMultiplier),
            ),
            updatedAt = input.now,
        )
    }

    private fun peakGovernor(input: BuildInput): AimiAdaptationModuleStatus =
        peakGovernor(input.peakGovernorEnabled, input.pkpdEnabled, input.peakGovernor, input.now)

    private fun peakGovernor(
        enabled: Boolean,
        pkpdEnabled: Boolean,
        result: TapPeakGovernorResult?,
        now: Long,
    ): AimiAdaptationModuleStatus {
        if (!enabled) return disabled(AimiAdaptationModuleId.PEAK_GOVERNOR)
        if (!pkpdEnabled) return blockedDependency(AimiAdaptationModuleId.PEAK_GOVERNOR)
        if (result == null) return waiting(AimiAdaptationModuleId.PEAK_GOVERNOR, AimiAdaptationReasonCode.NO_RESULT)
        return governorStatus(
            moduleId = AimiAdaptationModuleId.PEAK_GOVERNOR,
            applied = result.appliedGovernor,
            effective = result.effectivePeakMinutes,
            prior = result.peakPrior,
            learned = result.peakLearned,
            now = now,
        )
    }

    private fun diaGovernor(input: BuildInput): AimiAdaptationModuleStatus =
        diaGovernor(input.diaGovernorEnabled, input.pkpdEnabled, input.diaGovernor, input.now)

    private fun diaGovernor(
        enabled: Boolean,
        pkpdEnabled: Boolean,
        result: DiaGovernorResult?,
        now: Long,
    ): AimiAdaptationModuleStatus {
        if (!enabled) return disabled(AimiAdaptationModuleId.DIA_GOVERNOR)
        if (!pkpdEnabled) return blockedDependency(AimiAdaptationModuleId.DIA_GOVERNOR)
        if (result == null) return waiting(AimiAdaptationModuleId.DIA_GOVERNOR, AimiAdaptationReasonCode.NO_RESULT)
        return governorStatus(
            moduleId = AimiAdaptationModuleId.DIA_GOVERNOR,
            applied = result.appliedGovernor,
            effective = result.effectiveDiaHours,
            prior = result.diaPriorHours,
            learned = result.diaLearnedHours,
            now = now,
        )
    }

    private fun governorStatus(
        moduleId: AimiAdaptationModuleId,
        applied: Boolean,
        effective: Double,
        prior: Double,
        learned: Double?,
        now: Long,
    ) = AimiAdaptationModuleStatus(
        moduleId = moduleId,
        phase = if (applied) AimiAdaptationPhase.ACTIVE else AimiAdaptationPhase.READY,
        reason = if (applied) AimiAdaptationReasonCode.ACTIVE else AimiAdaptationReasonCode.READY,
        metrics = listOfNotNull(
            metric(AimiAdaptationMetricId.EFFECTIVE_VALUE, effective),
            metric(AimiAdaptationMetricId.PRIOR_VALUE, prior),
            learned?.let { metric(AimiAdaptationMetricId.LEARNED_VALUE, it) },
        ),
        updatedAt = now,
    )

    private fun disabled(moduleId: AimiAdaptationModuleId) = AimiAdaptationModuleStatus(
        moduleId = moduleId,
        phase = AimiAdaptationPhase.DISABLED,
        reason = AimiAdaptationReasonCode.FEATURE_DISABLED,
    )

    private fun waiting(
        moduleId: AimiAdaptationModuleId,
        reason: AimiAdaptationReasonCode,
    ) = AimiAdaptationModuleStatus(
        moduleId = moduleId,
        phase = AimiAdaptationPhase.WAITING,
        reason = reason,
    )

    private fun blockedDependency(moduleId: AimiAdaptationModuleId) = AimiAdaptationModuleStatus(
        moduleId = moduleId,
        phase = AimiAdaptationPhase.BLOCKED,
        reason = AimiAdaptationReasonCode.DEPENDENCY_DISABLED,
    )

    private fun metric(id: AimiAdaptationMetricId, value: Number) =
        AimiAdaptationMetric(id, value.toDouble())

    private fun aggregate(
        now: Long,
        modules: List<AimiAdaptationModuleStatus>,
    ) = AimiAdaptationStatus(
        updatedAt = now,
        modules = modules,
        activeCount = modules.count { it.phase == AimiAdaptationPhase.ACTIVE },
        readyCount = modules.count { it.phase == AimiAdaptationPhase.READY },
        waitingCount = modules.count { it.phase == AimiAdaptationPhase.WAITING },
        learningCount = modules.count { it.phase == AimiAdaptationPhase.LEARNING },
        blockedCount = modules.count { it.phase == AimiAdaptationPhase.BLOCKED },
        staleCount = modules.count { it.phase == AimiAdaptationPhase.STALE },
        disabledCount = modules.count { it.phase == AimiAdaptationPhase.DISABLED },
    )
}
