package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CalibrationMathTest {

    private val now: Long = 1_700_000_000_000L

    @Test
    fun fitLinearCalibration_zeroEntries_returnsNull() {
        assertThat(fitLinearCalibration(emptyList(), now)).isNull()
    }

    @Test
    fun fitLinearCalibration_oneEntry_returnsNull() {
        assertThat(fitLinearCalibration(listOf(entry(140.0, 145.0)), now)).isNull()
    }

    @Test
    fun fitLinearCalibration_wideSpread_returnsFullMode() {
        // 100 mg/dL spread — well above MIN_SENSOR_RANGE_FOR_SLOPE (54) — and three entries,
        // which is what MIN_ENTRIES_FOR_SLOPE asks for before a slope is trusted at all.
        val fit = fitLinearCalibration(
            listOf(entry(100.0, 110.0), entry(150.0, 165.0), entry(200.0, 220.0)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.Full)
        assertThat(fit.slope).isWithin(0.001).of(1.1)
        assertThat(fit.offset).isWithin(0.5).of(0.0)
    }

    @Test
    fun fitLinearCalibration_narrowSpread_returnsOffsetOnly() {
        // 4 mg/dL spread — well below MIN_SENSOR_RANGE_FOR_SLOPE
        val fit = fitLinearCalibration(
            listOf(entry(140.0, 145.0), entry(144.0, 148.0)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.OffsetOnly)
        assertThat(fit.slope).isEqualTo(1.0)
    }

    @Test
    fun fitLinearCalibration_atThreshold_returnsFullMode() {
        // Exactly at threshold (54 mg/dL spread) — still trusts slope.
        val fit = fitLinearCalibration(
            listOf(entry(120.0, 120.0), entry(147.0, 147.0), entry(174.0, 174.0)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.Full)
    }

    @Test
    fun fitLinearCalibration_futureDatedEntry_doesNotProduceNaN() {
        // Entry timestamp 5 minutes in the future (clock skew). Without the guard,
        // exp(+large) → Inf → NaN slope/offset. With the guard, weight = 1.0 and the
        // entry contributes normally.
        val fit = fitLinearCalibration(
            listOf(
                entry(100.0, 110.0, ageDays = 0L),
                entry(150.0, 165.0, ageDays = 0L),
                CAL(
                    id = 0L,
                    timestamp = now + T.mins(5).msecs(),
                    fingerstickMgdl = 220.0,
                    sensorMgdlAtPairing = 200.0
                )
            ),
            now
        )!!
        assertThat(fit.slope).isFinite()
        assertThat(fit.offset).isFinite()
        assertThat(fit.slope).isWithin(0.001).of(1.1)
    }

    @Test
    fun fitLinearCalibration_veryOldEntry_isHeavilyDownweighted() {
        // One fresh entry on y = x, one ancient entry (10τ ≈ 20 days) heavily off.
        // The old entry should barely influence the fit due to exp(-10) ≈ 4.5e-5.
        val fit = fitLinearCalibration(
            listOf(
                entry(100.0, 100.0, ageDays = 0L),
                entry(200.0, 200.0, ageDays = 0L),
                entry(150.0, 999.0, ageDays = 20L)
            ),
            now
        )!!
        // Slope should still be ~1.0 despite the ancient outlier.
        assertThat(fit.slope).isWithin(0.05).of(1.0)
    }

    @Test
    fun fitLinearCalibration_slopeAboveMax_clampsAndRefitsOffset() {
        // Two Syai-style entries — free slope ≈ 1.72, above SLOPE_MAX = 1.6.
        // Expected: slope clamped to 1.6, offset recomputed = mean(y) − 1.6·mean(x) = 140.4 − 195.84 ≈ −55.44.
        val fit = fitLinearCalibration(
            listOf(entry(sensor = 72.0, fs = 54.0), entry(sensor = 122.4, fs = 140.4), entry(sensor = 172.8, fs = 226.8)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.SlopeClamped)
        assertThat(fit.slope).isEqualTo(1.6)
        assertThat(fit.offset).isWithin(0.1).of(-55.44)
        assertThat(fit.isApplicable).isTrue()
        // Correction at center after clamping: (1.6−1)·100 + (−55.44) ≈ +4.56 mg/dL
        assertThat(fit.correctionAtCenter).isWithin(0.1).of(4.56)
    }

    @Test
    fun fitLinearCalibration_slopeBelowMin_clampsAndRefitsOffset() {
        // Entries imply slope ≈ 0.4 (sensor exaggerates). Clamp to SLOPE_MIN = 0.55, re-fit.
        val fit = fitLinearCalibration(
            listOf(entry(sensor = 100.0, fs = 80.0), entry(sensor = 150.0, fs = 100.0), entry(sensor = 200.0, fs = 120.0)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.SlopeClamped)
        assertThat(fit.slope).isEqualTo(0.55)
    }

    @Test
    fun calibrationFit_correctionAtCenter_matchesLineEvaluatedAtCenter() {
        // Line y = 1.5·x − 54. At sensor=100: y = 150 − 54 = 96; correction = −4.
        val fit = CalibrationFit(slope = 1.5, offset = -54.0)
        assertThat(fit.correctionAtCenter).isWithin(0.001).of(-4.0)
        assertThat(fit.isApplicable).isTrue() // slope ∈ [0.55, 1.6] and correction ∈ [−30, 30]
    }

    @Test
    fun calibrationFit_steepSlopeWithLargeOffset_clampedByCorrectionNotOffset() {
        // Old clamp on offset would reject offset = −270 instantly; the clamp on
        // correction-at-center sees correction = (2.87 − 1)·100 + (−270) = −83.
        // The centre no longer refuses a drop — the low end does, and so does the slope.
        val fit = CalibrationFit(slope = 2.87, offset = -270.0)
        assertThat(fit.slopeInRange).isFalse()
        assertThat(fit.correctionAtCenter).isWithin(0.5).of(-83.0)
        assertThat(fit.lowEndSafe).isFalse()
        assertThat(fit.isApplicable).isFalse()
    }

    @Test
    fun fitLinearCalibration_negativeDeltas_handledCorrectly() {
        // Sensor reads HIGHER than fingerstick — negative offset.
        val fit = fitLinearCalibration(
            listOf(entry(120.0, 110.0), entry(170.0, 160.0), entry(220.0, 210.0)),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.Full)
        assertThat(fit.slope).isWithin(0.001).of(1.0)
        assertThat(fit.offset).isWithin(0.5).of(-10.0)
    }

    @Test
    fun fitLinearCalibration_offsetOnly_isWeightedMeanDelta() {
        // Five entries at the same timestamp (equal weights). Deltas: 2, 3, 4, 5, 6 → mean 4.
        val fit = fitLinearCalibration(
            listOf(
                entry(140.0, 142.0), entry(141.0, 144.0), entry(142.0, 146.0),
                entry(143.0, 148.0), entry(144.0, 150.0)
            ),
            now
        )!!
        assertThat(fit.mode).isEqualTo(FitMode.OffsetOnly)
        assertThat(fit.offset).isWithin(0.01).of(4.0)
    }

    // ------------ stalenessConfidence() / blendTowardIdentity() ------------

    @Test
    fun stalenessConfidence_freshEntry_isFullConfidence() {
        assertThat(stalenessConfidence(now, now)).isEqualTo(1.0)
        assertThat(stalenessConfidence(now - T.days(STALE_CONFIDENCE_FULL_DAYS).msecs(), now)).isEqualTo(1.0)
    }

    @Test
    fun stalenessConfidence_atOrPastZeroThreshold_isZero() {
        assertThat(stalenessConfidence(now - T.days(STALE_CONFIDENCE_ZERO_DAYS).msecs(), now)).isEqualTo(0.0)
        assertThat(stalenessConfidence(now - T.days(STALE_CONFIDENCE_ZERO_DAYS + 10).msecs(), now)).isEqualTo(0.0)
    }

    @Test
    fun stalenessConfidence_betweenThresholds_fallsOffLinearly() {
        // Midpoint of [2, 6] days is 4 days -> confidence 0.5.
        val midpointDays = (STALE_CONFIDENCE_FULL_DAYS + STALE_CONFIDENCE_ZERO_DAYS) / 2
        val confidence = stalenessConfidence(now - T.days(midpointDays).msecs(), now)
        assertThat(confidence).isWithin(0.001).of(0.5)
    }

    @Test
    fun stalenessConfidence_futureDatedEntry_isFullConfidence() {
        // Clock skew guard, same idea as fitLinearCalibration_futureDatedEntry_doesNotProduceNaN.
        assertThat(stalenessConfidence(now + T.mins(5).msecs(), now)).isEqualTo(1.0)
    }

    @Test
    fun blendTowardIdentity_fullConfidence_leavesFitUnchanged() {
        val fit = CalibrationFit(slope = 1.3, offset = -20.0, mode = FitMode.Full)
        val blended = fit.blendTowardIdentity(1.0)
        assertThat(blended.slope).isEqualTo(1.3)
        assertThat(blended.offset).isEqualTo(-20.0)
        assertThat(blended.mode).isEqualTo(FitMode.Full)
    }

    @Test
    fun blendTowardIdentity_zeroConfidence_returnsIdentity() {
        val fit = CalibrationFit(slope = 1.3, offset = -20.0)
        val blended = fit.blendTowardIdentity(0.0)
        assertThat(blended.slope).isEqualTo(1.0)
        // isWithin, not isEqualTo: 0.0 * -20.0 is -0.0, arithmetically identical to 0.0 but not
        // Double.equals()-equal to it.
        assertThat(blended.offset).isWithin(0.0).of(0.0)
    }

    @Test
    fun blendTowardIdentity_halfConfidence_isMidwayToIdentity() {
        val fit = CalibrationFit(slope = 1.4, offset = -20.0)
        val blended = fit.blendTowardIdentity(0.5)
        assertThat(blended.slope).isWithin(0.001).of(1.2)
        assertThat(blended.offset).isWithin(0.001).of(-10.0)
    }

    // ------------ sensorValueForPairing() ------------

    @Test
    fun sensorValueForPairing_noReadings_returnsNull() {
        assertThat(sensorValueForPairing(emptyList(), now)).isNull()
    }

    @Test
    fun sensorValueForPairing_singleReading_returnsThatReading() {
        // A five minute sensor rarely has more than one reading in the window: the median must
        // then behave exactly like the older code, which took that one reading.
        assertThat(sensorValueForPairing(listOf(reading(now, 145.0)), now)!!).isWithin(0.01).of(145.0)
    }

    @Test
    fun sensorValueForPairing_oddCount_returnsMiddleValue() {
        val readings = listOf(reading(now, 150.0), reading(now - 60_000L, 140.0), reading(now - 120_000L, 145.0))
        assertThat(sensorValueForPairing(readings, now)!!).isWithin(0.01).of(145.0)
    }

    @Test
    fun sensorValueForPairing_evenCount_returnsMeanOfMiddleTwo() {
        val readings = listOf(reading(now, 150.0), reading(now - 60_000L, 140.0))
        assertThat(sensorValueForPairing(readings, now)!!).isWithin(0.01).of(145.0)
    }

    @Test
    fun sensorValueForPairing_ignoresSingleNoisyReading() {
        // One minute readings carry more noise per reading. A single spike must not become the
        // sensor side of a calibration pair, which is what taking the newest reading would do.
        val readings = listOf(
            reading(now, 118.0), // the spike, and the newest
            reading(now - 60_000L, 140.0),
            reading(now - 120_000L, 142.0),
            reading(now - 180_000L, 141.0),
            reading(now - 240_000L, 139.0)
        )
        assertThat(sensorValueForPairing(readings, now)!!).isWithin(0.01).of(140.0)
    }

    @Test
    fun sensorValueForPairing_usesOnlyTheNearestReadings() {
        // Six readings, only the five nearest count. The far one is 60 mg/dL away and must not
        // move the answer.
        val readings = listOf(
            reading(now, 140.0),
            reading(now - 60_000L, 141.0),
            reading(now - 120_000L, 142.0),
            reading(now - 180_000L, 143.0),
            reading(now - 240_000L, 144.0),
            reading(now - 600_000L, 200.0)
        )
        assertThat(sensorValueForPairing(readings, now)!!).isWithin(0.01).of(142.0)
    }

    @Test
    fun sensorValueForPairing_ordersByDistanceNotByListOrder() {
        // The caller may hand the readings over in any order.
        val readings = listOf(
            reading(now - 600_000L, 200.0),
            reading(now - 120_000L, 142.0),
            reading(now, 140.0)
        )
        assertThat(sensorValueForPairing(readings, now, maxSamples = 1)!!).isWithin(0.01).of(140.0)
    }

    // ------------ newestGapMidpoint() ------------

    @Test
    fun newestGapMidpoint_continuousReadings_returnsNull() {
        val readings = (0 until 10).map { reading(now - it * 60_000L, 140.0) }
        assertThat(newestGapMidpoint(readings, T.mins(30).msecs())).isNull()
    }

    @Test
    fun newestGapMidpoint_breakLongerThanThreshold_returnsMiddleOfBreak() {
        val readings = listOf(
            reading(now, 140.0),
            reading(now - T.mins(60).msecs(), 140.0),
            reading(now - T.mins(61).msecs(), 140.0)
        )
        assertThat(newestGapMidpoint(readings, T.mins(30).msecs())).isEqualTo(now - T.mins(30).msecs())
    }

    @Test
    fun newestGapMidpoint_breakShorterThanThreshold_returnsNull() {
        val readings = listOf(
            reading(now, 140.0),
            reading(now - T.mins(20).msecs(), 140.0),
            reading(now - T.mins(21).msecs(), 140.0)
        )
        assertThat(newestGapMidpoint(readings, T.mins(30).msecs())).isNull()
    }

    @Test
    fun newestGapMidpoint_stopsAtSessionStart() {
        // The break belongs to the sensor before this one, so it is not reported again: the search
        // stops as soon as the readings are older than the start of the running session.
        val sessionStart = now - T.mins(30).msecs()
        val readings = (0..7).map { reading(now - it * T.mins(5).msecs(), 140.0) } +
            reading(now - T.mins(200).msecs(), 140.0)
        assertThat(newestGapMidpoint(readings, T.mins(30).msecs(), notBefore = sessionStart)).isNull()
        // Without that limit the same break is found.
        assertThat(newestGapMidpoint(readings, T.mins(30).msecs())).isNotNull()
    }

    @Test
    fun newestGapMidpoint_fewerThanTwoReadings_returnsNull() {
        assertThat(newestGapMidpoint(emptyList(), T.mins(30).msecs())).isNull()
        assertThat(newestGapMidpoint(listOf(reading(now, 140.0)), T.mins(30).msecs())).isNull()
    }

    private fun reading(timestamp: Long, value: Double): GV = GV(
        timestamp = timestamp,
        value = value,
        raw = null,
        noise = null,
        trendArrow = TrendArrow.NONE,
        sourceSensor = SourceSensor.UNKNOWN
    )

    @Test
    fun `two fingersticks never fit a slope, however wide apart they are`() {
        // A line through exactly two points carries all their noise, and all the lag between a
        // fingerstick and the sensor, straight into the slope.
        val fit = fitLinearCalibration(
            listOf(entry(sensor = 110.0, fs = 135.0), entry(sensor = 180.0, fs = 175.0)),
            now
        )!!

        assertThat(fit.mode).isEqualTo(FitMode.OffsetOnly)
        assertThat(fit.slope).isEqualTo(1.0)
    }

    @Test
    fun `a fit that would hide a hypo is not applicable`() {
        // The real-life shape this guards: two sticks (110 -> 135, 180 -> 175) fit slope 0.571 and
        // offset 72.1. Correction at 100 is +29, so the centre check alone lets it through, and a
        // sensor reading 55 reaches the loop as 104 — no hypo, for the loop and for the alarms.
        val fit = CalibrationFit(slope = 0.571, offset = 72.1)

        assertThat(fit.correctionAtCenter).isLessThan(CORRECTION_AT_CENTER_MAX)
        assertThat(fit.correctionInRange).isTrue()
        assertThat(fit.correctionAt(55.0)).isGreaterThan(45.0)
        assertThat(fit.lowEndSafe).isFalse()
        assertThat(fit.isApplicable).isFalse()
    }

    @Test
    fun `reading a low value lower than the sensor stays allowed`() {
        // Only the lift is dangerous: a line that reads lower makes the loop more careful, so a
        // steep slope with a big negative offset must not be rejected by the low-end check.
        val fit = CalibrationFit(slope = 1.5, offset = -54.0)

        assertThat(fit.correctionAtLow).isLessThan(0.0)
        assertThat(fit.lowEndSafe).isTrue()
        assertThat(fit.isApplicable).isTrue()
    }

    @Test
    fun `a sensor that reads a third too high can be corrected`() {
        // Field case: blood 209 while the sensor said 309. The loop dosing for 309 is the harm.
        // A multiplicative fit takes 32 mg/dL off at the centre, which the old two-sided centre
        // bound refused by 2.4 mg/dL, and it stays small at the low end, so it is allowed now.
        val fit = CalibrationFit(slope = 209.0 / 309.0, offset = 0.0)

        assertThat(fit.correctionAtCenter).isLessThan(-30.0)
        assertThat(fit.correctionAtLow).isWithin(0.1).of(-12.96)
        assertThat(fit.isApplicable).isTrue()
        assertThat(fit.slope * 309.0 + fit.offset).isWithin(0.5).of(209.0)
    }

    @Test
    fun `a flat drop big enough to pin the reading at the floor is not applicable`() {
        // Two fingersticks 100 mg/dL below the sensor give slope 1, offset −100. That line turns a
        // reading of 60 into −40: the loop would see the floor for ever and stop dosing.
        val fit = CalibrationFit(slope = 1.0, offset = -100.0)

        assertThat(fit.slopeInRange).isTrue()
        assertThat(fit.correctionInRange).isTrue() // the centre does not catch it any more
        assertThat(fit.lowEndSafe).isFalse()       // the low end does
        assertThat(fit.isApplicable).isFalse()
    }

    @Test
    fun `the steepest accepted compression fit still passes the low end`() {
        // The Syai-style clamped fit must not become collateral damage of the new low-end floor.
        val fit = CalibrationFit(slope = SLOPE_MAX, offset = -55.44)

        assertThat(fit.correctionAtLow).isWithin(0.1).of(-31.44)
        assertThat(fit.lowEndSafe).isTrue()
        assertThat(fit.isApplicable).isTrue()
    }

    @Test
    fun `a fit that would invent a hyper is not applicable`() {
        // Slope at its ceiling with a small offset: a sensor at 300 would be handed to the loop as
        // 450, and the loop answers that with insulin.
        val fit = CalibrationFit(slope = 1.6, offset = -30.0)

        assertThat(fit.correctionAt(HIGH_MGDL)).isWithin(0.1).of(150.0)
        assertThat(fit.highEndSafe).isFalse()
        assertThat(fit.isApplicable).isFalse()
    }

    @Test
    fun `a steep but bounded sensor keeps its slope at the high end`() {
        // The Syai-style clamped fit: 300 -> 424.6, ratio 1.42, still under the cap.
        val fit = CalibrationFit(slope = 1.6, offset = -55.44)

        assertThat(fit.ratioAtHigh).isLessThan(MAX_RATIO_AT_HIGH)
        assertThat(fit.highEndSafe).isTrue()
        assertThat(fit.isApplicable).isTrue()
    }

    private fun entry(sensor: Double, fs: Double, ageDays: Long = 0L): CAL =
        CAL(
            id = 0L,
            timestamp = now - T.days(ageDays).msecs(),
            fingerstickMgdl = fs,
            sensorMgdlAtPairing = sensor
        )
}
