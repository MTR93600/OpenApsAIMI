package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The ISF and target levels of one tick, and the audit block built from them.
 *
 * The numbers come from two audited ticks of support package 1790063419538, as the design spec
 * quotes them: 1790021540129 (21 Sep, 22:12), where the floor holds the commanded ISF at the
 * profile value, and 1790013139101 (19:52), where it does not. A value the package does not quote
 * is marked where it is built.
 */
class AuditorProfileTickStateTest {

    /** 22:12 — the stress floor holds, so the commanded ISF is the profile ISF. */
    private fun tickOnFloor(keyOn: Boolean = true) = AuditorProfileTickState().apply {
        profileStaticIsfMgdl = 60.0
        dynamicIsfMgdl = 36.649
        commandIsfMgdl = 60.0
        commandPreFloorIsfMgdl = 36.649
        commandFloorMultiplier = 1.0
        profileTargetMgdl = 100.0
        tempTargetActive = false
        workingTargetMgdl = 100.0
        this.keyOn = keyOn
    }

    /** 19:52 — no floor: the commanded ISF is well under the profile ISF. */
    private fun tickOffFloor(keyOn: Boolean = true) = AuditorProfileTickState().apply {
        profileStaticIsfMgdl = 60.0
        // Built for this test: the package quotes the command and the pre-floor value, not the
        // dynamic one. A third value is needed to show the three levels do not collapse into one.
        dynamicIsfMgdl = 30.5
        commandIsfMgdl = 37.17
        commandPreFloorIsfMgdl = 37.17
        commandFloorMultiplier = 0.5
        profileTargetMgdl = 90.0
        tempTargetActive = false
        workingTargetMgdl = 90.0
        this.keyOn = keyOn
    }

    @Test
    fun `the command isf is not the profile isf`() {
        val levels = tickOffFloor().snapshotLevelsIfArmed()
        assertNotNull(levels)
        assertEquals(60.0, levels!!.isfProfileStatic!!, 1e-9)
        assertEquals(37.17, levels.isfCommand, 1e-9)
        assertEquals(0.6195, levels.isfCommandOverProfile!!, 1e-4)
    }

    @Test
    fun `the three isf levels stay apart`() {
        val levels = tickOffFloor().snapshotLevelsIfArmed()!!
        assertEquals(60.0, levels.isfProfileStatic!!, 1e-9)
        assertEquals(30.5, levels.isfDynamic, 1e-9)
        assertEquals(37.17, levels.isfCommand, 1e-9)
    }

    @Test
    fun `the floor flag is true when the floor raised the command`() {
        assertTrue(tickOnFloor().onProfileFloor)
        assertTrue(tickOnFloor().snapshotLevelsIfArmed()!!.isfOnProfileFloor)
    }

    @Test
    fun `the floor flag is false when the command is the value the chain asked for`() {
        assertFalse(tickOffFloor().onProfileFloor)
        assertFalse(tickOffFloor().snapshotLevelsIfArmed()!!.isfOnProfileFloor)
    }

    @Test
    fun `the floor flag is false when the pre floor value is unknown`() {
        val state = tickOnFloor().apply { commandPreFloorIsfMgdl = null }
        assertFalse(state.onProfileFloor)
    }

    @Test
    fun `the command over profile ratio is null when the profile isf is unknown`() {
        val state = tickOnFloor().apply { profileStaticIsfMgdl = null }
        assertNull(state.commandOverProfile)
        val levels = state.snapshotLevelsIfArmed()!!
        assertNull(levels.isfProfileStatic)
        assertNull(levels.isfCommandOverProfile)
    }

    @Test
    fun `the two target levels are told apart`() {
        val state = tickOnFloor().apply { workingTargetMgdl = 75.0 }
        val levels = state.snapshotLevelsIfArmed()!!
        assertEquals(100.0, levels.targetProfile, 1e-9)
        assertEquals(75.0, levels.targetWorking!!, 1e-9)
    }

