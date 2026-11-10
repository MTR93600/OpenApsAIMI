package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RbtAuthorityGateChaosTest {

    @Test
    fun chaos_active_blocks_authority() {
        val decision = RecursiveBeliefAuthorityGate.evaluate(
            RecursiveBeliefAuthorityGate.Input(
                authorityEnabled = true,
                requestedAuthority = ReleaseAuthority.HARD,
                predictionAvailable = true,
                phaseOutput = null,
                patternSnapshot = null,
                latentState = null,
                hypothesisState = null,
                patientState = null,
                patientModeDecision = null,
                safetyRiskExport = null,
                chaos = RbtChaosEvaluator.Result(
                    score = 0.80,
                    active = true,
                    caution = true,
                    reasonCodes = listOf("TENSION", "PARADOX"),
                ),
                episode = null,
            ),
        )
        assertThat(decision.effectiveAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(decision.reasonCodes).contains("CHAOS_BLOCK")
    }
}
