# Glass Tools Grid Personalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user pick which of the 13 Tools-grid tiles (Actions, Rapid Acting, Profile, Automation,
NSClient, Tidepool, xDrip+, Maintenance, xDrip+ BG source, Advisor, Meal Advisor, AIMI Context, Auditor
Report) show on the Glass dashboard's Tools screen.

**Architecture:** Add an optional `visibleActions: Set<DashboardV2ToolAction>? = null` filter parameter to
the shared `DashboardV2ToolsScreen` composable (default `null` preserves today's behavior for every existing
DASHBOARD_V2 caller). Reuse `DashboardV2ToolAction` directly as the selectable catalog (no new ID enum) with
a new `StringNonKey.GlassSelectedTools` preference. Extend the existing `GlassPersonalizeScreen` with a
second, labeled section for tool tiles, reached the same way pill personalization already is.

**Tech Stack:** Kotlin, Jetpack Compose, existing `StringNonKey` preference infrastructure.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-tools-personalization-design.md`

## Global Constraints

- `DashboardV2ToolsScreen`'s new `visibleActions` parameter must default to `null` (= show everything) so
  **no existing DASHBOARD_V2 call site needs to change**.
- Do not reorder, rename, or remove any `DashboardV2ToolAction` entry, and do not change `isAvailable()`'s
  existing enabled/disabled semantics — `DashboardV2ToolActionTest.kt`'s existing assertions must keep
  passing unmodified.
- All 13 tiles are `defaultSelected` (visible today; none may become default-hidden — that would be a silent
  regression). Use the same compiled-in-class-default technique as `GlassSelectedPills`
  (`StringNonKey.GlassSelectedTools`'s `defaultValue` is a literal, comma-joined list of all 13
  `DashboardV2ToolAction` names, not `""`) so "never touched Personalize" is distinguishable from
  "explicitly cleared every tile."
- English-only new string resources.
- Run `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` after each task; run
  `./gradlew :plugins:main:compileFullDebugUnitTestKotlin --no-daemon` and
  `./gradlew :plugins:main:testFullDebugUnitTest --tests "*DashboardV2ToolActionTest*" --no-daemon` too,
  since Task 1 touches the file that test covers.
- Do not run `git commit`.

---

### Task 1: Data layer — filter parameter, preference, parse/serialize helpers

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardV2ToolsScreen.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalization.kt`
- Modify: `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt`
- Modify: `plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalizationTest.kt`

**Interfaces:**
- Produces: `DashboardV2ToolsScreen(..., visibleActions: Set<DashboardV2ToolAction>? = null)` (new optional
  param, appended after `modifier`), `internal fun DashboardV2ToolAction.icon()`/`.labelRes()` (widened from
  `private`), `parseSelectedGlassTools(String): List<DashboardV2ToolAction>` and
  `serializeSelectedGlassTools(Set<DashboardV2ToolAction>): String` in `GlassPersonalization.kt`, and
  `StringNonKey.GlassSelectedTools`. Task 2 consumes all of these by name.

- [ ] **Step 1: Widen `icon()`/`labelRes()` visibility**

In `DashboardV2ToolsScreen.kt`, change:
```kotlin
private fun DashboardV2ToolAction.icon(): ImageVector =
```
to:
```kotlin
internal fun DashboardV2ToolAction.icon(): ImageVector =
```
and change:
```kotlin
@StringRes
private fun DashboardV2ToolAction.labelRes(): Int =
```
to:
```kotlin
@StringRes
internal fun DashboardV2ToolAction.labelRes(): Int =
```
(Both functions' bodies stay exactly as they are — only the visibility modifier changes. Both are in the
same module, `plugins:main`, as `GlassPersonalizeScreen.kt`, which will use them in Task 2.)

- [ ] **Step 2: Add the `visibleActions` filter parameter**

In the same file, change the `DashboardV2ToolsScreen` composable signature from:
```kotlin
@Composable
fun DashboardV2ToolsScreen(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    availablePluginClassNames: Set<String>,
    onAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
) {
```
to:
```kotlin
@Composable
fun DashboardV2ToolsScreen(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    availablePluginClassNames: Set<String>,
    onAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Null shows every tile (today's behavior, used by every DASHBOARD_V2 caller). A non-null set hides
     *  any tile not in it — used by Glass's Tools-tile personalization. */
    visibleActions: Set<DashboardV2ToolAction>? = null,
) {
```

