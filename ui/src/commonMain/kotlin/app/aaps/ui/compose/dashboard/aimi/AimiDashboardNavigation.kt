package app.aaps.ui.compose.dashboard.aimi

import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.ClientRole
import app.aaps.core.ui.compose.dashboard.DashboardHeroCommands
import app.aaps.core.ui.compose.navigation.AimiDashboardDestination
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.ui.compose.dashboard.glass.DashboardV2ToolAction
import app.aaps.ui.compose.dashboard.glass.DashboardV2ToolDestination
import app.aaps.ui.compose.dashboard.glass.aimiContextNavigationRequest
import app.aaps.ui.compose.dashboard.glass.isAvailable

/**
 * [DashboardHeroCommands] of the AIMI dashboard, built on the shared navigation. Same targets as
 * `DashboardShellController.createHeroCommands` on `dev_OAPSAIMI`:
 *
 * | command                    | opens                                   |
 * |----------------------------|-----------------------------------------|
 * | glucose circle, loop pill  | running mode (`RUNNING_MODE`)           |
 * | context badge, Context     | AIMI context                            |
 * | adaptation card            | AIMI adaptation status                  |
 * | Advisor                    | AIMI profile advisor                    |
 * | Adjust, "last AIMI run"    | adjustment details                      |
 * | Meal                       | AIMI meal advisor                       |
 * | stats, treatments          | `STATISTICS`, `TREATMENTS`              |
 * | carbs, wizard, quick wizard, temp target | the normal dialogs and screens |
 *
 * The protection is asked by the navigator, as for the same screens anywhere else. The commands that
 * change the loop or start a treatment do nothing when [commandsAllowed] is false or [clientRole] does
 * not allow the screen, like the drawer and the treatment sheet. The AIMI screens need
 * `OpenAPSAIMIPlugin` ([availablePluginClassNames]); without it they are not offered.
 */
internal class AimiDashboardNavigationCommands(
    private val onNavigate: (NavigationRequest) -> Unit,
    private val commandsAllowed: Boolean,
    private val clientRole: ClientRole,
    private val availablePluginClassNames: Set<String>,
) : DashboardHeroCommands {

    /** True when this build has the AIMI auditor report (it comes with the AIMI plugin). */
    val auditorAvailable: Boolean = DashboardV2ToolAction.AUDITOR_REPORT.isAvailable(availablePluginClassNames)

    private fun element(type: ElementType) = onNavigate(NavigationRequest.Element(type))

    private fun command(type: ElementType) {
        if (commandsAllowed && clientRole.allows(type.role)) element(type)
    }

    private fun aimiTool(action: DashboardV2ToolAction) {
        val destination = action.destination as? DashboardV2ToolDestination.Navigate ?: return
        if (action.isAvailable(availablePluginClassNames)) onNavigate(destination.request)
    }

    private fun aimiDashboard(destination: AimiDashboardDestination) {
        if (DashboardV2ToolAction.AIMI_CONTEXT.isAvailable(availablePluginClassNames)) {
            onNavigate(NavigationRequest.AimiDashboard(destination))
        }
    }

    fun openAuditorReport() {
        if (auditorAvailable) onNavigate(NavigationRequest.AuditorReport)
    }

    override fun openLoopDialogFromHero() = command(ElementType.RUNNING_MODE)
    override fun openContextFromBadge() = aimiContext()
    override fun onStatsClicked() = aimiContext()
    override fun onAimiAdaptationClicked() = aimiDashboard(AimiDashboardDestination.ADAPTATION_STATUS)
    override fun onAimiAdvisorClicked() = aimiTool(DashboardV2ToolAction.ADVISOR)
    override fun onAdjustClicked() = aimiDashboard(AimiDashboardDestination.ADJUSTMENT_DETAILS)
    override fun onAimiPulseClicked() = aimiDashboard(AimiDashboardDestination.ADJUSTMENT_DETAILS)
    override fun onAimiPreferencesClicked() = aimiTool(DashboardV2ToolAction.MEAL_ADVISOR)
    override fun openStatsScreen() = element(ElementType.STATISTICS)
    override fun openTreatmentsScreen() = element(ElementType.TREATMENTS)
    override fun openCarbsEntry() = command(ElementType.CARBS)
    override fun openBolusWizard() = command(ElementType.BOLUS_WIZARD)
    override fun openQuickWizardManagement() = command(ElementType.QUICK_WIZARD_MANAGEMENT)
    override fun openTempTargetManagement() = command(ElementType.TEMP_TARGET_MANAGEMENT)

    private fun aimiContext() {
        if (DashboardV2ToolAction.AIMI_CONTEXT.isAvailable(availablePluginClassNames)) onNavigate(aimiContextNavigationRequest())
    }
}

/** The graph ranges of the AIMI dashboard, stored in `IntNonKey.RangeToDisplay`. Same values as DEV. */
internal object AimiDashboardGraphRange {

    const val DEFAULT = 6
    private val RANGES = listOf(6, 9, 12, 18, 24)

    /** A stored value that is not a range gives the default, as DEV `syncGraphRange` did. */
    fun clamp(hours: Int): Int = if (hours in RANGES) hours else DEFAULT

    /** The range a double tap shows: the next one, and after 24 h back to 6 h. */
    fun next(hours: Int): Int = RANGES.getOrNull(RANGES.indexOf(clamp(hours)) + 1) ?: DEFAULT
}

/** Pure rules of the hero. */
internal object AimiDashboardHeroRules {

    /** The pointer angle of the glucose ring for a 5 min change in mg/dL, as DEV. */
    fun noseAngle(deltaMgdl: Double?): Float {
        val delta = deltaMgdl ?: 0.0
        return when {
            delta > 10  -> 45f
            delta > 5   -> 20f
            delta > 2   -> 10f
            delta < -10 -> -45f
            delta < -5  -> -20f
            delta < -2  -> -10f
            else        -> 0f
        }
    }
}
