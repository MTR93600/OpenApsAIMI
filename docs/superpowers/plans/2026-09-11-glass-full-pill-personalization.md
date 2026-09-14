# Glass Full Pill Personalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user hide/show any of the 7 top-grid pills on the Glass main screen (Pump, Cannula,
Battery, Tools, Sensor, Loop, Activity), joining the 5 bottom-row pills that are already personalizable
today.

**Architecture:** Extend the existing `GlassPillId`/`GLASS_PILL_CATALOG` catalog with 7 new entries and a
`defaultSelected` flag, fix the class default of the `GlassSelectedPills` preference so fresh installs see
the 7 new pills by default (distinguishing "never touched" from "explicitly cleared to nothing"), then wrap
each of the 7 existing top-grid pill Composable calls in `StatusAgoraCard.kt` with `if (id in
selectedPills)`. Nothing about the top-grid pills' own icons, click handlers, or styling changes.

**Tech Stack:** Kotlin, Jetpack Compose, existing `StringNonKey` preference infrastructure.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-full-pill-personalization-design.md`

## Global Constraints

- Do not generalize the top grid into a data-driven loop — keep each pill's existing hand-written call,
  just gate it behind an `if`. The 7 pills are too heterogeneous (custom icons, `combinedClickable`
  short/long press, an animated pulse indicator, a no-click Activity pill) for a clean loop refactor.
- The 7 new catalog entries are `defaultSelected = true` (top-grid pills are visible today; making them
  default-hidden would be a silent regression). The 5 existing entries stay `defaultSelected = false`
  (unchanged bottom-row behavior).
- English-only string resources — do not touch `values-fr/` or `values-fr-rFR/` (per project convention:
  translations are handled separately).
- No change to the CENTER glucose readout — it is never optional.
- Run `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` after each task; run
  `./gradlew :plugins:main:compileFullDebugUnitTestKotlin --no-daemon` too whenever a task touches or adds
  a test file (this project's standing rule after a past incident where 2 tasks in a row broke the unit
  test compile without either task's own review catching it, because only the main-sourceset compile was
  checked).
- Do not run `git commit` — leave all work in the working tree for the user to review.

---

### Task 1: Data model — catalog, preference default, string resources

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalization.kt`
- Modify: `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt:43`
- Modify: `plugins/main/src/main/res/values/strings.xml` (English only)
- Modify: `plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalizationTest.kt`

**Interfaces:**
- Produces: 7 new `GlassPillId` enum values (`PUMP_RESERVOIR`, `CANNULA`, `BATTERY`, `TOOLS_SHORTCUT`,
  `SENSOR`, `LOOP_STATUS`, `ACTIVITY`) that Task 2 references directly by name in `if (GlassPillId.X in
  selectedPills)` guards. `GlassPillCatalogEntry` gains a `defaultSelected: Boolean = false` field (existing
  5 entries are unaffected since the param has a default).

- [ ] **Step 1: Add the 7 new enum values and the `defaultSelected` field**

Replace the whole `GlassPersonalization.kt` file content with:

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.keys.StringNonKey
import app.aaps.plugins.main.R

/** One selectable compact pill for the Glass main screen — either a top-grid pill or a bottom-row pill. */
enum class GlassPillId {
    IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS,
    PUMP_RESERVOIR, CANNULA, BATTERY, TOOLS_SHORTCUT, SENSOR, LOOP_STATUS, ACTIVITY,
}

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
    /** Whether this pill is visible by default on an install that has never opened Personalize. */
    val defaultSelected: Boolean = false,
)

val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_t_label),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label),
    GlassPillCatalogEntry(GlassPillId.PUMP_RESERVOIR, R.string.dashboard_glass_personalize_pump_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.CANNULA, R.string.dashboard_glass_personalize_cannula_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.BATTERY, R.string.dashboard_glass_personalize_battery_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.TOOLS_SHORTCUT, R.string.dashboard_glass_personalize_tools_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.SENSOR, R.string.dashboard_glass_personalize_sensor_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.LOOP_STATUS, R.string.dashboard_glass_personalize_loop_label, defaultSelected = true),
    GlassPillCatalogEntry(GlassPillId.ACTIVITY, R.string.dashboard_glass_personalize_activity_label, defaultSelected = true),
)

