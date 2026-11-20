package app.aaps.ui.compose.main

import kotlin.test.Test
import kotlin.test.assertEquals

class GlassNavigationTabTest {

    @Test
    fun navigationExposesTheFixedFiveTabsInDisplayOrder() {
        // Five at most, so the labels stay readable. Profiles is the first entry of the Manage sheet.
        assertEquals(
            listOf(
                GlassNavigationTab.TREATMENTS,
                GlassNavigationTab.BOLUS,
                GlassNavigationTab.PUMP,
                GlassNavigationTab.SCENARIOS,
                GlassNavigationTab.MANAGEMENT,
            ),
            GlassNavigationTab.fixedOrder,
        )
        assertEquals(5, GlassNavigationTab.entries.size)
    }

    @Test
    fun onlyTheTabsThatChangeTherapyNeedAMasterOrPairedClient() {
        // A client that is not paired cannot deliver a dose, so Bolus is locked like Treatments and
        // Scenes. Pump and Manage stay enabled.
        assertEquals(
            setOf(GlassNavigationTab.TREATMENTS, GlassNavigationTab.SCENARIOS, GlassNavigationTab.BOLUS),
            MASTER_ONLY_TABS,
        )
    }
}
