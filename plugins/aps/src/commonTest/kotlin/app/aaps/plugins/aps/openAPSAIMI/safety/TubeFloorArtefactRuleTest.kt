package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The real ticks of 2026-10-02 21:09, 21:14 and 21:19: glucose 142, 140 and 144 mg/dL, flat, while the
 * predicted minimum the dose-feasibility check refused on read 40.97, 40.22 and 39.00 — the 39 being the
 * hard floor of the prediction curves, not a measurement.
 *
 * Those ticks sit BELOW the 160 mg/dL plateau band the project already uses for floor artefacts, so the
 * strict verdict refuses them on purpose. That is the whole point of keeping the two answers apart.
 */
class TubeFloorArtefactRuleTest {

    private fun verdict(
        minPred: Double = 39.0,
        bg: Double = 142.0,
        delta: Double = 0.4,
        feasible: Boolean = false,
        sport: Boolean = false,
        postHypo: Boolean = false,
    ) = TubeFloorArtefactRule.evaluate(
        feasible = feasible,
        minPredUsedMgdl = minPred,
        bgMgdl = bg,
        deltaMgdl5m = delta,
        sportActive = sport,
        postHypoActive = postHypo,
    )

    @Test
    fun `the real 21-09 tick is wide but not strict because 142 is under the plateau band`() {
        val v = verdict(minPred = 40.97, bg = 142.0, delta = 0.4)

        assertTrue(v.wide)
        assertFalse(v.strict)
    }

    @Test
    fun `the same artefact above the plateau band is strict`() {
        val v = verdict(minPred = 39.0, bg = 175.0, delta = 0.4)

        assertTrue(v.strict)
        assertTrue(v.wide)
    }

    @Test
    fun `a prediction well above the floor is not an artefact`() {
        assertFalse(verdict(minPred = 55.0, bg = 175.0).wide)
    }

    @Test
    fun `a feasible outcome is never an artefact case`() {
        assertFalse(verdict(feasible = true, bg = 175.0).wide)
    }

    @Test
    fun `a falling trend keeps the veto`() {
        // -4 is past MAX_NEG_DELTA_MGDL: a real fall, so the warning stands whatever the curves say.
        assertFalse(verdict(bg = 175.0, delta = -4.0).wide)
        assertFalse(verdict(bg = 175.0, delta = -4.0).strict)
    }

    @Test
    fun `a rising trend is not this rule's business`() {
        assertFalse(verdict(bg = 175.0, delta = 6.0).wide)
    }

    @Test
    fun `glucose near the claimed floor keeps the veto`() {
        // 85 is only 40 above the artefact band: a prediction of 39 there deserves to be believed.
        assertFalse(verdict(minPred = 39.0, bg = 85.0, delta = 0.0).wide)
    }

    @Test
    fun `sport and a post-hypo window always stand down`() {
        assertFalse(verdict(bg = 175.0, sport = true).wide)
        assertFalse(verdict(bg = 175.0, postHypo = true).wide)
        assertFalse(verdict(bg = 175.0, sport = true).strict)
        assertFalse(verdict(bg = 175.0, postHypo = true).strict)
    }

    @Test
    fun `a broken number never lifts a veto`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(verdict(minPred = bad, bg = 175.0).wide)
            assertFalse(verdict(bg = bad).wide)
            assertFalse(verdict(bg = 175.0, delta = bad).wide)
        }
    }

    @Test
    fun `strict is never true without wide`() {
        val cases = listOf(30.0, 39.0, 44.9, 45.1, 60.0)
        for (minPred in cases) {
            for (bg in listOf(80.0, 120.0, 142.0, 160.0, 200.0)) {
                for (delta in listOf(-5.0, -2.0, 0.0, 2.0, 5.0)) {
                    val v = verdict(minPred = minPred, bg = bg, delta = delta)
                    if (v.strict) assertTrue(v.wide)
                }
            }
        }
    }
}