fun parseSelectedGlassPills(rawValue: String): List<GlassPillId> =
    rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { GlassPillId.valueOf(name) }.getOrNull() }

fun serializeSelectedGlassPills(ids: Set<GlassPillId>): String =
    ids.joinToString(",") { it.name }
```

(Only the enum, `GlassPillCatalogEntry`, and `GLASS_PILL_CATALOG` changed. `parseSelectedGlassPills` and
`serializeSelectedGlassPills` are copied verbatim, unchanged.)

- [ ] **Step 2: Fix the preference's class default so fresh installs see the 7 new pills**

In `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt`, find line 43:

```kotlin
    GlassSelectedPills(key = "glass_selected_pills", defaultValue = "", exportable = false),
```

Replace it with:

```kotlin
    // Default lists the Glass top-grid pills that are visible today (defaultSelected = true in
    // GLASS_PILL_CATALOG, plugins:main). core:keys cannot reference that catalog (wrong dependency
    // direction), so this literal list must be kept in sync with it by hand.
    GlassSelectedPills(
        key = "glass_selected_pills",
        defaultValue = "PUMP_RESERVOIR,CANNULA,BATTERY,TOOLS_SHORTCUT,SENSOR,LOOP_STATUS,ACTIVITY",
        exportable = false
    ),
```

This is the one change that makes "never touched Personalize" (no persisted value → falls back to this
class default) different from "explicitly cleared every pill" (persisted value is the literal empty string
`""`, which `parseSelectedGlassPills` correctly turns into an empty list). No code that reads this
preference needs to change — `plugins/main/.../glass/GlassOverviewComposeEmbedded.kt:88`
(`parseSelectedGlassPills(selectedPillsRaw)`) already gets the right answer either way.

- [ ] **Step 3: Add the 7 new string resources and fix the now-inaccurate empty-hint string**

In `plugins/main/src/main/res/values/strings.xml`, find this line (around line 233):

```xml
    <string name="dashboard_glass_personalize_empty_hint">Nothing selected. The row stays hidden until you pick at least one item below.</string>
```

Replace it with (the old wording only made sense when Personalize controlled just the bottom row; now it
controls the whole main screen):

```xml
    <string name="dashboard_glass_personalize_empty_hint">Nothing selected. These items stay hidden on the main screen until you pick at least one below.</string>
```

Then add these 7 new strings right after the `dashboard_glass_tools_label` line (around line 235) — these
are separate from the existing `dashboard_glass_*_label` strings (which stay unchanged and are used for the
pills' own on-screen labels; these new ones are only for the Personalize checklist):

```xml
    <string name="dashboard_glass_personalize_pump_label">Pump</string>
    <string name="dashboard_glass_personalize_cannula_label">Cannula</string>
    <string name="dashboard_glass_personalize_battery_label">Battery</string>
    <string name="dashboard_glass_personalize_tools_label">Tools</string>
    <string name="dashboard_glass_personalize_sensor_label">Sensor</string>
    <string name="dashboard_glass_personalize_loop_label">Loop</string>
    <string name="dashboard_glass_personalize_activity_label">Activity</string>
```

Do not touch `values-fr/strings.xml` or `values-fr-rFR/strings.xml` — per project convention, only the
English source is updated; translations are handled separately.

- [ ] **Step 4: Add tests for the new catalog entries and the default-value fix**

Add these 2 tests to `GlassPersonalizationTest.kt` (after the existing 3 tests, before the closing brace):

```kotlin
    @Test
    fun `all 12 catalog entries round-trip through serialize and parse`() {
        val allIds = GLASS_PILL_CATALOG.map { it.id }.toSet()
        val serialized = serializeSelectedGlassPills(allIds)
        assertThat(parseSelectedGlassPills(serialized).toSet()).isEqualTo(allIds)
    }

    @Test
    fun `exactly the 7 top-grid pills default to selected`() {
        val defaultSelectedIds = GLASS_PILL_CATALOG.filter { it.defaultSelected }.map { it.id }.toSet()
        assertThat(defaultSelectedIds).containsExactly(
            GlassPillId.PUMP_RESERVOIR, GlassPillId.CANNULA, GlassPillId.BATTERY, GlassPillId.TOOLS_SHORTCUT,
            GlassPillId.SENSOR, GlassPillId.LOOP_STATUS, GlassPillId.ACTIVITY,
        )
    }