Then change the 2 `items(...)` calls inside it from:
```kotlin
        items(DashboardV2ToolAction.general, key = DashboardV2ToolAction::name) { action ->
```
to:
```kotlin
        items(DashboardV2ToolAction.general.filter { visibleActions == null || it in visibleActions }, key = DashboardV2ToolAction::name) { action ->
```
and from:
```kotlin
        items(DashboardV2ToolAction.aimi, key = DashboardV2ToolAction::name) { action ->
```
to:
```kotlin
        items(DashboardV2ToolAction.aimi.filter { visibleActions == null || it in visibleActions }, key = DashboardV2ToolAction::name) { action ->
```
(Nothing else in either `items(...)` block changes — same `DashboardV2ToolTile(...)` call, same `enabled`/
`onClick` wiring.)

- [ ] **Step 3: Add parse/serialize helpers for tools to `GlassPersonalization.kt`**

Add this import at the top of `GlassPersonalization.kt` (alongside the existing 2 imports):
```kotlin
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
```

Add these 2 functions at the end of the file (after `serializeSelectedGlassPills`):
```kotlin
/** Parses a comma-joined [StringNonKey.GlassSelectedTools] value, dropping unknown or blank entries. */
fun parseSelectedGlassTools(rawValue: String): List<DashboardV2ToolAction> =
    rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { DashboardV2ToolAction.valueOf(name) }.getOrNull() }

/** Serializes a tool-tile selection back to the comma-joined format [StringNonKey.GlassSelectedTools] stores. */
fun serializeSelectedGlassTools(actions: Set<DashboardV2ToolAction>): String =
    actions.joinToString(",") { it.name }
```

- [ ] **Step 4: Add the `GlassSelectedTools` preference**

In `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt`, add a new entry right after
`GlassSelectedPills`:
```kotlin
    // Default lists all 13 DashboardV2ToolAction entries (all tiles are visible today). plugins:main's
    // DashboardV2ToolAction is the source of truth; keep this literal list in sync with it by hand
    // (core:keys cannot depend on plugins:main).
    GlassSelectedTools(
        key = "glass_selected_tools",
        defaultValue = "ACTIONS,RAPID_ACTING,PROFILE,AUTOMATION,NSCLIENT,TIDEPOOL,XDRIP,MAINTENANCE,XDRIP_BG,ADVISOR,MEAL_ADVISOR,AIMI_CONTEXT,AUDITOR_REPORT",
        exportable = false
    ),
```

- [ ] **Step 5: Add tests**

Add these 3 tests to `GlassPersonalizationTest.kt` (needs `import
app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction` added to its imports):
```kotlin
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
    fun `StringNonKey tools default matches all 13 DashboardV2ToolAction entries`() {
        val expected = DashboardV2ToolAction.entries.toSet()
        assertThat(parseSelectedGlassTools(StringNonKey.GlassSelectedTools.defaultValue).toSet()).isEqualTo(expected)
    }
```
(The last test directly guards the hand-kept-in-sync literal in `StringNonKey.kt` against drift, the same
pattern the pill-personalization plan added for `GlassSelectedPills` after a review flagged the gap.)

- [ ] **Step 6: Compile and test**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`, then
`./gradlew :core:keys:compileFullDebugKotlin --no-daemon`, then
`./gradlew :plugins:main:compileFullDebugUnitTestKotlin --no-daemon`, then
`./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassPersonalizationTest*" --no-daemon`, then
`./gradlew :plugins:main:testFullDebugUnitTest --tests "*DashboardV2ToolActionTest*" --no-daemon` (this last
one guards against the Step 1/2 signature changes breaking the existing regression-lock test). All 5 must
succeed.

- [ ] **Step 7: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.

---

### Task 2: UI wiring — Tools section in `GlassPersonalizeScreen.kt` and its call sites

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalizeScreen.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml` (2 new section-header strings)

**Interfaces:**
- Consumes: `parseSelectedGlassTools`/`serializeSelectedGlassTools`/`StringNonKey.GlassSelectedTools` from
  Task 1, and `DashboardV2ToolsScreen`'s new `visibleActions` parameter.

- [ ] **Step 1: Add 2 new string resources**

In `plugins/main/src/main/res/values/strings.xml`, add near the other `dashboard_glass_personalize_*`
strings:
```xml
    <string name="dashboard_glass_personalize_section_main_screen">Main screen</string>
    <string name="dashboard_glass_personalize_section_tools">Tools screen</string>
