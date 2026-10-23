package app.aaps.implementation.glucose

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.time.T
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests the rules of `GlucoseCorrectionImpl.correctedFromSeries`, the pure part of the class.
 *
 * In commonTest, with no mocks, so they run on the JVM and on the iOS simulator.
 */
class GlucoseCorrectionImplTest {

    private val head = 1_700_000_000_000L
    private val fiveMin = T.mins(5).msecs()

    /** Corrected series, newest first, five minutes apart, every stored value 100. */
    private fun series(vararg corrected: Double) = corrected
        .mapIndexed { i, value -> InMemoryGlucoseValue(timestamp = head - i * fiveMin, value = 100.0, smoothed = value) }

    private fun corrected(series: List<InMemoryGlucoseValue>?, timestamp: Long, storedMgdl: Double) =
        GlucoseCorrectionImpl.correctedFromSeries(series, timestamp, storedMgdl)

    @Test
    fun noCorrectedSeriesKeepsTheStoredValue() {
        assertNull(corrected(null, head, 56.0))
    }

    @Test
    fun emptyCorrectedSeriesKeepsTheStoredValue() {
        assertNull(corrected(emptyList(), head, 56.0))
    }

    @Test
    fun readingOnTheNewestPointTakesTheValueTheDashboardShows() {
        assertEquals(65.0, corrected(series(65.0, 70.0, 75.0), head, 56.0))
    }

    @Test
    fun readingNewerThanAFreshHeadGetsTheHeadCorrectionAsAnOffset() {
        // A one minute sensor sends readings between two points of the five minute series. The head
        // was stored as 100 and is shown as 90, so a correction of -10 is added to the new reading.
        assertEquals(70.0, corrected(series(90.0, 95.0), head + T.mins(2).msecs(), 80.0))
    }

    @Test
    fun readingExactlyFiveMinutesAfterTheHeadStillGetsTheOffset() {
        assertEquals(70.0, corrected(series(90.0, 95.0), head + fiveMin, 80.0))
    }

    @Test
    fun readingNewerThanAnOldHeadKeepsTheStoredValue() {
        // The head is 6 minutes old. Its correction says nothing about this reading.
        assertNull(corrected(series(90.0, 95.0), head + T.mins(6).msecs(), 80.0))
    }

    /**
     * The bug: a fresh low reading of 50 took the value 100 of an old head (the ratio 2.0 still
     * passed the plausibility net), so the low alarm compared 100 and stayed quiet.
     */
    @Test
    fun freshLowReadingIsNotReplacedByAnOldHeadValue() {
        val oldHead = listOf(InMemoryGlucoseValue(timestamp = head, value = 100.0, smoothed = 100.0))
        assertNull(corrected(oldHead, head + T.mins(20).msecs(), 50.0))
    }

    @Test
    fun offsetFromAFreshHeadStillPassesThePlausibilityNet() {
        // Head stored 100, shown 60: offset -40. A new reading of 70 would become 30, under the 39 floor.
        assertNull(corrected(series(60.0), head + T.mins(1).msecs(), 70.0))
    }

    @Test
    fun readingBetweenTwoPointsIsInterpolated() {
        // Halfway between the point at now-5min (75) and the point at now (65).
        assertEquals(70.0, corrected(series(65.0, 75.0), head - T.mins(2).msecs() - T.secs(30).msecs(), 60.0))
    }

    @Test
    fun readingExactlyOnAnOlderPointTakesThatPoint() {
        assertEquals(85.0, corrected(series(65.0, 75.0, 85.0), head - 2 * fiveMin, 70.0))
    }

    @Test
    fun readingOlderThanTheWholeSeriesKeepsTheStoredValue() {
        assertNull(corrected(series(65.0, 75.0, 85.0), head - 3 * fiveMin, 90.0))
    }

    @Test
    fun storedValueOfZeroKeepsTheStoredValue() {
        assertNull(corrected(series(65.0), head, 0.0))
    }

    @Test
    fun correctionFarBelowThePlausibleRangeIsRefused() {
        assertNull(corrected(series(20.0), head, 56.0))
    }

    @Test
    fun correctionFarAboveThePlausibleRangeIsRefused() {
        assertNull(corrected(series(450.0), head, 300.0))
    }

    @Test
    fun correctionMoreThanDoubleTheStoredValueIsRefused() {
        assertNull(corrected(series(160.0), head, 60.0))
    }

    @Test
    fun correctionBelowHalfTheStoredValueIsRefused() {
        assertNull(corrected(series(90.0), head, 200.0))
    }

    // ---- whole mg/dL on the way out -------------------------------------------------------------

    /**
     * The bug seen on Nightscout on 2026-09-13: `sgv` read 83.17856343415423 while the phone showed
     * 83. Both sensors build their reading from an `Int`, so before calibration existed the value
     * sent out was always whole and nothing on the way out ever rounded.
     */
    @Test
    fun anInterpolatedReadingComesBackWhole() {
        // Two minutes past the older point of a five minute span: 90 - (2 / 5) * 10 = 86.0 exactly,
        // so shift by one more second to land on a value that is not whole.
        val value = corrected(series(80.0, 90.0), head - T.mins(3).msecs() + T.secs(1).msecs(), 85.0)
        assertNotNull(value)
        assertEquals(value.toLong().toDouble(), value)
    }

    @Test
    fun anOffsetReadingComesBackWhole() {
        // Head stored 100, shown 90.4: offset -9.6, so 80 becomes 70.4, rounded to 70.
        assertEquals(70.0, corrected(series(90.4), head + T.mins(1).msecs(), 80.0))
    }

    @Test
    fun aCorrectedValueWithDecimalsIsRoundedToTheNearestWholeNumber() {
        assertEquals(83.0, corrected(series(83.17856343415423), head, 83.0))
        assertEquals(84.0, corrected(series(83.6), head, 83.0))
    }

    /** Rounding happens after the plausibility net, so the net keeps working on the exact value. */
    @Test
    fun roundingDoesNotLetAnImplausibleCorrectionThrough() {
        // 38.6 is under the 39 floor. Rounding it first would have made it 39 and let it pass.
        assertNull(corrected(series(38.6), head, 56.0))
    }

    @Test
    fun roundingDoesNotChangeACorrectionThatIsAlreadyWhole() {
        assertEquals(65.0, corrected(series(65.0, 70.0, 75.0), head, 56.0))
    }

    @Test
    fun seriesWithoutSmoothingFallsBackToTheCalibratedValue() {
        assertEquals(65.0, corrected(listOf(InMemoryGlucoseValue(timestamp = head, value = 56.0, calibrated = 65.0)), head, 56.0))
    }

    @Test
    fun seriesWithoutCalibrationAndSmoothingReturnsThePlainValue() {
        assertEquals(56.0, corrected(listOf(InMemoryGlucoseValue(timestamp = head, value = 56.0)), head, 56.0))
    }
}
