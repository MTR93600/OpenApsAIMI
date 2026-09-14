package app.aaps.plugins.main.general.dashboard

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DashboardV2ToolActionTest {

    @Test
    fun `catalog contains ten general and four AIMI actions in display order`() {
        assertThat(DashboardV2ToolAction.entries).hasSize(14)
        assertThat(DashboardV2ToolAction.general).containsExactly(
            DashboardV2ToolAction.ACTIONS,
            DashboardV2ToolAction.RAPID_ACTING,
            DashboardV2ToolAction.PROFILE,
            DashboardV2ToolAction.AUTOMATION,
            DashboardV2ToolAction.NSCLIENT,
            DashboardV2ToolAction.TIDEPOOL,
            DashboardV2ToolAction.XDRIP,
            DashboardV2ToolAction.MAINTENANCE,
            DashboardV2ToolAction.XDRIP_BG,
            DashboardV2ToolAction.SENSOR,
        ).inOrder()
        assertThat(DashboardV2ToolAction.aimi).containsExactly(
            DashboardV2ToolAction.ADVISOR,
            DashboardV2ToolAction.MEAL_ADVISOR,
            DashboardV2ToolAction.AIMI_CONTEXT,
            DashboardV2ToolAction.AUDITOR_REPORT,
        ).inOrder()
    }

    @Test
    fun `general actions map to existing elements and compose plugins`() {
        assertThat(DashboardV2ToolAction.ACTIONS.destination)
            .isEqualTo(DashboardV2ToolDestination.Actions)
        assertThat(DashboardV2ToolAction.RAPID_ACTING.destination)
            .isEqualTo(DashboardV2ToolDestination.Element(ElementType.INSULIN_MANAGEMENT))
        assertThat(DashboardV2ToolAction.PROFILE.destination)
            .isEqualTo(DashboardV2ToolDestination.Element(ElementType.PROFILE_MANAGEMENT))
        assertThat(DashboardV2ToolAction.AUTOMATION.destination)
            .isEqualTo(DashboardV2ToolDestination.Element(ElementType.AUTOMATION_MANAGEMENT))
        assertThat(DashboardV2ToolAction.MAINTENANCE.destination)
            .isEqualTo(DashboardV2ToolDestination.Element(ElementType.MAINTENANCE))
        assertThat(DashboardV2ToolAction.NSCLIENT.destination)
            .isEqualTo(DashboardV2ToolDestination.Plugin("NSClientV3Plugin"))
        assertThat(DashboardV2ToolAction.TIDEPOOL.destination)
            .isEqualTo(DashboardV2ToolDestination.Plugin("TidepoolPlugin"))
        assertThat(DashboardV2ToolAction.XDRIP.destination)
            .isEqualTo(DashboardV2ToolDestination.Plugin("XdripPlugin"))
        assertThat(DashboardV2ToolAction.XDRIP_BG.destination)
            .isEqualTo(DashboardV2ToolDestination.Plugin("XdripSourcePlugin"))
        assertThat(DashboardV2ToolAction.SENSOR.destination)
            .isEqualTo(DashboardV2ToolDestination.Element(ElementType.BGSOURCE))
    }

    @Test
    fun `AIMI actions carry their required protection`() {
        assertThat(DashboardV2ToolAction.ADVISOR.aimiProtection())
            .isEqualTo(ProtectionCheck.Protection.PREFERENCES)
        assertThat(DashboardV2ToolAction.MEAL_ADVISOR.aimiProtection())
            .isEqualTo(ProtectionCheck.Protection.BOLUS)
        assertThat(DashboardV2ToolAction.AIMI_CONTEXT.aimiProtection())
            .isEqualTo(ProtectionCheck.Protection.BOLUS)
        assertThat(DashboardV2ToolAction.AUDITOR_REPORT.aimiProtection())
            .isEqualTo(ProtectionCheck.Protection.NONE)
    }

    @Test
    fun `plugin actions are available only when their compose plugin is present`() {
        assertThat(DashboardV2ToolAction.NSCLIENT.isAvailable(emptySet())).isFalse()
        assertThat(DashboardV2ToolAction.NSCLIENT.isAvailable(setOf("NSClientV3Plugin"))).isTrue()
        assertThat(DashboardV2ToolAction.XDRIP_BG.isAvailable(setOf("XdripPlugin"))).isFalse()
        assertThat(DashboardV2ToolAction.XDRIP_BG.isAvailable(setOf("XdripSourcePlugin"))).isTrue()
        assertThat(DashboardV2ToolAction.ACTIONS.isAvailable(emptySet())).isTrue()
        assertThat(DashboardV2ToolAction.ADVISOR.isAvailable(emptySet())).isTrue()
        assertThat(DashboardV2ToolAction.SENSOR.isAvailable(emptySet())).isTrue()
    }

    private fun DashboardV2ToolAction.aimiProtection(): ProtectionCheck.Protection =
        (destination as DashboardV2ToolDestination.AimiActivity).protection
}
