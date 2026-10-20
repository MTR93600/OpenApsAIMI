package app.aaps.plugins.aps.openAPSAIMI.basal

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class TerminalRateGuardsTest {

    /** What the auditor sent on 2026-10-20: the loop rate echoed back. */
    @Test
    fun auditorEchoKeepsTheRate() {
        assertThat(TerminalRateGuards.auditorTbr(2.208, 2.208)).isEqualTo(2.208)
    }

    @Test
    fun auditorCannotRaiseTheRate() {
        assertThat(TerminalRateGuards.auditorTbr(2.208, 6.0)).isEqualTo(2.208)
    }

    @Test
    fun auditorCanLowerTheRate() {
        assertThat(TerminalRateGuards.auditorTbr(6.803, 1.0)).isEqualTo(1.0)
    }

    @Test
    fun auditorNonFiniteOrNegativeKeepsTheLoopRate() {
        assertThat(TerminalRateGuards.auditorTbr(3.257, Double.NaN)).isEqualTo(3.257)
        assertThat(TerminalRateGuards.auditorTbr(3.257, -1.0)).isEqualTo(3.257)
    }

    /** No loop rate: the auditor cannot create one. */
    @Test
    fun auditorCannotCreateARate() {
        assertThat(TerminalRateGuards.auditorTbr(null, 2.0)).isNull()
    }

    @Test
    fun forceExactIsBoundByTheMealModeCeiling() {
        assertThat(TerminalRateGuards.forceExactCeiling(12.0, 7.0, 10.0)).isEqualTo(10.0)
    }

    @Test
    fun forceExactWithoutMealCeilingUsesMaxBasal() {
        assertThat(TerminalRateGuards.forceExactCeiling(12.0, 7.0, 0.0)).isEqualTo(7.0)
    }

    @Test
    fun forceExactKeepsALowerRate() {
        assertThat(TerminalRateGuards.forceExactCeiling(10.0, 7.0, 10.0)).isEqualTo(10.0)
        assertThat(TerminalRateGuards.forceExactCeiling(-1.0, 7.0, 10.0)).isEqualTo(0.0)
    }
}
