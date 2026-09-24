package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RbtChaosEvaluatorTest {

    @Test
    fun high_tension_and_paradoxes_mark_chaos_active() {
        val snapshot = RecursiveBeliefSnapshot(
            scales = listOf(
                BeliefScaleNode(15, 0.8, 260.0, 2.5, emptyList()),
                BeliefScaleNode(60, 0.7, 280.0, -1.0, emptyList()),
                BeliefScaleNode(180, 0.5, 200.0, 0.2, emptyList()),
            ),
            tensions = listOf(
                ScaleTension(60, 15, 2.2, BeliefParadoxId.FLOOR_VS_REALITY),
                ScaleTension(180, 60, 1.8, BeliefParadoxId.HYPER_VS_CLEARANCE),
            ),
            paradoxes = listOf(
                BeliefParadox(BeliefParadoxId.FLOOR_VS_REALITY, false, "test"),
                BeliefParadox(BeliefParadoxId.HYPER_VS_CLEARANCE, false, "test"),
                BeliefParadox(BeliefParadoxId.SPIRAL_VS_RISE, false, "test"),
            ),
            resolutions = DoseChannelResolution(
                smbDemandU = 0.0,
                tbrDemandFraction = 1.0,
                waitBias = 0.15,
                dominantScaleMinutes = 60,
                releaseAuthority = ReleaseAuthority.NONE,
                hypoGuardMode = HypoGuardMode.PARTIAL,
                autodriveModeHint = AutodriveModeHint.V3,
                mealChannel = MealChannelHint.NORMAL,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = true,
                reasonCodes = emptyList(),
            ),
            mr7Trace = emptyList(),
        )
        val result = RbtChaosEvaluator.evaluate(
            RbtChaosEvaluator.Input(
                snapshot = snapshot,
                trajectoryUncertain = true,
                patternCapFlapping = true,
            ),
        )
        assertThat(result.active).isTrue()
        assertThat(result.reasonCodes).contains("TENSION")
        assertThat(result.reasonCodes).contains("TRAJ_UNCERTAIN")
        assertThat(result.reasonCodes).contains("CAP_FLAP")
    }
}
