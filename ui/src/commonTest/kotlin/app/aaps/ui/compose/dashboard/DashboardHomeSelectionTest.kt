package app.aaps.ui.compose.dashboard

import app.aaps.core.keys.StringKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DashboardHomeSelectionTest {

    @Test
    fun aimiDashboardIsTheDefaultLikeDevOapsAimi() {
        assertEquals("aimi_dashboard", StringKey.GeneralDashboardSkin.defaultValue)
        assertEquals(
            DashboardHomeSelection.AIMI_DASHBOARD,
            DashboardHomeSelection.resolve(StringKey.GeneralDashboardSkin.defaultValue),
        )
    }

    @Test
    fun unknownValuesResolveToOverview() {
        assertEquals(DashboardHomeSelection.OVERVIEW, DashboardHomeSelection.resolve(""))
        assertEquals(DashboardHomeSelection.OVERVIEW, DashboardHomeSelection.resolve("unknown"))
    }

    @Test
    fun everyChoiceHasItsOwnPreferenceValueAndSettingsEntry() {
        assertEquals("overview", DashboardHomeSelection.OVERVIEW.preferenceValue)
        assertEquals("aimi_dashboard", DashboardHomeSelection.AIMI_DASHBOARD.preferenceValue)
        assertEquals("dashboard_v2", DashboardHomeSelection.DASHBOARD_V2.preferenceValue)
        assertEquals("glass", DashboardHomeSelection.GLASS.preferenceValue)
        // The settings list offers exactly the choices the home can draw.
        assertEquals(
            DashboardHomeSelection.entries.map { it.preferenceValue }.toSet(),
            StringKey.GeneralDashboardSkin.entries.keys,
        )
        DashboardHomeSelection.entries.forEach { selection ->
            assertEquals(selection, DashboardHomeSelection.resolve(selection.preferenceValue))
        }
    }

    @Test
    fun legacyCombinedChoiceKeepsUsersOnDashboardV2() {
        assertEquals(
            DashboardHomeSelection.DASHBOARD_V2,
            DashboardHomeSelection.resolve("kmp_v2_glass"),
        )
    }
}
