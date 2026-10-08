package app.aaps.core.ui.compose.dashboard

import androidx.compose.runtime.compositionLocalOf

/**
 * Listener for dashboard quick actions and the AIMI pulse card.
 *
 * Ported from `plugins/main` `CircleTopDashboardView.CircleTopActionListener`
 * so [DashboardHeroCommands] can live in commonMain.
 */
interface DashboardHeroActionListener {
    fun onAimiAdvisorClicked()
    fun onAdjustClicked()
    fun onAimiPreferencesClicked()
    fun onStatsClicked()
    fun onAimiPulseClicked()
}

/**
 * Actions for the AIMI dashboard hero in Compose (loop / context + tiles).
 */
interface DashboardHeroCommands : DashboardHeroActionListener {
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
