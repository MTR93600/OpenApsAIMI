package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Test

/**
 * The awake resting heart rate, and above all the cases where it refuses to give one.
 *
 * A substituted baseline is what made the stress ISF floor hold for half of every day, so "no value"
 * has to be a first-class answer here.
 */
class AwakeRestingHeartRateTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Days 10, 11 and 12, [perDay] readings each, all inside the awake window by default. */
    private fun samples(
        perDay: Int,
        bpm: (index: Int) -> Double,
        days: List<Int> = listOf(10, 11, 12),
        stepsLast15m: Int? = 0,
        firstHour: Int = 9,
        hourSpan: Int = 12,
    ): List<AwakeRestingHeartRate.Sample> =
        days.flatMap { day ->
            (0 until perDay).map { index ->
                AwakeRestingHeartRate.Sample(
                    timestampMs = at(day, firstHour + index % hourSpan, (index * 7) % 60),
                    bpm = bpm(index),
                    stepsLast15m = stepsLast15m,
                )
            }
        }

    private fun awakeSamples(
        perDay: Int,
        bpm: (index: Int) -> Double,
        days: List<Int> = listOf(10, 11, 12),
        stepsLast15m: Int? = 0,
    ): List<AwakeRestingHeartRate.Sample> = samples(perDay, bpm, days, stepsLast15m)

    @Test
    fun `fewer than fifty usable samples gives no value`() {
        val samples = awakeSamples(perDay = 16, bpm = { 70.0 + it })

        assertThat(samples).hasSize(48)
        assertThat(AwakeRestingHeartRate.estimate(samples, zone)).isNull()
    }

    @Test
    fun `fewer than three days gives no value, however many samples there are`() {
        val samples = awakeSamples(perDay = 60, bpm = { 70.0 + it % 20 }, days = listOf(10, 11))

        assertThat(samples).hasSize(120)
        assertThat(AwakeRestingHeartRate.estimate(samples, zone)).isNull()
    }

    @Test
    fun `an empty history gives no value`() {
        assertThat(AwakeRestingHeartRate.estimate(emptyList(), zone)).isNull()
    }

    @Test
    fun `night readings do not count, so a sleeping rate cannot become the baseline`() {
        // 60 readings at 50 bpm between 00:00 and 05:00, plus 60 awake ones. Only the awake ones count.
        val night = samples(perDay = 20, bpm = { 50.0 }, firstHour = 0, hourSpan = 5)
        val awake = awakeSamples(perDay = 20, bpm = { 70.0 + it })

        val nightOnly = AwakeRestingHeartRate.estimate(night, zone)
        val both = AwakeRestingHeartRate.estimate(night + awake, zone)

        assertThat(nightOnly).isNull()
        assertThat(both).isEqualTo(AwakeRestingHeartRate.estimate(awake, zone))
    }

    @Test
    fun `readings taken while walking do not count`() {
        val calm = awakeSamples(perDay = 20, bpm = { 70.0 + it })
        val walking = awakeSamples(perDay = 20, bpm = { 120.0 }, stepsLast15m = 600)

        assertThat(AwakeRestingHeartRate.estimate(calm + walking, zone))
            .isEqualTo(AwakeRestingHeartRate.estimate(calm, zone))
    }

    @Test
    fun `an unknown step count keeps the reading`() {
        val known = awakeSamples(perDay = 20, bpm = { 70.0 + it })
        val unknown = known.map { it.copy(stepsLast15m = null) }

        assertThat(AwakeRestingHeartRate.estimate(unknown, zone))
            .isEqualTo(AwakeRestingHeartRate.estimate(known, zone))
    }

    @Test
    fun `the value is the tenth centile, not the minimum`() {
        // 60 readings: one at 48, the rest from 69 upwards. A minimum would answer 48.
        val samples = awakeSamples(perDay = 20, bpm = { if (it == 0) 48.0 else 68.0 + it })

        val estimate = AwakeRestingHeartRate.estimate(samples, zone)

        assertThat(estimate).isNotNull()
        assertThat(estimate!!).isGreaterThan(48)
        assertThat(estimate).isAtMost(75)
    }

    @Test
    fun `a reading of zero is missing data and is dropped`() {
        val samples = awakeSamples(perDay = 20, bpm = { if (it < 5) 0.0 else 70.0 + it })

        // 45 usable readings out of 60, which is under the minimum.
        assertThat(AwakeRestingHeartRate.estimate(samples, zone)).isNull()
    }
}
