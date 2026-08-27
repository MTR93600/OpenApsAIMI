package app.aaps.plugins.aimitestkit

import app.aaps.plugins.aimicontracts.TimedValue
import kotlin.test.Test
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
    fun member_and_schedule_targets_stay_different() {
        val snap = AimiTestSnapshots.emptyInput()
        assertNotEquals(snap.profile.memberTargetBgMgdl, snap.profile.scheduleTargetBgMgdl)
    }
}
