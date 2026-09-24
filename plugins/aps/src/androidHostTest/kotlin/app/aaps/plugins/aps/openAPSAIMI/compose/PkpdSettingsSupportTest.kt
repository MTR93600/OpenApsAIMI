package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PkpdSettingsSupportTest {

    /**
     * Wave1: both Simple sliders share UI polarity — left = cautious (less insulin pressure),
     * right = more delivery. Tail storage prudence is inverted for the UI via [PkpdTailPrudence.readUiLevel].
     */
    @Test
    fun `tail UI left is cautious stronger damping, right allows more`() {
        // Storage level 1 = strongest damping; UI shows that as left (0).
        assertEquals(0.0, 1.0 - 1.0, 0.001)
        assertEquals(PkpdSmbTailDamping.DAMPING_STRONG, PkpdSmbTailDamping.dampingForSliderLevel(1.0), 0.001)
        assertEquals(PkpdSmbTailDamping.DAMPING_LIGHT, PkpdSmbTailDamping.dampingForSliderLevel(0.0), 0.001)
        // UI 0 (cautious) → storage 1 → strong damping (lower floor)
        assertEquals(PkpdSmbTailDamping.DAMPING_STRONG, PkpdTailPrudence.dampingForLevel(1.0 - 0.0), 0.001)
        // UI 1 (allow more) → storage 0 → light damping
        assertEquals(PkpdSmbTailDamping.DAMPING_LIGHT, PkpdTailPrudence.dampingForLevel(1.0 - 1.0), 0.001)
        assertTrue(
            PkpdTailPrudence.dampingForLevel(1.0) < PkpdTailPrudence.dampingForLevel(0.0),
            "Storage: higher prudence must store a lower damping floor",
        )
    }

    @Test
    fun `correction left cautious right aggressive matches insulin pressure polarity`() {
        val (minCautious, maxCautious) = PkpdCorrectionPrudence.factorsForLevel(0.0)
        val (minAgg, maxAgg) = PkpdCorrectionPrudence.factorsForLevel(1.0)
        assertTrue(minCautious > minAgg, "Cautious left must raise ISF min factor (less aggressive)")
        assertTrue(maxAgg > maxCautious, "Aggressive right must raise ISF max factor")
    }

    @Test
    fun `tail prudence round trip at center`() {
        val damping = PkpdTailPrudence.dampingForLevel(0.5)
        assertEquals(0.85, damping, 0.001)
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(damping), 0.001)
    }

    @Test
    fun `legacy tail damping 0_5 maps to center slider`() {
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(0.5), 0.001)
        assertEquals(0.85, PkpdSmbTailDamping.effectiveStoredValue(0.5), 0.001)
    }

    @Test
    fun `correction prudence round trip at center`() {
        val (min, max) = PkpdCorrectionPrudence.factorsForLevel(0.5)
        assertEquals(0.75, min, 0.001)
        assertEquals(1.25, max, 0.001)
        assertEquals(0.5, PkpdCorrectionPrudence.readLevelFromFactors(min, max), 0.001)
    }

    @Test
    fun `tail prudence does not map sub 0_72 to permissive max`() {
        assertEquals(0.5, PkpdTailPrudence.readLevelFromDamping(0.5), 0.001)
        assertEquals(1.0, PkpdTailPrudence.readLevelFromDamping(0.70), 0.02)
    }

    @Test
    fun `level 0_4 matches two steps left from center`() {
        val (min, max) = PkpdCorrectionPrudence.factorsForLevel(0.4)
        assertEquals(0.77, min, 0.01)
        assertEquals(1.22, max, 0.01)
    }

    @Test
    fun `slider endpoints map to expected preference values`() {
        assertEquals(0.85 to 1.10, PkpdCorrectionPrudence.factorsForLevel(0.0))
        assertEquals(0.65 to 1.40, PkpdCorrectionPrudence.factorsForLevel(1.0))
        assertEquals(0.92, PkpdTailPrudence.dampingForLevel(0.0), 0.001)
        assertEquals(0.70, PkpdTailPrudence.dampingForLevel(1.0), 0.001)
    }
}