```

- [ ] **Step 5: Compile and test**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`, then
`./gradlew :core:keys:compileFullDebugKotlin --no-daemon`, then
`./gradlew :plugins:main:compileFullDebugUnitTestKotlin --no-daemon`, then
`./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassPersonalizationTest*" --no-daemon`.
All 4 must succeed (5 pre-existing + 2 new tests, all passing).

- [ ] **Step 6: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.

---

### Task 2: UI wiring — gate the 7 top-grid pills in `StatusAgoraCard.kt`

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`

**Interfaces:**
- Consumes: the 7 new `GlassPillId` values from Task 1 (`PUMP_RESERVOIR`, `CANNULA`, `BATTERY`,
  `TOOLS_SHORTCUT`, `SENSOR`, `LOOP_STATUS`, `ACTIVITY`), and the existing `selectedPills:
  List<GlassPillId>` parameter (already present on `StatusAgoraCard`, already wired end-to-end from
  `GlassOverviewComposeEmbedded.kt` — no signature or call-site change needed here).

- [ ] **Step 1: Wrap each of the 4 LEFT-column pills in a selection guard**

In `StatusAgoraCard.kt`, the LEFT column (`// LEFT: pump status` comment, lines ~79-144) has 4
`GlassPill(...)` calls. Wrap each one in an `if`, keeping the call itself byte-for-byte identical. For
example, the first one (Insulin/Pump):

```kotlin
                    if (GlassPillId.PUMP_RESERVOIR in selectedPills) {
                        GlassPill(
                            label = state.insulinLabel,
                            value = state.insulinAge,
                            isDark = isDark,
                            valueColor = statusLevelToColor(state.insulinAgeStatus),
                            modifier = Modifier.width(100.dp).clickable { onOpenPump() },
                            leadingIcon = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_glyco_insulin),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }
```

Apply the same pattern (wrap the existing call in `if (GlassPillId.X in selectedPills) { ... }`, no other
change) to the remaining 3 LEFT-column pills using these mappings:
- Cannula pill (`state.cannulaLabel`/`onOpenCannula`) → `GlassPillId.CANNULA`
- Battery pill (`state.batteryLabel`/`onOpenBattery`) → `GlassPillId.BATTERY`
- Tools pill (`stringResource(R.string.dashboard_glass_tools_label)`/`onOpenTools`) → `GlassPillId.TOOLS_SHORTCUT`

- [ ] **Step 2: Wrap each of the 3 RIGHT-column pills in a selection guard**

The RIGHT column (`// RIGHT: CGM & loop` comment, lines ~199-286) has 3 `GlassPill(...)` calls. Apply the
same wrapping pattern, with these mappings:
- Sensor pill (`state.sensorLabel`, `combinedClickable(onOpenSensorQuality, onOpenSensorInsert)`) →
  `GlassPillId.SENSOR`
- Loop pill (`stringResource(R.string.dashboard_glass_loop_label)`, the animated-pulse leading icon,
  `combinedClickable(onOpenLoopDashboard, onOpenLoop)`) → `GlassPillId.LOOP_STATUS`
- Activity pill (`stringResource(R.string.dashboard_glass_activity_label)`, no click handler) →
  `GlassPillId.ACTIVITY`

Do not touch the CENTER column (glucose readout) — it has no `GlassPill` calls and stays exactly as-is.

- [ ] **Step 3: Verify the existing bottom-row filtering is untouched**

Confirm lines ~289-314 (`if (selectedPills.isNotEmpty()) { ... GLASS_PILL_CATALOG.filter { it.id in
selectedPills }.forEach { ... } }`) are unchanged — this task only adds guards around the 7 top-grid pill
calls above it, it does not touch the bottom-row block at all.

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Must succeed with no new warnings about
unused imports (all existing imports in this file — `combinedClickable`, `rememberInfiniteTransition`,
etc. — stay in use since the wrapped calls are unchanged).

- [ ] **Step 5: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.
