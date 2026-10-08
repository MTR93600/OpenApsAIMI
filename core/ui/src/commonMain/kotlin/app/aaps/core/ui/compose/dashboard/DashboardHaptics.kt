package app.aaps.core.ui.compose.dashboard

/**
 * Platform haptic feedback for dashboard interactions.
 *
 * Android: `View.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)`.
 * iOS: `UIImpactFeedbackGenerator` (or no-op when unavailable).
 *
 * The no-op default keeps previews and JVM tests silent.
 */
interface DashboardHaptics {
    fun vibrate()
}

/** No-op [DashboardHaptics] for previews, tests, and platforms without haptics. */
object NoOpDashboardHaptics : DashboardHaptics {
    override fun vibrate() = Unit
}