    @Test
    fun `the working target is null when the tick ended before the engine set it`() {
        val state = tickOnFloor().apply { workingTargetMgdl = null }
        assertNull(state.snapshotLevelsIfArmed()!!.targetWorking)
        assertTrue(state.toJsonObject().getJSONObject("target").isNull("working_raw_mgdl"))
    }

    @Test
    fun `no levels are sent while the key is off`() {
        assertNull(tickOnFloor(keyOn = false).snapshotLevelsIfArmed())
    }

    @Test
    fun `the audit block carries the levels and the key state`() {
        val json = tickOnFloor(keyOn = false).toJsonObject()
        assertEquals("auditor_profile_factors_v1", json.getString("schema"))
        assertFalse(json.getBoolean("key_on"))
        assertEquals("no_proposal", json.getString("status"))

        val isf = json.getJSONObject("isf")
        assertEquals(60.0, isf.getDouble("profile_static_mgdl"), 1e-9)
        assertEquals(36.649, isf.getDouble("dynamic_raw_mgdl"), 1e-9)
        assertEquals(60.0, isf.getDouble("command_raw_mgdl"), 1e-9)
        assertEquals(36.649, isf.getDouble("command_pre_floor_mgdl"), 1e-9)
        assertEquals(1.0, isf.getDouble("command_over_profile"), 1e-9)
        assertTrue(isf.getBoolean("on_profile_floor"))
        assertEquals(1.0, isf.getDouble("floor_multiplier"), 1e-9)

        val target = json.getJSONObject("target")
        assertEquals(100.0, target.getDouble("profile_mgdl"), 1e-9)
        assertFalse(target.getBoolean("temp_target_active"))
        assertEquals(100.0, target.getDouble("working_raw_mgdl"), 1e-9)
    }

    @Test
    fun `the audit block says when the key is on`() {
        assertTrue(tickOnFloor(keyOn = true).toJsonObject().getBoolean("key_on"))
    }

    @Test
    fun `a tick that ended before the apply step reports the ISF as not reached and not applied`() {
        // This is what a T3C, a manual meal mode or a stale abort leaves behind: the early decision
        // exists and says 0.85, but the sensitivity was never touched. The shadow study joins on
        // exactly these two fields, so they must not claim more than happened.
        val state = AuditorProfileTickState().apply {
            keyOn = true
            isfDecision = IsfTickDecision(
                requested = 0.85,
                effective = 0.85,
                lowerBoundMgdl = null,
                workingRawMgdl = null,
                workingAdjustedMgdl = null,
                refusedBy = emptyList(),
                applied = false,
                ageMs = 600_608L,
            )
        }
        val isf = state.toJsonObject().getJSONObject("isf")
        assertFalse(isf.getBoolean("applied"))
        assertEquals("not_reached", isf.getString("path"))
        assertEquals(0.85, isf.getDouble("effective"), 1e-9)
        assertEquals(1.0, state.isfAppliedFactor, 1e-9)
    }

    @Test
    fun `the audit block carries the shortest time between two profile requests`() {
        assertEquals(
            AuditorProfileFactorLimits.REQUEST_MIN_INTERVAL_MS,
            AuditorProfileTickState().toJsonObject().getLong("request_min_interval_ms"),
        )
    }

    @Test
    fun `the audit block of an empty tick keeps every field with a null value`() {
        val json = AuditorProfileTickState().toJsonObject()
        val isf = json.getJSONObject("isf")
        assertTrue(isf.isNull("profile_static_mgdl"))
        assertTrue(isf.isNull("dynamic_raw_mgdl"))
        assertTrue(isf.isNull("command_raw_mgdl"))
        assertTrue(isf.isNull("command_pre_floor_mgdl"))
        assertTrue(isf.isNull("command_over_profile"))
        assertFalse(isf.getBoolean("on_profile_floor"))
        assertTrue(json.getJSONObject("target").isNull("profile_mgdl"))
    }
}
