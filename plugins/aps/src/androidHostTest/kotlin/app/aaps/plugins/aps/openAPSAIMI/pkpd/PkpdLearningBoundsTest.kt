package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PkpdLearningBoundsTest {

    @Test
    fun `legacy dia change rate is coerced to normal pace`() {
        assertEquals(0.5, PkpdLearningBounds.coerceMaxDiaChangePerDayH(3.0), 0.0)
        assertEquals(1.0, PkpdLearningBounds.coerceMaxDiaChangePerDayH(1.0), 0.0)
    }

    @Test
    fun `legacy peak change rate is coerced to normal pace`() {
        assertEquals(5.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(20.0), 0.0)
        assertEquals(10.0, PkpdLearningBounds.coerceMaxPeakChangePerDayMin(10.0), 0.0)
    }
}
