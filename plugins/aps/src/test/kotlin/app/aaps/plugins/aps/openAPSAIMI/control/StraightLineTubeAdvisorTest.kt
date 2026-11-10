package app.aaps.plugins.aps.openAPSAIMI.control

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The tube sets this tick's SMB ceiling, and when it answers zero the caller floors the ceiling at
 * 0.05 U — a hard SMB shutdown.
 *
 * Measured on the 2026-08-13 support package (286 decision ticks over 24 h): the tube answered zero
 * on 81 ticks, 28 % of the day. Reading the cap could not say why, because three different paths all
 * end at zero and all three looked identical in the export. These tests pin the three paths apart and
 * pin the one property the shutdown rests on: the veto is nothing more than a restatement of
 * `minPredictedBg < hypoFloor`.
 */
class StraightLineTubeAdvisorTest {

    private val hypoFloor = 70.0
    private val hyperBand = 20.0

    private fun advisor(): StraightLineTubeAdvisor {
        val preferences = mockk<Preferences>()
        every { preferences.get(DoubleKey.AimiTubeHypoFloorMgdl) } returns hypoFloor
        every { preferences.get(DoubleKey.AimiTubeHyperBandMgdl) } returns hyperBand
        every { preferences.get(DoubleKey.AimiTubeAggressiveness) } returns 1.275
        every { preferences.get(DoubleKey.AimiTubeBasalTrimMax) } returns 0.15
        every { preferences.get(DoubleKey.AimiTubeKappaSafetyMargin) } returns 0.10
        val logger = mockk<AAPSLogger>(relaxed = true)
        return StraightLineTubeAdvisor(preferences, logger)
    }

    private fun input(
        bgMgdl: Double,
        minPredictedBg: Double?,
        eventualBgMgdl: Double,
        maxSmbU: Double = 1.87,
        iobU: Double = 1.0,
        isfMgdlPerU: Double = 40.0,
    ) = StraightLineTubeAdvisor.Input(
        bgMgdl = bgMgdl,
        deltaMgdlPer5m = 0.0,
        iobU = iobU,
        cobG = 0.0,
        isfMgdlPerU = isfMgdlPerU,
        diaHours = 6.0,
        targetMgdl = 90.0,
        maxSmbU = maxSmbU,
        minPredictedBg = minPredictedBg,
        eventualBgMgdl = eventualBgMgdl,
    )

    /**
     * The candidate ladder ends at 0.0, and a zero dose moves the projected minimum by nothing. So
     * "no candidate is feasible" can only mean the minimum was already under the floor. The advisor
     * runs no projection of its own for this test — it trusts whatever the caller hands in.
     */
    @Test
    fun `infeasible is exactly minPred below the hypo floor`() {
        val advisor = advisor()
        for (minPred in listOf(39.0, 55.0, 64.3, 69.0, 69.999)) {
            val out = advisor.advise(input(bgMgdl = 110.0, minPredictedBg = minPred, eventualBgMgdl = 376.0))
            assertThat(out.feasible).isFalse()
            assertThat(out.branch).isEqualTo(StraightLineTubeAdvisor.Branch.VETO_HYPO_FLOOR)
            assertThat(out.smbCapScale).isEqualTo(0.0)
        }
        for (minPred in listOf(70.0, 73.7, 116.8, 200.0)) {
            val out = advisor.advise(input(bgMgdl = 110.0, minPredictedBg = minPred, eventualBgMgdl = 376.0))
            assertThat(out.feasible).isTrue()
            assertThat(out.branch).isNotEqualTo(StraightLineTubeAdvisor.Branch.VETO_HYPO_FLOOR)
        }
    }

    /**
     * Lunch onset, 12 Aug 13:21: BG 110 rising, eventual 376, min of the prediction 73.7 — only
     * 3.7 mg/dL of room above the floor. With kappa near 39 mg/dL per U and a 1.87 U ceiling, the
     * smallest non-zero rung (0.1) already asks for 7.3 mg/dL. So the tube is *feasible* and still
     * answers zero. That is quantisation, not a hypo forecast, and it needs a different fix from a
     * veto — hence its own branch.
     */
    @Test
    fun `above the floor but with no reachable rung is reported as ZERO_ONLY_FEASIBLE`() {
        val out = advisor().advise(input(bgMgdl = 110.0, minPredictedBg = 73.7, eventualBgMgdl = 376.0))
        assertThat(out.feasible).isTrue()
        assertThat(out.branch).isEqualTo(StraightLineTubeAdvisor.Branch.ZERO_ONLY_FEASIBLE)
        assertThat(out.smbCapScale).isEqualTo(0.0)
        assertThat(out.sMaxFeasible).isLessThan(0.1)
        assertThat(out.sMaxFeasible).isGreaterThan(0.0)
    }

