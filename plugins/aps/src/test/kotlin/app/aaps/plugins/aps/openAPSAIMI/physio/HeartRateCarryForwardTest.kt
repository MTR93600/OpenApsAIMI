package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * How long a heart rate may be carried forward when a refresh brings nothing.
 *
 * The defect this replaces: `HealthContextRepository` carried the previous heart rate into a new
 * snapshot and stamped it `timestamp = snapshot.timestamp`, then stored that snapshot as the new
 * previous one. The re-dating therefore **compounded** — the same reading was presented as current
 * on every tick, for ever, with nothing recording when it had actually been measured.
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
        assertThat(r.hrNow).isEqualTo(72)
        assertThat(r.measuredAtMs).isEqualTo(t0)
    }

    @Test
    fun aMissingReadingCarriesThePreviousOneAndKeepsItsOwnAge() {
        val measured = t0 - 4 * oneMinute
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 61, previousMeasuredAtMs = measured,
        )
        assertThat(r.hrNow).isEqualTo(60)
        assertThat(r.hrAvg15m).isEqualTo(61)
        // The age is NOT refreshed. This is the whole point.
        assertThat(r.measuredAtMs).isEqualTo(measured)
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
        assertThat(hrNow).isEqualTo(0)
        assertThat(measuredAt).isEqualTo(0L)
    }

    @Test
    fun aReadingOlderThanTheLimitIsDroppedRatherThanCarried() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60,
            previousMeasuredAtMs = t0 - HeartRateCarryForward.MAX_AGE_MS - 1L,
        )
        assertThat(r.hrNow).isEqualTo(0)
        assertThat(r.hrAvg15m).isEqualTo(0)
        assertThat(r.measuredAtMs).isEqualTo(0L)
    }

    @Test
    fun aReadingExactlyAtTheLimitIsStillCarried() {
        val measured = t0 - HeartRateCarryForward.MAX_AGE_MS
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = measured,
        )
        assertThat(r.hrNow).isEqualTo(60)
        assertThat(r.measuredAtMs).isEqualTo(measured)
    }

    @Test
    fun withNoPreviousMeasurementThereIsNothingToCarry() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = 0L,
        )
        assertThat(r.hrNow).isEqualTo(0)
        assertThat(r.measuredAtMs).isEqualTo(0L)
    }

    @Test
    fun aClockThatMovedBackDropsTheReading() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 60, previousHrAvg15m = 60, previousMeasuredAtMs = t0 + oneMinute,
        )
        assertThat(r.hrNow).isEqualTo(0)
        assertThat(r.measuredAtMs).isEqualTo(0L)
    }

    @Test
    fun aPreviousValueOfZeroIsNotCarried() {
        val r = HeartRateCarryForward.resolve(
            freshHrNow = 0, freshHrAvg15m = 0, nowMs = t0,
            previousHrNow = 0, previousHrAvg15m = 0, previousMeasuredAtMs = t0 - oneMinute,
        )
        assertThat(r.hrNow).isEqualTo(0)
        assertThat(r.measuredAtMs).isEqualTo(0L)
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
        assertThat(fresh.hrNow).isEqualTo(80)
        assertThat(fresh.hrAvg15m).isEqualTo(80)
    }

    @Test
    fun theLimitMatchesTheProvidersOwnLookbackWindow() {
        // The provider asks for the latest heart rate over 15 minutes, so carrying a reading past
        // that adds nothing the provider would not have returned itself.
        assertThat(HeartRateCarryForward.MAX_AGE_MS).isEqualTo(15 * 60 * 1000L)
    }
}
