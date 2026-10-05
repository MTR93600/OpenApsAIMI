package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ISF.CommandedIsf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DecideAimiDecisionContextTest {

    @Test
    fun factoryCopiesTheSameFieldsAndLeavesTheRaFloorNull() {
        IsfSourceTelemetry.recordProfileStatic(50.0)
        CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = 36.6,
            profileIsfMgdlPerU = 60.0,
        )
        val ctx = decideAimiDecisionContext(
            eventId = "evt_1",
            timestamp = 10L,
            trigger = "Routine_Cycle",
            profileIsfMgdl = 40.0,
            profileBasalUph = 1.0,
            currentBgMgdl = 150.0,
            cobG = 0.0,
            iobU = 1.0,
            estimatedRaMgdlPerMin = 2.0,
            sensitivityRatioR = 0.9,
            isfShadowSMgdl = 44.0,
            sensitivityObservations = 3,
        )
        val base = ctx.baseline_state
        assertEquals("evt_1", ctx.event_id)
        assertEquals(10L, ctx.timestamp)
        assertEquals("Routine_Cycle", ctx.trigger)
        assertEquals(40.0, base.profile_isf_mgdl)
        assertEquals(40.0, base.command_isf_mgdl)
        assertEquals(1.0, base.profile_basal_uph)
        assertEquals(150.0, base.current_bg_mgdl)
        assertEquals(0.0, base.cob_g)
        assertEquals(1.0, base.iob_u)
        assertEquals(50.0, base.profile_isf_static_mgdl)
        assertEquals(36.6, base.isf_pre_floor_mgdl)
        assertEquals(2.0, base.estimated_ra_mgdl_per_min)
        assertEquals(0.9, base.sensitivity_ratio_r)
        assertEquals(44.0, base.isf_shadow_s_mgdl)
        assertEquals(3, base.sensitivity_observations)
        assertNull(base.htr_ra_floor_mgdl_per_min)
    }

    @AfterTest
    fun leaveTelemetryAsAndroidStartsIt() {
        IsfSourceTelemetry.reset()
        CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = Double.NaN,
            profileIsfMgdlPerU = 0.0,
        )
    }
}
