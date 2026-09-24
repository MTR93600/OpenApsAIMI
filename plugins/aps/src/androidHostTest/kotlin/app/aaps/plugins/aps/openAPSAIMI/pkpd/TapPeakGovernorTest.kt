package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TapPeakGovernorTest {

    @Test
    fun `governor off uses anchor within bounds`() {
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 75,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 90.0,
            pkpdEnabled = true,
            governorEnabled = false,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.55,
        )
        assertEquals(75.0, r.effectivePeakMinutes, 0.01)
        assertNull(r.logLine)
    }

    @Test
    fun `governor off includes site shift in anchor`() {
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 75,
            physioPeakShiftMinutes = 0,
            sitePeakShiftMinutes = 3.0,
            pkpdLearnedPeak = 90.0,
            pkpdEnabled = true,
            governorEnabled = false,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.55,
        )
        assertEquals(78.0, r.effectivePeakMinutes, 0.01)
        assertEquals(3.0, r.peakSite, 0.001)
    }

    @Test
    fun `governor on blends toward learned`() {
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 75,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 95.0,
            pkpdEnabled = true,
            governorEnabled = true,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(85.0, r.effectivePeakMinutes, 0.01)
        assertNotNull(r.logLine)
        assertEquals(true, r.appliedGovernor)
    }

    @Test
    fun `anchor floor 35 minutes when insulin prior is low`() {
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 20,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 90.0,
            pkpdEnabled = true,
            governorEnabled = false,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(35.0, r.effectivePeakMinutes, 0.01)
    }

    @Test
    fun `insulin prior change re anchors peakPrior field`() {
        val r50 = TapPeakGovernor.resolve(
            insulinPeakMinutes = 50,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 70.0,
            pkpdEnabled = true,
            governorEnabled = false,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.5,
        )
        val r80 = TapPeakGovernor.resolve(
            insulinPeakMinutes = 80,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 70.0,
            pkpdEnabled = true,
            governorEnabled = false,
            peakMinBound = 30.0,
            peakMaxBound = 240.0,
            learnedBlendWeight = 0.5,
        )
        assertEquals(50.0, r50.peakPrior, 0.01)
        assertEquals(80.0, r80.peakPrior, 0.01)
        assertEquals(50.0, r50.effectivePeakMinutes, 0.01)
        assertEquals(80.0, r80.effectivePeakMinutes, 0.01)
    }

    @Test
    fun `learned peak is clamped to bounds before blending`() {
        val r = TapPeakGovernor.resolve(
            insulinPeakMinutes = 45,
            physioPeakShiftMinutes = 0,
            pkpdLearnedPeak = 200.0,
            pkpdEnabled = true,
            governorEnabled = true,
            peakMinBound = 35.0,
            peakMaxBound = 90.0,
            learnedBlendWeight = 0.8,
        )
        // learned coerced to 90; blend = 45*0.2 + 90*0.8
        assertEquals(81.0, r.effectivePeakMinutes, 0.05)
    }
}
