package app.aaps.plugins.aps.openAPSAIMI.prediction

import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NaiveEventualBgSignGuardTest {

    @Test
    fun negativeAapsIobPlusPkpdPeakActivityDoesNotInflateNaiveEventualBg() {
        val res = NaiveEventualBgSignGuard.resolve(
            bgMgdl = 116.0,
            iobUnits = -0.30,
            sensMgDlPerU = 45.0,
            pkpdRelativeActivity = 0.89,
            pkpdStage = InsulinActivityStage.PEAK,
        )
        assertTrue(res.signGuardApplied)
        assertEquals(116.0, res.naiveEventualBgMgdl, 0.001)
    }

    @Test
    fun positiveIobStillProducesStandardNaiveEventualBgDrop() {
        val res = NaiveEventualBgSignGuard.resolve(
            bgMgdl = 160.0,
            iobUnits = 1.5,
            sensMgDlPerU = 45.0,
            pkpdRelativeActivity = 0.89,
            pkpdStage = InsulinActivityStage.PEAK,
        )
        assertFalse(res.signGuardApplied)
        assertEquals(93.0, res.naiveEventualBgMgdl, 0.001)
    }

    @Test
    fun negativeIobWithLowPkpdActivityFallsThroughToStandardFormula() {
        val res = NaiveEventualBgSignGuard.resolve(
            bgMgdl = 116.0,
            iobUnits = -0.30,
            sensMgDlPerU = 45.0,
            pkpdRelativeActivity = 0.20,
            pkpdStage = InsulinActivityStage.TAIL,
        )
        assertFalse(res.signGuardApplied)
        assertEquals(130.0, res.naiveEventualBgMgdl, 0.001)
    }

    @Test
    fun negativeIobWithHighActivityButNonPeakStageFallsThrough() {
        val res = NaiveEventualBgSignGuard.resolve(
            bgMgdl = 116.0,
            iobUnits = -0.30,
            sensMgDlPerU = 45.0,
            pkpdRelativeActivity = 0.75,
            pkpdStage = InsulinActivityStage.TAIL,
        )
        assertFalse(res.signGuardApplied)
        assertEquals(130.0, res.naiveEventualBgMgdl, 0.001)
    }

    @Test
    fun risingStageTriggersGuard() {
        val res = NaiveEventualBgSignGuard.resolve(
            bgMgdl = 116.0,
            iobUnits = -0.30,
            sensMgDlPerU = 45.0,
            pkpdRelativeActivity = 0.75,
            pkpdStage = InsulinActivityStage.RISING,
        )
        assertTrue(res.signGuardApplied)
        assertEquals(116.0, res.naiveEventualBgMgdl, 0.001)
    }
}
