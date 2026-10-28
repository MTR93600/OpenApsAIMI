package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.compositionLocalOf

/**
 * What the Glass home can open from its hero card, pills and top bar.
 *
 * The screens only call these functions. The host implements them by emitting a
 * [app.aaps.core.ui.compose.navigation.NavigationRequest] (or another route) for each one, so the
 * screens never hold a navigation controller. [NoopGlassHeroCommands] is the default for previews
 * and tests.
 */
interface GlassHeroCommands {
    fun openLoop()
    fun openLoopDashboard()
    fun openTarget()
    fun openInsulin()
    fun openPump()
    fun openCannula()
    fun openBattery()
    fun openBasal()
    fun openSensorInsert()
    fun openSensorQuality()
    fun openStatsScreen()
    fun openTreatmentsScreen()
}

object NoopGlassHeroCommands : GlassHeroCommands {
    override fun openLoop() {}
    override fun openLoopDashboard() {}
    override fun openTarget() {}
    override fun openInsulin() {}
    override fun openPump() {}
    override fun openCannula() {}
    override fun openBattery() {}
    override fun openBasal() {}
    override fun openSensorInsert() {}
    override fun openSensorQuality() {}
    override fun openStatsScreen() {}
    override fun openTreatmentsScreen() {}
}

val LocalGlassHeroCommands = compositionLocalOf<GlassHeroCommands> { NoopGlassHeroCommands }
