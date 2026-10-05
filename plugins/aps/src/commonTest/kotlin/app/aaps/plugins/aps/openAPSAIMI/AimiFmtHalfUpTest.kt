package app.aaps.plugins.aps.openAPSAIMI

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `aimiFmt*` matches `String.format(Locale.US, "%.Nf", x)`: ties go away from zero.
 * `0.25` at one decimal is `0.3`. `-1.25` at one decimal is `-1.3`. `-0.5` at zero decimals is `-1`.
 */
class AimiFmtHalfUpTest {

    @Test
    fun oneDecimalQuarterGoesAwayFromZero() {
        assertEquals("0.3", aimiFmt1(0.25))
        assertEquals("-0.3", aimiFmt1(-0.25))
        assertEquals("-1.3", aimiFmt1(-1.25))
        assertEquals("1.3", aimiFmt1(1.25))
    }

    @Test
    fun zeroDecimalsNegativeHalfGoesAwayFromZero() {
        assertEquals("-1", aimiFmt0(-0.5))
        assertEquals("1", aimiFmt0(0.5))
        assertEquals("-2", aimiFmt0(-1.5))
    }

    @Test
    fun threeDecimalsHalfGoesAwayFromZero() {
        assertEquals("1.235", aimiFmt3(1.2345))
        assertEquals("-1.235", aimiFmt3(-1.2345))
    }

    @Test
    fun negativeValueThatRoundsToZeroKeepsTheSign() {
        assertEquals("-0", aimiFmt0(-0.25))
        assertEquals("-0.0", aimiFmt1(-0.04))
        assertEquals("-2.0", aimiFmt1(-1.95))
    }

    @Test
    fun floatPromotesBeforeHalfUp() {
        assertEquals("0.3", aimiFmt1(0.25f))
        assertEquals("-1.3", aimiFmt1(-1.25f))
        assertEquals("-1", aimiFmt0(-0.5f))
    }

    @Test
    fun nullPrintsFormatterPrecisionSlice() {
        assertEquals("", aimiFmt0(null as Double?))
        assertEquals("n", aimiFmt1(null as Double?))
        assertEquals("nu", aimiFmt2(null as Double?))
        assertEquals("nul", aimiFmt3(null as Double?))
        assertEquals("null", aimiFmt4(null as Double?))
    }
}
