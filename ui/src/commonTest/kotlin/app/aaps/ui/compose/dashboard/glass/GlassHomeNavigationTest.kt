package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.protection.ProtectionCheck
import app.aaps.core.keys.interfaces.ClientRole
import app.aaps.core.ui.compose.navigation.GlassDestination
import app.aaps.core.ui.compose.navigation.NavigationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the Glass home taps go: the ported Glass screens, and Stats / Treatments as on DEV. */
class GlassHomeNavigationTest {

    private val requests = mutableListOf<NavigationRequest>()

    private fun commands(commandsAllowed: Boolean = true, clientRole: ClientRole = ClientRole.Admin) =
        GlassNavigationCommands(requests::add, commandsAllowed, clientRole)

    private fun element(type: ElementType) = NavigationRequest.Element(type)
    private fun glass(destination: GlassDestination) = NavigationRequest.Glass(destination)

    @Test
    fun `each command opens its screen`() {
        val c = commands()
        c.openLoop()
        c.openLoopDashboard()
        c.openTarget()
        c.openInsulin()
        c.openPump()
        c.openCannula()
        c.openBattery()
        c.openBasal()
        c.openSensorInsert()
        c.openSensorQuality()
        c.openStatsScreen()
        c.openTreatmentsScreen()
        assertEquals(
            listOf(
                glass(GlassDestination.LOOP_DETAIL),
                glass(GlassDestination.LOOP_DASHBOARD),
                glass(GlassDestination.TARGET_DETAIL),
                glass(GlassDestination.INSULIN_DETAIL),
                glass(GlassDestination.PUMP_DETAIL),
                glass(GlassDestination.CANNULA_DETAIL),
                glass(GlassDestination.BATTERY_DETAIL),
                glass(GlassDestination.BASAL_DETAIL),
                glass(GlassDestination.SENSOR_INSERT_DETAIL),
                glass(GlassDestination.SENSOR_QUALITY),
                element(ElementType.STATISTICS),
                element(ElementType.TREATMENTS),
            ),
            requests,
        )
    }

    @Test
    fun `an unpaired client cannot open the treatment dialogs`() {
        val c = commands(commandsAllowed = false)
        c.openLoop()
        c.openTarget()
        c.openInsulin()
        c.openCannula()
        c.openBasal()
        c.openSensorInsert()
        assertTrue(requests.isEmpty())
        // The information screens only show values: they open also on an unpaired client.
        c.openPump()
        c.openLoopDashboard()
        c.openStatsScreen()
        assertEquals(
            listOf(glass(GlassDestination.PUMP_DETAIL), glass(GlassDestination.LOOP_DASHBOARD), element(ElementType.STATISTICS)),
            requests,
        )
    }

    /** The DEV protection table: every screen that starts a treatment or changes the loop asks for BOLUS first. */
    @Test
    fun `treatment and loop screens need bolus protection and information screens none`() {
        val bolus = setOf(
            GlassDestination.INSULIN_DETAIL,
            GlassDestination.CANNULA_DETAIL,
            GlassDestination.BASAL_DETAIL,
            GlassDestination.SENSOR_INSERT_DETAIL,
            GlassDestination.TARGET_DETAIL,
            GlassDestination.LOOP_DETAIL,
        )
        GlassDestination.entries.forEach { destination ->
            val expected = if (destination in bolus) ProtectionCheck.Protection.BOLUS else ProtectionCheck.Protection.NONE
            assertEquals(expected, destination.protection, destination.name)
            assertEquals(destination in bolus, destination.element != null, destination.name)
        }
    }

    @Test
    fun `a client role below the normal screen's role cannot open the glass screen`() {
        // A meal assistant may give insulin, but not change the cannula, a temp basal, a target or the loop.
        val c = commands(clientRole = ClientRole.MealAssistant)
        c.openInsulin()
        c.openCannula()
        c.openBasal()
        c.openSensorInsert()
        c.openTarget()
        c.openLoop()
        c.openPump()
        assertEquals(listOf<NavigationRequest>(glass(GlassDestination.INSULIN_DETAIL), glass(GlassDestination.PUMP_DETAIL)), requests)

        requests.clear()
        val readOnly = commands(clientRole = ClientRole.ReadOnly)
        readOnly.openInsulin()
        readOnly.openLoop()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `tool tiles go to the actions sheet or the bg source or their request`() {
        var actionsOpened = 0
        dispatchGlassToolAction(DashboardV2ToolAction.ACTIONS, requests::add, { actionsOpened++ }, "DexcomPlugin")
        dispatchGlassToolAction(DashboardV2ToolAction.SENSOR, requests::add, { actionsOpened++ }, "DexcomPlugin")
        dispatchGlassToolAction(DashboardV2ToolAction.SENSOR, requests::add, { actionsOpened++ }, null)
        dispatchGlassToolAction(DashboardV2ToolAction.PROFILE, requests::add, { actionsOpened++ }, null)
        assertEquals(1, actionsOpened)
        assertEquals(
            listOf(NavigationRequest.Plugin("DexcomPlugin"), element(ElementType.PROFILE_MANAGEMENT)),
            requests,
        )
    }
}
