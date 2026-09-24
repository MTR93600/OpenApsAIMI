package app.aaps.plugins.aps.openAPSAIMI.physio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysioPhaseFusionTest {

    @Test
    fun hormonal_phase_damps_physio_multipliers() {
        val base = PhysioMultipliersMTR(
            smbFactor = 1.05,
            reactivityFactor = 1.05,
            basalFactor = 1.0,
        )
        val policy = BehavioralRiskPolicy.forPhase(
            PhysiologicalPhase.DAWN_CORTISOL,
            0.9,
            "test",
        )
        val fused = PhysioPhaseFusion.applyPhaseToMultipliers(base, policy)
        assertTrue(fused.smbFactor <= 0.92)
        assertTrue(fused.reactivityFactor <= 0.90)
        assertTrue(fused.basalFactor >= 1.02)
        assertEquals(PhysiologicalPhase.DAWN_CORTISOL, fused.physiologicalPhase)
    }

    @Test
    fun preview_best_uses_hybrid_when_available() {
        val preview = PhysioPhaseFusion.previewBestTerminalMgdl(120.0, 2.0, 135.0)
        assertEquals(135.0, preview, 0.01)
    }
}
