package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.runtime.compositionLocalOf
import app.aaps.plugins.main.general.dashboard.views.CircleTopActionListener

/**
 * Actions for the AIMI dashboard hero in Compose (loop / context + [CircleTopActionListener] tiles).
 */
interface DashboardHeroCommands : CircleTopActionListener {
    fun openLoopDialogFromHero()
    fun openContextFromBadge()
    fun onAimiAdaptationClicked()
    fun openStatsScreen()
    fun openTreatmentsScreen()
    fun openCarbsEntry()
    fun openBolusWizard()
    fun openQuickWizardManagement()
    fun openTempTargetManagement()
}

object NoopDashboardHeroCommands : DashboardHeroCommands {
    override fun openLoopDialogFromHero() {}
    override fun openContextFromBadge() {}
    override fun onAimiAdaptationClicked() {}
    override fun onAimiAdvisorClicked() {}
    override fun onAdjustClicked() {}
    override fun onAimiPreferencesClicked() {}
    override fun onStatsClicked() {}
    override fun onAimiPulseClicked() {}
    override fun openStatsScreen() {}
    override fun openTreatmentsScreen() {}
    override fun openCarbsEntry() {}
    override fun openBolusWizard() {}
    override fun openQuickWizardManagement() {}
    override fun openTempTargetManagement() {}
}

val LocalDashboardHeroCommands = compositionLocalOf<DashboardHeroCommands> { NoopDashboardHeroCommands }
