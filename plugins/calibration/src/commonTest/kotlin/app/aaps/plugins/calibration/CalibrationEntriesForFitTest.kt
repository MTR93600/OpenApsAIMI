package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.data.time.T
import app.aaps.plugins.calibration.keys.CalibrationLongKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Locks `entriesForFit` selection against `origin/dev_OAPSAIMI` @ `6598201d26`.
 *
 * Source commit `1b81e356c8`. Line numbers below are that tip, file
 * `plugins/calibration/src/main/kotlin/app/aaps/plugins/calibration/LinearCalibrationPlugin.kt`
 * unless named otherwise. `sensorValueForPairing` is `CalibrationMath.kt` L291–304
 * (introduced `8452e845f6`, called from lag re-pair L305).
 *
 * The clock is the `now` argument. Nothing here reads the wall clock.
 */
class CalibrationEntriesForFitTest {

    private val now = 1_700_000_000_000L

    @Test
    fun constants_match_the_reference() {
        // L521, L524, CalibrationMath.kt L273, CalibrationLongKey.kt L21
        assertEquals(10L * 60L * 1000L, CalibrationEntriesForFit.PAIR_LAG_MS)
        assertEquals(15L * 60L * 1000L, CalibrationEntriesForFit.PAIR_LAG_WINDOW_MS)
        assertEquals(5, PAIR_MEDIAN_MAX_SAMPLES)
        assertEquals("calibration_entries_valid_from", CalibrationLongKey.EntriesValidFrom.key)
        assertEquals(0L, CalibrationLongKey.EntriesValidFrom.defaultValue)
        assertFalse(CalibrationLongKey.EntriesValidFrom.exportable)
    }

    @Test
    fun cutoff_is_the_later_of_session_start_and_entries_valid_from() {
        // L283: maxOf(sessionStart, preferences.get(EntriesValidFrom))
        val sessionStart = now - T.hours(12).msecs()
        assertEquals(sessionStart, CalibrationEntriesForFit.fitCutoff(sessionStart, 0L))
        val promotedAt = now - T.hours(1).msecs()
        assertEquals(promotedAt, CalibrationEntriesForFit.fitCutoff(sessionStart, promotedAt))
        // A cutoff older than the session changes nothing (plugin test L853–858).
        val fiveDaysAgo = now - T.days(5).msecs()
        assertEquals(sessionStart, CalibrationEntriesForFit.fitCutoff(sessionStart, fiveDaysAgo))
    }

    @Test
    fun cutoff_advances_only_when_the_new_timestamp_is_strictly_later() {
        // L288: if (timestamp <= current) return
        val promotedAt = now - T.hours(1).msecs()
        assertTrue(CalibrationEntriesForFit.shouldAdvanceEntriesValidFrom(0L, promotedAt))
        assertFalse(CalibrationEntriesForFit.shouldAdvanceEntriesValidFrom(promotedAt, promotedAt))
        assertFalse(CalibrationEntriesForFit.shouldAdvanceEntriesValidFrom(promotedAt, promotedAt - 1L))
    }

    @Test
    fun an_old_entry_is_repaired_against_the_readings_that_follow_it() {
        // Plugin test L864–887. Stored pair says the sensor is 30 low (120 vs fingerstick 150).
        // Ten minutes later the sensor reads 150. Median of 140 and 150 at the +10 min target is 145.
        val stickAt = now - T.hours(1).msecs()
        val stored = entry(id = 7L, timestamp = stickAt, fingerstick = 150.0, sensor = 120.0)
        val step = CalibrationEntriesForFit.beginLagRepair(stored, now, cachedSensorMgdl = null)
        assertTrue(step is CalibrationEntriesForFit.LagRepairStep.ReadWindow)
        val window = step as CalibrationEntriesForFit.LagRepairStep.ReadWindow
        assertEquals(stickAt, window.startMs)
        assertEquals(stickAt + T.mins(15).msecs(), window.endMs)
        assertEquals(stickAt + T.mins(10).msecs(), window.targetMs)

        val readings = listOf(
            reading(stickAt + T.mins(10).msecs(), 150.0),
            reading(stickAt + T.mins(5).msecs(), 140.0)
        )
        val finished = CalibrationEntriesForFit.finishLagRepair(stored, readings, window.targetMs)
        assertEquals(145.0, finished.entry.sensorMgdlAtPairing, 0.01)
        assertEquals(150.0, finished.entry.fingerstickMgdl, 0.01)
        assertEquals(145.0, finished.storeInCache!!, 0.01)
        // The stored row is not the object the fit sees (L269–270, L310 copy).
        assertEquals(120.0, stored.sensorMgdlAtPairing, 0.01)
    }

    @Test
    fun a_fresh_entry_keeps_the_pair_it_was_stored_with() {
        // L298 and plugin test L891–903: now - timestamp < 15 min, no window read.
        val stickAt = now - T.mins(2).msecs()
        val stored = entry(id = 8L, timestamp = stickAt, fingerstick = 150.0, sensor = 120.0)
        val step = CalibrationEntriesForFit.beginLagRepair(stored, now, cachedSensorMgdl = null)
        assertTrue(step is CalibrationEntriesForFit.LagRepairStep.Keep)
        assertEquals(120.0, (step as CalibrationEntriesForFit.LagRepairStep.Keep).entry.sensorMgdlAtPairing, 0.01)
    }

