package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation.Level
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Matching tests from `dev_OAPSAIMI` @ `ac78a11883`, in `commonTest` with `kotlin.test`. */
class UndeclaredEvidenceCapTest {

    private fun limits(belief: Double?, bg: Double = 125.0, undeclared: Boolean = true, level: Level? = Level.CONFIRMED) =
        UndeclaredEvidenceCap.limits(undeclared, bg, belief, level)

    @Test
    fun `weak belief gets the tight limits`() {
        val l = limits(0.25)
        assertEquals(2.0, l.basalFactor)
        assertEquals(0.3, l.smbCapU)
    }

    @Test
    fun `limits rise in a straight line between 0_40 and 0_60`() {
        val l = limits(0.50)
        assertEquals(3.0, l.basalFactor, 1e-9)
        assertEquals(0.45, l.smbCapU, 1e-9)
    }

    @Test
    fun `strong belief with a confirmed rise allows 10x basal and free SMB`() {
        val l = limits(0.60)
        assertEquals(10.0, l.basalFactor)
        assertEquals(Double.POSITIVE_INFINITY, l.smbCapU)
        assertEquals(10.0, limits(0.9).basalFactor)
    }

    @Test
    fun `strong belief without confirmation stays at 4x and 0_6 U`() {
        val l = limits(0.9, level = Level.WEAK)
        assertEquals(4.0, l.basalFactor)
        assertEquals(0.6, l.smbCapU)
    }

    @Test
    fun `declared meal or high glucose or missing input keep the old behavior`() {
        assertFalse(limits(0.2, undeclared = false).active)
        assertFalse(limits(0.2, bg = 190.0).active)
        assertFalse(limits(null).active)
        assertFalse(limits(0.2, level = null).active)
    }

    @Test
    fun `basal is lowered but never below the profile basal`() {
        val l = limits(0.25)
        assertEquals(1.1, UndeclaredEvidenceCap.capBasal(3.5, 0.55, l), 1e-9)
        assertEquals(0.3, UndeclaredEvidenceCap.capBasal(0.3, 0.55, l))
        assertEquals(0.0, UndeclaredEvidenceCap.capBasal(0.0, 0.55, l))
    }

    @Test
    fun `smb is lowered and never raised`() {
        val l = limits(0.25)
        assertEquals(0.3, UndeclaredEvidenceCap.capSmb(1.2, l))
        assertEquals(0.1, UndeclaredEvidenceCap.capSmb(0.1, l))
        assertEquals(1.2, UndeclaredEvidenceCap.capSmb(1.2, UndeclaredEvidenceCap.NO_LIMIT))
    }

    private fun priorityInput(level: Level?) = MealAbsorptionPhaseEngine.Input(
        bgMgdl = 150.0,
        targetBgMgdl = 100.0,
        highBgPreferenceMgdl = 140.0,
        deltaMgdlPer5 = 1.0,
        shortAvgDeltaMgdlPer5 = 1.0,
        combinedDeltaMgdlPer5 = 1.0,
        deltaPrevMgdlPer5 = null,
        mealCobG = 0.0,
        hourOfDay = 13,
        iobU = 3.0,
        maxIobU = 12.0,
        bestTerminalMgdl = 150.0,
        floorTerminalMgdl = 100.0,
        gapPrevMgdl = null,
        heartRateBpm = 70,
        restingHeartRateBpm = 60,
        stepsLast15m = 0,
        mealIntent = false,
        physiologicalPhase = PhysiologicalPhase.MEAL_UNDECLARED,
        riseLevel = level,
        nowMs = 1_000_000L,
    )

    @Test
    fun `inter wave without a confirmed rise has no delivery priority`() {
        val none = MealAbsorptionPhaseEngine.mealDeliveryPriority(
            MealAbsorptionPhase.INTER_WAVE, priorityInput(Level.NONE), 0.5, fastRise = false, moderateRise = false,
        )
        val confirmed = MealAbsorptionPhaseEngine.mealDeliveryPriority(
            MealAbsorptionPhase.INTER_WAVE, priorityInput(Level.CONFIRMED), 0.5, fastRise = false, moderateRise = false,
        )
        val unknown = MealAbsorptionPhaseEngine.mealDeliveryPriority(
            MealAbsorptionPhase.INTER_WAVE, priorityInput(null), 0.5, fastRise = false, moderateRise = false,
        )
        assertFalse(none)
        assertTrue(confirmed)
        assertTrue(unknown)
    }

    private fun stance(level: Level?, phase: MealAbsorptionPhase, mealMode: Boolean = false, delta: Double = 1.0) =
        InsulinStackingStance.evaluate(
            bg = 152.0,
            delta = delta,
            shortAvgDelta = 1.5,
            targetBg = 100.0,
            iob = 6.5,
            maxIob = 20.0,
            eventualBg = 104.0,
            minPredBg = 100.0,
            trajectoryEnergy = null,
            isExplicitUserAction = false,
            enabled = true,
            mealPriorityContext = true,
            mealAbsorptionPhase = phase,
            mealModeActive = mealMode,
            riseLevel = level,
        )

    @Test
    fun `a memory phase does not switch the surveillance off without a confirmed rise`() {
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, stance(Level.NONE, MealAbsorptionPhase.SECOND_WAVE).kind)
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, stance(Level.CONFIRMED, MealAbsorptionPhase.SECOND_WAVE).kind)
        assertEquals(InsulinStackingStance.Kind.CORRECTION_ACTIVE, stance(null, MealAbsorptionPhase.SECOND_WAVE).kind)
    }

    @Test
    fun `a declared meal mode keeps its bypass`() {
        assertEquals(
            InsulinStackingStance.Kind.CORRECTION_ACTIVE,
            stance(Level.NONE, MealAbsorptionPhase.SECOND_WAVE, mealMode = true).kind,
        )
    }
}
