package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsulinStackingStanceTest {

    @Test
    fun `surveillance when plateau high IOB and eventual well below BG`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 1.0,
            shortAvgDelta = 1.5,
            targetBg = 100.0,
            iob = 6.5,
            maxIob = 20.0,
            eventualBg = 104.0,
            minPredBg = 100.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
        assertTrue(e.suppressRedCarpetRestore)
        assertEquals(0.32, e.smbMultiplier, 1e-6)
        assertEquals(0.38, e.smbAbsoluteCapU, 1e-6)
    }

    @Test
    fun `active correction on sharp rise despite high IOB`() {
        val e = InsulinStackingStance.evaluate(
            bg = 160.0,
            delta = 5.0,
            shortAvgDelta = 4.0,
            targetBg = 100.0,
            iob = 7.0,
            maxIob = 20.0,
            eventualBg = 200.0,
            minPredBg = 180.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertFalse(e.suppressRedCarpetRestore)
    }

    @Test
    fun `active when IOB below floor`() {
        val e = InsulinStackingStance.evaluate(
            bg = 150.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 2.0,
            maxIob = 20.0,
            eventualBg = 90.0,
            minPredBg = 85.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    // ── The floor follows the user, and the caution band 70-130 ──────────────────

    @Test
    fun `the floor follows the user's own dosing scale, not a fixed 3 point 2`() {
        // A fixed 3.2 U was the same for someone using 20 units a day and someone using 80, and it
        // always won: maxIob * 0.26 only passes 3.2 above a maxIob of 12.3 U.
        assertEquals(1.82, InsulinStackingStance.iobFloorU(7.0), 1e-6)
        assertEquals(5.2, InsulinStackingStance.iobFloorU(20.0), 1e-6)
        // Never below the absolute minimum, however small the user's maxIob is.
        assertEquals(1.0, InsulinStackingStance.iobFloorU(2.0), 1e-6)
    }

    @Test
    fun `two units on board are now watched, with an ordinary maxIob`() {
        // The field episode: 2 U already active, a stress rise, and no brake at all because the old
        // floor of 3.2 U had not been reached.
        val e = InsulinStackingStance.evaluate(
            bg = 150.0,
            delta = 1.0,
            shortAvgDelta = 1.5,
            targetBg = 100.0,
            iob = 2.0,
            maxIob = 7.0,
            eventualBg = 120.0,
            minPredBg = 118.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }

    @Test
    fun `a gentle rise near target is damped when nothing says meal`() {
        // 110 with a target of 100 is below the usual target+18 band, so this used to be delivered at
        // full strength even with a predicted drop. In 70-130 a rise is not an emergency.
        val e = InsulinStackingStance.evaluate(
            bg = 110.0,
            delta = 1.0,
            shortAvgDelta = 1.2,
            targetBg = 100.0,
            iob = 2.5,
            maxIob = 7.0,
            eventualBg = 100.0,
            minPredBg = 95.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }

    @Test
    fun `a declared meal keeps its full authority inside the band`() {
        val e = InsulinStackingStance.evaluate(
            bg = 110.0,
            delta = 1.0,
            shortAvgDelta = 1.2,
            targetBg = 100.0,
            iob = 2.5,
            maxIob = 7.0,
            eventualBg = 100.0,
            minPredBg = 95.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealModeActive = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertEquals("bg_below_surveillance_band", e.activeReason)
    }

    @Test
    fun `a real rise escapes the band like any other`() {
        val e = InsulinStackingStance.evaluate(
            bg = 110.0,
            delta = 5.0,
            shortAvgDelta = 4.0,
            targetBg = 100.0,
            iob = 2.5,
            maxIob = 7.0,
            eventualBg = 100.0,
            minPredBg = 95.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertEquals("sharp_rise_escape", e.activeReason)
    }

    @Test
    fun `above the band the old rule still decides`() {
        // 135 with a target of 130: outside 70-130, and below target+18, so no surveillance.
        val e = InsulinStackingStance.evaluate(
            bg = 135.0,
            delta = 1.0,
            shortAvgDelta = 1.2,
            targetBg = 130.0,
            iob = 2.5,
            maxIob = 7.0,
            eventualBg = 120.0,
            minPredBg = 118.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertEquals("bg_below_surveillance_band", e.activeReason)
    }

    @Test
    fun `surveillance from trajectory energy alone`() {
        val e = InsulinStackingStance.evaluate(
            bg = 148.0,
            delta = 1.2,
            shortAvgDelta = 2.0,
            targetBg = 100.0,
            iob = 5.0,
            maxIob = 18.0,
            eventualBg = null,
            minPredBg = null,
            trajectoryEnergy = 2.4,
            isExplicitUserAction = false,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }

    @Test
    fun `disabled falls through to active`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 95.0,
            minPredBg = 90.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = false
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    @Test
    fun `explicit user action bypasses`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 0.5,
            shortAvgDelta = 1.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 90.0,
            minPredBg = 85.0,
            trajectoryEnergy = 3.0,
            isExplicitUserAction = true,
            enabled = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
    }

    @Test
    fun `meal priority with clear absorption rise bypasses surveillance`() {
        val e = InsulinStackingStance.evaluate(
            bg = 155.0,
            delta = 2.2,
            shortAvgDelta = 2.0,
            targetBg = 100.0,
            iob = 6.0,
            maxIob = 20.0,
            eventualBg = 92.0,
            minPredBg = 88.0,
            trajectoryEnergy = 2.5,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true
        )
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, e.kind)
        assertEquals("meal_absorption_rise_priority", e.activeReason)
    }

    @Test
    fun `meal priority on mild plateau still allows surveillance when signals fire`() {
        val e = InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = 1.0,
            shortAvgDelta = 1.6,
            targetBg = 100.0,
            iob = 6.5,
            maxIob = 20.0,
            eventualBg = 104.0,
            minPredBg = 100.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true
        )
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, e.kind)
    }

    @Test
    fun `endogenous mode blocks meal priority bypass on moderate rise`() {
        val bypassed = InsulinStackingStance.evaluate(
            bg = 129.0,
            delta = 3.0,
            shortAvgDelta = 2.5,
            targetBg = 100.0,
            iob = 1.5,
            maxIob = 20.0,
            eventualBg = 120.0,
            minPredBg = 115.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true,
            endogenousCounterRegulatory = false,
        )
        assertEquals("meal_absorption_rise_priority", bypassed.activeReason)

        val endogenous = InsulinStackingStance.evaluate(
            bg = 129.0,
            delta = 3.0,
            shortAvgDelta = 2.5,
            targetBg = 100.0,
            iob = 1.5,
            maxIob = 20.0,
            eventualBg = 120.0,
            minPredBg = 115.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true,
            endogenousCounterRegulatory = true,
        )
        assertEquals("iob_below_floor", endogenous.activeReason)
    }

    @Test
    fun `sanitize eventual strips hyper numeric artifact for stacking`() {
        assertEquals(null, InsulinStackingStance.sanitizeEventualMgdlForStackingSignals(160.0, 401.0))
        assertEquals(118.0, InsulinStackingStance.sanitizeEventualMgdlForStackingSignals(160.0, 118.0))
    }
}
