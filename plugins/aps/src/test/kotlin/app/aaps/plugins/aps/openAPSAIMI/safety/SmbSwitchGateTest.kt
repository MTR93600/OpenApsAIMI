package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * "Enable SMB" switched off still let Autodrive V3 deliver SMBs (support package of 2026-08-15:
 * 6 of 8 ticks). Every SMB writer now asks this gate. Only the meal prebolus stays allowed: the
 * person decided that a declared meal or the Meal Advisor needs its prebolus to cover the meal.
 */
class SmbSwitchGateTest {

    @Test
    fun switchOnAllowsEveryWriter() {
        for (owner in listOf("AutodriveV3", "DriftTerminator", "GlobalAIMI", "AiAuditor", "MealAdvisor")) {
            assertThat(SmbSwitchGate.allows(smbEnabled = true, owner = owner)).isTrue()
        }
    }

    @Test
    fun switchOffBlocksTheAutomaticWriters() {
        for (owner in listOf("AutodriveV3", "DriftTerminator", "GlobalAIMI", "AiAuditor", "AIMI", "HTR_RBT")) {
            assertThat(SmbSwitchGate.allows(smbEnabled = false, owner = owner)).isFalse()
        }
    }

    @Test
    fun switchOffKeepsTheMealPrebolus() {
        for (owner in listOf("MealAdvisor", "LegacyMealModes", "LegacyPrebolus")) {
            assertThat(SmbSwitchGate.allows(smbEnabled = false, owner = owner)).isTrue()
        }
    }
}
