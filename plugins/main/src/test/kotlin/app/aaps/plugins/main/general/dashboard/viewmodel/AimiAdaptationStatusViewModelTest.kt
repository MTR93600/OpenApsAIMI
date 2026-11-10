package app.aaps.plugins.main.general.dashboard.viewmodel

import app.aaps.core.interfaces.aps.AimiAdaptationEvidenceType
import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleStatus
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationProgress
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.AimiAdaptationStatus
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiAdaptationStatusViewModelTest {

    @Test
    fun `no status produces explicit no-data state`() {
        val result = AimiAdaptationStatusPresenter.present(
            status = null,
            now = NOW,
            staleAfterMs = STALE_AFTER_MS,
        )

        assertThat(result.hasStatus).isFalse()
        assertThat(result.snapshotAgeMillis).isNull()
        assertThat(result.modules).isEmpty()
        assertThat(result.activeCount).isEqualTo(0)
        assertThat(result.attentionCount).isEqualTo(0)
    }

    @Test
    fun `phase counts are recalculated from modules`() {
        val status = statusOf(
            module(AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE, AimiAdaptationPhase.ACTIVE),
            module(AimiAdaptationModuleId.UNIFIED_REACTIVITY, AimiAdaptationPhase.READY),
            module(AimiAdaptationModuleId.BASAL_MULTI_SCALE, AimiAdaptationPhase.WAITING),
            module(AimiAdaptationModuleId.PKPD, AimiAdaptationPhase.LEARNING),
            module(AimiAdaptationModuleId.ONLINE_LEARNER, AimiAdaptationPhase.BLOCKED),
            module(AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE, AimiAdaptationPhase.STALE),
            module(AimiAdaptationModuleId.WCYCLE, AimiAdaptationPhase.DISABLED),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.activeCount).isEqualTo(1)
        assertThat(result.snapshotAgeMillis).isEqualTo(0L)
        assertThat(result.readyCount).isEqualTo(1)
        assertThat(result.waitingOrLearningCount).isEqualTo(2)
        assertThat(result.attentionCount).isEqualTo(2)
        assertThat(result.disabledCount).isEqualTo(1)
    }

    @Test
    fun `old enabled module is normalized to stale before counting`() {
        val oldTimestamp = NOW - STALE_AFTER_MS - 1L
        val status = statusOf(
            module(
                id = AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE,
                phase = AimiAdaptationPhase.ACTIVE,
                updatedAt = oldTimestamp,
            ),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.modules.single().status.phase).isEqualTo(AimiAdaptationPhase.STALE)
        assertThat(result.modules.single().status.reason).isEqualTo(AimiAdaptationReasonCode.DATA_STALE)
        assertThat(result.activeCount).isEqualTo(0)
        assertThat(result.staleCount).isEqualTo(1)
    }

    @Test
    fun `disabled module remains neutral when old`() {
        val status = statusOf(
            module(
                id = AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE,
                phase = AimiAdaptationPhase.DISABLED,
                updatedAt = NOW - STALE_AFTER_MS - 1L,
            ),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.modules.single().status.phase).isEqualTo(AimiAdaptationPhase.DISABLED)
        assertThat(result.disabledCount).isEqualTo(1)
        assertThat(result.staleCount).isEqualTo(0)
    }

    @Test
    fun `module without an update does not borrow aggregate timestamp`() {
        val status = statusOf(
            module(
                id = AimiAdaptationModuleId.ONLINE_LEARNER,
                phase = AimiAdaptationPhase.WAITING,
                updatedAt = null,
            ),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.modules.single().effectiveUpdatedAt).isNull()
        assertThat(result.modules.single().ageMillis).isNull()
        assertThat(result.modules.single().status.phase).isEqualTo(AimiAdaptationPhase.WAITING)
    }

    @Test
    fun `attention modules are presented before routine states`() {
        val status = statusOf(
            module(AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE, AimiAdaptationPhase.READY),
            module(AimiAdaptationModuleId.UNIFIED_REACTIVITY, AimiAdaptationPhase.ACTIVE),
            module(AimiAdaptationModuleId.PKPD, AimiAdaptationPhase.STALE),
            module(AimiAdaptationModuleId.DIA_GOVERNOR, AimiAdaptationPhase.BLOCKED),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.modules.map { it.status.phase }).containsExactly(
            AimiAdaptationPhase.BLOCKED,
            AimiAdaptationPhase.STALE,
            AimiAdaptationPhase.ACTIVE,
            AimiAdaptationPhase.READY,
        ).inOrder()
    }

    @Test
    fun `presenter preserves supplied progress and does not fabricate missing progress`() {
        val progress = AimiAdaptationProgress(
            type = AimiAdaptationEvidenceType.SAMPLES,
            completed = 4,
            required = 12,
        )
        val status = statusOf(
            module(
                id = AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE,
                phase = AimiAdaptationPhase.LEARNING,
                progress = progress,
            ),
            module(
                id = AimiAdaptationModuleId.UNIFIED_REACTIVITY,
                phase = AimiAdaptationPhase.WAITING,
                progress = null,
            ),
        )

        val result = AimiAdaptationStatusPresenter.present(status, NOW, STALE_AFTER_MS)

        assertThat(result.modules[0].status.progress).isSameInstanceAs(progress)
        assertThat(result.modules[1].status.progress).isNull()
    }

    private fun statusOf(vararg modules: AimiAdaptationModuleStatus) = AimiAdaptationStatus(
        updatedAt = NOW,
        modules = modules.toList(),
        activeCount = 99,
        readyCount = 99,
        waitingCount = 99,
        learningCount = 99,
        blockedCount = 99,
        staleCount = 99,
        disabledCount = 99,
    )

    private fun module(
        id: AimiAdaptationModuleId,
        phase: AimiAdaptationPhase,
        updatedAt: Long? = NOW,
        progress: AimiAdaptationProgress? = null,
    ) = AimiAdaptationModuleStatus(
        moduleId = id,
        phase = phase,
        reason = reasonFor(phase),
        progress = progress,
        updatedAt = updatedAt,
    )

    private fun reasonFor(phase: AimiAdaptationPhase) = when (phase) {
        AimiAdaptationPhase.DISABLED -> AimiAdaptationReasonCode.FEATURE_DISABLED
        AimiAdaptationPhase.WAITING -> AimiAdaptationReasonCode.WAITING_FOR_SAMPLES
        AimiAdaptationPhase.LEARNING -> AimiAdaptationReasonCode.LEARNING_IN_PROGRESS
        AimiAdaptationPhase.READY -> AimiAdaptationReasonCode.READY
        AimiAdaptationPhase.ACTIVE -> AimiAdaptationReasonCode.ACTIVE
        AimiAdaptationPhase.STALE -> AimiAdaptationReasonCode.DATA_STALE
        AimiAdaptationPhase.BLOCKED -> AimiAdaptationReasonCode.CONTEXT_BLOCKED
    }

    private companion object {
        const val NOW = 10_000_000L
        const val STALE_AFTER_MS = 30 * 60 * 1_000L
    }
}
