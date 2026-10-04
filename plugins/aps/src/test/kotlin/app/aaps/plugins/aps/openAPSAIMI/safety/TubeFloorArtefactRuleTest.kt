package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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
    fun `the real 21-09 tick is wide but not strict, because 142 is under the plateau band`() {
        val v = verdict(minPred = 40.97, bg = 142.0, delta = 0.4)

        assertThat(v.wide).isTrue()
        assertThat(v.strict).isFalse()
    }

    @Test
    fun `the same artefact above the plateau band is strict`() {
        val v = verdict(minPred = 39.0, bg = 175.0, delta = 0.4)

        assertThat(v.strict).isTrue()
        assertThat(v.wide).isTrue()
    }

    @Test
    fun `a prediction well above the floor is not an artefact`() {
        assertThat(verdict(minPred = 55.0, bg = 175.0).wide).isFalse()
    }

    @Test
    fun `a feasible outcome is never an artefact case`() {
        assertThat(verdict(feasible = true, bg = 175.0).wide).isFalse()
    }

    @Test
    fun `a falling trend keeps the veto`() {
        // -4 is past MAX_NEG_DELTA_MGDL: a real fall, so the warning stands whatever the curves say.
        assertThat(verdict(bg = 175.0, delta = -4.0).wide).isFalse()
        assertThat(verdict(bg = 175.0, delta = -4.0).strict).isFalse()
    }

    @Test
    fun `a rising trend is not this rule's business`() {
        assertThat(verdict(bg = 175.0, delta = 6.0).wide).isFalse()
    }

    @Test
    fun `glucose near the claimed floor keeps the veto`() {
        // 85 is only 40 above the artefact band: a prediction of 39 there deserves to be believed.
        assertThat(verdict(minPred = 39.0, bg = 85.0, delta = 0.0).wide).isFalse()
    }

    @Test
    fun `sport and a post-hypo window always stand down`() {
        assertThat(verdict(bg = 175.0, sport = true).wide).isFalse()
        assertThat(verdict(bg = 175.0, postHypo = true).wide).isFalse()
        assertThat(verdict(bg = 175.0, sport = true).strict).isFalse()
        assertThat(verdict(bg = 175.0, postHypo = true).strict).isFalse()
    }

    @Test
    fun `a broken number never lifts a veto`() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThat(verdict(minPred = bad, bg = 175.0).wide).isFalse()
            assertThat(verdict(bg = bad).wide).isFalse()
            assertThat(verdict(bg = 175.0, delta = bad).wide).isFalse()
        }
    }

    @Test
    fun `strict is never true without wide`() {
        val cases = listOf(30.0, 39.0, 44.9, 45.1, 60.0)
        for (minPred in cases) {
            for (bg in listOf(80.0, 120.0, 142.0, 160.0, 200.0)) {
                for (delta in listOf(-5.0, -2.0, 0.0, 2.0, 5.0)) {
                    val v = verdict(minPred = minPred, bg = bg, delta = delta)
                    if (v.strict) assertThat(v.wide).isTrue()
                }
            }
        }
    }
}
