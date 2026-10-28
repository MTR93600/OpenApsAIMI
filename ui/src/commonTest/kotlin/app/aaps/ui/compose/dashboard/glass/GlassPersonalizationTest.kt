package app.aaps.ui.compose.dashboard.glass

import app.aaps.core.keys.StringNonKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassPersonalizationTest {

    @Test
    fun `serialize then parse round-trips a selection`() {
        val selection = setOf(GlassPillId.IOB, GlassPillId.LAST_CARBS)
        val serialized = serializeSelectedGlassPills(selection)
        assertEquals(selection, parseSelectedGlassPills(serialized).toSet())
    }

    @Test
    fun `parse drops unknown or malformed entries without crashing`() {
        val result = parseSelectedGlassPills("IOB,NOT_A_REAL_PILL,,TARGET")
        assertEquals(listOf(GlassPillId.IOB, GlassPillId.TARGET), result)
    }

    @Test
    fun `parse of empty string returns an empty list`() {
        assertTrue(parseSelectedGlassPills("").isEmpty())
    }

    @Test
    fun `all 11 catalog entries round-trip through serialize and parse`() {
        val allIds = GLASS_PILL_CATALOG.map { it.id }.toSet()
        assertEquals(11, allIds.size)
        val serialized = serializeSelectedGlassPills(allIds)
        assertEquals(allIds, parseSelectedGlassPills(serialized).toSet())
    }

    @Test
    fun `exactly the 6 top-grid pills default to selected`() {
        val defaultSelectedIds = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertEquals(
            setOf(
                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
            ),
            defaultSelectedIds,
        )
    }

    @Test
    fun `every catalog entry location matches whether it is a top-grid or bottom-row pill`() {
        // Locks in the split that keeps the bottom row from also rendering the always-visible top-grid
        // pills a second time: top-grid pills must never carry GlassPillLocation.BOTTOM_ROW.
        val bottomRowIds = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.BOTTOM_ROW }.map { it.id }.toSet()
        assertEquals(
            setOf(GlassPillId.IOB, GlassPillId.TARGET, GlassPillId.BASAL_RATE, GlassPillId.LAST_BOLUS, GlassPillId.LAST_CARBS),
            bottomRowIds,
        )
        val topGridIds = GLASS_PILL_CATALOG.filter { it.location == GlassPillLocation.TOP_GRID }.map { it.id }.toSet()
        assertEquals(
            setOf(
                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
            ),
            topGridIds,
        )
    }

    @Test
    fun `withTopGridDefaultsMerged adds missing top-grid defaults without dropping an existing custom selection`() {
        // Simulates an old install: only bottom-row pills were ever selectable, so its persisted value has
        // none of the top-grid IDs yet.
        val oldInstallSelection = setOf(GlassPillId.IOB, GlassPillId.TARGET)
        val merged = withTopGridDefaultsMerged(oldInstallSelection)
        assertEquals(
            setOf(
                GlassPillId.IOB, GlassPillId.TARGET,
                GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY,
                GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
            ),
            merged,
        )
    }

    @Test
    fun `withTopGridDefaultsMerged is a no-op on the default selection of a fresh install`() {
        val freshInstallSelection = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertEquals(freshInstallSelection, withTopGridDefaultsMerged(freshInstallSelection))
    }

    @Test
    fun `StringNonKey class default matches the default-selected pills of the catalog`() {
        // core:keys cannot reference GLASS_PILL_CATALOG (wrong dependency direction), so its class default
        // is a literal kept in sync by hand. This test checks that it really is.
        val expected = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertEquals(expected, parseSelectedGlassPills(StringNonKey.GlassSelectedPills.defaultValue).toSet())
    }

    @Test
    fun `serialize then parse round-trips a tool selection`() {
        val selection = setOf(DashboardV2ToolAction.ACTIONS, DashboardV2ToolAction.PROFILE)
        val serialized = serializeSelectedGlassTools(selection)
        assertEquals(selection, parseSelectedGlassTools(serialized).toSet())
    }

    @Test
    fun `tool parse drops unknown or malformed entries without crashing`() {
        val result = parseSelectedGlassTools("ACTIONS,NOT_A_REAL_TOOL,,PROFILE")
        assertEquals(listOf(DashboardV2ToolAction.ACTIONS, DashboardV2ToolAction.PROFILE), result)
    }

    @Test
    fun `tool parse keeps AUDITOR_REPORT saved by dev_OAPSAIMI`() {
        // A value stored by dev_OAPSAIMI names the auditor tile. It has a destination again, so it must stay.
        val result = parseSelectedGlassTools("ACTIONS,AUDITOR_REPORT,AIMI_CONTEXT")
        assertEquals(
            listOf(DashboardV2ToolAction.ACTIONS, DashboardV2ToolAction.AUDITOR_REPORT, DashboardV2ToolAction.AIMI_CONTEXT),
            result,
        )
    }

    @Test
    fun `tool parse of empty string returns an empty list`() {
        // "" is the value that means "user explicitly cleared every tile" (as opposed to a never-written
        // key, which reads back the class default instead) — the most load-bearing input for this function.
        assertTrue(parseSelectedGlassTools("").isEmpty())
    }

    @Test
    fun `StringNonKey tools default matches all DashboardV2ToolAction entries`() {
        val expected = DashboardV2ToolAction.entries.toSet()
        assertEquals(expected, parseSelectedGlassTools(StringNonKey.GlassSelectedTools.defaultValue).toSet())
    }
}
