package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlassPersonalizationTest {

    @Test
    fun `serialize then parse round-trips a selection`() {
        val selection = setOf(GlassPillId.IOB, GlassPillId.LAST_CARBS)
        val serialized = serializeSelectedGlassPills(selection)
        assertThat(parseSelectedGlassPills(serialized).toSet()).isEqualTo(selection)
    }

    @Test
    fun `parse drops unknown or malformed entries without crashing`() {
        val result = parseSelectedGlassPills("IOB,NOT_A_REAL_PILL,,TARGET")
        assertThat(result).containsExactly(GlassPillId.IOB, GlassPillId.TARGET)
    }

    @Test
    fun `parse of empty string returns an empty list`() {
        assertThat(parseSelectedGlassPills("")).isEmpty()
    }

    @Test
    fun `all 11 catalog entries round-trip through serialize and parse`() {
        val allIds = GLASS_PILL_CATALOG.map { it.id }.toSet()
        val serialized = serializeSelectedGlassPills(allIds)
        assertThat(parseSelectedGlassPills(serialized).toSet()).isEqualTo(allIds)
    }

    @Test
    fun `exactly the 6 top-grid pills default to selected`() {
        val defaultSelectedIds = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertThat(defaultSelectedIds).containsExactly(
            GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
            GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
        )
    }

    @Test
    fun `every catalog entry's location matches whether it is a top-grid or bottom-row pill`() {
        // Locks in the split that keeps the bottom row from also rendering the always-visible top-grid
        // pills a second time: top-grid pills must never carry GlassPillLocation.BOTTOM_ROW.
        val bottomRowIds = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.BOTTOM_ROW }.map { it.id }.toSet()
        assertThat(bottomRowIds).containsExactly(
            GlassPillId.IOB, GlassPillId.TARGET, GlassPillId.BASAL_RATE, GlassPillId.LAST_BOLUS, GlassPillId.LAST_CARBS,
        )
        val topGridIds = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.TOP_GRID }.map { it.id }.toSet()
        assertThat(topGridIds).containsExactly(
            GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
            GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
        )
    }

    @Test
    fun `withTopGridDefaultsMerged adds missing top-grid defaults without dropping an existing custom selection`() {
        // Simulates an old install: only bottom-row pills were ever selectable, so its persisted value has
        // none of the top-grid IDs yet.
        val oldInstallSelection = setOf(GlassPillId.IOB, GlassPillId.TARGET)
        val merged = withTopGridDefaultsMerged(oldInstallSelection)
        assertThat(merged).containsExactly(
            GlassPillId.IOB, GlassPillId.TARGET,
            GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
            GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
        )
    }

    @Test
    fun `withTopGridDefaultsMerged is a no-op on a fresh install's already-complete default selection`() {
        val freshInstallSelection = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertThat(withTopGridDefaultsMerged(freshInstallSelection)).isEqualTo(freshInstallSelection)
    }

    @Test
    fun `StringNonKey class default matches the catalog's default-selected pills`() {
        // core:keys cannot reference GLASS_PILL_CATALOG (wrong dependency direction), so its class default
        // is a hand-kept-in-sync literal — this test converts that "keep them in sync by hand" comment into
        // a compile-time-enforced (well, test-enforced) invariant instead of a hope.
        val expected = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertThat(parseSelectedGlassPills(StringNonKey.GlassSelectedPills.defaultValue).toSet()).isEqualTo(expected)
    }

    @Test
    fun `serialize then parse round-trips a tool selection`() {
        val selection = setOf(DashboardV2ToolAction.ACTIONS, DashboardV2ToolAction.PROFILE)
        val serialized = serializeSelectedGlassTools(selection)
        assertThat(parseSelectedGlassTools(serialized).toSet()).isEqualTo(selection)
    }

    @Test
    fun `tool parse drops unknown or malformed entries without crashing`() {
        val result = parseSelectedGlassTools("ACTIONS,NOT_A_REAL_TOOL,,PROFILE")
        assertThat(result).containsExactly(DashboardV2ToolAction.ACTIONS, DashboardV2ToolAction.PROFILE)
    }

    @Test
    fun `tool parse of empty string returns an empty list`() {
        // "" is the value that means "user explicitly cleared every tile" (as opposed to a never-written
        // key, which reads back the class default instead) — the most load-bearing input for this function.
        assertThat(parseSelectedGlassTools("")).isEmpty()
    }

    @Test
    fun `StringNonKey tools default matches all 13 DashboardV2ToolAction entries`() {
        val expected = DashboardV2ToolAction.entries.toSet()
        assertThat(parseSelectedGlassTools(StringNonKey.GlassSelectedTools.defaultValue).toSet()).isEqualTo(expected)
    }
}
