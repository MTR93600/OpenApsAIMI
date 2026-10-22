package app.aaps.ui.compose.dashboard

/** Shared persisted choice for the home renderer. Unknown/future values retain safe Overview behavior. */
enum class DashboardHomeSelection(val preferenceValue: String) {
    OVERVIEW("overview"),
    DASHBOARD_V2("dashboard_v2"),
    GLASS("glass");

    companion object {
        fun resolve(value: String): DashboardHomeSelection =
            entries.firstOrNull { it.preferenceValue == value }
                ?: if (value == LEGACY_COMBINED_VALUE) DASHBOARD_V2 else OVERVIEW

        private const val LEGACY_COMBINED_VALUE = "kmp_v2_glass"
    }
}
