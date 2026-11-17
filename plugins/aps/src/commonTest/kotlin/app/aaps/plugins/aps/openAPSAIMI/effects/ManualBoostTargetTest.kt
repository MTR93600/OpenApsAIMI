package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals

/** During a manual Boost the target stays at 80 mg/dL at most; it is never raised. */
class ManualBoostTargetTest {

    @Test
    fun a_boost_brings_a_higher_target_down_to_80() {
        assertEquals(80.0, manualBoostTarget(100.0, manualBoostActive = true))
        assertEquals(80.0, manualBoostTarget(110.0, manualBoostActive = true))
    }

    @Test
    fun a_boost_keeps_a_target_already_lower_than_80() {
        // AIMI's own target when rising can be 70: the Boost never raises it
        assertEquals(70.0, manualBoostTarget(70.0, manualBoostActive = true))
    }

    @Test
    fun without_a_boost_the_target_is_unchanged() {
        assertEquals(110.0, manualBoostTarget(110.0, manualBoostActive = false))
    }
}
