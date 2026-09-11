package app.aaps.plugins.aps.openAPSAIMI.smb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the descent re-dose guard on the two reference episodes it was fitted on, plus one case per
 * condition.
 *
 * The two reference episodes carry their real numbers. They are the reason the guard exists:
 *  - the night of 08/09, three boluses re-dosing a descent, which ended at BG 70.4;
 *  - the lunch of 09/09, a good burst of 8.96 U that the guard must leave alone.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `fe96b64f12`. Study source set: [DescentRedoseGuard]
 * is commonMain and has no mocks, so the tests live in `commonTest` (`kotlin.test`) — same layout
 * as [MaxSmbLadderRiseByDeltaTest], not `androidHostTest`. Assertions are the same locks as the
 * Truth/JUnit reference corpus.
 */
class DescentRedoseGuardTest {

    private val t0 = 1_757_000_000_000L // arbitrary epoch anchor, the guard only uses differences

    private fun at(minutes: Double) = t0 + (minutes * 60_000L).toLong()

    /** Builds readings from (minute offset, mg/dL) pairs. */
    private fun series(vararg points: Pair<Double, Double>) =
        points.map { DescentRedoseGuard.Reading(at(it.first), it.second) }

    // ---------------------------------------------------------------------------------------
    // Reference episode 1 — night of 08/09. Peak 232.5, three re-doses at +57, +63 and +98 min.
    // ---------------------------------------------------------------------------------------

    /**
     * The measured trace, on the 5 min CGM cadence, with the peak at offset 0.
     *
     * It holds the local bump the guard was built for: 170.2 at +40 then 186.8 at +55, so BG is
     * *rising* at the moment of the first re-dose. An instant "BG is falling" test sees nothing here.
     */
    private val night0809 = series(
        0.0 to 232.5,
        5.0 to 226.0,
        10.0 to 218.0,
        15.0 to 209.0,
        20.0 to 200.0,
        25.0 to 192.0,
        30.0 to 185.0,
        35.0 to 177.0,
        40.0 to 170.2, // trough of the first two re-doses
        45.0 to 174.0,
        50.0 to 180.0,
        55.0 to 186.8, // first re-dose reads this
        60.0 to 190.8, // second re-dose reads this
        65.0 to 188.0,
        70.0 to 183.0,
        75.0 to 178.0,
        80.0 to 173.0,
        85.0 to 169.0,
        90.0 to 166.6, // trough of the third re-dose
        95.0 to 171.6, // third re-dose reads this
    )

