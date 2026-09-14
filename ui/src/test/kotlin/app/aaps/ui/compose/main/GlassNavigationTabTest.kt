package app.aaps.ui.compose.main

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassNavigationTabTest {

    @Test
    fun `navigation exposes the fixed six tabs in display order`() {
        assertThat(GlassNavigationTab.fixedOrder).containsExactly(
            GlassNavigationTab.TREATMENTS,
            GlassNavigationTab.PROFILES,
            GlassNavigationTab.BOLUS,
            GlassNavigationTab.PUMP,
            GlassNavigationTab.SCENARIOS,
            GlassNavigationTab.MANAGEMENT,
        ).inOrder()
        assertThat(GlassNavigationTab.entries).hasSize(6)
    }

    @Test
    fun `only the mutating tabs require a master or paired client`() {
        // Locks in the fix for a Major finding: Bolus must be gated the same way Treatments/Scenarios are —
        // a non-paired client has no channel to actually deliver a dose, so the calculator must not be
        // reachable. Profiles/Pump/Management stay enabled regardless.
        assertThat(MASTER_ONLY_TABS).containsExactly(
            GlassNavigationTab.TREATMENTS,
            GlassNavigationTab.SCENARIOS,
            GlassNavigationTab.BOLUS,
        )
    }
}
