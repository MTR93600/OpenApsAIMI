package app.aaps.ui.compose.dashboard

import app.aaps.core.keys.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardHomeSelectionTest {

    @Test
    fun overviewRemainsTheSafeDefaultAndUnknownValuesResolveToOverview() {
        assertEquals("overview", StringKey.GeneralDashboardSkin.defaultValue)
        assertEquals(DashboardHomeSelection.OVERVIEW, DashboardHomeSelection.resolve(""))
        assertEquals(DashboardHomeSelection.OVERVIEW, DashboardHomeSelection.resolve("unknown"))
    }

    @Test
    fun dashboardV2AndGlassHaveDistinctPreferenceValues() {
        assertEquals("dashboard_v2", DashboardHomeSelection.DASHBOARD_V2.preferenceValue)
        assertEquals("glass", DashboardHomeSelection.GLASS.preferenceValue)
        assertEquals(
            setOf("overview", "dashboard_v2", "glass"),
            StringKey.GeneralDashboardSkin.entries.keys,
        )
        assertEquals(
            DashboardHomeSelection.DASHBOARD_V2,
            DashboardHomeSelection.resolve("dashboard_v2"),
        )
        assertEquals(
            DashboardHomeSelection.GLASS,
            DashboardHomeSelection.resolve("glass"),
        )
    }

    @Test
    fun legacyCombinedChoiceKeepsUsersOnDashboardV2() {
        assertEquals(
            DashboardHomeSelection.DASHBOARD_V2,
            DashboardHomeSelection.resolve("kmp_v2_glass"),
        )
    }
}
