package app.aaps.plugins.aps.openAPSAIMI.physio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EndogenousBasalBridgePolicyTest {

    @Test
    fun bridge_rate_modest_above_profile() {
        val rate = EndogenousBasalBridgePolicy.computeBridgeRateUph(
            bgMgdl = 300.0,
            targetBgMgdl = 100.0,
            isfMgdlPerU = 40.0,
            profileBasalUph = 0.7,
            maxBasalUph = 4.0,
        )
        assertNotNull(rate)
        assertEquals(true, rate!! > 0.7 && rate < 2.0)
    }

    @Test
    fun bridge_skipped_near_target() {
        val rate = EndogenousBasalBridgePolicy.computeBridgeRateUph(
            bgMgdl = 104.0,
            targetBgMgdl = 100.0,
            isfMgdlPerU = 40.0,
            profileBasalUph = 0.7,
            maxBasalUph = 4.0,
        )
        assertNull(rate)
    }
}
