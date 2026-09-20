package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.plugins.aps.openAPSAIMI.patient.DataCoherenceLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalBranches
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalFruits
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalLeaves
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRoots
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalSeasons
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalSignalState
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTrunk
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class T3cAutodriveBasalBridgeTest {

    @Test
    fun `fuse takes max of PI and Autodrive and strips SMB into bounded TBR boost`() {
        val unlock = T3cAutodriveBasalBridge.UnlockDecision(true, "test")
        val fusion = T3cAutodriveBasalBridge.fuse(
            piUph = 1.2,
            adTbrUph = 1.5,
            strippedSmbU = 0.5,
            profileBasalUph = 0.6,
            steadyCapUph = 2.0,
            riseCapUph = 6.0,
            previousRateUph = 1.0,
            unlock = unlock,
        )
        // 0.5U SMB / 0.25h = +2 U/h → max(1.5, 0.6+2) = 2.6; max(pi=1.2, 2.6) = 2.6
        assertThat(fusion.fusedTargetUph).isWithin(0.01).of(2.6)
        assertThat(fusion.unlock).isTrue()
        assertThat(fusion.maxBasalCapUph).isEqualTo(6.0)
        assertThat(fusion.maxStepUpUph).isAtLeast(1.0)
        assertThat(fusion.strippedSmbU).isEqualTo(0.5)
    }

    @Test
    fun `without unlock uses steady cap and gentle ramp`() {
        val unlock = T3cAutodriveBasalBridge.UnlockDecision(false, "no_rise")
        val fusion = T3cAutodriveBasalBridge.fuse(
            piUph = 5.0,
            adTbrUph = 5.0,
            strippedSmbU = 0.0,
            profileBasalUph = 0.6,
            steadyCapUph = 2.0,
            riseCapUph = 6.0,
            previousRateUph = 1.0,
            unlock = unlock,
        )
        assertThat(fusion.fusedTargetUph).isWithin(0.01).of(2.0)
        assertThat(fusion.maxStepUpUph).isWithin(0.01).of(0.30)
    }

    @Test
    fun `glycemic rise unlocks regardless of tree activity veto and without a resistance signal`() {
        // Self-sufficiency: a confirmed rising hyper unlocks even when the (often false-positive) activity
        // branch is high and the tree reports no resistance signal — the tree must not throttle a real rise.
        val unlock = T3cAutodriveBasalBridge.evaluateTreeUnlock(
            tree = tree(
                risk = PhysiologicalRiskLevel.MODERATE,
                global = GlobalPhysiologicalState.RESISTANCE_PROBABLE,
                hormonalResistance = 0.1, // no resistance signal
                activity = 0.7,           // high activity — previously vetoed
            ),
            bg = 180.0,
            delta = 3.0f,
            activationThreshold = 130.0,
            postHypoActive = false,
            eventualBg = 200.0,
            targetBg = 100.0,
        )
        assertThat(unlock.unlock).isTrue()
        assertThat(unlock.reason).isEqualTo("glycemic_override")
    }

    @Test
    fun `tree critical risk still vetoes unlock even on a glycemic rise`() {
        val blocked = T3cAutodriveBasalBridge.evaluateTreeUnlock(
            tree = tree(
                risk = PhysiologicalRiskLevel.CRITICAL,
                global = GlobalPhysiologicalState.RESISTANCE_PROBABLE,
                hormonalResistance = 0.7,
                activity = 0.1,
            ),
            bg = 180.0,
            delta = 3.0f,
            activationThreshold = 130.0,
            postHypoActive = false,
            eventualBg = 200.0,
            targetBg = 100.0,
        )
        assertThat(blocked.unlock).isFalse()
        assertThat(blocked.reason).isEqualTo("tree_critical")
    }

    @Test
    fun `unlock is anticipatory - projected BG above threshold unlocks even when current BG is below`() {
        val unlock = T3cAutodriveBasalBridge.evaluateTreeUnlock(
            tree = tree(
                risk = PhysiologicalRiskLevel.MODERATE,
                global = GlobalPhysiologicalState.RESISTANCE_PROBABLE,
                hormonalResistance = 0.7,
                activity = 0.1,
            ),
            bg = 120.0,               // current BG below activationThreshold
            delta = 2.0f,
            activationThreshold = 130.0,
            postHypoActive = false,
            eventualBg = 200.0,
            targetBg = 100.0,
            projectedBg = 145.0,      // heading above threshold → engage now
        )
        assertThat(unlock.unlock).isTrue()
    }

    @Test
    fun `unlock requires positive delta even when projected BG is high`() {
        val decision = T3cAutodriveBasalBridge.evaluateTreeUnlock(
            tree = tree(
                risk = PhysiologicalRiskLevel.MODERATE,
                global = GlobalPhysiologicalState.RESISTANCE_PROBABLE,
                hormonalResistance = 0.7,
                activity = 0.1,
            ),
            bg = 180.0,
            delta = 0.0f,             // not rising → must stay locked
            activationThreshold = 130.0,
            postHypoActive = false,
            eventualBg = 200.0,
            targetBg = 100.0,
            projectedBg = 200.0,
        )
        assertThat(decision.unlock).isFalse()
        assertThat(decision.reason).isEqualTo("no_confirmed_rise")
    }

    @Test
    fun `applyRamp respects step up`() {
        val ramped = T3cAutodriveBasalBridge.applyRamp(1.0, 4.0, 1.5)
        assertThat(ramped).isWithin(0.01).of(2.5)
    }

    private fun tree(
        risk: PhysiologicalRiskLevel,
        global: GlobalPhysiologicalState,
        hormonalResistance: Double,
        activity: Double,
    ): PhysiologicalTreeSnapshot {
        fun sig(conf: Double) = PhysiologicalSignalState(
            detected = conf >= 0.45,
            confidence = conf,
            intensity = conf,
            label = "t",
            reasons = emptyList(),
        )
        val neutral = sig(0.1)
        return PhysiologicalTreeSnapshot(
            timestamp = 1L,
            roots = PhysiologicalRoots(
                profileState = neutral,
                tddState = neutral,
                basalState = neutral,
                isfState = neutral,
                preferenceState = neutral,
                historicalPatternState = neutral,
                mlAsyncState = neutral,
            ),
            trunk = PhysiologicalTrunk(
                globalState = global,
                confidence = 0.7,
                riskLevel = risk,
                dataCoherence = DataCoherenceLevel.GOOD,
                mainReason = "test",
            ),
            branches = PhysiologicalBranches(
                digestion = neutral,
                meal = neutral,
                activity = sig(activity),
                postActivity = neutral,
                sleepRecovery = neutral,
                stress = neutral,
                hormonalResistance = sig(hormonalResistance),
                insulinEffectiveness = neutral,
                sensorTrust = sig(0.8),
                hypoRisk = neutral,
                hyperRisk = neutral,
            ),
            leaves = PhysiologicalLeaves(
                userSummary = "",
                auditorNotes = emptyList(),
                advisorHints = emptyList(),
                mealAdvisorHints = emptyList(),
                aimiContextNotes = emptyList(),
                safetyNotes = emptyList(),
                decisionExplanation = emptyList(),
                noActionReasons = emptyList(),
            ),
            fruits = PhysiologicalFruits(null, emptyList(), emptyList()),
            seasons = PhysiologicalSeasons(null, null, null, null, null),
            compactSummary = "test",
        )
    }
}