```

- [ ] **Step 2: Extend `GlassPersonalizeScreen.kt` with a Tools section**

Add these imports:
```kotlin
import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction
import app.aaps.plugins.main.general.dashboard.labelRes
```
(`labelRes()` is the extension function Task 1 widened to `internal` in `DashboardV2ToolsScreen.kt`,
imported here as a top-level function since Kotlin extension functions import like any other top-level
declaration. This checklist follows the existing pill-row style — label + checkbox, no icon — so `icon()`
is not needed here; it was still widened in Task 1 alongside `labelRes()` for symmetry and possible future
use, at zero cost since Kotlin does not warn about an unused `internal` visibility widening.)

Change the composable's signature from:
```kotlin
internal fun GlassPersonalizeScreen(
    selectedPills: Set<GlassPillId>,
    onToggle: (GlassPillId, Boolean) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
```
to:
```kotlin
internal fun GlassPersonalizeScreen(
    selectedPills: Set<GlassPillId>,
    onToggle: (GlassPillId, Boolean) -> Unit,
    selectedTools: Set<DashboardV2ToolAction>,
    onToolToggle: (DashboardV2ToolAction, Boolean) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
```

Find the closing of the pill `GLASS_PILL_CATALOG.forEach { entry -> ... }` block (the last thing before the
outer `Column`'s closing brace). Right before it, add a section header for the pill list:
```kotlin
        Text(
            text = stringResource(R.string.dashboard_glass_personalize_section_main_screen),
            color = muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
```
(Insert this new `Text` immediately before the existing `GLASS_PILL_CATALOG.forEach { entry -> ... }` line —
so the pill list now has a small header above it, matching the new Tools section's header below.)

Then, right after the `GLASS_PILL_CATALOG.forEach { entry -> ... }` block's closing brace (still inside the
outer `Column`), add the new Tools section:
```kotlin
        Text(
            text = stringResource(R.string.dashboard_glass_personalize_section_tools),
            color = muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        DashboardV2ToolAction.entries.forEach { action ->
            val checked = action in selectedTools
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(card)
                    .clickable { onToolToggle(action, !checked) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(action.labelRes()),
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToolToggle(action, it) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            }
        }
```
(This mirrors the existing pill-row `Row` exactly — same shape, same tokens — just iterating
`DashboardV2ToolAction.entries` instead of `GLASS_PILL_CATALOG`, and using `action.labelRes()` instead of a
`GlassPillCatalogEntry.labelRes` field, since `DashboardV2ToolAction` carries its label via the extension
function, not a constructor property.)

- [ ] **Step 3: Wire the new params in `GlassOverviewComposeEmbedded.kt`**

Add, right after the existing `val selectedPills = remember(selectedPillsRaw) {
parseSelectedGlassPills(selectedPillsRaw) }` line:
```kotlin
        val selectedToolsRaw by preferences.observe(StringNonKey.GlassSelectedTools).collectAsState()
        val selectedTools = remember(selectedToolsRaw) { parseSelectedGlassTools(selectedToolsRaw).toSet() }
```

Change the existing `GlassPersonalizeScreen(...)` call from:
```kotlin
                            GlassPersonalizeScreen(
                                selectedPills = selectedPills.toSet(),
                                onToggle = { id, checked ->
                                    val next = selectedPills.toMutableSet()
                                    if (checked) next.add(id) else next.remove(id)
                                    preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(next))
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                            )
```
to:
```kotlin
                            GlassPersonalizeScreen(
                                selectedPills = selectedPills.toSet(),
                                onToggle = { id, checked ->
                                    val next = selectedPills.toMutableSet()
                                    if (checked) next.add(id) else next.remove(id)
                                    preferences.put(StringNonKey.GlassSelectedPills, serializeSelectedGlassPills(next))
                                },
                                selectedTools = selectedTools,
                                onToolToggle = { action, checked ->
                                    val next = selectedTools.toMutableSet()
                                    if (checked) next.add(action) else next.remove(action)
                                    preferences.put(StringNonKey.GlassSelectedTools, serializeSelectedGlassTools(next))
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                            )
```

Change the existing `DashboardV2ToolsScreen(...)` call from:
```kotlin
                            DashboardV2ToolsScreen(
                                paddingValues = PaddingValues(0.dp),
                                fabBottomOffset = 0.dp,
                                availablePluginClassNames = availablePluginClassNames,
                                onAction = { action ->
                                    showTools = false
                                    onToolAction(action)
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
```
to:
```kotlin
                            DashboardV2ToolsScreen(
                                paddingValues = PaddingValues(0.dp),
                                fabBottomOffset = 0.dp,
                                availablePluginClassNames = availablePluginClassNames,
                                onAction = { action ->
                                    showTools = false
                                    onToolAction(action)
                                },
                                modifier = Modifier.fillMaxSize(),
                                visibleActions = selectedTools,
                            )
```

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Must succeed.

- [ ] **Step 5: Commit is NOT performed by the implementer**

Do not run `git commit`. Leave the changes in the working tree.
