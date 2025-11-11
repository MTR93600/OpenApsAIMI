package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.safety.HighBgOverride
import app.aaps.plugins.aps.openAPSAIMI.safety.HypoTools
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbDampingUsecase
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbQuantizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmbDomainHeuristicsTest {

    private fun runtime(tailFraction: Double = 0.0): PkPdRuntime =
        PkPdRuntime(
            params = PkPdParams(diaHrs = 10.0, peakMin = 60.0),
            tailFraction = tailFraction,
            fusedIsf = 45.0,
            profileIsf = 45.0,
            tddIsf = 45.0,
            pkpdScale = 1.0,
            damping = SmbDamping()
        )

    @Test
    fun mealModeBypassSkipsDamping() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = true,
                highBgRiseActive = false
            )
        )
        assertEquals(1.0, out.smbAfterDamping, 1e-6)
        assertTrue(out.audit?.mealBypass == true)
    }

    @Test
    fun highBgRiseBypassSkipsDamping() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.2,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = true
            )
        )
        assertEquals(1.2, out.smbAfterDamping, 1e-6)
        assertTrue(out.audit?.mealBypass == true)
    }

    @Test
    fun exerciseDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = true,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.exerciseApplied == true)
    }

    @Test
    fun lateFatDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = true,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.lateFatApplied == true)
    }

    @Test
    fun tailDampingApplied() {
        val out = SmbDampingUsecase.run(
            runtime(tailFraction = 0.6),
            SmbDampingUsecase.Input(
                smbDecision = 1.0,
                exercise = false,
                suspectedLateFatMeal = false,
                mealModeRun = false,
                highBgRiseActive = false
            )
        )
        assertTrue(out.smbAfterDamping < 1.0)
        assertTrue(out.audit?.tailApplied == true)
    }

    @Test
    fun highBgOverrideForcesInterval() {
        val res = HighBgOverride.apply(
            bg = 190.0,
            delta = 2.0,
            predictedBg = 170.0,
            eventualBg = 165.0,
            hypoGuard = 70.0,
            iob = 0.2,
            maxSmb = 2.0,
            currentDose = 0.1,
            pumpStep = 0.05
        )
        assertTrue(res.overrideUsed)
        assertEquals(0.05, res.dose, 1e-6)
        assertEquals(0, res.newInterval)
    }

    @Test
    fun highBgOverrideRespectsHypoGuard() {
        val res = HighBgOverride.apply(
            bg = 155.0,
            delta = 1.6,
            predictedBg = 75.0,
            eventualBg = 72.0,
            hypoGuard = 80.0,
            iob = 0.1,
            maxSmb = 2.0,
            currentDose = 0.2,
            pumpStep = 0.05
        )
        assertFalse(res.overrideUsed)
        assertEquals(0.2, res.dose, 1e-6)
        assertEquals(null, res.newInterval)
    }

    @Test
    fun quantizerRespectsPumpStep() {
        val quantized = SmbQuantizer.quantizeToPumpStep(0.123f, 0.05f)
        assertEquals(0.1f, quantized, 1e-6f)
    }

    @Test
    fun hypoToolsMinutesAboveThreshold() {
        val minutes = HypoTools.calculateMinutesAboveThreshold(bg = 80.0, slope = -1.0, thresholdBG = 70.0)
        assertEquals(10, minutes)
    }
}
