package app.aaps.ui.compose.main

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassNavigationTabTest {

    @Test
    fun navigationExposesTheFixedSixTabsInDisplayOrder() {
        assertEquals(
            listOf(
                GlassNavigationTab.TREATMENTS,
                GlassNavigationTab.PROFILES,
                GlassNavigationTab.BOLUS,
                GlassNavigationTab.PUMP,
                GlassNavigationTab.SCENARIOS,
                GlassNavigationTab.MANAGEMENT,
            ),
            GlassNavigationTab.fixedOrder,
        )
        assertEquals(6, GlassNavigationTab.entries.size)
    }

    @Test
    fun onlyTheTabsThatChangeTherapyNeedAMasterOrPairedClient() {
        // A client that is not paired cannot deliver a dose, so Bolus is locked like Treatments and
        // Scenes. Profiles, Pump and Manage stay enabled.
        assertEquals(
            setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS),
            MASTER_ONLY_TABS,
        )
    }
}