    @Test
    fun `night of 08-09 first re-dose at peak plus 57 minutes is blocked`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)

        assertTrue(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_BLOCKED, verdict.reasonCode)
        assertEquals(232.5, verdict.peakMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(57.0, verdict.peakAgeMinutes!!, absoluteTolerance = 1e-9)
        assertEquals(186.8, verdict.currentBgMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(170.2, verdict.troughMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `night of 08-09 second re-dose at peak plus 63 minutes is blocked`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(63.0), iobU = 14.29)

        assertTrue(verdict.block)
        assertEquals(190.8, verdict.currentBgMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(170.2, verdict.troughMgdl!!, absoluteTolerance = 1e-9)
        // The bump is +20.6 over the trough, still inside the basin, so condition (e) holds.
        assertEquals(20.6, verdict.reboundFromTroughMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `night of 08-09 third re-dose at peak plus 98 minutes is blocked`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(98.0), iobU = 11.28)

        assertTrue(verdict.block)
        assertEquals(171.6, verdict.currentBgMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(166.6, verdict.troughMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(98.0, verdict.peakAgeMinutes!!, absoluteTolerance = 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Reference episode 2 — lunch of 09/09, a good burst of 8.96 U that must not be touched.
    // ---------------------------------------------------------------------------------------

    /** BG 128 up to a peak of 240.4, then a plateau ending at 240.2, a fall of only 0.2 mg/dL. */
    private val lunch0909 = series(
        0.0 to 128.0,
        5.0 to 136.0,
        10.0 to 149.0,
        15.0 to 165.0,
        20.0 to 183.0,
        25.0 to 201.0,
        30.0 to 218.0,
        35.0 to 231.0,
        40.0 to 240.4, // peak
        45.0 to 239.5,
        50.0 to 238.9,
        55.0 to 239.2,
        60.0 to 239.8,
        65.0 to 240.0,
        70.0 to 240.1,
        75.0 to 240.2,
    )

    @Test
    fun `lunch of 09-09 is not blocked because BG only fell 0 point 2 from the peak`() {
        val verdict = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_DROP_TOO_SMALL, verdict.reasonCode)
        assertEquals(240.4, verdict.peakMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(240.2, verdict.currentBgMgdl!!, absoluteTolerance = 1e-9)
        // The peak is old enough and IOB is heavy, so (a) (b) (d) all hold; only (c) refuses.
        assertTrue(verdict.peakAgeMinutes!! >= DescentRedoseGuard.PEAK_AGE_MINUTES)
        assertEquals(0.2, verdict.dropFromPeakMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `no moment of the 09-09 rise is blocked whatever the IOB`() {
        for (index in lunch0909.indices) {
            val now = lunch0909[index].timeMs + 120_000L // two minutes after that reading
            for (iob in listOf(0.0, 6.0, 9.0, 14.0)) {
                val verdict = DescentRedoseGuard.evaluate(
                    lunch0909.subList(0, index + 1), nowMs = now, iobU = iob,
                )
                assertFalse(verdict.block)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Condition (e) — the rebound guard is required, not a refinement.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a new rise after the peak is not blocked even though a b c and d all hold`() {
        val newRise = series(
            0.0 to 250.0, // peak
            10.0 to 225.0,
            20.0 to 200.0,
            30.0 to 175.0,
            40.0 to 158.0,
            50.0 to 150.0, // trough
            60.0 to 162.0,
            70.0 to 178.0,
            80.0 to 192.0,
            90.0 to 200.0, // 50 mg/dL above the trough: this is a new rise, not a bump
        )
        val verdict = DescentRedoseGuard.evaluate(newRise, nowMs = at(92.0), iobU = 8.0)

        assertEquals(DescentRedoseGuard.REASON_REBOUND_ABOVE_TROUGH, verdict.reasonCode)
        assertFalse(verdict.block)
        // Proof that only (e) saved it: (a) (b) (c) (d) are all satisfied here.
        assertTrue(verdict.peakMgdl!! >= DescentRedoseGuard.PEAK_MIN_MGDL)
        assertTrue(verdict.peakAgeMinutes!! >= DescentRedoseGuard.PEAK_AGE_MINUTES)
        assertTrue(verdict.dropFromPeakMgdl!! >= DescentRedoseGuard.DROP_MGDL)
        assertTrue(verdict.reboundFromTroughMgdl!! > DescentRedoseGuard.REBOUND_MGDL)
    }

    // ---------------------------------------------------------------------------------------
    // Data holes.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a hole longer than ten minutes hides the peak that sits before it`() {
        val withHole = series(
            0.0 to 250.0, // peak, but on the far side of the hole
            5.0 to 240.0,
            10.0 to 230.0,
            // 30 min hole here
            40.0 to 150.0,
            45.0 to 148.0,
            50.0 to 146.0,
            55.0 to 145.0,
        )
        val verdict = DescentRedoseGuard.evaluate(withHole, nowMs = at(57.0), iobU = 12.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_PEAK_TOO_LOW, verdict.reasonCode)
        // The peak seen is the highest value AFTER the hole, never the 250 before it.
        assertEquals(150.0, verdict.peakMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the same series without the hole does see the peak and blocks`() {
        val noHole = series(
            0.0 to 250.0,
            5.0 to 240.0,
            10.0 to 230.0,
            18.0 to 210.0,
            26.0 to 190.0,
            34.0 to 170.0,
            40.0 to 150.0,
            45.0 to 148.0,
            50.0 to 146.0,
            55.0 to 145.0,
        )
        val verdict = DescentRedoseGuard.evaluate(noHole, nowMs = at(57.0), iobU = 12.0)

        assertTrue(verdict.block)
        assertEquals(250.0, verdict.peakMgdl!!, absoluteTolerance = 1e-9)
    }

    @Test
    fun `an empty window says no data and never blocks`() {
        val verdict = DescentRedoseGuard.evaluate(emptyList(), nowMs = at(10.0), iobU = 20.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_NO_DATA, verdict.reasonCode)
        assertNull(verdict.peakMgdl)
    }

    @Test
    fun `a window whose newest reading is stale never blocks`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(115.0), iobU = 13.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_STALE_DATA, verdict.reasonCode)
    }

    // ---------------------------------------------------------------------------------------
    // One case per condition: everything else holds, only the named condition is missing.
    // ---------------------------------------------------------------------------------------

    /** A descent that blocks, scaled so each condition can be broken one at a time. */
    private fun descent(
        peak: Double = 232.5,
        trough: Double = 170.0,
        current: Double = 180.0,
    ) = series(
        0.0 to peak,
        10.0 to (peak + trough) / 2.0,
        20.0 to trough + 5.0,
        30.0 to trough,
        40.0 to current,
    )

    @Test
    fun `the reference descent blocks when all five conditions hold`() {
        val verdict = DescentRedoseGuard.evaluate(descent(), nowMs = at(42.0), iobU = 10.0)

        assertTrue(verdict.block)
    }

    @Test
    fun `condition a alone missing - the peak never reached 180`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 175.0, trough = 120.0, current = 130.0), nowMs = at(42.0), iobU = 10.0,
        )

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_PEAK_TOO_LOW, verdict.reasonCode)
    }

    @Test
    fun `condition b alone missing - the peak is only 20 minutes behind`() {
        val quick = series(
            0.0 to 232.5,
            5.0 to 210.0,
            10.0 to 195.0,
            15.0 to 180.0,
        )
        val verdict = DescentRedoseGuard.evaluate(quick, nowMs = at(20.0), iobU = 10.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_PEAK_TOO_RECENT, verdict.reasonCode)
    }

    @Test
    fun `condition c alone missing - BG has fallen only 10 from the peak`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 232.5, trough = 224.0, current = 222.5), nowMs = at(42.0), iobU = 10.0,
        )

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_DROP_TOO_SMALL, verdict.reasonCode)
    }

    @Test
    fun `condition d alone missing - only 5 U still active`() {
        val verdict = DescentRedoseGuard.evaluate(descent(), nowMs = at(42.0), iobU = 5.0)

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_IOB_TOO_LOW, verdict.reasonCode)
    }

    @Test
    fun `condition e alone missing - BG is 30 above the trough`() {
        val verdict = DescentRedoseGuard.evaluate(
            descent(peak = 232.5, trough = 170.0, current = 200.0), nowMs = at(42.0), iobU = 10.0,
        )

        assertFalse(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_REBOUND_ABOVE_TROUGH, verdict.reasonCode)
    }

    @Test
    fun `exactly on each threshold the guard still blocks`() {
        // The conditions use >= and <=, so a tick sitting on a threshold is inside, not outside.
        val onEdge = series(
            0.0 to 180.0,  // (a) exactly PEAK_MIN_MGDL
            10.0 to 170.0,
            20.0 to 160.0,
            25.0 to 130.0, // trough
            30.0 to 155.0, // (c) drop is exactly 25, (e) rebound is exactly 25
        )
        val verdict = DescentRedoseGuard.evaluate(onEdge, nowMs = at(30.0), iobU = 6.0)

        assertTrue(verdict.block)
        assertEquals(25.0, verdict.dropFromPeakMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(25.0, verdict.reboundFromTroughMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(30.0, verdict.peakAgeMinutes!!, absoluteTolerance = 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Arming: the verdict is measured on every tick, the dose is only touched when the key is on.
    // ---------------------------------------------------------------------------------------

    @Test
    fun `with the key off the verdict is still computed but no dose is withheld`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)

        // The shadow measurement happens whatever the key says.
        assertTrue(verdict.block)
        assertEquals(DescentRedoseGuard.REASON_BLOCKED, verdict.reasonCode)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = false, isExplicitUserAction = false, proposedUnits = 0.55,
        )
        assertFalse(withheld)
    }

    @Test
    fun `with the key on the same tick withholds the dose`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.55,
        )
        assertTrue(withheld)
    }

    @Test
    fun `a user-initiated bolus is never withheld even with the key on`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = true, proposedUnits = 0.55,
        )
        assertFalse(withheld)
    }

    @Test
    fun `a tick that already proposes nothing is not reported as a withholding`() {
        val verdict = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 0.0,
        )
        assertFalse(withheld)
    }

    @Test
    fun `the good 09-09 lunch is not withheld even with the key on`() {
        val verdict = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        val withheld = DescentRedoseGuard.shouldWithhold(
            verdict = verdict, armed = true, isExplicitUserAction = false, proposedUnits = 1.20,
        )
        assertFalse(withheld)
    }

    @Test
    fun `the reason string always starts with the reason code so it can be counted`() {
        val blocked = DescentRedoseGuard.evaluate(night0809, nowMs = at(57.0), iobU = 13.81)
        val passed = DescentRedoseGuard.evaluate(lunch0909, nowMs = at(77.0), iobU = 9.0)

        assertTrue(blocked.reason.startsWith(DescentRedoseGuard.REASON_BLOCKED))
        assertTrue(passed.reason.startsWith(DescentRedoseGuard.REASON_DROP_TOO_SMALL))
        assertTrue(blocked.reason.contains("peak=232.5"))
        assertTrue(blocked.reason.contains("iob=13.81"))
    }
}
