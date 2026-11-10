package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.RiseConfirmation.Level
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UndeclaredEvidenceCapTest {

    private fun limits(belief: Double?, bg: Double = 125.0, undeclared: Boolean = true, level: Level? = Level.CONFIRMED) =
        UndeclaredEvidenceCap.limits(undeclared, bg, belief, level)

    @Test
    fun `weak belief gets the tight limits`() {
        val l = limits(0.25)
        assertThat(l.basalFactor).isEqualTo(2.0)
        assertThat(l.smbCapU).isEqualTo(0.3)
    }

    @Test
    fun `limits rise in a straight line between 0_40 and 0_60`() {
        val l = limits(0.50)
        assertThat(l.basalFactor).isWithin(1e-9).of(3.0)
        assertThat(l.smbCapU).isWithin(1e-9).of(0.45)
    }

    @Test
    fun `strong belief with a confirmed rise allows 10x basal and free SMB`() {
        val l = limits(0.60)
        assertThat(l.basalFactor).isEqualTo(10.0)
        assertThat(l.smbCapU).isEqualTo(Double.POSITIVE_INFINITY)
        assertThat(limits(0.9).basalFactor).isEqualTo(10.0)
    }

    @Test
    fun `strong belief without confirmation stays at 4x and 0_6 U`() {
        val l = limits(0.9, level = Level.WEAK)
        assertThat(l.basalFactor).isEqualTo(4.0)
        assertThat(l.smbCapU).isEqualTo(0.6)
    }

    @Test
    fun `declared meal, high glucose or missing input keep the old behavior`() {
        assertThat(limits(0.2, undeclared = false).active).isFalse()
        assertThat(limits(0.2, bg = 190.0).active).isFalse()
        assertThat(limits(null).active).isFalse()
        assertThat(limits(0.2, level = null).active).isFalse()
    }

    @Test
    fun `basal is lowered but never below the profile basal`() {
        val l = limits(0.25)
        assertThat(UndeclaredEvidenceCap.capBasal(3.5, 0.55, l)).isWithin(1e-9).of(1.1)
        assertThat(UndeclaredEvidenceCap.capBasal(0.3, 0.55, l)).isEqualTo(0.3)
        assertThat(UndeclaredEvidenceCap.capBasal(0.0, 0.55, l)).isEqualTo(0.0)
    }

    @Test
    fun `smb is lowered and never raised`() {
        val l = limits(0.25)
        assertThat(UndeclaredEvidenceCap.capSmb(1.2, l)).isEqualTo(0.3)
        assertThat(UndeclaredEvidenceCap.capSmb(0.1, l)).isEqualTo(0.1)
        assertThat(UndeclaredEvidenceCap.capSmb(1.2, UndeclaredEvidenceCap.NO_LIMIT)).isEqualTo(1.2)
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
        assertThat(none).isFalse()
        assertThat(confirmed).isTrue()
        assertThat(unknown).isTrue()
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
        assertThat(stance(Level.NONE, MealAbsorptionPhase.SECOND_WAVE).kind)
            .isEqualTo(InsulinStackingStance.Kind.SURVEILLANCE_IOB)
        assertThat(stance(Level.CONFIRMED, MealAbsorptionPhase.SECOND_WAVE).kind)
            .isEqualTo(InsulinStackingStance.Kind.CORRECTION_ACTIVE)
        assertThat(stance(null, MealAbsorptionPhase.SECOND_WAVE).kind)
            .isEqualTo(InsulinStackingStance.Kind.CORRECTION_ACTIVE)
    }

    @Test
    fun `a declared meal mode keeps its bypass`() {
        assertThat(stance(Level.NONE, MealAbsorptionPhase.SECOND_WAVE, mealMode = true).kind)
            .isEqualTo(InsulinStackingStance.Kind.CORRECTION_ACTIVE)
    }
}
