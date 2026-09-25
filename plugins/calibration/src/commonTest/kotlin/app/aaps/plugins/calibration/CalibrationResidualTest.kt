package app.aaps.plugins.calibration

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.plugins.calibration.keys.CalibrationLongKey
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks the P4.5b residual against `origin/dev_OAPSAIMI` @ `6598201d26`.
 *
 * Line numbers are `LinearCalibrationPlugin.kt` at that tip unless named otherwise.
 * The clock is the `now` / `timestamp` argument. Nothing here reads the wall clock.
 */
class CalibrationResidualTest {

    private val now = 1_700_000_000_000L

    @Test
    fun add_entry_stores_the_median_not_the_newest_reading() {
        // Ref test addEntry_oneMinuteReadings_pairsWithMedianNotWithTheNewest.
        // addEntry L358: sensorValueForPairing(readings, timestamp). The newest reading is 118;
        // the median of the five nearest holds at 140. sensorValueForPairing is CalibrationMath,
        // not a second median.
        val readings = listOf(
            reading(now, 118.0),
            reading(now - T.mins(1).msecs(), 140.0),
            reading(now - T.mins(2).msecs(), 142.0),
            reading(now - T.mins(3).msecs(), 141.0),
            reading(now - T.mins(4).msecs(), 139.0)
        )
        val stored = sensorValueForPairing(readings, now)
        assertEquals(140.0, stored!!, 0.01)
        assertNotEquals(readings.first().value, stored)
    }

    @Test
    fun a_null_short_avg_delta_falls_back_to_the_stored_readings() {
        // L231: shortAvgDelta ?: fallbackDeltaPer5Min(timestamp)
        // L327–332: newest minus oldest, over the span, scaled to 5 min.
        // Ref test: 20 mg/dL in 10 min is 10 per 5 min, above the gate of 5.
        val rising = listOf(
            reading(now - T.mins(8).msecs(), 170.0),
            reading(now - T.mins(18).msecs(), 150.0)
        )
        assertEquals(10.0, preconditionDelta(shortAvgDelta = null, readings = rising, timestamp = now)!!, 0.1)
        // A present shortAvgDelta is used as-is. The fallback is not consulted.
        assertEquals(0.5, preconditionDelta(shortAvgDelta = 0.5, readings = rising, timestamp = now)!!, 0.01)
    }

    @Test
    fun a_flat_sensor_stays_under_the_delta_gate_when_status_is_missing() {
        // Ref test: 1 mg/dL in 10 min is 0.5 per 5 min, under the gate of 5.
        val flat = listOf(
            reading(now - T.mins(8).msecs(), 151.0),
            reading(now - T.mins(18).msecs(), 150.0)
        )
        val delta = preconditionDelta(shortAvgDelta = null, readings = flat, timestamp = now)!!
        assertEquals(0.5, delta, 0.1)
        assertTrue(abs(delta) <= 5.0)
    }

    @Test
    fun fallback_delta_is_null_when_the_rate_cannot_be_worked_out() {
        // L324: fewer than two readings. L328: spanMs <= 0.
        assertNull(fallbackDeltaPer5Min(emptyList(), now))
        assertNull(fallbackDeltaPer5Min(listOf(reading(now, 145.0)), now))
        val sameInstant = listOf(reading(now, 160.0), reading(now, 140.0))
        assertNull(fallbackDeltaPer5Min(sameInstant, now))
        // L527: the window the plugin reads is 20 min. The rate itself does not apply the window.
        assertEquals(20L * 60L * 1000L, DELTA_FALLBACK_WINDOW_MS)
    }

    @Test
    fun newest_gap_midpoint_matches_the_reference() {
        // CalibrationMath.kt L318–330 @ 6598201d. Newest first. Stop at the session. Middle of the break.
        val continuous = (0 until 10).map { reading(now - it * 60_000L, 140.0) }
        assertNull(newestGapMidpoint(continuous, T.mins(30).msecs()))

        val broken = listOf(
            reading(now, 140.0),
            reading(now - T.mins(60).msecs(), 140.0),
            reading(now - T.mins(61).msecs(), 140.0)
        )
        assertEquals(now - T.mins(30).msecs(), newestGapMidpoint(broken, T.mins(30).msecs()))

        val shortBreak = listOf(
            reading(now, 140.0),
            reading(now - T.mins(20).msecs(), 140.0),
            reading(now - T.mins(21).msecs(), 140.0)
        )
        assertNull(newestGapMidpoint(shortBreak, T.mins(30).msecs()))

        val sessionStart = now - T.mins(30).msecs()
        val acrossSensors = (0..7).map { reading(now - it * T.mins(5).msecs(), 140.0) } +
            reading(now - T.mins(200).msecs(), 140.0)
        assertNull(newestGapMidpoint(acrossSensors, T.mins(30).msecs(), notBefore = sessionStart))
        assertEquals(
            now - T.mins(200).msecs() + (now - T.mins(35).msecs() - (now - T.mins(200).msecs())) / 2,
            newestGapMidpoint(acrossSensors, T.mins(30).msecs())
        )

        assertNull(newestGapMidpoint(emptyList(), T.mins(30).msecs()))
        assertNull(newestGapMidpoint(listOf(reading(now, 140.0)), T.mins(30).msecs()))
    }

