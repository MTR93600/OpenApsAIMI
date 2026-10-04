package app.aaps.plugins.aps.openAPSAIMI

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `aimiFmt*` must print the same text as `String.format(Locale.US, "%.Nf", x)` for the specs
 * the tick uses. A mismatch here is a reason to stop the formatting move.
 */
class AimiFmtStringFormatParityTest {

    @Test
    fun aimiFmtMatchesLocaleUsStringFormatIncludingNegativeHalves() {
        val samples = ArrayList<Double>()
        for (i in -40..40) samples.add(i / 4.0) // includes .0, .25, .5, .75
        for (i in -20..20) samples.add(i / 8.0)
        samples.add(1.2345)
        samples.add(-1.2345)
        samples.add(-1.95)
        samples.add(9.996)
        samples.add(-0.25)
        samples.add(1.0e7)
        samples.add(-1.0e-7)
        samples.add(0.0)
        samples.add(-0.0)
        samples.add(Double.NaN)
        samples.add(Double.POSITIVE_INFINITY)
        samples.add(Double.NEGATIVE_INFINITY)
        val mismatches = ArrayList<String>()
        for (value in samples) {
            check(0, value, aimiFmt0(value), mismatches)
            check(1, value, aimiFmt1(value), mismatches)
            check(2, value, aimiFmt2(value), mismatches)
            check(3, value, aimiFmt3(value), mismatches)
            check(4, value, aimiFmt4(value), mismatches)
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun floatAndNullMatchLocaleUsStringFormat() {
        val mismatches = ArrayList<String>()
        val floats = floatArrayOf(0.25f, -0.25f, -1.25f, 0.5f, -0.5f, -1.5f, 1.2345f, -1.2345f, 0.0f, -0.0f)
        for (value in floats) {
            check(0, value.toDouble(), aimiFmt0(value), mismatches)
            check(1, value.toDouble(), aimiFmt1(value), mismatches)
            check(2, value.toDouble(), aimiFmt2(value), mismatches)
            check(3, value.toDouble(), aimiFmt3(value), mismatches)
            check(4, value.toDouble(), aimiFmt4(value), mismatches)
        }
        val absent = null as Double?
        checkNull(0, aimiFmt0(absent), mismatches)
        checkNull(1, aimiFmt1(absent), mismatches)
        checkNull(2, aimiFmt2(absent), mismatches)
        checkNull(3, aimiFmt3(absent), mismatches)
        checkNull(4, aimiFmt4(absent), mismatches)
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun checkNull(decimals: Int, actual: String, mismatches: MutableList<String>) {
        val expected = String.format(Locale.US, "%.${decimals}f", null as Any?)
        if (expected != actual) mismatches.add("fmt$decimals(null): expected '$expected' actual '$actual'")
    }

    private fun check(decimals: Int, value: Double, actual: String, mismatches: MutableList<String>) {
        val expected = String.format(Locale.US, "%.${decimals}f", value)
        if (expected != actual) mismatches.add("fmt$decimals($value): expected '$expected' actual '$actual'")
    }
}
