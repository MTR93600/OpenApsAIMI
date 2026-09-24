package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TapSitePeakShiftTest {

    @Test
    fun `fresh site has no shift`() {
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(0f), 0.001)
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(1.9f), 0.001)
    }

    @Test
    fun `aged site ramps then caps`() {
        assertEquals(0.0, TapSitePeakShift.minutesForSiteAge(2f), 0.001)
        assertEquals(0.45, TapSitePeakShift.minutesForSiteAge(3f), 0.001)
        assertEquals(5.0, TapSitePeakShift.minutesForSiteAge(30f), 0.001)
    }

    /**
     * The site shift must reach the peak governor and be reported there. The intelligence
     * snapshot used to leave the site argument at its default, so `site` was always 0.0 in the
     * `PEAK_GOV` line even for an old cannula.
     */
    @Test
    fun `site shift reaches the peak governor`() {
        val siteShift = TapSitePeakShift.minutesForSiteAge(6f)
        assertTrue(siteShift > 0.0, "a 6 day old site must ask for a later peak")
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 75,
            physioPeakShiftMinutes = 0,
            sitePeakShiftMinutes = siteShift,
            pkpdLearnedPeak = 75.0,
            pkpdEnabled = true,
            governorEnabled = true,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(siteShift, r.peakSite, 1e-9)
        assertTrue(r.effectivePeakMinutes > 75.0, "the effective peak must move, got ${r.effectivePeakMinutes}")
    }
}
