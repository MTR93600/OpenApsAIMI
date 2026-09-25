package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.calibration.CalibrationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fit guards from `origin/dev_OAPSAIMI` @ `6598201d`, `CalibrationMath.kt` and
 * `CalibrationMathTest.kt`. The line is [fitLinearCalibration]; applicability is
 * [CalibrationFit.isApplicable]. `status()` reads that same fit.
 */
class CalibrationFitGuardsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun two_fingersticks_never_fit_a_slope() {
        // Ref CalibrationMathTest, two points 110→135 and 180→175.
        // MIN_ENTRIES_FOR_SLOPE = 3 (ref L87, used L192): OffsetOnly, not slope 4/7.
        val entries = listOf(entry(110.0, 135.0), entry(180.0, 175.0))
        val fit = fitLinearCalibration(entries, now)!!

        assertEquals(FitMode.OffsetOnly, fit.mode)
        assertEquals(1.0, fit.slope)
        // Deltas +25 and −5, equal weights, offset +10.
        assertEquals(10.0, fit.offset, absoluteTolerance = 1e-9)
        assertTrue(fit.isApplicable)
        assertEquals(65.0, fit.slope * 55.0 + fit.offset, absoluteTolerance = 1e-9)

        val running = now - T.hours(12).msecs()
        assertEquals(
            CalibrationStatus.AppliedOffsetOnly,
            calibrationStatus(running, now, entries, warmUpHours = 2L)
        )
    }

    @Test
    fun three_points_on_the_hiding_line_are_full_and_unsafe_at_the_low_end() {
        // 110→135, 145→155, 180→175. Equal weights: slope 4/7, offset 505/7.
        // Correction at 40 mg/dL is 385/7 = +55, above CORRECTION_AT_LOW_MAX = 20.
        val entries = listOf(entry(110.0, 135.0), entry(145.0, 155.0), entry(180.0, 175.0))
        val fit = fitLinearCalibration(entries, now)!!

        assertEquals(FitMode.Full, fit.mode)
        assertEquals(4.0 / 7.0, fit.slope, absoluteTolerance = 1e-9)
        assertEquals(505.0 / 7.0, fit.offset, absoluteTolerance = 1e-9)
        assertEquals(205.0 / 7.0, fit.correctionAtCenter, absoluteTolerance = 1e-9)
        assertTrue(fit.correctionInRange)
        assertEquals(55.0, fit.correctionAt(LOW_MGDL), absoluteTolerance = 1e-9)
        assertFalse(fit.lowEndSafe)
        assertFalse(fit.isApplicable)

        val running = now - T.hours(12).msecs()
        assertEquals(
            CalibrationStatus.UnsafeFit,
            calibrationStatus(running, now, entries, warmUpHours = 2L)
        )
    }

    @Test
    fun a_fit_that_would_hide_a_hypo_is_not_applicable() {
        // Ref constructs the geometric line (slope 0.571, offset 72.1) directly.
        // Centre passes; a sensor at 55 is handed on as about 104.
        val fit = CalibrationFit(slope = 0.571, offset = 72.1)

        assertTrue(fit.correctionAtCenter < CORRECTION_AT_CENTER_MAX)
        assertTrue(fit.correctionInRange)
        assertTrue(fit.correctionAt(55.0) > 45.0)
        assertFalse(fit.lowEndSafe)
        assertFalse(fit.isApplicable)
    }

    @Test
    fun reading_a_low_value_lower_than_the_sensor_stays_allowed() {
        val fit = CalibrationFit(slope = 1.5, offset = -54.0)

        assertTrue(fit.correctionAtLow < 0.0)
        assertTrue(fit.lowEndSafe)
        assertTrue(fit.isApplicable)
    }

    @Test
    fun a_sensor_that_reads_a_third_too_high_can_be_corrected() {
        // Blood 209, sensor 309. Centre correction is below −30 and still applicable.
        val fit = CalibrationFit(slope = 209.0 / 309.0, offset = 0.0)

        assertTrue(fit.correctionAtCenter < -30.0)
        assertEquals(-12.96, fit.correctionAtLow, absoluteTolerance = 0.1)
        assertTrue(fit.correctionInRange)
        assertTrue(fit.lowEndSafe)
        assertTrue(fit.isApplicable)
        assertEquals(209.0, fit.slope * 309.0 + fit.offset, absoluteTolerance = 0.5)
    }

    @Test
    fun a_flat_drop_big_enough_to_pin_the_reading_at_the_floor_is_not_applicable() {
        val fit = CalibrationFit(slope = 1.0, offset = -100.0)

        assertTrue(fit.slopeInRange)
        assertTrue(fit.correctionInRange)
        assertFalse(fit.lowEndSafe)
        assertFalse(fit.isApplicable)
    }

    @Test
    fun the_steepest_accepted_compression_fit_still_passes_the_low_end() {
        val fit = CalibrationFit(slope = SLOPE_MAX, offset = -55.44)

        assertEquals(-31.44, fit.correctionAtLow, absoluteTolerance = 0.1)
        assertTrue(fit.lowEndSafe)
        assertTrue(fit.isApplicable)
    }

    @Test
    fun a_fit_that_would_invent_a_hyper_is_not_applicable() {
        // Ref CalibrationFit(slope = 1.6, offset = -30): 300 becomes 450, ratio 1.5 > 1.45.
        // The centre lift sits on the +30 boundary; 1.6 is not exact in binary, so this test
        // does not assert correctionInRange. The ref asserts the high end and isApplicable.
        val fit = CalibrationFit(slope = 1.6, offset = -30.0)

        assertEquals(150.0, fit.correctionAt(HIGH_MGDL), absoluteTolerance = 0.1)
        assertFalse(fit.highEndSafe)
        assertFalse(fit.isApplicable)
    }

    @Test
    fun three_points_on_the_hyper_line_are_refused_by_status() {
        // Points on y = 1.6x − 31. Centre lift is +29 (inside the old [−30, 30] and the new ≤ 30),
        // low-end correction is −7, high-end ratio is 449/300 ≈ 1.497 > 1.45.
        // The ref's published line is offset −30 (300 → 450). In IEEE that centre is
        // 30.000000000000007, already outside study's old closed range, so it does not show the
        // change. Offset −31 does.
        val entries = listOf(entry(100.0, 129.0), entry(150.0, 209.0), entry(200.0, 289.0))
        val fit = fitLinearCalibration(entries, now)!!

        assertEquals(FitMode.Full, fit.mode)
        assertEquals(1.6, fit.slope, absoluteTolerance = 1e-9)
        assertEquals(-31.0, fit.offset, absoluteTolerance = 1e-9)
        assertTrue(fit.correctionInRange)
        assertTrue(fit.lowEndSafe)
        assertFalse(fit.highEndSafe)
        assertFalse(fit.isApplicable)

        val running = now - T.hours(12).msecs()
        assertEquals(
            CalibrationStatus.UnsafeFit,
            calibrationStatus(running, now, entries, warmUpHours = 2L)
        )
    }

    @Test
    fun a_steep_but_bounded_sensor_keeps_its_slope_at_the_high_end() {
        val fit = CalibrationFit(slope = 1.6, offset = -55.44)

        assertTrue(fit.ratioAtHigh < MAX_RATIO_AT_HIGH)
        assertTrue(fit.highEndSafe)
        assertTrue(fit.isApplicable)
    }

    @Test
    fun bounds_are_inclusive_and_the_next_step_is_refused() {
        // Slope 1 makes the correction equal to the offset, and 1.25 / 1.6 keep the other
        // checks inside the window so each assertion fails for the bound under test.
        // Integers and quarters are exact in IEEE, which keeps the inclusive edge stable.

        val lowFloor = CalibrationFit(slope = 1.0, offset = -35.0)
        assertEquals(-35.0, lowFloor.correctionAtLow)
        assertTrue(lowFloor.lowEndSafe)
        assertTrue(lowFloor.isApplicable)

        val belowLowFloor = CalibrationFit(slope = 1.0, offset = -35.5)
        assertEquals(-35.5, belowLowFloor.correctionAtLow)
        assertFalse(belowLowFloor.lowEndSafe)
        assertTrue(belowLowFloor.correctionInRange)
        assertFalse(belowLowFloor.isApplicable)

        val lowCeiling = CalibrationFit(slope = 1.0, offset = 20.0)
        assertEquals(20.0, lowCeiling.correctionAtLow)
        assertTrue(lowCeiling.lowEndSafe)
        assertTrue(lowCeiling.isApplicable)

        val aboveLowCeiling = CalibrationFit(slope = 1.0, offset = 20.5)
        assertEquals(20.5, aboveLowCeiling.correctionAtLow)
        assertFalse(aboveLowCeiling.lowEndSafe)
        assertTrue(aboveLowCeiling.correctionInRange)
        assertFalse(aboveLowCeiling.isApplicable)

        // y = 1.25x + 5. Centre correction is +30. Low end is +15, high-end ratio is 1.266….
        val centerCeiling = CalibrationFit(slope = 1.25, offset = 5.0)
        assertEquals(30.0, centerCeiling.correctionAtCenter)
        assertTrue(centerCeiling.correctionInRange)
        assertTrue(centerCeiling.lowEndSafe)
        assertTrue(centerCeiling.highEndSafe)
        assertTrue(centerCeiling.isApplicable)

        val aboveCenter = CalibrationFit(slope = 1.25, offset = 6.0)
        assertEquals(31.0, aboveCenter.correctionAtCenter)
        assertFalse(aboveCenter.correctionInRange)
        assertTrue(aboveCenter.lowEndSafe)
        assertTrue(aboveCenter.highEndSafe)
        assertFalse(aboveCenter.isApplicable)

        // Exact ref compression fit. The KDoc rounds it to offset −55.4 taking 31.4 mg/dL
        // off a reading of 40. The fit and the ref tests are offset −55.44: correction at 40
        // is −31.44, a sensor at 300 becomes 424.56, ratio 1.4152. Inside every bound.
        val compression = CalibrationFit(slope = SLOPE_MAX, offset = -55.44)
        assertEquals(-31.44, compression.correctionAtLow, absoluteTolerance = 1e-9)
        assertEquals(424.56, compression.slope * HIGH_MGDL + compression.offset, absoluteTolerance = 1e-9)
        assertEquals(1.4152, compression.ratioAtHigh, absoluteTolerance = 1e-9)
        assertTrue(compression.correctionInRange)
        assertTrue(compression.lowEndSafe)
        assertTrue(compression.highEndSafe)
        assertTrue(compression.isApplicable)

        // Inclusive cap above that ratio: y = 1.6x − 45 gives 1.45 at 300.
        // Centre is +15, low end is −21. The next whole offset, −44, is the only failure.
        val highCeiling = CalibrationFit(slope = SLOPE_MAX, offset = -45.0)
        assertEquals(MAX_RATIO_AT_HIGH, highCeiling.ratioAtHigh, absoluteTolerance = 1e-12)
        assertTrue(highCeiling.highEndSafe)
        assertTrue(highCeiling.correctionInRange)
        assertTrue(highCeiling.lowEndSafe)
        assertTrue(highCeiling.isApplicable)

        val aboveHigh = CalibrationFit(slope = SLOPE_MAX, offset = -44.0)
        assertTrue(aboveHigh.ratioAtHigh > MAX_RATIO_AT_HIGH)
        assertFalse(aboveHigh.highEndSafe)
        assertTrue(aboveHigh.correctionInRange)
        assertTrue(aboveHigh.lowEndSafe)
        assertFalse(aboveHigh.isApplicable)
    }

    private fun entry(sensor: Double, fingerstick: Double) = CAL(
        timestamp = now,
        fingerstickMgdl = fingerstick,
        sensorMgdlAtPairing = sensor
    )
}
