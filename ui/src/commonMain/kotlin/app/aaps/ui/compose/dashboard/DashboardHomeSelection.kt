package app.aaps.ui.compose.dashboard

/**
 * Shared persisted choice for the home renderer, stored in `StringKey.GeneralDashboardSkin`.
 *
 * [AIMI_DASHBOARD] is the AIMI Compose dashboard of `dev_OAPSAIMI` (the `SkinMinimal` home) and the
 * default of a new install. A stored value that this build does not know keeps the safe Overview.
 */
enum class DashboardHomeSelection(val preferenceValue: String) {
    OVERVIEW("overview"),
    AIMI_DASHBOARD("aimi_dashboard"),
    DASHBOARD_V2("dashboard_v2"),
    GLASS("glass");

    companion object {
        fun resolve(value: String): DashboardHomeSelection =
            entries.firstOrNull { it.preferenceValue == value }
                ?: if (value == LEGACY_COMBINED_VALUE) DASHBOARD_V2 else OVERVIEW

        private const val LEGACY_COMBINED_VALUE = "kmp_v2_glass"
    }
}
