package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two rules of [PhysioPipeline] that can poison the rolling baseline without anyone noticing:
 * which HRV metric a run reports, and what happens to a raw read that came back empty.
 *
 * Both are pure, so they are tested on their own rather than through the whole run.
 */
class PhysioPipelineTest {

    // ═══════════════════════════════════════════════════════════════════════
    // HRV METRIC
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `android readings report RMSSD`() {
        val samples = listOf(
            HRVDataMTR(timestamp = 1_000, rmssd = 42.0),
            HRVDataMTR(timestamp = 2_000, rmssd = 44.0),
        )
        assertEquals(HrvMetric.RMSSD, PhysioPipeline.hrvMetricOf(samples, HrvMetric.RMSSD))
    }

    @Test
    fun `iphone readings report SDNN`() {
        val samples = listOf(
            HRVDataMTR(timestamp = 1_000, sdnn = 60.0, metric = HrvMetric.SDNN),
            HRVDataMTR(timestamp = 2_000, sdnn = 65.0, metric = HrvMetric.SDNN),
        )
        assertEquals(HrvMetric.SDNN, PhysioPipeline.hrvMetricOf(samples, HrvMetric.RMSSD))
    }

    /**
     * The one that matters most. A tick with no HRV must not look like a metric change, or the
     * baseline would drop a history that is still good and the person would start from cold.
     */
    @Test
    fun `no readings keeps the metric the baseline already holds`() {
        assertEquals(HrvMetric.SDNN, PhysioPipeline.hrvMetricOf(emptyList(), HrvMetric.SDNN))
        assertEquals(HrvMetric.RMSSD, PhysioPipeline.hrvMetricOf(emptyList(), HrvMetric.RMSSD))
    }

    @Test
    fun `readings that all hold zero keep the metric the baseline already holds`() {
        val samples = listOf(
            HRVDataMTR(timestamp = 1_000, sdnn = 0.0, metric = HrvMetric.SDNN),
            HRVDataMTR(timestamp = 2_000, sdnn = 0.0, metric = HrvMetric.SDNN),
        )
        assertEquals(HrvMetric.RMSSD, PhysioPipeline.hrvMetricOf(samples, HrvMetric.RMSSD))
    }

    @Test
    fun `the newest reading with a value decides`() {
        val samples = listOf(
            HRVDataMTR(timestamp = 1_000, sdnn = 60.0, metric = HrvMetric.SDNN),
            HRVDataMTR(timestamp = 2_000, sdnn = 0.0, metric = HrvMetric.SDNN),
        )
        assertEquals(HrvMetric.SDNN, PhysioPipeline.hrvMetricOf(samples, HrvMetric.RMSSD))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ENRICHMENT OF AN EMPTY READ
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `a read that holds data is never touched`() {
        val raw = RawPhysioDataMTR(steps = 5_000)
        assertSame(raw, PhysioPipeline.enrichRaw(raw, ambientHeartRateBpm = 70, ambientSteps = 9_000))
    }

    @Test
    fun `an empty read with nothing to merge stays empty`() {
        val raw = RawPhysioDataMTR()
        val merged = PhysioPipeline.enrichRaw(raw, ambientHeartRateBpm = 0, ambientSteps = 0)
        assertSame(raw, merged)
        assertTrue(!merged.hasAnyData())
    }

    @Test
    fun `an empty read takes the phone database vitals`() {
        val merged = PhysioPipeline.enrichRaw(
            RawPhysioDataMTR(),
            ambientHeartRateBpm = 68,
            ambientSteps = 4_200,
        )
        assertEquals(68, merged.ambientHeartRateBpm)
        assertEquals(4_200, merged.ambientStepsAggregated)
        assertTrue(merged.hasAnyData())
    }

    @Test
    fun `one ambient reading alone is enough to merge`() {
        val onlySteps = PhysioPipeline.enrichRaw(RawPhysioDataMTR(), ambientHeartRateBpm = 0, ambientSteps = 300)
        assertEquals(300, onlySteps.ambientStepsAggregated)
        assertEquals(0, onlySteps.ambientHeartRateBpm)

        val onlyHr = PhysioPipeline.enrichRaw(RawPhysioDataMTR(), ambientHeartRateBpm = 72, ambientSteps = 0)
        assertEquals(72, onlyHr.ambientHeartRateBpm)
        assertEquals(0, onlyHr.ambientStepsAggregated)
    }
}
