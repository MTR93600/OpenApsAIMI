package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecursiveBeliefMr7Test {

    @BeforeEach
    fun resetMemory() {
        RecursiveBeliefMemory.clearForTests()
    }

    @Test
    fun `MR-7 P2 resolves HYPER_VS_CLEARANCE`() {
        val scales = listOf(
            scale(15, belief = 0.82, urgency = 1.8, terminal = 260.0),
            scale(60, belief = 0.76, urgency = 2.4, terminal = 401.0),
            scale(180, belief = 0.44, urgency = -0.3, terminal = 118.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(v3Smb = 0.37, bestTerminal = 401.0)
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
        assertThat(snapshot.paradoxes.map { it.id }).contains(BeliefParadoxId.HYPER_VS_CLEARANCE)
        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.HARD)
        assertThat(snapshot.resolutions.smbDemandU).isAtLeast(0.7)
    }

    @Test
    fun `P0 tier1 hypo blocks release`() {
        val scales = listOf(scale(15, 0.9, 2.0, 300.0))
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(tier1Hypo = true)
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(snapshot.resolutions.smbDemandU).isEqualTo(0.0)
    }

    @Test
    fun `believe normalizes mixed leaf signals`() {
        val leaves = listOf(
            BeliefLeafReading(BeliefLeafId.MEAL_PHASE, 0.85, 1.0, 0.9, "test"),
            BeliefLeafReading(BeliefLeafId.DELTA_NOW, 3.0, 1.0, 1.0, "test"),
        )
        val belief = RecursiveBeliefEngine.believe(leaves)
        assertThat(belief).isGreaterThan(0.5)
        assertThat(belief).isAtMost(1.0)
    }

    @Test
    fun `wavelet decompose produces three bands`() {
        val history = (1..48).map { 120.0 + it * 0.5 }
        val bands = WaveletBelief.decompose(history)
        assertThat(bands).isNotNull()
        assertThat(bands!!.high).isAtLeast(0.0)
        assertThat(bands.mid).isAtLeast(0.0)
        assertThat(bands.low).isAtLeast(0.0)
    }

    @Test
    fun `credibility cascade lowers parent leaf credibility under tension`() {
        val parentLeaves = listOf(
            BeliefLeafReading(BeliefLeafId.SAFETY_TERMINALS, 65.0, 1.0, 0.9, "floor"),
        )
        val childLeaves = listOf(
            BeliefLeafReading(BeliefLeafId.DELTA_NOW, 4.0, 1.0, 0.8, "rise"),
        )
        val scales = listOf(
            BeliefScaleNode(180, 0.8, 250.0, 1.5, childLeaves),
            BeliefScaleNode(480, 0.3, 70.0, -0.2, parentLeaves),
        )
        val tensions = listOf(ScaleTension(480, 180, 0.9, BeliefParadoxId.FLOOR_VS_REALITY))
        val cascaded = CredibilityCascade.apply(scales, tensions)
        val parentCred = cascaded.first { it.horizonMinutes == 480 }.leaves.first().credibility
        assertThat(parentCred).isLessThan(0.9)
    }

    private fun scale(tau: Int, belief: Double, urgency: Double, terminal: Double) =
        BeliefScaleNode(
            horizonMinutes = tau,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = emptyList(),
        )
}