    /**
     * The cost function's only term that rewards dosing is the hyper excess. Its `bgErr` and `evErr`
     * terms do not depend on the candidate scale at all, so they add the same constant to every
     * candidate and cannot influence the choice.
     *
     * That leaves the scale as a leaky integrator with no input: with the eventual at or under
     * `target + hyperBand` the objective is minimised near `0.4 × lastScale`, so the ceiling decays
     * geometrically to zero and then stays there — plentiful hypo headroom does not stop it. This is
     * the ladder seen in production (0.85, 0.70, 0.55, 0.40, 0.25, 0.10, 0) and it held on 17 of the
     * 81 zero-cap ticks, overnight 13 Aug 03:06–06:21 at a flat BG near 130.
     */
    @Test
    fun `with no hyper excess the ceiling decays to zero and stays there`() {
        val advisor = advisor()
        val scales = (1..8).map {
            advisor.advise(
                input(bgMgdl = 134.0, minPredictedBg = 111.0, eventualBgMgdl = 105.0, maxSmbU = 1.21),
            )
        }
        assertThat(scales.first().hyperExcessMgdl).isEqualTo(0.0)
        // Monotone decay, never a recovery.
        for (i in 1 until scales.size) {
            assertThat(scales[i].smbCapScale).isAtMost(scales[i - 1].smbCapScale)
        }
        val settled = scales.last()
        assertThat(settled.smbCapScale).isEqualTo(0.0)
        assertThat(settled.feasible).isTrue()
        assertThat(settled.branch).isEqualTo(StraightLineTubeAdvisor.Branch.ZERO_BY_COST)
        // Headroom was plentiful — nothing about the hypo floor stopped this one.
        assertThat(settled.sMaxFeasible).isGreaterThan(0.5)
    }

    /**
     * Dinner onset, 12 Aug 20:52 as exported: min 116.8, eventual 184. Both the floor and the cost
     * function permit a dose here, so the graded path is what the exported numbers predict. The tick
     * was nonetheless capped at 0.05 U in production, which is why the deciding inputs are now
     * exported.
     */
    @Test
    fun `clear headroom plus hyper excess yields a graded cap`() {
        val out = advisor().advise(
            input(bgMgdl = 117.0, minPredictedBg = 116.8, eventualBgMgdl = 184.0, maxSmbU = 1.21),
        )
        assertThat(out.feasible).isTrue()
        assertThat(out.branch).isEqualTo(StraightLineTubeAdvisor.Branch.GRADED)
        assertThat(out.smbCapScale).isGreaterThan(0.0)
        assertThat(out.hyperExcessMgdl).isGreaterThan(0.0)
    }

    /** Every chosen scale must keep the projected minimum at or above the floor. */
    @Test
    fun `the chosen scale never projects below the hypo floor`() {
        val advisor = advisor()
        val cases = listOf(
            Triple(110.0, 73.7, 376.0),
            Triple(117.0, 116.8, 184.0),
            Triple(200.0, 81.1, 213.0),
            Triple(180.0, 80.0, 197.0),
            Triple(134.0, 111.0, 132.0),
        )
        for ((bg, minPred, eventual) in cases) {
            val out = advisor.advise(input(bgMgdl = bg, minPredictedBg = minPred, eventualBgMgdl = eventual))
            if (!out.feasible) continue
            val minAfter = minPred - out.smbCapScale * out.maxSmbU * out.kappaMgdlPerU
            assertThat(minAfter).isAtLeast(hypoFloor - 1e-9)
        }
    }

    /** No prediction minimum means no opinion: the advisor must not cap anything. */
    @Test
    fun `a missing minPred skips instead of vetoing`() {
        val out = advisor().advise(input(bgMgdl = 110.0, minPredictedBg = null, eventualBgMgdl = 376.0))
        assertThat(out.feasible).isTrue()
        assertThat(out.branch).isEqualTo(StraightLineTubeAdvisor.Branch.SKIP_NO_MIN_PRED)
        assertThat(out.smbCapScale).isEqualTo(1.0)
        assertThat(out.basalCapScale).isEqualTo(1.0)
    }
}
