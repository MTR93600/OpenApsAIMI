package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.calibration.CalibrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Order of `LinearCalibrationPlugin.status` on `origin/dev_OAPSAIMI` @ `6598201d26`, L209–224.
 *
 * The clock is the `now` argument. The fit is [fitLinearCalibration] and the gate is
 * [CalibrationFit.isApplicable] (slope, centre, low end, high end).
 */
class CalibrationStatusTest {

    private val now = 1_700_000_000_000L
    private val warmUpHours = 2L

    @Test
    fun status_follows_the_reference_order() {
        val applied = listOf(
            entry(100.0, 105.0),
            entry(150.0, 157.5),
            entry(200.0, 210.0)
        )

        // 1. No SENSOR_CHANGE, even when the entries would otherwise apply.
        assertEquals(
            CalibrationStatus.NoSession,
            calibrationStatus(sessionStart = null, now = now, entries = applied, warmUpHours = warmUpHours)
        )

        // 2. Warm-up is not over. Entries that would apply are not consulted.
        val sessionStart = now - T.hours(1).msecs()
        val warmUpEndsAt = sessionStart + T.hours(warmUpHours).msecs()
        assertEquals(
            CalibrationStatus.WarmUp(warmUpEndsAt),
            calibrationStatus(sessionStart, now, applied, warmUpHours)
        )
        // The window is strict: at the exact end, warm-up is over (ref L214 `now < warmUpEndsAt`).
        assertEquals(
            CalibrationStatus.NeedMoreEntries(0),
            calibrationStatus(sessionStart, warmUpEndsAt, emptyList(), warmUpHours)
        )

        // 3. Past warm-up, no line (fewer than MIN_ENTRIES_FOR_FIT). Count is the entry count.
        val running = now - T.hours(12).msecs()
        assertEquals(
            CalibrationStatus.NeedMoreEntries(1),
            calibrationStatus(running, now, listOf(entry(100.0, 110.0)), warmUpHours)
        )

        // 4. A fit exists but isApplicable is false. Checked before FitMode.
        // Ref fixture status_slopeOutOfRange is two points (100→200, 200→400). With
        // MIN_ENTRIES_FOR_SLOPE those two points are OffsetOnly, offset +150, still UnsafeFit.
        val twoPointUnsafe = listOf(entry(100.0, 200.0), entry(200.0, 400.0))
        val twoPointFit = fitLinearCalibration(twoPointUnsafe, now)!!
        assertEquals(FitMode.OffsetOnly, twoPointFit.mode)
        assertEquals(150.0, twoPointFit.offset, absoluteTolerance = 1e-9)
        assertFalse(twoPointFit.correctionInRange)
        assertFalse(twoPointFit.isApplicable)
        assertEquals(CalibrationStatus.UnsafeFit, calibrationStatus(running, now, twoPointUnsafe, warmUpHours))

        // Three points on y = 2x still clamp the slope, and the centre lift stays above 30, so the
        // result is UnsafeFit rather than AppliedSlopeClamped.
        val clampedUnsafe = listOf(entry(100.0, 200.0), entry(150.0, 300.0), entry(200.0, 400.0))
        val clampedUnsafeFit = fitLinearCalibration(clampedUnsafe, now)!!
        assertEquals(FitMode.SlopeClamped, clampedUnsafeFit.mode)
        assertFalse(clampedUnsafeFit.isApplicable)
        assertEquals(CalibrationStatus.UnsafeFit, calibrationStatus(running, now, clampedUnsafe, warmUpHours))

        // 5. OffsetOnly and still applicable. Ref fixture: sensor values 1 mg/dL apart,
        // under MIN_SENSOR_RANGE_FOR_SLOPE. Deltas +3 and +5, offset +4, centre lift +4 ≤ 30.
        assertEquals(
            CalibrationStatus.AppliedOffsetOnly,
            calibrationStatus(
                running,
                now,
                listOf(entry(140.0, 143.0), entry(141.0, 146.0)),
                warmUpHours
            )
        )

        // The UnsafeFit branch is checked before FitMode. An OffsetOnly fit whose centre
        // lift is above 30 is UnsafeFit, not AppliedOffsetOnly.
        // Slope stays 1 (range 1 mg/dL); both deltas are +60.
        val offsetUnsafe = listOf(entry(140.0, 200.0), entry(141.0, 201.0))
        val offsetUnsafeFit = fitLinearCalibration(offsetUnsafe, now)!!
        assertEquals(FitMode.OffsetOnly, offsetUnsafeFit.mode)
        assertFalse(offsetUnsafeFit.isApplicable)
        assertEquals(
            CalibrationStatus.UnsafeFit,
            calibrationStatus(running, now, offsetUnsafe, warmUpHours)
        )

        // 6. SlopeClamped and still applicable. Three points on the free line of slope 12/7
        // (two points would be OffsetOnly).
        val slope = 12.0 / 7.0
        val intercept = 54.0 - slope * 72.0
        val clamped = listOf(72.0, 120.0, 172.8).map { sensor ->
            entry(sensor, slope * sensor + intercept)
        }
        assertEquals(FitMode.SlopeClamped, fitLinearCalibration(clamped, now)!!.mode)
        assertEquals(
            CalibrationStatus.AppliedSlopeClamped,
            calibrationStatus(running, now, clamped, warmUpHours)
        )

        // 7. Free slope inside the safe range. Ref fixture goodEntriesWithSlope.
        assertEquals(CalibrationStatus.Applied, calibrationStatus(running, now, applied, warmUpHours))
    }

    @Test
    fun a_fit_the_reference_rejects_at_the_low_end_is_unsafe() {
        // Three points 110→135, 145→155, 180→175, equal weights: slope 4/7, offset 505/7,
        // correction at 100 = 205/7 (centre check passes). Correction at 40 mg/dL is 385/7 = +55,
        // above CORRECTION_AT_LOW_MAX = 20, so lowEndSafe fails and status() is UnsafeFit.
        val hiding = listOf(entry(110.0, 135.0), entry(145.0, 155.0), entry(180.0, 175.0))
        val fit = fitLinearCalibration(hiding, now)!!
        assertEquals(FitMode.Full, fit.mode)
        assertEquals(4.0 / 7.0, fit.slope, absoluteTolerance = 1e-9)
        assertEquals(505.0 / 7.0, fit.offset, absoluteTolerance = 1e-9)
        assertEquals(205.0 / 7.0, fit.correctionAtCenter, absoluteTolerance = 1e-9)
        assertEquals(55.0, fit.correctionAt(LOW_MGDL), absoluteTolerance = 1e-9)
        assertFalse(fit.lowEndSafe)
        assertFalse(fit.isApplicable)
        val running = now - T.hours(12).msecs()
        assertEquals(CalibrationStatus.UnsafeFit, calibrationStatus(running, now, hiding, warmUpHours))

        // Two points 110→135 and 180→175 are not that case. OffsetOnly, deltas +25 and −5,
        // offset +10, AppliedOffsetOnly.
        val two = listOf(entry(110.0, 135.0), entry(180.0, 175.0))
        val offsetOnly = fitLinearCalibration(two, now)!!
        assertEquals(FitMode.OffsetOnly, offsetOnly.mode)
        assertEquals(10.0, offsetOnly.offset, absoluteTolerance = 1e-9)
        assertEquals(
            CalibrationStatus.AppliedOffsetOnly,
            calibrationStatus(running, now, two, warmUpHours)
        )
    }

    private fun entry(sensor: Double, fingerstick: Double) = CAL(
        timestamp = now,
        fingerstickMgdl = fingerstick,
        sensorMgdlAtPairing = sensor
    )
}
