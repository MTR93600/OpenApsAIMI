package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.keys.BooleanNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction

/** One selectable compact pill for the Glass main screen — either a top-grid pill or a bottom-row pill. */
enum class GlassPillId {
    IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS,
    PUMP_RESERVOIR, CANNULA, BATTERY, SENSOR, LOOP_STATUS, ACTIVITY,
}

/** Where a pill renders: the always-shown top grid, or the optional bottom row. */
enum class GlassPillLocation { TOP_GRID, BOTTOM_ROW }

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
    val location: GlassPillLocation,
    /** Whether this pill is visible by default on an install that has never opened Personalize. */
    val defaultSelected: Boolean = false,
)

/**
 * Fixed, code-defined catalog of every selectable Glass pill. For `BOTTOM_ROW` entries, catalog order is
 * also the render order of the bottom row. `TOP_GRID` entries render in the fixed order their own
 * hand-written Composable calls appear in `StatusAgoraCard.kt`, independent of catalog order.
 */
val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label, GlassPillLocation.BOTTOM_ROW),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label, GlassPillLocation.BOTTOM_ROW),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_t_label, GlassPillLocation.BOTTOM_ROW),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label, GlassPillLocation.BOTTOM_ROW),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label, GlassPillLocation.BOTTOM_ROW),
    GlassPillCatalogEntry(GlassPillId.PUMP_RESERVOIR, R.string.dashboard_glass_personalize_pump_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.CANNULA, R.string.dashboard_glass_personalize_cannula_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.BATTERY, R.string.dashboard_glass_personalize_battery_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.SENSOR, R.string.dashboard_glass_personalize_sensor_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.LOOP_STATUS, R.string.dashboard_glass_loop_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.ACTIVITY, R.string.dashboard_glass_activity_label, GlassPillLocation.TOP_GRID, defaultSelected = true),
)

/** Parses a comma-joined [StringNonKey.GlassSelectedPills] value, dropping unknown or blank entries. */
fun parseSelectedGlassPills(rawValue: String): List<GlassPillId> =
    rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { GlassPillId.valueOf(name) }.getOrNull() }

/** Serializes a pill selection back to the comma-joined format [StringNonKey.GlassSelectedPills] stores. */
fun serializeSelectedGlassPills(ids: Set<GlassPillId>): String =
    ids.joinToString(",") { it.name }

/**
 * Adds every default-selected pill to an existing selection. Used by the one-time migration
 * ([BooleanNonKey.GlassTopGridPillsMigrated]) that repairs installs whose persisted [StringNonKey.GlassSelectedPills]
 * value predates the top-grid pills (their raw value has none of them, since it was written before those
 * IDs existed) — a no-op for a fresh install, since its value already has them all from the class default.
 */
fun withTopGridDefaultsMerged(currentIds: Set<GlassPillId>): Set<GlassPillId> =
    currentIds + GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }

/** Parses a comma-joined [StringNonKey.GlassSelectedTools] value, dropping unknown or blank entries. */
fun parseSelectedGlassTools(rawValue: String): List<DashboardV2ToolAction> =
    rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { DashboardV2ToolAction.valueOf(name) }.getOrNull() }

/** Serializes a tool-tile selection back to the comma-joined format [StringNonKey.GlassSelectedTools] stores. */
fun serializeSelectedGlassTools(actions: Set<DashboardV2ToolAction>): String =
    actions.joinToString(",") { it.name }
