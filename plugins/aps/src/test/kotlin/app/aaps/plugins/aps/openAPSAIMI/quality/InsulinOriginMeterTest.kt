package app.aaps.plugins.aps.openAPSAIMI.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Specification locks for [InsulinOriginMeter].
 *
 * The first tests write down the two rules the meter must never break: the window stays bounded,
 * and the parts always add back to the tick-weighted total.
 *
 * The later tests are the ones that describe a real defect. The window used to be counted in ticks
 * (2016, documented as "one week of 5 minute ticks"), while the loop runs about every 60 seconds on
 * a one minute sensor, so the window was 33 hours and the totals were tick-weighted sums that the
 * export called delivered insulin. See the class doc of [InsulinOriginMeter].
 */
class InsulinOriginMeterTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun sample(
        index: Int,
        finalU: Double,
        modelOutputU: Double?,
        autodriveFloorU: Double?,
    ) = InsulinOriginMeter.Sample(
        timestampMs = t0 + index * 5 * minute,
        finalU = finalU,
        modelOutputU = modelOutputU,
        mpcOutputU = null,
        autodriveFloorU = autodriveFloorU,
        bindingStage = "FINAL",
        originOwner = "TEST",
    )

    /**
     * The window is a ring, not a log. At the 5 minute cadence this test feeds, seven days are
     * exactly 2016 samples, so 5000 ticks must leave 2016 kept and the running sums must describe
     * exactly those. A leak here would make the shares drift slowly over days, which is precisely
     * the time scale the meter is meant to answer on.
     */
    @Test
    fun windowIsBoundedAndSharesSumToOne() {
        val meter = InsulinOriginMeter()
        for (i in 0 until 5000) {
            // Varied but always explained: sometimes the model leads, sometimes the floor does.
            val model = (i % 7) * 0.10
            val floor = (i % 5) * 0.15
            val delivered = maxOf(model, floor)
            meter.observe(sample(i, finalU = delivered, modelOutputU = model, autodriveFloorU = floor))
        }

        val reading = meter.read(t0 + 5000L * 5 * minute)
        assertEquals(2016, reading.tickCount)
        assertTrue(reading.tickWeightedU > 0.0)

        val parts = reading.modelOriginU + reading.floorOriginU + reading.otherOriginU
        val sum = parts / reading.tickWeightedU
        assertTrue(sum in 0.999..1.001, "parts must add back to the delivered amount, got $sum")

        assertNotNull(reading.modelOriginShare)
        assertNotNull(reading.floorOriginShare)
        assertEquals(1.0, reading.modelOriginShare!! + reading.floorOriginShare!!, 1e-3)
        assertEquals(t0 + (5000L - 2016L) * 5 * minute, reading.windowStartMs)
    }

    /**
     * The case the meter exists for: the model asked for nothing and the floor delivered anyway.
     * Every unit must be credited to the floor, none to the model, and the tick must be counted as
     * a model-zero tick. Crediting any of it to the model would hide exactly the failure being
     * looked for.
     */
    @Test
    fun modelZeroIsCountedWhenTheFloorDelivers() {
        val meter = InsulinOriginMeter()
        meter.observe(sample(0, finalU = 1.5, modelOutputU = 0.0, autodriveFloorU = 1.5))

        val reading = meter.read(t0)
        assertEquals(1, reading.tickCount)
        assertEquals(1.5, reading.tickWeightedU, 1e-9)
        assertEquals(0.0, reading.modelOriginU, 1e-9)
        assertEquals(1.5, reading.floorOriginU, 1e-9)
        assertEquals(0.0, reading.otherOriginU, 1e-9)
        assertEquals(1.5, reading.modelZeroTickWeightedU, 1e-9)
        assertEquals(1, reading.modelZeroTickCount)
        assertEquals(0.0, reading.modelOriginShare!!, 1e-9)
        assertEquals(1.0, reading.floorOriginShare!!, 1e-9)
    }

    /** Nothing delivered yet: the shares must stay `null`, never `0.0`. */
    @Test
    fun sharesStayNullBeforeAnyDelivery() {
        val meter = InsulinOriginMeter()
        meter.observe(sample(0, finalU = 0.0, modelOutputU = 0.0, autodriveFloorU = 0.0))

        val reading = meter.read(t0)
        assertEquals(1, reading.tickCount)
        assertEquals(0.0, reading.tickWeightedU, 1e-9)
        assertNull(reading.modelOriginShare)
        assertNull(reading.floorOriginShare)
    }

    /** Same helper, but the caller places the sample in time itself. */
    private fun sampleAt(
        timestampMs: Long,
        finalU: Double,
        modelOutputU: Double?,
        autodriveFloorU: Double?,
    ) = InsulinOriginMeter.Sample(
        timestampMs = timestampMs,
        finalU = finalU,
        modelOutputU = modelOutputU,
        mpcOutputU = null,
        autodriveFloorU = autodriveFloorU,
        bindingStage = "FINAL",
        originOwner = "TEST",
    )

    /**
     * The window is seven days of clock time, whatever the cadence.
     *
     * This is the defect the rewrite fixes. The old window kept 2016 samples and its doc called that
     * one week; fed at one tick per minute, as the loop really runs on a one minute sensor, those
     * 2016 samples were 33 hours. Eight days in must leave seven days kept, and the export must say
     * out loud what the real span and the real cadence were.
     */
    @Test
    fun windowKeepsSevenDaysAtOneTickPerMinute() {
        val meter = InsulinOriginMeter()
        val ticks = 8 * 24 * 60
        for (i in 0 until ticks) {
            meter.observe(sampleAt(t0 + i * minute, finalU = 0.2, modelOutputU = 0.2, autodriveFloorU = 0.0))
        }

        val reading = meter.read(t0 + ticks * minute)
        val sevenDaysOfMinutes = 7 * 24 * 60
        assertEquals(sevenDaysOfMinutes, reading.tickCount)
        assertEquals(t0 + (ticks - sevenDaysOfMinutes) * minute, reading.windowStartMs)
        assertEquals((sevenDaysOfMinutes - 1) * minute, reading.windowSpanMs)
        assertEquals(minute, reading.tickGapMedianMs)
    }

    /**
     * Why the shares are kept and the totals are renamed.
     *
     * Two meters see the same decisions. The second one is ticked twice as often, which is exactly
     * what happens when the sensor moves from five minute samples to one minute samples: the same
     * micro-bolus is re-proposed on every run. The totals double, so they cannot be read as insulin.
     * The shares do not move at all, so they can.
     */
    @Test
    fun sharesAreCadenceProofWhileTotalsAreNot() {
        val decisions = listOf(
            Triple(0.30, 0.30, 0.00),
            Triple(0.80, 0.00, 0.80),
            Triple(0.60, 0.40, 0.60),
            Triple(0.10, 0.10, 0.05),
        )

        val slow = InsulinOriginMeter()
        val fast = InsulinOriginMeter()
        decisions.forEachIndexed { index, (finalU, modelU, floorU) ->
            slow.observe(sampleAt(t0 + index * 10 * minute, finalU, modelU, floorU))
            // Same decision, seen twice because the loop ran twice while it stood.
            fast.observe(sampleAt(t0 + index * 10 * minute, finalU, modelU, floorU))
            fast.observe(sampleAt(t0 + index * 10 * minute + 5 * minute, finalU, modelU, floorU))
        }

        val slowReading = slow.read(t0 + 40 * minute)
        val fastReading = fast.read(t0 + 40 * minute)

        assertEquals(decisions.size, slowReading.tickCount)
        assertEquals(2 * decisions.size, fastReading.tickCount)
        // Totals are tick-weighted: doubling the cadence doubles them for the same therapy.
        assertEquals(2.0 * slowReading.tickWeightedU, fastReading.tickWeightedU, 1e-9)
        assertEquals(2.0 * slowReading.modelOriginU, fastReading.modelOriginU, 1e-9)
        assertEquals(2.0 * slowReading.floorOriginU, fastReading.floorOriginU, 1e-9)
        // Shares are ratios over the same ticks, so they do not move.
        assertEquals(slowReading.modelOriginShare!!, fastReading.modelOriginShare!!, 1e-12)
        assertEquals(slowReading.floorOriginShare!!, fastReading.floorOriginShare!!, 1e-12)
        assertEquals(10 * minute, slowReading.tickGapMedianMs)
        assertEquals(5 * minute, fastReading.tickGapMedianMs)
    }

    /** The renamed keys must be the ones exported, and the source must say why they moved. */
    @Test
    fun exportNamesTheTotalsTickWeighted() {
        val meter = InsulinOriginMeter()
        meter.observe(sampleAt(t0, finalU = 0.5, modelOutputU = 0.0, autodriveFloorU = 0.5))
        meter.observe(sampleAt(t0 + minute, finalU = 0.5, modelOutputU = 0.0, autodriveFloorU = 0.5))

        val json = meter.read(t0 + minute).toJsonObject()
        assertEquals("insulin_origin_meter_v2", json.getString("source"))
        assertTrue(json.has("tick_weighted_u"))
        assertTrue(json.has("model_zero_tick_weighted_u"))
        assertTrue(json.has("window_span_ms"))
        assertTrue(json.has("tick_gap_median_ms"))
        assertTrue(!json.has("delivered_u"), "delivered_u claimed to be enacted insulin, it must be gone")
        assertTrue(!json.has("model_zero_delivered_u"), "model_zero_delivered_u must be gone too")
        assertEquals(1.0, json.getDouble("tick_weighted_u"), 1e-9)
        assertEquals(minute, json.getLong("tick_gap_median_ms"))
        assertEquals(minute, json.getLong("window_span_ms"))
    }
}
