package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How long a heart rate may be carried forward when a refresh brings nothing.
 *
 * The defect this replaces: `HealthContextRepository` carried the previous heart rate into a new
 * snapshot and stamped it `timestamp = snapshot.timestamp`, then stored that snapshot as the new
 * previous one. The re-dating therefore **compounded** — the same reading was presented as current
 * on every tick, for ever, with nothing recording when it had actually been measured.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `9d0e9bdb9f` (file unchanged on tip `f61474bb73`).
 * Study source set: [HeartRateCarryForward] is commonMain and has no mocks, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [app.aaps.plugins.aps.openAPSAIMI.ISF.HeartRateTrendIsfTest].
 */
class HeartRateCarryForwardTest {

    private val t0 = 1_789_600_000_000L
    private val oneMinute = 60_000L

    @Test
    fun aFreshReadingIsUsedAndStampedNow() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 72, freshHrAvg15m = 72, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = t0 - 30 * oneMinute,
        )
        assertEquals(72, r.hrNow)
        assertEquals(t0, r.measuredAtMs)
    }

    @Test
    fun aMissingReadingCarriesThePreviousOneAndKeepsItsOwnAge() {
        val measured = t0 - 4 * oneMinute
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 61, previousMeasuredAtMs = measured,
        )
        assertEquals(60, r.hrNow)
        assertEquals(61, r.hrAvg15m)
        // The age is NOT refreshed. This is the whole point.
        assertEquals(measured, r.measuredAtMs)
    }

    /**
     * The compounding the old code allowed: carry forward, re-date, store, repeat. Here the same
     * reading is carried for one minute at a time and must still expire on its own real age.
     */
    @Test
    fun carryingForwardTickAfterTickStillExpiresOnTheRealAge() {
        var hrNow = 60
        var hrAvg = 60
        var measuredAt = t0
        for (i in 1..30) {
            val r = HeartRateCarryForward.resolve(
                freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0 + i * oneMinute,
                previousHrNow = hrNow, previousHrAvg15m = hrAvg, previousMeasuredAtMs = measuredAt,
            )
            hrNow = r.hrNow; hrAvg = r.hrAvg15m; measuredAt = r.measuredAtMs
        }
        assertEquals(0, hrNow)
        assertEquals(0L, measuredAt)
    }

    @Test
    fun aReadingOlderThanTheLimitIsDroppedRatherThanCarried() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60,
            previousMeasuredAtMs = t0 - HeartRateCarryForward.MAX_AGE_MS - 1L,
        )
        assertEquals(0, r.hrNow)
        assertEquals(0, r.hrAvg15m)
        assertEquals(0L, r.measuredAtMs)
    }

    @Test
    fun aReadingExactlyAtTheLimitIsStillCarried() {
        val measured = t0 - HeartRateCarryForward.MAX_AGE_MS
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = measured,
        )
        assertEquals(60, r.hrNow)
        assertEquals(measured, r.measuredAtMs)
    }

    @Test
    fun withNoPreviousMeasurementThereIsNothingToCarry() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = 0L,
        )
        assertEquals(0, r.hrNow)
        assertEquals(0L, r.measuredAtMs)
    }

    @Test
    fun aClockThatMovedBackDropsTheReading() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = t0 + oneMinute,
        )
        assertEquals(0, r.hrNow)
        assertEquals(0L, r.measuredAtMs)
    }

    @Test
    fun aPreviousValueOfZeroIsNotCarried() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 0, previousHrAvg15m = 0, previousMeasuredAtMs = t0 - oneMinute,
        )
        assertEquals(0, r.hrNow)
        assertEquals(0L, r.measuredAtMs)
    }

    /**
     * The carried average must never outlive the carried point, because every consumer that reads
     * `hrAvg15m` as a mean is already reading a single held sample.
     */
    @Test
    fun theAverageIsCarriedAndDroppedTogetherWithThePoint() {
        val fresh = HeartRateCarryForward.resolve(
            freshHrNow = 80, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 61, previousMeasuredAtMs = t0 - oneMinute,
        )
        assertEquals(80, fresh.hrNow)
        assertEquals(80, fresh.hrAvg15m)
    }

    @Test
    fun theLimitMatchesTheProvidersOwnLookbackWindow() {
        // The provider asks for the latest heart rate over 15 minutes, so carrying a reading past
        // that adds nothing the provider would not have returned itself.
        assertEquals(15 * 60 * 1000L, HeartRateCarryForward.MAX_AGE_MS)
    }
}