    @Test
    fun the_window_opens_at_exactly_15_minutes() {
        // L298 uses `<`, so equality is already complete and the pair is re-made.
        val stickAt = now - CalibrationEntriesForFit.PAIR_LAG_WINDOW_MS
        val stored = entry(id = 9L, timestamp = stickAt, fingerstick = 150.0, sensor = 120.0)
        val oneMsEarly = CalibrationEntriesForFit.beginLagRepair(stored, now - 1L, null)
        assertTrue(oneMsEarly is CalibrationEntriesForFit.LagRepairStep.Keep)

        val exact = CalibrationEntriesForFit.beginLagRepair(stored, now, null)
        assertTrue(exact is CalibrationEntriesForFit.LagRepairStep.ReadWindow)
    }

    @Test
    fun a_cached_lag_pair_is_reused_and_does_not_ask_for_readings() {
        // L299: lagPairedSensorValues[entry.id]?.let { return entry.copy(...) }
        val stickAt = now - T.hours(1).msecs()
        val stored = entry(id = 7L, timestamp = stickAt, fingerstick = 150.0, sensor = 120.0)
        val step = CalibrationEntriesForFit.beginLagRepair(stored, now, cachedSensorMgdl = 150.0)
        assertTrue(step is CalibrationEntriesForFit.LagRepairStep.Keep)
        val kept = (step as CalibrationEntriesForFit.LagRepairStep.Keep).entry
        assertEquals(150.0, kept.sensorMgdlAtPairing, 0.01)
        assertEquals(120.0, stored.sensorMgdlAtPairing, 0.01)
    }

    @Test
    fun no_reading_in_the_window_keeps_the_stored_pair_and_does_not_cache() {
        // L305: sensorValueForPairing(...) ?: return entry  — cache write is after that
        val stickAt = now - T.hours(1).msecs()
        val stored = entry(id = 7L, timestamp = stickAt, fingerstick = 150.0, sensor = 120.0)
        val finished = CalibrationEntriesForFit.finishLagRepair(stored, emptyList(), stickAt + CalibrationEntriesForFit.PAIR_LAG_MS)
        assertEquals(120.0, finished.entry.sensorMgdlAtPairing, 0.01)
        assertNull(finished.storeInCache)
        assertTrue(finished.entry === stored)
    }

    @Test
    fun a_new_session_drops_the_previous_sensor_pairs_and_ignore_clears_without_moving_session() {
        // L277–280 bind session; L290 ignoreEntriesBefore clears the map only.
        val cache = CalibrationEntriesForFit.LagPairCache()
        cache.bindSession(10L)
        cache.remember(7L, 150.0)
        cache.bindSession(10L)
        assertEquals(150.0, cache.cached(7L)!!, 0.01)
        cache.bindSession(11L)
        assertNull(cache.cached(7L))

        cache.bindSession(11L)
        cache.remember(7L, 140.0)
        cache.dropPairs()
        assertNull(cache.cached(7L))
        cache.remember(8L, 130.0)
        cache.bindSession(11L)
        assertEquals(130.0, cache.cached(8L)!!, 0.01)
    }

    @Test
    fun sensor_value_for_pairing_matches_the_reference_median() {
        // CalibrationMathTest.kt L229–289 @ 6598201d
        assertNull(sensorValueForPairing(emptyList(), now))
        assertEquals(145.0, sensorValueForPairing(listOf(reading(now, 145.0)), now)!!, 0.01)

        val odd = listOf(reading(now, 150.0), reading(now - 60_000L, 140.0), reading(now - 120_000L, 145.0))
        assertEquals(145.0, sensorValueForPairing(odd, now)!!, 0.01)

        val even = listOf(reading(now, 150.0), reading(now - 60_000L, 140.0))
        assertEquals(145.0, sensorValueForPairing(even, now)!!, 0.01)

        val noisy = listOf(
            reading(now, 118.0),
            reading(now - 60_000L, 140.0),
            reading(now - 120_000L, 142.0),
            reading(now - 180_000L, 141.0),
            reading(now - 240_000L, 139.0)
        )
        assertEquals(140.0, sensorValueForPairing(noisy, now)!!, 0.01)

        val six = listOf(
            reading(now, 140.0),
            reading(now - 60_000L, 141.0),
            reading(now - 120_000L, 142.0),
            reading(now - 180_000L, 143.0),
            reading(now - 240_000L, 144.0),
            reading(now - 600_000L, 200.0)
        )
        assertEquals(142.0, sensorValueForPairing(six, now)!!, 0.01)

        val unordered = listOf(
            reading(now - 600_000L, 200.0),
            reading(now - 120_000L, 142.0),
            reading(now, 140.0)
        )
        assertEquals(140.0, sensorValueForPairing(unordered, now, maxSamples = 1)!!, 0.01)
        assertNull(sensorValueForPairing(listOf(reading(now, 140.0)), now, maxSamples = 0))
    }

    private fun entry(id: Long, timestamp: Long, fingerstick: Double, sensor: Double) = CAL(
        id = id,
        timestamp = timestamp,
        fingerstickMgdl = fingerstick,
        sensorMgdlAtPairing = sensor
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
