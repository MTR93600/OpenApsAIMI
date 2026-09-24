package app.aaps.plugins.aps.openAPSAIMI.learning

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BasalAdaptiveMultiplierTest {

    @Test
    fun defensive_path_uses_min() {
        assertEquals(0.88, BasalAdaptiveMultiplier.combine(hMult = 0.88, nMult = 1.20), 0.0001)
        assertEquals(0.92, BasalAdaptiveMultiplier.combine(hMult = 1.10, nMult = 0.92), 0.0001)
    }

    @Test
    fun boost_path_blends_instead_of_max_pinning_at_h_ceiling() {
        val combined = BasalAdaptiveMultiplier.combine(hMult = 2.32, nMult = 1.0)
        assertEquals(1.726, combined, 0.001)
    }

    @Test
    fun equal_boosts_stay_at_least_neutral() {
        assertEquals(1.25, BasalAdaptiveMultiplier.combine(hMult = 1.25, nMult = 1.25), 0.0001)
    }
}
