package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * The per-night HRV baseline, built from the timestamped samples themselves.
 *
 * The baseline used to take one value per pipeline run. The pipeline runs every 15 minutes while
 * HRV itself only arrives a few times a night, so those repeats collapsed the spread, and the HRV
 * z-score and [HrvDepression] divide by that spread on the way to an insulin decision.
 *
 * Reading the samples makes the night median a property of the data, not of how often the pipeline
 * ran. It also makes it idempotent: the daily pass fetches seven days of samples, so it rebuilds the
 * same window from source every time.
 */
class HrvNightBaselineTest {

    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val logger: AAPSLogger = mock()

    private class Clock(var nowMs: Long)

    private fun modelAt(clock: Clock, zone: ZoneId = paris) =
        AIMIPhysioBaselineModelMTR(logger, { clock.nowMs }, { zone })

    /** Local wall-clock time in [zone], as epoch milliseconds. */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: ZoneId = paris): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun sample(ms: Long, value: Double) = HRVDataMTR(timestamp = ms, rmssd = value)

    /** A night that clearly counts: readings across the window, well over the coverage floor. */
    private fun fullNight(year: Int, month: Int, day: Int, value: Double): List<HRVDataMTR> =
        listOf(
            sample(at(year, month, day, 0, 30), value),
            sample(at(year, month, day, 2, 30), value),
            sample(at(year, month, day, 4, 30), value),
        )

    private fun features() = PhysioFeaturesMTR(hrvMeanRMSSD = 50.0, hasValidData = true)

    private fun AIMIPhysioBaselineModelMTR.feed(samples: List<HRVDataMTR>) =
        updateBaseline(features(), samples, hrvPerNightBaseline = true)

    @Test
    fun `three closed nights make a baseline`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        model.feed(fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0))

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertWithMessage("three nights is a personal normal").that(baseline.isValid()).isTrue()
        assertThat(baseline.sampleCount).isEqualTo(3)
        assertThat(baseline.mean).isEqualTo(50.0)
    }

    /**
     * The point of reading samples and not runs: feeding the same nights ten times must not make
     * them count ten times, and must not shrink the spread.
     */
    @Test
    fun `feeding the same samples again changes nothing`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)
        val week = fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)

        model.feed(week)
        val once = model.getCurrentBaseline().hrvRMSSD
        repeat(9) { model.feed(week) }
        val tenTimes = model.getCurrentBaseline().hrvRMSSD

        assertThat(tenTimes.sampleCount).isEqualTo(once.sampleCount)
        assertThat(tenTimes.mean).isEqualTo(once.mean)
        assertWithMessage("repeats must not collapse the spread").that(tenTimes.stdDev).isEqualTo(once.stdDev)
    }

    /** A night gives its median, so one odd reading cannot carry it. */
    @Test
    fun `a night contributes its median`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val oddNight = listOf(
            sample(at(2026, 3, 1, 0, 30), 50.0),
            sample(at(2026, 3, 1, 2, 30), 9.0),
            sample(at(2026, 3, 1, 4, 30), 51.0),
        )
        model.feed(oddNight + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        assertWithMessage("the 9.0 must not drag the night").that(model.getCurrentBaseline().hrvRMSSD.mean).isEqualTo(50.0)
    }

    /** Readings taken in the day say nothing about a resting normal, so they are not a night. */
    @Test
    fun `daytime readings are ignored`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val daytime = listOf(
            sample(at(2026, 3, 1, 14, 0), 10.0),
            sample(at(2026, 3, 1, 16, 0), 10.0),
            sample(at(2026, 3, 1, 18, 0), 10.0),
        )
        model.feed(
            daytime + fullNight(2026, 3, 1, 50.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0)
        )

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertWithMessage("the daytime readings must not become a fourth night").that(baseline.sampleCount).isEqualTo(3)
        assertWithMessage("and must not drag the mean toward 10").that(baseline.mean).isEqualTo(50.0)
    }

    /** The window opens at 22:00, so a late evening reading belongs to the morning that follows. */
    @Test
    fun `an evening reading belongs to the next morning's night`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val acrossMidnight = listOf(
            sample(at(2026, 3, 1, 23, 30), 50.0),
            sample(at(2026, 3, 2, 2, 0), 50.0),
        )
        // Nights 3 and 4 are both closed at 4 March noon.
        model.feed(acrossMidnight + fullNight(2026, 3, 3, 50.0) + fullNight(2026, 3, 4, 50.0))

        // The pair is one night (2 March), not two. Three nights in all, not four.
        assertThat(model.getCurrentBaseline().hrvRMSSD.sampleCount).isEqualTo(3)
    }

    /**
     * The night in progress must never be part of the baseline its own reading is compared with.
     * At 03:00 the current night is still open.
     */
    @Test
    fun `the night in progress is not counted`() {
        val clock = Clock(at(2026, 3, 4, 3))
        val model = modelAt(clock)

        model.feed(
            fullNight(2026, 3, 1, 50.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0) +
                fullNight(2026, 3, 4, 20.0)
        )

        val baseline = model.getCurrentBaseline().hrvRMSSD
        assertWithMessage("tonight is still open").that(baseline.sampleCount).isEqualTo(3)
        assertWithMessage("tonight's low reading must not enter its own baseline").that(baseline.mean).isEqualTo(50.0)
    }

    /** Two readings minutes apart say nothing about a night. Coverage is what counts. */
    @Test
    fun `a night with too little coverage is not counted`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val thin = listOf(
            sample(at(2026, 3, 1, 2, 0), 50.0),
            sample(at(2026, 3, 1, 2, 30), 50.0),
        )
        model.feed(
            thin + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0) + fullNight(2026, 3, 4, 50.0)
        )

        assertWithMessage("half an hour is not a night").that(model.getCurrentBaseline().hrvRMSSD.sampleCount).isEqualTo(3)
    }

    /** The clocks go forward at 02:00 on 29 March 2026, so that night is one hour shorter. */
    @Test
    fun `the spring forward night is one night`() {
        val clock = Clock(at(2026, 3, 30, 12))
        val model = modelAt(clock)

        val shortNight = listOf(
            sample(at(2026, 3, 28, 23, 30), 50.0),
            sample(at(2026, 3, 29, 1, 0), 50.0),
            sample(at(2026, 3, 29, 4, 0), 50.0),
        )
        model.feed(shortNight + fullNight(2026, 3, 27, 50.0) + fullNight(2026, 3, 26, 50.0))

        assertThat(model.getCurrentBaseline().hrvRMSSD.sampleCount).isEqualTo(3)
    }

    /** The night is decided in the injected zone, not in the zone of the machine. */
    @Test
    fun `the zone decides the night and not the machine`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val parisModel = modelAt(clock, paris)
        val pragueModel = modelAt(clock, ZoneId.of("Europe/Prague"))
        val tokyoModel = modelAt(clock, ZoneId.of("Asia/Tokyo"))

        val week = fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        parisModel.feed(week)
        pragueModel.feed(week)
        tokyoModel.feed(week)

        // Paris and Prague share an offset, so the same samples fall in the same nights.
        assertThat(pragueModel.getCurrentBaseline().hrvRMSSD.mean).isEqualTo(parisModel.getCurrentBaseline().hrvRMSSD.mean)
        // In Tokyo, 00:30-04:30 Paris is 08:30-12:30: daytime, so no night at all.
        assertThat(tokyoModel.getCurrentBaseline().hrvRMSSD.sampleCount).isEqualTo(0)
    }

    /** Only seven nights are kept, by local date. */
    @Test
    fun `the window keeps seven nights`() {
        val clock = Clock(at(2026, 3, 20, 12))
        val model = modelAt(clock)

        val tenNights = (1..10).flatMap { fullNight(2026, 3, it, 50.0) }
        model.feed(tenNights)

        assertThat(model.getCurrentBaseline().hrvRMSSD.sampleCount).isEqualTo(7)
    }

    /** The nights must also count as days, or `isValid` disagrees with itself. */
    @Test
    fun `valid days count sees the nights`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        val baseline = model.feed(
            fullNight(2026, 3, 1, 40.0) + fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 60.0)
        )

        assertWithMessage("HRV-only nights still make days").that(baseline.validDaysCount).isEqualTo(3)
    }

    /**
     * Features that also carry a resting heart rate and a step count, so the run writes the other
     * histories as well as the nights. That overlap is what the day count has to handle.
     */
    private fun featuresWithOtherSources() =
        PhysioFeaturesMTR(hrvMeanRMSSD = 50.0, rhrMorning = 58, stepsDailyAverage = 6000, hasValidData = true)

    private fun AIMIPhysioBaselineModelMTR.feedWithOtherSources(samples: List<HRVDataMTR>) =
        updateBaseline(featuresWithOtherSources(), samples, hrvPerNightBaseline = true)

    /** One calendar day that has both a night and a reading from another source is one day. */
    @Test
    fun `a date with both a night and another reading counts once`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)

        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        assertWithMessage("one day, not one plus one").that(model.getCurrentBaseline().validDaysCount).isEqualTo(1)
    }

    /** Two real days must not pass the "at least three days" test. */
    @Test
    fun `two real days do not make a valid baseline`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        val baseline = model.getCurrentBaseline()
        assertThat(baseline.validDaysCount).isEqualTo(2)
        assertWithMessage("two days is not three").that(baseline.isValid()).isFalse()
    }

    /** Three real days do. */
    @Test
    fun `three real days make a valid baseline`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 3, 50.0))

        clock.nowMs = at(2026, 3, 4, 12)
        model.feedWithOtherSources(fullNight(2026, 3, 4, 50.0))

        val baseline = model.getCurrentBaseline()
        assertThat(baseline.validDaysCount).isEqualTo(3)
        assertThat(baseline.isValid()).isTrue()
    }

    /**
     * The day count reaches an insulin decision through the confidence score, which adds
     * `0.3 x (days / 7)`. A doubled count would buy confidence the data does not support.
     */
    @Test
    fun `confidence is not inflated by a day counted twice`() {
        val clock = Clock(at(2026, 3, 2, 12))
        val model = modelAt(clock)
        model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0))

        clock.nowMs = at(2026, 3, 3, 12)
        val baseline = model.feedWithOtherSources(fullNight(2026, 3, 2, 50.0) + fullNight(2026, 3, 3, 50.0))

        val engine = AIMIPhysioContextEngineMTR(logger)
        val withTwoDays = engine.analyze(featuresWithOtherSources(), baseline).confidence
        val withFourDays = engine.analyze(featuresWithOtherSources(), baseline.copy(validDaysCount = 4)).confidence

        assertThat(baseline.validDaysCount).isEqualTo(2)
        assertWithMessage("the score follows the day count, so the count must not be inflated")
            .that(withTwoDays).isLessThan(withFourDays)
    }

    /**
     * A day is a local idea. At 00:30 in Paris the UTC date is still the day before, so a UTC
     * boundary would split one day in two and the night would never line up with it.
     */
    @Test
    fun `the day count uses the local date and not the UTC one`() {
        val clock = Clock(at(2026, 3, 2, 0, 30))
        val model = modelAt(clock)
        // Writes the heart rate and steps at 00:30 local, which is 23:30 UTC the day before.
        model.feedWithOtherSources(emptyList())

        clock.nowMs = at(2026, 3, 2, 12)
        model.feedWithOtherSources(
            listOf(sample(at(2026, 3, 1, 23, 30), 50.0), sample(at(2026, 3, 2, 2, 0), 50.0))
        )

        assertWithMessage("both readings are the 2nd of March").that(model.getCurrentBaseline().validDaysCount).isEqualTo(1)
    }

    /**
     * With the switch off the count must stay exactly what it always was, UTC day boundary and all.
     * Three runs a day apart made three days before this change, and still do.
     */
    @Test
    fun `the switch off counts days exactly as before`() {
        val clock = Clock(at(2026, 3, 1, 12))
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(featuresWithOtherSources(), fullNight(2026, 3, 1, 50.0), hrvPerNightBaseline = false)
            clock.nowMs += 24 * 60 * 60 * 1000L
        }

        assertThat(model.getCurrentBaseline().validDaysCount).isEqualTo(3)
    }

    /** With the switch off nothing of the night path may run: three runs still make a baseline. */
    @Test
    fun `the switch off still counts runs rather than nights`() {
        val clock = Clock(at(2026, 3, 4, 12))
        val model = modelAt(clock)

        repeat(3) {
            model.updateBaseline(features(), fullNight(2026, 3, 1, 50.0), hrvPerNightBaseline = false)
            clock.nowMs += 15 * 60_000L
        }

        assertWithMessage("off, three runs still make a baseline - this is the behaviour in production")
            .that(model.getCurrentBaseline().hrvRMSSD.isValid()).isTrue()
    }
}
