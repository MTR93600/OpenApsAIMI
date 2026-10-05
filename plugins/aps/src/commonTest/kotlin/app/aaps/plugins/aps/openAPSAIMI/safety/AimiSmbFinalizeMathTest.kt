package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden numbers for the SMB finalize chain. Each case is the formula in
 * `finalizeAndCapSMB` on `dev_OAPSAIMI` @ `3dd0ca64772`.
 */
class AimiSmbFinalizeMathTest {

    private fun assertNear(expected: Double, actual: Double, delta: Double = 1e-4) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta but was $actual")
    }

    @Test
    fun `a quiet dose stays at the safety units`() {
        val out = AimiSmbFinalizeMath.decide(AimiSmbFinalizeMath.Input(proposedUnits = 2.0, safetyUnits = 2f))
        assertNear(2.0, out.finalUnits)
        assertEquals("standard_safe_cap", out.smbFinalSource)
        assertFalse(out.mealPriorityContext)
    }

    @Test
    fun `refractory without meal priority zeros the dose`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                lastBolusAgeMinutes = 1.0,
                baseRefractoryMinutes = 10.0,
            ),
        )
        assertNear(0.0, out.finalUnits)
        assertTrue(out.logs.any { it.startsWith("⏸️ REFRACTORY_BLOCK") })
    }

    @Test
    fun `meal priority relaxes refractory by progress`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                baseLimit = 5.0,
                lastBolusAgeMinutes = 5.0,
                baseRefractoryMinutes = 10.0,
                rbtMealPriority = true,
                bg = 160.0,
                delta = 2.0,
                shortAvgDelta = 2.0,
                mealCob = 12.0,
            ),
        )
        // progress 0.5 → factor 0.525 → 2 * 0.525 = 1.05
        assertNear(1.05, out.finalUnits)
        assertTrue(out.logs.any { it.contains("factor=0.53") || it.contains("factor=0.52") })
        assertTrue(out.smbDeliveryPriorityContext)
    }

    @Test
    fun `absorption halves a recent bolus when the rise is modest`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                lastBolusAgeMinutes = 10.0,
                baseRefractoryMinutes = 3.0,
                iobActivityNow = 1.0,
                tdd24h = 30.0,
                bg = 150.0,
                targetBg = 100.0,
                delta = 1.0,
            ),
        )
        assertNear(0.5, out.absorptionFactor)
        assertNear(1.0, out.finalUnits)
    }

    @Test
    fun `a missing prediction caps at half of max SMB`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                predMissing = true,
                maxSmb = 2.0,
                baseRefractoryMinutes = 4.0,
            ),
        )
        assertNear(1.0, out.finalUnits)
        assertNear(6.0, out.refractoryWindow)
    }

    @Test
    fun `red carpet restores a minor cut and the 30 U belt holds`() {
        val restored = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 4.0,
                safetyUnits = 1f,
                isExplicitUserAction = true,
                baseLimit = 8.0,
                maxSmbHb = 8.0,
                maxIob = 20.0,
                iob = 1.0,
            ),
        )
        assertNear(4.0, restored.finalUnits)
        assertEquals("red_carpet", restored.smbFinalSource)
        assertTrue(restored.logs.any { it.contains("RED CARPET: Restoring") })

        val belt = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 40.0,
                safetyUnits = 40f,
                isExplicitUserAction = true,
                baseLimit = 50.0,
                maxSmbHb = 50.0,
                maxIob = 80.0,
                iob = 0.0,
            ),
        )
        assertNear(30.0, belt.finalUnits)
    }

    @Test
    fun `a vital zero is not restored`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 4.0,
                safetyUnits = 0.5f,
                isExplicitUserAction = true,
                criticalSafetyZeroed = true,
                baseLimit = 8.0,
                maxSmbHb = 8.0,
            ),
        )
        assertNear(0.5, out.finalUnits)
        assertTrue(out.logs.any { it.startsWith("⛔ RED_CARPET_DENIED") })
    }

    @Test
    fun `the hyper trajectory floor lifts a standard cap`() {
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 0.2,
                safetyUnits = 0.2f,
                hyperReleaseFloorU = 1.5,
                baseLimit = 5.0,
                maxIob = 10.0,
                iob = 1.0,
                bg = 100.0,
                targetBg = 100.0,
                delta = 0.0,
            ),
        )
        assertFalse(out.isRedCarpetSituation)
        assertNear(1.5, out.finalUnits)
        assertTrue(out.logs.any { it.startsWith("🚀 HTR finalize floor") })
    }

    @Test
    fun `hypo recovery context zeros and effort halves`() {
        val suppressed = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(proposedUnits = 2.0, safetyUnits = 2f, contextSuppressSmb = true),
        )
        assertNear(0.0, suppressed.finalUnits)
        assertTrue(suppressed.reasonBits.any { it.contains("hypoRecovery") })

        val effort = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                effortFactorRaw = 0.5,
                effortFactorApplied = 0.5,
                effortStateName = "ACTIVE",
                effortPostureName = "UPRIGHT",
            ),
        )
        assertNear(1.0, effort.finalUnits)
        assertTrue(effort.logs.any { it.startsWith("🏃 EFFORT_PROTECT_SMB") })
    }

    @Test
    fun `an armed rise ceiling withholds a dose that already ends on the cap`() {
        val now = 1_700_000_000_000L
        val out = AimiSmbFinalizeMath.decide(
            AimiSmbFinalizeMath.Input(
                proposedUnits = 2.0,
                safetyUnits = 2f,
                baseLimit = 2.0,
                maxSmbHb = 5.0,
                delta = 10.0,
                riseCeilingArmed = true,
                ceilingRepeatCount = 2,
                ceilingRepeatLastMs = now - 60_000L,
                nowMs = now,
            ),
        )
        assertNear(0.0, out.finalUnits)
        assertTrue(out.riseCeilingBlock)
        assertEquals(3, out.ceilingRepeatCount)
        assertTrue(out.logs.any { it.startsWith("🧱 RISE_CEILING_GUARD") })
    }
}
