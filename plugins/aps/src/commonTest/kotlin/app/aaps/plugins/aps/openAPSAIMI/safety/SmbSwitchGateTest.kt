package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Enable SMB" switched off still let Autodrive V3 deliver SMBs (support package of 2026-08-15:
 * 6 of 8 ticks). Every SMB writer now asks this gate. Only the meal prebolus stays allowed: the
 * person decided that a declared meal or the Meal Advisor needs its prebolus to cover the meal.
 *
 * Matching tests from `dev_OAPSAIMI` @ `509fd17800`, in `commonTest` with `kotlin.test`.
 */
class SmbSwitchGateTest {

    @Test
    fun switchOnAllowsEveryWriter() {
        for (owner in listOf("AutodriveV3", "DriftTerminator", "GlobalAIMI", "AiAuditor", "MealAdvisor")) {
            assertTrue(SmbSwitchGate.allows(smbEnabled = true, owner = owner), owner)
        }
    }

    @Test
    fun switchOffBlocksTheAutomaticWriters() {
        for (owner in listOf("AutodriveV3", "DriftTerminator", "GlobalAIMI", "AiAuditor", "AIMI", "HTR_RBT")) {
            assertFalse(SmbSwitchGate.allows(smbEnabled = false, owner = owner), owner)
        }
    }

    @Test
    fun switchOffKeepsTheMealPrebolus() {
        for (owner in listOf("MealAdvisor", "LegacyMealModes", "LegacyPrebolus")) {
            assertTrue(SmbSwitchGate.allows(smbEnabled = false, owner = owner), owner)
        }
    }
}
