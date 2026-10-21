package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Source: `dev_OAPSAIMI` @ `bef0366f4c` `TerminalRateGuardsTest`, same cases in `kotlin.test`. */
class TerminalRateGuardsTest {

    /** What the auditor sent on 2026-10-20: the loop rate echoed back. */
    @Test
    fun auditorEchoKeepsTheRate() {
        assertEquals(2.208, TerminalRateGuards.auditorTbr(2.208, 2.208))
    }

    @Test
    fun auditorCannotRaiseTheRate() {
        assertEquals(2.208, TerminalRateGuards.auditorTbr(2.208, 6.0))
    }

    @Test
    fun auditorCanLowerTheRate() {
        assertEquals(1.0, TerminalRateGuards.auditorTbr(6.803, 1.0))
    }

    @Test
    fun auditorNonFiniteOrNegativeKeepsTheLoopRate() {
        assertEquals(3.257, TerminalRateGuards.auditorTbr(3.257, Double.NaN))
        assertEquals(3.257, TerminalRateGuards.auditorTbr(3.257, -1.0))
    }

    /** No loop rate: the auditor cannot create one. */
    @Test
    fun auditorCannotCreateARate() {
        assertNull(TerminalRateGuards.auditorTbr(null, 2.0))
    }

    @Test
    fun forceExactIsBoundByTheMealModeCeiling() {
        assertEquals(10.0, TerminalRateGuards.forceExactCeiling(12.0, 7.0, 10.0))
    }

    @Test
    fun forceExactWithoutMealCeilingUsesMaxBasal() {
        assertEquals(7.0, TerminalRateGuards.forceExactCeiling(12.0, 7.0, 0.0))
    }

    @Test
    fun forceExactKeepsALowerRate() {
        assertEquals(10.0, TerminalRateGuards.forceExactCeiling(10.0, 7.0, 10.0))
        assertEquals(0.0, TerminalRateGuards.forceExactCeiling(-1.0, 7.0, 10.0))
    }
}
