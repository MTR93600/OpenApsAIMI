package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Golden numbers for the legacy PKPD red-carpet cap. Distinct from the finalize chain:
 * the restore test is `<= threshold` and the MaxIOB clamp uses the debridage ceiling.
 */
class AimiLegacySmbCapMathTest {

    private fun assertNear(expected: Double, actual: Double, delta: Double = 1e-4) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta but was $actual")
    }

    @Test
    fun `an explicit advisor run uses at least 10 U as the max`() {
        val maxSmb = AimiLegacySmbCapMath.currentMaxSmb(
            isExplicitAdvisorRun = true,
            bg = 90.0,
            honeymoon = true,
            slopeFromMinDeviation = 0.0,
            mealLunchDinnerOrHc = false,
            maxSmb = 1.0,
            maxSmbHb = 3.0,
        )
        assertNear(10.0, maxSmb)
    }

    @Test
    fun `a fast high rise asks for a 12 U IOB target`() {
        val relief = AimiLegacySmbCapMath.iobRelief(
            pkpdReliefEnabled = true,
            isAggressivePriorityContext = true,
            maxIob = 5.0,
            priorityMaxIobFactor = 1.2,
            priorityMaxIobExtraU = 2.0,
            bg = 250.0,
            delta = 30.0,
            shortAvgDelta = 10.0,
            predictedBg = 200.0,
            eventualBg = 180.0,
        )
        assertNear(12.0, relief.iobTargetU ?: -1.0)
        assertNear(12.0, relief.effectiveMaxIobForDebridage)
        assertTrue(relief.logs.any { it.startsWith("MEAL_DEBRIDAGE_MAXIOB") })
    }

    @Test
    fun `red carpet restores below the threshold and the cap note stays null when the dose grows`() {
        val restored = AimiLegacySmbCapMath.redCarpetOrCap(
            smbAfterGuards = 1f,
            proposedUnits = 4f,
            finalSmb = 4.0,
            isExplicitAction = true,
            anyMealMode = false,
            redCarpetEligible = false,
            mealSummary = "",
            isConfirmedHighRise = false,
            mealCob = 0.0,
            delta = 1.0,
            bg = 180.0,
            shortAvgDelta = 1.0,
            pkpdReliefEnabled = false,
            isAggressivePriorityContext = false,
            restoreThresholdPref = 0.6f,
            criticalSafetyZeroed = false,
            suppressRedCarpet = false,
            suppressSummary = "",
            currentMaxSmb = 8.0,
            maxSmbHb = 8.0,
            effectiveMaxIob = 20.0,
            iobForCap = 1.0,
            memberIob = 1.0,
        )
        assertNear(4.0, restored.units.toDouble())
        assertNull(restored.reasonCap)

        val denied = AimiLegacySmbCapMath.redCarpetOrCap(
            smbAfterGuards = 1f,
            proposedUnits = 4f,
            finalSmb = 4.0,
            isExplicitAction = true,
            anyMealMode = false,
            redCarpetEligible = false,
            mealSummary = "",
            isConfirmedHighRise = false,
            mealCob = 0.0,
            delta = 1.0,
            bg = 180.0,
            shortAvgDelta = 1.0,
            pkpdReliefEnabled = false,
            isAggressivePriorityContext = false,
            restoreThresholdPref = 0.6f,
            criticalSafetyZeroed = true,
            suppressRedCarpet = false,
            suppressSummary = "",
            currentMaxSmb = 8.0,
            maxSmbHb = 8.0,
            effectiveMaxIob = 20.0,
            iobForCap = 1.0,
            memberIob = 1.0,
        )
        assertNear(1.0, denied.units.toDouble())
        assertEquals(
            "⛔ RED_CARPET_DENIED: vital hypo safety zeroed SMB this tick — no restore (Proposed=4.00 Gated=1.00)",
            denied.logs.single(),
        )
    }
}
