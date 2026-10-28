package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.ClientRole
import app.aaps.core.ui.compose.navigation.GlassDestination
import app.aaps.core.ui.compose.navigation.NavigationRequest

/**
 * [GlassHeroCommands] built on the shared navigation. Every command opens a Glass screen ported from
 * `dev_OAPSAIMI`, except Stats and Treatments, which open the normal screens as on DEV.
 *
 * | command              | opens                     | protection | uses the view model of     |
 * |----------------------|---------------------------|------------|----------------------------|
 * | openLoop             | Glass loop detail         | BOLUS      | `RUNNING_MODE`             |
 * | openLoopDashboard    | Glass loop dashboard      | none       |                            |
 * | openTarget           | Glass target detail       | BOLUS      | `TEMP_TARGET_MANAGEMENT`   |
 * | openInsulin          | Glass insulin detail      | BOLUS      | `INSULIN`                  |
 * | openPump             | Glass pump screen         | none       |                            |
 * | openCannula          | Glass cannula detail      | BOLUS      | `CANNULA_CHANGE` (fill)    |
 * | openBattery          | Glass battery screen      | none       |                            |
 * | openBasal            | Glass basal detail        | BOLUS      | `TEMP_BASAL`               |
 * | openSensorInsert     | Glass sensor insert       | BOLUS      | `SENSOR_INSERT` (care)     |
 * | openSensorQuality    | Glass sensor quality      | none       |                            |
 * | openStatsScreen      | `STATISTICS`              | none       |                            |
 * | openTreatmentsScreen | `TREATMENTS`              | none       |                            |
 *
 * The protection is [GlassDestination.protection], asked by the navigator before it opens the route.
 *
 * The commands that start a treatment or change the loop do nothing when [commandsAllowed] is false
 * (a client that is not paired with a master cannot send anything; the status lights hide the same
 * buttons), or when [clientRole] does not allow the normal screen ([GlassDestination.element]); the
 * drawer and the treatment sheet hide that screen for the same role. The information screens only
 * show values, so they open on every phone.
 *
 * @param commandsAllowed false on an unpaired client, see `MainViewModel.masterOrPairedClient`
 * @param clientRole this phone's role on its master; [ClientRole.Admin] on a master
 */
internal class GlassNavigationCommands(
    private val onNavigate: (NavigationRequest) -> Unit,
    private val commandsAllowed: Boolean,
    private val clientRole: ClientRole,
) : GlassHeroCommands {

    private fun element(type: ElementType) = onNavigate(NavigationRequest.Element(type))

    private fun glass(destination: GlassDestination) = onNavigate(NavigationRequest.Glass(destination))

    private fun command(destination: GlassDestination) {
        val role = destination.element?.role ?: ClientRole.ReadOnly
        if (commandsAllowed && clientRole.allows(role)) glass(destination)
    }

    override fun openLoop() = command(GlassDestination.LOOP_DETAIL)
    override fun openLoopDashboard() = glass(GlassDestination.LOOP_DASHBOARD)
    override fun openTarget() = command(GlassDestination.TARGET_DETAIL)
    override fun openInsulin() = command(GlassDestination.INSULIN_DETAIL)
    override fun openPump() = glass(GlassDestination.PUMP_DETAIL)
    override fun openCannula() = command(GlassDestination.CANNULA_DETAIL)
    override fun openBattery() = glass(GlassDestination.BATTERY_DETAIL)
    override fun openBasal() = command(GlassDestination.BASAL_DETAIL)
    override fun openSensorInsert() = command(GlassDestination.SENSOR_INSERT_DETAIL)
    override fun openSensorQuality() = glass(GlassDestination.SENSOR_QUALITY)
    override fun openStatsScreen() = element(ElementType.STATISTICS)
    override fun openTreatmentsScreen() = element(ElementType.TREATMENTS)
}

/**
 * Runs a Tools tile: [DashboardV2ToolDestination.Actions] opens the host's Manage sheet,
 * [DashboardV2ToolDestination.ActiveBgSource] the screen of the active BG source plugin (nothing when
 * there is none), and every other tile its [NavigationRequest].
 */
internal fun dispatchGlassToolAction(
    action: DashboardV2ToolAction,
    onNavigate: (NavigationRequest) -> Unit,
    onOpenActions: () -> Unit,
    bgSourcePluginClassName: String?,
) {
    when (val destination = action.destination) {
        DashboardV2ToolDestination.Actions        -> onOpenActions()
        DashboardV2ToolDestination.ActiveBgSource -> bgSourcePluginClassName?.let { onNavigate(NavigationRequest.Plugin(it)) }
        is DashboardV2ToolDestination.Navigate    -> onNavigate(destination.request)
    }
}
