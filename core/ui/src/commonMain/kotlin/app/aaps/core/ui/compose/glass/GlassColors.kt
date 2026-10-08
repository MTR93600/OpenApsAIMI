package app.aaps.core.ui.compose.glass

import androidx.compose.ui.graphics.Color

/**
 * Shared color tokens for the Glass visual language, used by both the Glass dashboard skin
 * (`:plugins:main`) and Glass-restyled screens in `:ui`. Values match the dashboard's existing
 * Glass files exactly (GlassContainer, GlassLoopDashboardScreen) — kept here so screens outside
 * `:plugins:main` can reuse them without a new inter-module dependency.
 */
object GlassColors {
    val skyBlue = Color(0xFF38BDF8)
    val skyBlueDark = Color(0xFF0284C7)
    val emerald = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
    val indigo = Color(0xFF6366F1)

    fun textBright(isDark: Boolean): Color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    fun textMuted(isDark: Boolean): Color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    fun cardBgStart(isDark: Boolean): Color = if (isDark) Color(0x24FFFFFF) else Color(0xF5FFFFFF)
    fun cardBgEnd(isDark: Boolean): Color = if (isDark) Color(0x0EFFFFFF) else Color(0xE0EEF2FA)
    fun borderCard(isDark: Boolean): Color = if (isDark) Color(0x26FFFFFF) else Color(0xB8CBD5E1)
    fun screenBgTop(isDark: Boolean): Color = if (isDark) Color(0xFF070E1B) else Color(0xFFF1F5F9)
    fun screenBgBottom(isDark: Boolean): Color = if (isDark) Color(0xFF0B1424) else Color(0xFFE8EEF8)
}