    @Test
    fun a_new_gap_is_asked_and_an_already_ignored_gap_is_not() {
        // L390 midpoint, L397 same break already told, L398 IgnoredSensorGapAt.
        val readings = readingsWithHourGap()
        val midpoint = now - T.mins(30).msecs()
        assertEquals(
            midpoint,
            CalibrationGap.gapWorthAsking(readings, sessionStart = null, lastNotifiedGapAt = 0L, ignoredGapAt = { 0L })
        )
        assertNull(
            CalibrationGap.gapWorthAsking(readings, sessionStart = null, lastNotifiedGapAt = midpoint, ignoredGapAt = { 0L })
        )
        assertNull(
            CalibrationGap.gapWorthAsking(readings, sessionStart = null, lastNotifiedGapAt = 0L, ignoredGapAt = { midpoint })
        )
        // L393: `if (notBefore != null && newer <= notBefore) return null`.
        // The newer reading of the first pair is `now`. A session that starts then ends the search.
        assertNull(
            CalibrationGap.gapWorthAsking(
                readings,
                sessionStart = now,
                lastNotifiedGapAt = 0L,
                ignoredGapAt = { 0L }
            )
        )
        // The same break is still reported when that newer reading is inside the session,
        // even if the older reading is not. The comparison is on `newer`, not on the midpoint.
        assertEquals(
            midpoint,
            CalibrationGap.gapWorthAsking(
                readings,
                sessionStart = now - T.mins(10).msecs(),
                lastNotifiedGapAt = 0L,
                ignoredGapAt = { 0L }
            )
        )
        // L397 is before L398: no midpoint, or the break already told, and the key is not read.
        var reads = 0
        val continuous = (0 until 10).map { reading(now - it * 60_000L, 140.0) }
        assertNull(
            CalibrationGap.gapWorthAsking(continuous, sessionStart = null, lastNotifiedGapAt = 0L, ignoredGapAt = { reads += 1; 0L })
        )
        assertNull(
            CalibrationGap.gapWorthAsking(readings, sessionStart = null, lastNotifiedGapAt = midpoint, ignoredGapAt = { reads += 1; 0L })
        )
        assertEquals(0, reads)
    }

    @Test
    fun ignoring_a_gap_stores_its_midpoint_on_the_non_exportable_key() {
        // CalibrationLongKey.kt L15. Action L415–416: put(IgnoredSensorGapAt, detectedAt).
        assertEquals("calibration_ignored_sensor_gap_at", CalibrationLongKey.IgnoredSensorGapAt.key)
        assertEquals(0L, CalibrationLongKey.IgnoredSensorGapAt.defaultValue)
        assertFalse(CalibrationLongKey.IgnoredSensorGapAt.exportable)

        val detectedAt = CalibrationGap.gapWorthAsking(
            readingsWithHourGap(),
            sessionStart = null,
            lastNotifiedGapAt = 0L,
            ignoredGapAt = { CalibrationLongKey.IgnoredSensorGapAt.defaultValue }
        )
        assertEquals(now - T.mins(30).msecs(), detectedAt)
        // What the ignore action writes, then what a later scan (restart: lastNotified back to 0) sees.
        val stored = detectedAt!!
        assertNull(
            CalibrationGap.gapWorthAsking(
                readingsWithHourGap(),
                sessionStart = null,
                lastNotifiedGapAt = 0L,
                ignoredGapAt = { stored }
            )
        )
    }

    @Test
    fun gap_scan_is_spaced_and_looks_only_at_the_recent_window() {
        // L382: now - lastGapScanAt < GAP_SCAN_INTERVAL_MS. L489 / L498–504: half of 30 min, and 6 h.
        assertEquals(T.mins(15).msecs(), CalibrationGap.SCAN_INTERVAL_MS)
        assertEquals(T.hours(6).msecs(), CalibrationGap.SCAN_WINDOW_MS)
        assertTrue(CalibrationGap.shouldScan(now, lastScanAt = 0L))
        assertFalse(CalibrationGap.shouldScan(now, lastScanAt = now - T.mins(14).msecs()))
        assertTrue(CalibrationGap.shouldScan(now, lastScanAt = now - T.mins(15).msecs()))
    }

    private fun readingsWithHourGap() = listOf(
        reading(now, 150.0),
        reading(now - T.mins(60).msecs(), 150.0),
        reading(now - T.mins(61).msecs(), 150.0)
    )

    private fun reading(timestamp: Long, value: Double) = GV(
        timestamp = timestamp,
        value = value,
        raw = null,
        noise = null,
        trendArrow = TrendArrow.NONE,
        sourceSensor = SourceSensor.UNKNOWN
    )
}
