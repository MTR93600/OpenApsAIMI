package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.ui.compose.navigation.NavigationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DashboardV2ToolActionTest {

    @Test
    fun `catalog contains ten general and four AIMI actions in display order`() {
        assertEquals(14, DashboardV2ToolAction.entries.size)
        assertEquals(
            listOf(
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
            ),
            DashboardV2ToolAction.general,
        )
        assertEquals(
            listOf(
                DashboardV2ToolAction.ADVISOR,
                DashboardV2ToolAction.MEAL_ADVISOR,
                DashboardV2ToolAction.AIMI_CONTEXT,
                DashboardV2ToolAction.AUDITOR_REPORT,
            ),
            DashboardV2ToolAction.aimi,
        )
    }

    @Test
    fun `general actions map to existing elements and compose plugins`() {
        assertEquals(DashboardV2ToolDestination.Actions, DashboardV2ToolAction.ACTIONS.destination)
        assertEquals(navigate(NavigationRequest.Element(ElementType.INSULIN_MANAGEMENT)), DashboardV2ToolAction.RAPID_ACTING.destination)
        assertEquals(navigate(NavigationRequest.Element(ElementType.PROFILE_MANAGEMENT)), DashboardV2ToolAction.PROFILE.destination)
        assertEquals(navigate(NavigationRequest.Element(ElementType.AUTOMATION_MANAGEMENT)), DashboardV2ToolAction.AUTOMATION.destination)
        assertEquals(navigate(NavigationRequest.Element(ElementType.MAINTENANCE)), DashboardV2ToolAction.MAINTENANCE.destination)
        assertEquals(navigate(NavigationRequest.Plugin("NSClientV3Plugin")), DashboardV2ToolAction.NSCLIENT.destination)
        assertEquals(navigate(NavigationRequest.Plugin("TidepoolPlugin")), DashboardV2ToolAction.TIDEPOOL.destination)
        assertEquals(navigate(NavigationRequest.Plugin("XdripPlugin")), DashboardV2ToolAction.XDRIP.destination)
        assertEquals(navigate(NavigationRequest.Plugin("XdripSourcePlugin")), DashboardV2ToolAction.XDRIP_BG.destination)
        assertEquals(DashboardV2ToolDestination.ActiveBgSource, DashboardV2ToolAction.SENSOR.destination)
    }

    @Test
    fun `AIMI actions open the AIMI plugin screens with their required protection`() {
        assertEquals(
            NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin", "aimi_profile_advisor_compose", ProtectionCheck.Protection.PREFERENCES),
            DashboardV2ToolAction.ADVISOR.aimiRequest(),
        )
        assertEquals(
            NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin", "aimi_meal_advisor_compose", ProtectionCheck.Protection.BOLUS),
            DashboardV2ToolAction.MEAL_ADVISOR.aimiRequest(),
        )
        assertEquals(
            NavigationRequest.PluginPreferences("OpenAPSAIMIPlugin", "aimi_context_compose", ProtectionCheck.Protection.BOLUS),
            DashboardV2ToolAction.AIMI_CONTEXT.aimiRequest(),
        )
    }

    @Test
    fun `auditor report tile asks the navigator for the report dialog`() {
        // The protection (none, as on dev_OAPSAIMI) is decided by the navigator for this request.
        assertEquals(navigate(NavigationRequest.AuditorReport), DashboardV2ToolAction.AUDITOR_REPORT.destination)
    }

    @Test
    fun `plugin actions are available only when their compose plugin is present`() {
        assertFalse(DashboardV2ToolAction.NSCLIENT.isAvailable(emptySet()))
        assertTrue(DashboardV2ToolAction.NSCLIENT.isAvailable(setOf("NSClientV3Plugin")))
        assertFalse(DashboardV2ToolAction.XDRIP_BG.isAvailable(setOf("XdripPlugin")))
        assertTrue(DashboardV2ToolAction.XDRIP_BG.isAvailable(setOf("XdripSourcePlugin")))
        assertTrue(DashboardV2ToolAction.ACTIONS.isAvailable(emptySet()))
        assertTrue(DashboardV2ToolAction.SENSOR.isAvailable(emptySet()))
    }

    @Test
    fun `AIMI actions are available only when the AIMI plugin is present`() {
        // Desktop has no OpenAPSAIMIPlugin: its AIMI tiles must be disabled, not dead.
        DashboardV2ToolAction.aimi.forEach { action ->
            assertFalse(action.isAvailable(setOf("NSClientV3Plugin")), action.name)
            assertTrue(action.isAvailable(setOf("OpenAPSAIMIPlugin")), action.name)
        }
    }

    @Test
    fun `every action has its own label`() {
        val labels = DashboardV2ToolAction.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    private fun navigate(request: NavigationRequest) = DashboardV2ToolDestination.Navigate(request)

    private fun DashboardV2ToolAction.aimiRequest(): NavigationRequest.PluginPreferences =
        (destination as DashboardV2ToolDestination.Navigate).request as NavigationRequest.PluginPreferences
}
