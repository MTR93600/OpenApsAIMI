package app.aaps.core.interfaces.overview

import kotlinx.coroutines.flow.StateFlow

/** How urgently a [PluginStatusBadge] should draw the eye. Maps to the existing snackbar colors. */
enum class PluginStatusLevel { IDLE, PROCESSING, READY, WARNING, ERROR }

/**
 * One plugin's status, shown as a small chip on the Overview screen next to the BG circle.
 *
 * [badgeCount] is 0 when there is nothing to count (e.g. while idle or processing); a positive
 * value renders as a small numeric badge. [statusMessage] is a short, already-localised summary
 * for the chip's own label - not a dialog body.
 */
data class PluginStatusBadge(
    val level: PluginStatusLevel,
    val badgeCount: Int = 0,
    val statusMessage: String = ""
)

/**
 * Lets exactly one plugin (today: the AIMI Auditor) contribute a status chip to the shared
 * Overview screen without `:ui` depending on that plugin's module - same shape as [Loop][app.aaps.core.interfaces.aps.Loop]
 * for the active APS algorithm. The implementation decides what "nothing to show" looks like
 * (typically [PluginStatusLevel.IDLE] with an empty message) rather than this interface offering
 * a null case, so a chip is always safe to render.
 */
interface PluginStatusBadgeSource {

    val badge: StateFlow<PluginStatusBadge>

    /** The chip was tapped - show whatever detail the plugin has for its current status. */
    fun onBadgeClick()
}
