package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.overview.graph.BgDataPoint
import app.aaps.core.interfaces.overview.graph.BgRange
import app.aaps.core.interfaces.overview.graph.BgType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The home screen forecast: damped measured trend, calibrated range, insulin-only low warning. */
class DisplayForecastTest {

    private val now = 1_000_000_000L
    private val minute = 60_000L
    private val mgdl = { v: Double -> v }
    private val tolerance = 1e-9

    private fun reading(minutesAgo: Int, value: Double, type: BgType = BgType.REGULAR) =
        BgDataPoint(timestamp = now - minutesAgo * minute, value = value, range = BgRange.IN_RANGE, type = type)

    private fun insulinOnly(vararg values: Double) = values.mapIndexed { i, v ->
        BgDataPoint(timestamp = now + (i + 1) * 5 * minute, value = v, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION)
    }

    /** Falling 3 mg/dL every 5 min for the last 15 min, last reading 2 min ago. */
    private val falling = listOf(reading(17, 204.0), reading(12, 201.0), reading(7, 198.0), reading(2, 195.0))

    @Test
    fun `the centre follows the measured trend and fades out`() {
        val f = assertNotNull(DisplayForecast.build(falling, emptyList(), now, mgdl))

        assertEquals(now - 2 * minute, f.startMs)
        assertTrue(f.trendKnown)
        assertEquals(195.0, f.centre[0].value, tolerance)
        assertEquals(195.0 - 3 * 0.7, f.centre[1].value, tolerance)
        assertEquals(195.0 - 3 * 0.7 - 3 * 0.49, f.centre[2].value, tolerance)
        // The sum of the fading steps stays under 3 x 0.7 / 0.3 = 7 mg/dL below the last reading.
        val end = f.centre.last().value
        assertTrue(end < 195.0 && end > 195.0 - 7.0, "end=$end")
    }

    @Test
    fun `the forecast reaches 60 minutes in 5 minute steps`() {
        val f = assertNotNull(DisplayForecast.build(falling, emptyList(), now, mgdl))

        assertEquals(13, f.centre.size)
        assertEquals(f.startMs + 60 * minute, f.centre.last().timeMs)
        assertEquals(f.centre[6], f.at(f.centre, DisplayForecast.HEADLINE_MINUTES))
    }

    @Test
    fun `the range widens with the horizon around the centre`() {
        val f = assertNotNull(DisplayForecast.build(falling, emptyList(), now, mgdl))

        assertEquals(f.centre[0].value, f.low[0].value, tolerance)
        assertEquals(15.0, f.high[3].value - f.centre[3].value, tolerance)
        assertEquals(27.0, f.centre[6].value - f.low[6].value, tolerance)
        assertEquals(41.0, f.high[12].value - f.centre[12].value, tolerance)
    }

    @Test
    fun `the half width is interpolated between the measured points`() {
        assertEquals(0.0, DisplayForecast.halfWidthMgdl(0), tolerance)
        assertEquals(5.0, DisplayForecast.halfWidthMgdl(5), tolerance)
        assertEquals(19.0, DisplayForecast.halfWidthMgdl(20), tolerance)
        assertEquals(41.0, DisplayForecast.halfWidthMgdl(60), tolerance)
        assertEquals(41.0, DisplayForecast.halfWidthMgdl(90), tolerance)
    }

    @Test
    fun `the range is converted to the display unit`() {
        val mmol = { v: Double -> v / 18.0 }
        val readings = listOf(reading(17, 11.0), reading(2, 11.0))

        val f = assertNotNull(DisplayForecast.build(readings, emptyList(), now, mmol))

        assertEquals(41.0 / 18.0, f.high[12].value - f.centre[12].value, tolerance)
    }

    @Test
    fun `without a reading 15 minutes back the centre is flat`() {
        val f = assertNotNull(DisplayForecast.build(listOf(reading(2, 150.0), reading(7, 140.0)), emptyList(), now, mgdl))

        assertFalse(f.trendKnown)
        assertTrue(f.centre.all { it.value == 150.0 })
    }

    @Test
    fun `no forecast from a stale reading`() {
        assertNull(DisplayForecast.build(listOf(reading(11, 150.0), reading(26, 150.0)), emptyList(), now, mgdl))
    }

    @Test
    fun `no forecast without a reading`() {
        assertNull(DisplayForecast.build(emptyList(), insulinOnly(120.0), now, mgdl))
    }

    @Test
    fun `loop curves are never taken as readings`() {
        val withPrediction = falling + BgDataPoint(now, 400.0, BgRange.HIGH, BgType.UAM_PREDICTION)

        val f = assertNotNull(DisplayForecast.build(withPrediction, emptyList(), now, mgdl))

        assertEquals(195.0, f.centre[0].value, tolerance)
    }

    @Test
    fun `bucketed readings count as measured`() {
        val f = assertNotNull(DisplayForecast.build(listOf(reading(2, 120.0, BgType.BUCKETED)), emptyList(), now, mgdl))

        assertEquals(120.0, f.centre[0].value, tolerance)
    }

    @Test
    fun `an insulin-only curve under 70 raises the low warning`() {
        val f = assertNotNull(DisplayForecast.build(falling, insulinOnly(160.0, 120.0, 85.0, 69.0, 60.0), now, mgdl))

        assertTrue(f.lowRisk)
        assertEquals(5, f.insulinOnly.size)
        assertEquals(69.0, f.insulinOnly[3].value, tolerance)
    }

    @Test
    fun `an insulin-only curve that stays above 70 gives no warning and no curve`() {
        val f = assertNotNull(DisplayForecast.build(falling, insulinOnly(160.0, 120.0, 85.0, 70.0), now, mgdl))

        assertFalse(f.lowRisk)
        assertTrue(f.insulinOnly.isEmpty())
    }

    @Test
    fun `a low insulin-only point after the horizon does not count`() {
        val far = BgDataPoint(now + 90 * minute, 50.0, BgRange.LOW, BgType.IOB_PREDICTION)

        val f = assertNotNull(DisplayForecast.build(falling, insulinOnly(150.0) + far, now, mgdl))

        assertFalse(f.lowRisk)
    }

    @Test
    fun `only the insulin-only curve can raise the warning`() {
        val uamLow = BgDataPoint(now + 10 * minute, 50.0, BgRange.LOW, BgType.UAM_PREDICTION)

        val f = assertNotNull(DisplayForecast.build(falling, listOf(uamLow), now, mgdl))

        assertFalse(f.lowRisk)
    }

    @Test
    fun `the warning threshold is converted to the display unit`() {
        val mmol = { v: Double -> v / 18.0 }
        val readings = listOf(reading(2, 8.0))

        assertTrue(assertNotNull(DisplayForecast.build(readings, insulinOnly(3.8), now, mmol)).lowRisk)
        assertFalse(assertNotNull(DisplayForecast.build(readings, insulinOnly(4.0), now, mmol)).lowRisk)
    }
}
