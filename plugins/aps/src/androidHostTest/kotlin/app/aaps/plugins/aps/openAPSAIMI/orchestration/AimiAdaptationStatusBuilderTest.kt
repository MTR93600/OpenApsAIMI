package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AimiAdaptationMetricId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.NGRResult
import app.aaps.plugins.aps.openAPSAIMI.NGRState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdaptivePkPdStatusSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.DiaGovernorResult
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdUpdateReason
import app.aaps.plugins.aps.openAPSAIMI.pkpd.TapPeakGovernorResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AimiAdaptationStatusBuilderTest {

    @Test
    fun `basal governance is enabled by either production path`() {
        assertEquals(true, AimiAdaptationStatusBuilder.basalGovernanceEnabled(true, false))
        assertEquals(true, AimiAdaptationStatusBuilder.basalGovernanceEnabled(false, true))
        assertEquals(false, AimiAdaptationStatusBuilder.basalGovernanceEnabled(false, false))
    }

    @Test
    fun `basal learner can remain enabled independently of governance`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                basalGovernanceEnabled = false,
                basalLearnerEnabled = true,
            )
        )

        val basalLearner = status.modules.single { it.moduleId == AimiAdaptationModuleId.BASAL_MULTI_SCALE }
        assertEquals(AimiAdaptationPhase.WAITING, basalLearner.phase)
        assertEquals(AimiAdaptationReasonCode.WAITING_FOR_SAMPLES, basalLearner.reason)
    }

    @Test
    fun `disabled and contextual modules never fabricate progress`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                ngrEnabled = true,
                ngr = NGRResult(NGRState.INACTIVE, 1.0, 1.0, 0.0, ""),
                wCycleEnabled = true,
            )
        )

        val governance = status.modules.single { it.moduleId == AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE }
        val ngr = status.modules.single { it.moduleId == AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE }
        val wCycle = status.modules.single { it.moduleId == AimiAdaptationModuleId.WCYCLE }

        assertEquals(AimiAdaptationPhase.DISABLED, governance.phase)
        assertEquals(AimiAdaptationPhase.READY, ngr.phase)
        assertEquals(AimiAdaptationPhase.WAITING, wCycle.phase)
        assertNull(governance.progress)
        assertNull(ngr.progress)
        assertNull(wCycle.progress)
    }

    @Test
    fun `enabled learners wait without fallback progress`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                basalGovernanceEnabled = true,
                unifiedReactivityEnabled = true,
                basalLearnerEnabled = true,
                pkpdEnabled = true,
                onlineLearnerEnabled = true,
            )
        )

        status.modules
            .filter {
                it.moduleId in setOf(
                    AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE,
                    AimiAdaptationModuleId.UNIFIED_REACTIVITY,
                    AimiAdaptationModuleId.BASAL_MULTI_SCALE,
                    AimiAdaptationModuleId.PKPD,
                    AimiAdaptationModuleId.ONLINE_LEARNER,
                )
            }
            .forEach {
                assertEquals(AimiAdaptationPhase.WAITING, it.phase)
                assertNull(it.progress)
            }
    }

    @Test
    fun `PKPD eligibility skip waits without fabricated progress`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                pkpdEnabled = true,
                pkpd = pkpdStatus(
                    reason = PkPdUpdateReason.IOB_TOO_LOW,
                    acceptedCount = 0L,
                ),
            )
        )

        val pkpd = status.modules.single { it.moduleId == AimiAdaptationModuleId.PKPD }
        assertEquals(AimiAdaptationPhase.WAITING, pkpd.phase)
        assertEquals(AimiAdaptationReasonCode.WAITING_FOR_EVENTS, pkpd.reason)
        assertNull(pkpd.progress)
    }

    @Test
    fun `accepted PKPD event is active without target progress`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                pkpdEnabled = true,
                pkpd = pkpdStatus(
                    reason = PkPdUpdateReason.ACCEPTED,
                    acceptedCount = 4L,
                ),
            )
        )

        val pkpd = status.modules.single { it.moduleId == AimiAdaptationModuleId.PKPD }
        assertEquals(AimiAdaptationPhase.ACTIVE, pkpd.phase)
        assertNull(pkpd.progress)
        assertEquals(
            4.0,
            pkpd.metrics.single { it.id == AimiAdaptationMetricId.ACCEPTED_UPDATE_COUNT }.value,
        )
    }

    @Test
    fun `builder preserves producer phase regardless of snapshot age`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                now = 10_000_000L,
                pkpdEnabled = true,
                pkpd = pkpdStatus(
                    reason = PkPdUpdateReason.ACCEPTED,
                    acceptedCount = 1L,
                ),
            )
        )

        val pkpd = status.modules.single { it.moduleId == AimiAdaptationModuleId.PKPD }
        assertEquals(AimiAdaptationPhase.ACTIVE, pkpd.phase)
        assertEquals(AimiAdaptationReasonCode.ACTIVE, pkpd.reason)
    }

    @Test
    fun `evaluated online feedback is active without target progress`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                onlineLearnerEnabled = true,
                onlineLearner = OnlineLearner.StatusSnapshot(
                    pendingPredictionCount = 2,
                    evaluatedFeedbackCount = 7L,
                    releaseCount = 1L,
                    learnedSensitivityFactor = 0.98,
                    lastFeedbackAt = 900L,
                    lastError = -4.0,
                    updatedAt = 1_000L,
                ),
            )
        )

        val online = status.modules.single { it.moduleId == AimiAdaptationModuleId.ONLINE_LEARNER }
        assertEquals(AimiAdaptationPhase.ACTIVE, online.phase)
        assertNull(online.progress)
        assertEquals(
            7.0,
            online.metrics.single { it.id == AimiAdaptationMetricId.EVALUATED_FEEDBACK_COUNT }.value,
        )
    }

    @Test
    fun `aggregate reports READY separately from every other phase`() {
        val status = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                ngrEnabled = true,
                ngr = NGRResult(NGRState.INACTIVE, 1.0, 1.0, 0.0, ""),
                wCycleEnabled = true,
            )
        )

        assertEquals(0, status.activeCount)
        assertEquals(1, status.readyCount)
        assertEquals(1, status.waitingCount)
        assertEquals(0, status.learningCount)
        assertEquals(0, status.blockedCount)
        assertEquals(0, status.staleCount)
        assertEquals(7, status.disabledCount)
        assertEquals(status.modules.size, status.activeCount + status.readyCount + status.waitingCount +
            status.learningCount + status.blockedCount + status.staleCount + status.disabledCount)
    }

    @Test
    fun `enrich governors replaces placeholders and recomputes aggregate counts`() {
        val partial = AimiAdaptationStatusBuilder.build(
            emptyInput().copy(
                pkpdEnabled = true,
                peakGovernorEnabled = true,
                diaGovernorEnabled = true,
            )
        )
        val enriched = AimiAdaptationStatusBuilder.enrichGovernors(
            status = partial,
            now = 2_000L,
            pkpdEnabled = true,
            peakGovernorEnabled = true,
            peakGovernor = TapPeakGovernorResult(
                effectivePeakMinutes = 62.0,
                peakPrior = 55.0,
                peakPhysio = 2.0,
                peakSite = 1.0,
                peakTrajectory = 0.0,
                peakLearned = 70.0,
                dominantBranch = "LEARNED",
                logLine = null,
                appliedGovernor = true,
            ),
            diaGovernorEnabled = true,
            diaGovernor = DiaGovernorResult(
                effectiveDiaHours = 5.2,
                diaPriorHours = 5.0,
                diaContextualShiftHours = 0.0,
                diaLearnedHours = 5.5,
                dominantBranch = "LEARNED",
                logLine = null,
                appliedGovernor = true,
            ),
        )

        val peak = enriched.modules.single { it.moduleId == AimiAdaptationModuleId.PEAK_GOVERNOR }
        val dia = enriched.modules.single { it.moduleId == AimiAdaptationModuleId.DIA_GOVERNOR }
        assertEquals(AimiAdaptationPhase.ACTIVE, peak.phase)
        assertEquals(AimiAdaptationPhase.ACTIVE, dia.phase)
        assertEquals(62.0, peak.metrics.single { it.id == AimiAdaptationMetricId.EFFECTIVE_VALUE }.value)
        assertEquals(5.2, dia.metrics.single { it.id == AimiAdaptationMetricId.EFFECTIVE_VALUE }.value)
        assertEquals(2, enriched.activeCount)
        assertEquals(1, enriched.waitingCount)
        assertEquals(6, enriched.disabledCount)
    }

    @Test
    fun `RT status remains outside serialized wire format`() {
        val rt = RT(
            algorithm = APSResult.Algorithm.AIMI,
            runningDynamicIsf = false,
            aimiAdaptationStatus = AimiAdaptationStatusBuilder.build(emptyInput()),
        )

        assertFalse(rt.serialize().contains("aimiAdaptationStatus"))
    }

    private fun emptyInput() = AimiAdaptationStatusBuilder.BuildInput(
        now = 1_000L,
        basalGovernanceEnabled = false,
        basalGovernance = null,
        unifiedReactivityEnabled = false,
        unifiedReactivity = null,
        basalLearnerEnabled = false,
        basalLearner = null,
        pkpdEnabled = false,
        pkpd = null,
        onlineLearnerEnabled = false,
        onlineLearner = null,
        ngrEnabled = false,
        ngr = null,
        wCycleEnabled = false,
        wCycle = null,
        peakGovernorEnabled = false,
        diaGovernorEnabled = false,
    )

    private fun pkpdStatus(
        reason: PkPdUpdateReason,
        acceptedCount: Long,
    ) = AdaptivePkPdStatusSnapshot(
        params = PkPdParams(diaHrs = 4.0, peakMin = 75.0),
        acceptedUpdateCount = acceptedCount,
        latestReason = reason,
        lastAcceptedUpdateAt = if (acceptedCount > 0L) 1_000L else null,
        updatedAt = 1_000L,
    )
}
