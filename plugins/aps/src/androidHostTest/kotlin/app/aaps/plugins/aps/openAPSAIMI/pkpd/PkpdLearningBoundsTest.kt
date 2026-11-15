package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PkpdLearningBoundsTest {

    @Test
    fun `a dia change rate above the faster pace is capped to it`() {
        assertEquals(2.0, PkpdLearningBounds.coerceMaxDiaChangePerDayH(3.0), 0.0)
        assertEquals(1.0, PkpdLearningBounds.coerceMaxDiaChangePerDayH(1.0), 0.0)
    }

    @Test
    fun `a peak change rate above the faster pace is capped to it`() {
        assertEquals(20.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(30.0), 0.0)
        assertEquals(10.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(10.0), 0.0)
    }
}
