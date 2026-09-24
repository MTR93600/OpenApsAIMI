package app.aaps.plugins.aps.openAPSAIMI.physio.thyroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ThyroidSafetyGatesTest {

    @Test
    fun `test normalizing phase blocks smb on fast drop`() {
        val gates = ThyroidSafetyGates()
        val inputs = ThyroidInputs(
            isEnabled = true, 
            treatmentPhase = ThyroidTreatmentPhase.TITRATION,
            guardLevel = NormalizingGuardLevel.HIGH
        )
        val effects = ThyroidEffects(
            status = ThyroidStatus.NORMALIZING,
            diaMultiplier = 0.9,
            egpMultiplier = 1.1
        )

        val gatedEffects = gates.applyGates(
            inputs = inputs,
            effects = effects,
            currentBg = 90.0,
            bgDelta = -5.0, // Fast drop
            currentIob = 2.0 // Still IOB active
        )

        assertTrue(gatedEffects.blockSmb)
        assertEquals(null, gatedEffects.smbCapUnits)
    }

    @Test
    fun `test euthyroid state bypasses safety caps`() {
        val gates = ThyroidSafetyGates()
        val inputs = ThyroidInputs(
            isEnabled = true, 
            treatmentPhase = ThyroidTreatmentPhase.NONE,
            guardLevel = NormalizingGuardLevel.LOW
        )
        val effects = ThyroidEffects(
            status = ThyroidStatus.EUTHYROID,
            diaMultiplier = 1.0,
            egpMultiplier = 1.0
        )

        val gatedEffects = gates.applyGates(
            inputs = inputs,
            effects = effects,
            currentBg = 100.0,
            bgDelta = 1.0, 
            currentIob = 1.0 
        )

        assertEquals(false, gatedEffects.blockSmb)
        assertEquals(null, gatedEffects.smbCapUnits)
    }
}
