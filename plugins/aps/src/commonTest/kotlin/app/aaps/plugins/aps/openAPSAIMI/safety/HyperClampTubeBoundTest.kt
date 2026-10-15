package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.Test

class HyperClampTubeBoundTest {

    private fun eval(
        minPred: Double = 80.0,
        bg: Double = 174.0,
        iob: Double = 1.0,
        tubeIsf: Double = 25.0,
        doseIsf: Double? = 25.0,
        floor: Double = 76.0,
        clampPossible: Boolean = true,
        armed: Boolean = true,
    ) = HyperClampTubeBound.evaluate(minPred, bg, iob, tubeIsf, doseIsf, floor, clampPossible, armed)

    /** Field case shape (2026-10-05 15:36): rising meal, little insulin on board → real room. */
    @Test
    fun lowStackOnARiseGivesTheTubeRealRoom() {
        val v = eval(bg = 174.0, iob = 2.0, tubeIsf = 25.0, doseIsf = 25.0)
        assertTrue(v.clampDetected)
        assertTrue(v.applied)
        assertEquals(124.0, v.minPredForTubeMgdl, 1e-9)
        assertEquals(false, v.stackCanBreachFloor)
    }

    /** Field case (2026-10-06 14:54): BG 160.4 with 10.8 U on board → the bound sits under the floor. */
    @Test
    fun largeStackIsVetoedInsteadOfReleasedAt160() {
        val v = eval(bg = 160.4, iob = 10.8, tubeIsf = 42.0, doseIsf = 42.0, floor = 70.0)
        assertTrue(v.applied)
        assertEquals(true, v.stackCanBreachFloor)
        assertTrue(v.minPredForTubeMgdl < 70.0)
    }

    /** Sensitivity comes back as glucose falls: the larger ISF is the cautious one. */
    @Test
    fun usesTheLargerIsfAndExportsTheDoseIsfVariant() {
        val v = eval(bg = 174.0, iob = 1.5, tubeIsf = 60.0, doseIsf = 25.0)
        assertEquals(60.0, v.isfUsedMgdlPerU)
        assertEquals(84.0, v.minPredForTubeMgdl, 1e-9)
        assertEquals(136.5, v.physicalMinDoseIsfMgdl!!, 1e-9)
    }

    @Test
    fun belowHyperLevelOrNotTheClampValueIsLeftAlone() {
        assertFalse(eval(bg = 159.9).clampDetected)
        assertFalse(eval(minPred = 95.0).clampDetected)
        assertEquals(39.0, eval(minPred = 39.0).minPredForTubeMgdl)
        assertFalse(eval(clampPossible = false).clampDetected)
    }

    /** Disarmed: the bound is computed and exported, the tube keeps the old value. */
    @Test
    fun disarmedOnlyReports() {
        val v = eval(iob = 2.0, armed = false)
        assertTrue(v.clampDetected)
        assertFalse(v.applied)
        assertEquals(80.0, v.minPredForTubeMgdl)
        assertNotNull(v.physicalMinMgdl)
    }

    /** Fail closed: no usable IOB or ISF → keep the clamp value the tube used before this rule. */
    @Test
    fun missingInputsKeepTheOldValue() {
        val noIsf = eval(tubeIsf = Double.NaN, doseIsf = null)
        assertTrue(noIsf.clampDetected)
        assertFalse(noIsf.applied)
        assertEquals(80.0, noIsf.minPredForTubeMgdl)
        assertFalse(eval(iob = Double.NaN).applied)
    }

    /** A negative IOB (net low temp) must not raise the bound above glucose itself. */
    @Test
    fun negativeIobCountsAsZero() {
        assertEquals(200.0, eval(bg = 200.0, iob = -0.8).minPredForTubeMgdl)
    }
}
