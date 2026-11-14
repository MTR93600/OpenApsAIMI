package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.FixedOffsetTimeZone
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset

/**
 * [DashBasalDrift], with the cases and numbers of Android `IntegrateExpectedDeliveryTest` and
 * `CalculateBolusPulseIncreaseTest` (`:pump:omnipod:common`), unchanged. Android sets the default time
 * zone of the JVM ("UTC", "UTC+2", "UTC-5"); here the same fixed offsets are passed as the phone time
 * zone, which is what `TimeZone.getDefault()` gives the Android code.
 */
class DashBasalDriftTest {

    // 2026-01-01T00:00:00Z
    private val jan1Utc = 1_767_225_600_000L
    private val hour = 3_600_000L
    private val min30 = 1_800_000L

    private val offsetUtc = 0
    private val offsetPlus2 = 2 * 60 * 60 * 1000
    private val offsetMinus5 = -5 * 60 * 60 * 1000

    private val utc: TimeZone = FixedOffsetTimeZone(UtcOffset(hours = 0))
    private val plus2: TimeZone = FixedOffsetTimeZone(UtcOffset(hours = 2))
    private val minus5: TimeZone = FixedOffsetTimeZone(UtcOffset(hours = -5))

    private fun seg(startSlot: Int, endSlot: Int, hundredthsPerHour: Int) = BasalProgram.Segment(startSlot.toShort(), endSlot.toShort(), hundredthsPerHour)

    private fun flatBasal(rateUh: Double) = BasalProgram(listOf(seg(0, 48, (rateUh * 100).toInt())))

    private fun twoSegmentBasal(rate1Uh: Double, rate2Uh: Double, splitSlot: Int) =
        BasalProgram(listOf(seg(0, splitSlot, (rate1Uh * 100).toInt()), seg(splitSlot, 48, (rate2Uh * 100).toInt())))

    private fun tempBasal(startOffsetMs: Long, durationMins: Int, rateUh: Double) =
        DashTempBasal(startTimeMs = jan1Utc + startOffsetMs, rate = rateUh, durationInMinutes = durationMins)

    private fun integrate(start: Long, end: Long, offset: Int?, tb: DashTempBasal?, program: BasalProgram?, device: TimeZone = utc) =
        DashBasalDrift.integrateExpectedDelivery(start, end, offset, tb, program, device)

    private fun assertUnits(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")
    }

    // ---- happy path

    @Test
    fun `single rate basal in one segment`() = assertUnits(0.5, integrate(jan1Utc, jan1Utc + min30, offsetUtc, null, flatBasal(1.0)))

    @Test
    fun `single rate basal for 24 h`() = assertUnits(24.0, integrate(jan1Utc, jan1Utc + 24 * hour, offsetUtc, null, flatBasal(1.0)))

    @Test
    fun `two segment basal over the segment boundary`() =
        assertUnits(3.0, integrate(jan1Utc + 7 * hour, jan1Utc + 9 * hour, offsetUtc, null, twoSegmentBasal(1.0, 2.0, splitSlot = 16)))

    @Test
    fun `the window crosses midnight`() =
        assertUnits(3.0, integrate(jan1Utc + 23 * hour, jan1Utc + 25 * hour, offsetUtc, null, twoSegmentBasal(1.0, 2.0, splitSlot = 16)))

    // ---- temp basal

