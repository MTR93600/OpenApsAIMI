package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecursiveBeliefPostHypoSmbTest {

    @BeforeEach
    fun resetMemory() {
        RecursiveBeliefMemory.clearForTests()
        RbtEpisodeMemory.clearForTests()
    }

    @Test
    fun shouldSuppressRbtSmb_whenPostHypoDeliveryAuthoritySignals() {
        val ext = RbtExtendedSignals(postHypoDeliverySuppressSmb = true, t3cActive = true)
        assertThat(
            RecursiveBeliefResolver.shouldSuppressRbtSmbDemand(
                ext = ext,
                t3cBasalFirst = null,
                basalFirstChannel = BasalFirstChannel.NONE,
            ),
        ).isTrue()
    }

    @Test
    fun shouldSuppressRbtSmb_whenT3cActiveAndPostHypoBlock() {
        val ext = RbtExtendedSignals(t3cActive = true, t3cPostHypoBlock = true)
        val t3c = t3cResolution(eligible = false, active = true)
        assertThat(
            RecursiveBeliefResolver.shouldSuppressRbtSmbDemand(
                ext = ext,
                t3cBasalFirst = t3c,
                basalFirstChannel = BasalFirstChannel.T3C_BASAL_FIRST,
            ),
        ).isTrue()
    }

    @Test
    fun resolve_zerosSmbDemand_whenPostHypoDeliverySuppressSmb() {
        val mealOutput = MealAbsorptionPhaseEngine.Output(
            phase = MealAbsorptionPhase.FIRST_WAVE,
            belief = 0.74,
            reason = "FIRST_WAVE",
            deltaMgdlPer5 = 5.0,
            gapMgdl = 0.0,
            bestTerminalMgdl = 180.0,
            memoryActive = true,
            waveCount = 1,
            mealDeliveryPriority = true,
            chronoPrior = 0.0,
            kineticScore = 0.0,
            trajectoryScore = 0.0,
            physioScore = 0.0,
        )
        val scales = listOf(
            beliefScale(15, belief = 0.82, urgency = 1.8, terminal = 180.0),
            beliefScale(60, belief = 0.76, urgency = 2.4, terminal = 200.0),
            beliefScale(180, belief = 0.44, urgency = -0.3, terminal = 118.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 0.77,
            replaceHtrRelease = true,
            extended = RbtExtendedSignals(
                t3cActive = true,
                t3cPostHypoBlock = true,
                postHypoDeliverySuppressSmb = true,
            ),
        ).copy(
            v3SmbU = 0.77,
            mealAbsorption = mealOutput,
        )
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
        assertThat(snapshot.resolutions.smbDemandU).isWithin(1e-6).of(0.0)
        assertThat(snapshot.resolutions.reasonCodes).contains("POST_HYPO_SMB_ARBITER")
    }

    private fun t3cResolution(eligible: Boolean, active: Boolean): T3cBasalFirstResolution =
        T3cBasalFirstResolution(
            active = active,
            eligible = eligible,
            basalDemandRateUph = 1.2,
            boundedRateUph = 1.2,
            maxBasalCapUph = 5.0,
            anticipationStrength = 0.0,
            mealConflict = false,
            postHypoBlock = true,
            exerciseBlock = false,
            hardSafetyBlock = false,
            dominantBlocker = "POST_HYPO",
            governanceBasalFloorUph = null,
            governanceAggressivenessFloor = null,
            reasonCodes = listOf("T3C_POST_HYPO_BLOCK"),
        )

    private fun beliefScale(tau: Int, belief: Double, urgency: Double, terminal: Double) =
        BeliefScaleNode(
            horizonMinutes = tau,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = emptyList(),
        )
}
