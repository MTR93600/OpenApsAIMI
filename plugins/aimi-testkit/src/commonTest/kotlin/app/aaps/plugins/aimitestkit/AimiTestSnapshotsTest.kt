package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.TimedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiTestSnapshotsTest {

    @Test
    fun empty_input_does_not_turn_missing_glucose_into_zero() {
        val snap = AimiTestSnapshots.emptyInput()
        assertTrue(snap.glucose.glucoseMgdl is TimedValue.Missing)
        assertNull(snap.glucose.glucoseMgdl.valueOrNull)
        assertNotEquals(0.0, snap.glucose.glucoseMgdl.valueOrNull)
    }

    @Test
    fun empty_input_carries_full_test_profile() {
        val snap = AimiTestSnapshots.emptyInput()
        assertEquals(100.0, snap.profile.profile.target_bg)
        assertEquals(50.0, snap.profile.profile.sens)
    }

    @Test
    fun empty_input_glucose_deltas_stay_missing() {
        val snap = AimiTestSnapshots.emptyInput()
        assertTrue(snap.glucose.delta is TimedValue.Missing)
        assertTrue(snap.glucose.shortAvgDelta is TimedValue.Missing)
        assertTrue(snap.glucose.longAvgDelta is TimedValue.Missing)
        assertTrue(snap.glucose.noise is TimedValue.Missing)
    }

    @Test
    fun empty_input_iob_history_defaults_empty() {
        val snap = AimiTestSnapshots.emptyInput()
        assertTrue(snap.insulin.iobHistory.isEmpty())
    }
}