    @Test
    fun `a temp basal covers the whole window`() = assertUnits(1.5, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(-hour, 180, 1.5), flatBasal(1.0)))

    @Test
    fun `a temp basal covers part of the window`() = assertUnits(1.5, integrate(jan1Utc, jan1Utc + 2 * hour, offsetUtc, tempBasal(min30, 30, 0.0), flatBasal(1.0)))

    @Test
    fun `a temp basal starts at the start`() = assertUnits(0.5, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(0L, 30, 0.0), flatBasal(1.0)))

    @Test
    fun `a temp basal ends at the end`() = assertUnits(2.0, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(0L, 60, 2.0), flatBasal(1.0)))

    @Test
    fun `a temp basal starts before and ends inside the window`() =
        assertUnits(0.5, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(-min30, 60, 0.0), flatBasal(1.0)))

    @Test
    fun `a temp basal starts before and ends after the window`() =
        assertUnits(3.0, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(-hour, 180, 3.0), flatBasal(1.0)))

    @Test
    fun `a zero temp basal for the whole window`() = assertUnits(0.0, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(-hour, 180, 0.0), flatBasal(1.0)))

    @Test
    fun `a temp basal over a segment boundary`() =
        assertUnits(2.0, integrate(jan1Utc + 6 * hour, jan1Utc + 10 * hour, offsetUtc, tempBasal(6 * hour, 240, 0.5), twoSegmentBasal(1.0, 2.0, splitSlot = 16)))

    // ---- pod time zone is the phone time zone

    @Test
    fun `pod UTC and phone UTC`() =
        assertUnits(3.0, integrate(jan1Utc + 7 * hour, jan1Utc + 9 * hour, offsetUtc, null, twoSegmentBasal(1.0, 2.0, 16), utc))

    @Test
    fun `pod UTC plus 2 and phone UTC plus 2`() =
        assertUnits(3.0, integrate(jan1Utc + 5 * hour, jan1Utc + 7 * hour, offsetPlus2, null, twoSegmentBasal(1.0, 2.0, 16), plus2))

    @Test
    fun `pod UTC minus 5 and phone UTC minus 5`() =
        assertUnits(3.0, integrate(jan1Utc + 12 * hour, jan1Utc + 14 * hour, offsetMinus5, null, twoSegmentBasal(1.0, 2.0, 16), minus5))

    // ---- pod time zone is not the phone time zone

    @Test
    fun `pod UTC and phone UTC plus 2`() =
        assertUnits(3.0, integrate(jan1Utc + 7 * hour, jan1Utc + 9 * hour, offsetUtc, null, twoSegmentBasal(1.0, 2.0, 16), plus2))

    @Test
    fun `pod UTC plus 2 and phone UTC`() =
        assertUnits(3.0, integrate(jan1Utc + 5 * hour, jan1Utc + 7 * hour, offsetPlus2, null, twoSegmentBasal(1.0, 2.0, 16), utc))

    @Test
    fun `pod UTC plus 2 and phone UTC over the pod midnight`() =
        assertUnits(3.0, integrate(jan1Utc + 21 * hour, jan1Utc + 23 * hour, offsetPlus2, null, twoSegmentBasal(1.0, 2.0, 16), utc))

    // ---- edge cases

    @Test
    fun `start equals end gives 0`() = assertUnits(0.0, integrate(jan1Utc, jan1Utc, offsetUtc, null, flatBasal(1.0)))

    @Test
    fun `start after end gives null`() = assertNull(integrate(jan1Utc + hour, jan1Utc, offsetUtc, null, flatBasal(1.0)))

    @Test
    fun `no program and no temp basal gives null`() = assertNull(integrate(jan1Utc, jan1Utc + hour, offsetUtc, null, null))

    @Test
    fun `no pod time zone gives null`() = assertNull(integrate(jan1Utc, jan1Utc + hour, null, null, flatBasal(1.0)))

    @Test
    fun `no program but a temp basal over the whole window gives the temp rate`() =
        assertUnits(2.0, integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(-hour, 180, 2.0), null))

    @Test
    fun `no program and a temp basal over part of the window gives null`() =
        assertNull(integrate(jan1Utc, jan1Utc + hour, offsetUtc, tempBasal(0L, 30, 2.0), null))

    // ---- calculateBolusPulseIncrease

    private fun increase(prevTotal: Int, newTotal: Int, prevRemaining: Int?, newRemaining: Int) =
        DashBasalDrift.calculateBolusPulseIncrease(prevTotal, newTotal, prevRemaining, newRemaining)

    @Test
    fun `no previous tracking gives the whole increase`() {
        assertEquals(3, increase(100, 103, null, 0))
        assertEquals(0, increase(100, 100, null, 0))
    }

    @Test
    fun `a normal bolus gives the bolus decrease`() {
        assertEquals(3, increase(100, 103, 3, 0))
        assertEquals(1, increase(100, 101, 2, 1))
        assertEquals(0, increase(100, 100, 3, 3))
    }

    @Test
    fun `basal at the same time is capped to the bolus part`() {
        assertEquals(3, increase(100, 105, 3, 0))
        assertEquals(2, increase(100, 104, 3, 1))
    }

    @Test
    fun `fewer pulses than the bolus decrease gives the real increase`() = assertEquals(2, increase(100, 102, 3, 0))
}
