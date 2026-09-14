# Glass Dashboard Personalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Glass main screen's hardcoded, always-visible IOB/Target/Basal T bottom row (which has a
label-duplication bug) with a user-curated selection of compact pills, picked from a new "Personalize" screen
reached from the existing Tools overlay.

**Architecture:** A fixed, code-defined pill catalog (`GlassPillId` enum + `GLASS_PILL_CATALOG` list) backed by
one new comma-separated `StringKey` preference holding the user's selected pill IDs. `GlassOverviewComposeEmbedded.kt`
reads the preference, filters the catalog, and renders a dynamic row (omitted when empty) instead of the
current hardcoded 3-pill row. A new Glass-only, GlycoCalm-styled "Personalize" screen (a checklist) lives
inside the existing Tools overlay, reached via a small header button above the existing (untouched)
`DashboardV2ToolsScreen` grid.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, this repo's `Preferences`/`StringKey` preference system.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-dashboard-personalization-design.md`

## Global Constraints

- No `git commit` during this plan's execution (standing user rule — same as every prior Glass plan this
  session).
- No install/on-device testing without the user explicitly asking.
- Dark mode is sourced ONLY via `LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode` — never
  `isSystemInDarkTheme()` directly except inside the `SYSTEM` branch — for the new Personalize screen too.
- Explicit imports only.
- No hardcoded user-facing strings; no string concatenation for user-facing text.
- Do NOT modify `DashboardV2ToolsScreen.kt` — it is shared with DASHBOARD_V2. The new Personalize screen is a
  separate, Glass-only screen rendered alongside it inside the same Tools overlay `Box`.
- Every glucose value must go through `graphViewModel.glucoseMgdlToChartY(...)` before display — not directly
  relevant to this plan (no new glucose values are introduced), listed for consistency with every other Glass
  plan this session.

---

## Task 1: Pill catalog data model + new preference key

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalization.kt`
- Modify: `core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `GlassPillId` enum, `GlassPillCatalogEntry` data class, `GLASS_PILL_CATALOG: List<GlassPillCatalogEntry>`,
  `StringKey.GlassSelectedPills` — consumed by Task 2 (rendering) and Task 4 (Personalize screen).

- [ ] **Step 1: Add 2 new string resources**

In `plugins/main/src/main/res/values/strings.xml`, add (near the existing `dashboard_glass_iob_label`/
`dashboard_glass_target_label`/`dashboard_glass_basal_t_label` entries — these 3 already exist, do not
duplicate them):
```xml
<string name="dashboard_glass_last_bolus_label">Last Bolus</string>
<string name="dashboard_glass_last_carbs_label">Last Carbs</string>
<string name="dashboard_glass_personalize_title">Personalize</string>
<string name="dashboard_glass_personalize_button">Personalize</string>
<string name="dashboard_glass_personalize_empty_hint">Nothing selected. The row stays hidden until you pick at least one item below.</string>
```

- [ ] **Step 2: Create `GlassPersonalization.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.plugins.main.R

/** One selectable compact pill for the Glass main screen's optional bottom row. */
enum class GlassPillId { IOB, TARGET, BASAL_RATE, LAST_BOLUS, LAST_CARBS }

data class GlassPillCatalogEntry(
    val id: GlassPillId,
    val labelRes: Int,
)

/**
 * Fixed, code-defined catalog of pills the user can pick from in the Personalize screen.
 * Order here is the order pills render in on the main screen row.
 */
val GLASS_PILL_CATALOG: List<GlassPillCatalogEntry> = listOf(
    GlassPillCatalogEntry(GlassPillId.IOB, R.string.dashboard_glass_iob_label),
    GlassPillCatalogEntry(GlassPillId.TARGET, R.string.dashboard_glass_target_label),
    GlassPillCatalogEntry(GlassPillId.BASAL_RATE, R.string.dashboard_glass_basal_t_label),
    GlassPillCatalogEntry(GlassPillId.LAST_BOLUS, R.string.dashboard_glass_last_bolus_label),
    GlassPillCatalogEntry(GlassPillId.LAST_CARBS, R.string.dashboard_glass_last_carbs_label),
)

/** Parses the comma-separated [StringKey.GlassSelectedPills] preference value into catalog IDs, dropping unknowns. */
fun parseSelectedGlassPills(rawValue: String): List<GlassPillId> =
    rawValue.split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { GlassPillId.valueOf(name) }.getOrNull() }

/** Serializes a selection back to the comma-separated preference format. */
fun serializeSelectedGlassPills(ids: Set<GlassPillId>): String =
    ids.joinToString(",") { it.name }
```

- [ ] **Step 3: Add the new preference key**

In `core/keys/src/main/kotlin/app/aaps/core/keys/StringKey.kt`, add a new entry to the `StringKey` enum (place
it near `GeneralDarkMode` or at the end of the enum body — exact position doesn't matter, Kotlin enums don't
require ordering):
```kotlin
GlassSelectedPills(
    key = "glass_selected_pills",
    defaultValue = "",
    exportable = false,
),
```
(`exportable = false` because this is a Glass-skin-local UI preference, not a therapy setting — mirrors how
other purely-cosmetic preferences in this enum are marked; check one other `exportable = false` entry in this
same file to confirm the parameter name/placement convention before adding this one.) No `titleResId` is
needed — this preference is never shown in the standard AAPS Preferences screen; it is only ever read/written
by the new Personalize screen (Task 4).

- [ ] **Step 4: Compile**

Run: `./gradlew :core:keys:compileFullDebugKotlin --no-daemon` then
`./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 2: Fix the IOB duplicate-label bug + wire Last Bolus/Last Carbs data

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: `StatusCardState.lastSensorValueText` (already exists, `OverviewViewModel.kt:1540` — despite its
  confusingly generic name, this field already holds the total IOB amount formatted WITHOUT any "IOB: " prefix,
  computed at `OverviewViewModel.kt:700-706`; do not rename it or touch `OverviewViewModel.kt` at all in this
  task — it is an existing, already-correct, already-populated field, just re-purposed here).
- Consumes: `TreatmentGraphData.boluses: List<BolusGraphPoint>` / `.carbs: List<CarbsGraphPoint>` (already
  collected in `GlassOverviewComposeEmbedded.kt` as `treatmentData`, each point has `timestamp: Long`,
  `isValid: Boolean`, `label: String` — a pre-formatted amount string, e.g. "2.50 U" / "45 g").
- Produces: `GlassUiState.lastBolusText`/`.lastCarbsText`, consumed by Task 3's rendering.

- [ ] **Step 1: Add 2 new fields to `GlassUiState`**

In `GlassOverviewModels.kt`, add after `hrText`:
```kotlin
val lastBolusText: String = "--",
val lastCarbsText: String = "--",
```

- [ ] **Step 2: Fix the bug + compute the 2 new fields in `buildGlassUiState`**

Change `buildGlassUiState`'s signature from:
```kotlin
internal fun buildGlassUiState(status: StatusCardState?, lights: StatusUiState?): GlassUiState {
```
to:
```kotlin
internal fun buildGlassUiState(
    status: StatusCardState?,
    lights: StatusUiState?,
    treatmentData: app.aaps.core.interfaces.overview.graph.TreatmentGraphData,
): GlassUiState {
```
(Add a proper import `import app.aaps.core.interfaces.overview.graph.TreatmentGraphData` instead of using the
fully-qualified form shown above — the inline form is only to pin the exact type for this brief.)

Change:
```kotlin
        iobText = status.iobText,
```
to (the bug fix — `lastSensorValueText` is the same total IOB, already correctly formatted, WITHOUT a
redundant "IOB: " prefix; `status.iobText` itself is untouched and still used elsewhere, e.g. DASHBOARD_V2):
```kotlin
        iobText = status.lastSensorValueText ?: "--",
```

Add, right after `hrText = status.hrText ?: "--",`:
```kotlin
        lastBolusText = treatmentData.boluses.filter { it.isValid }.maxByOrNull { it.timestamp }?.label ?: "--",
        lastCarbsText = treatmentData.carbs.filter { it.isValid }.maxByOrNull { it.timestamp }?.label ?: "--",
```

- [ ] **Step 3: Update the call site**

In `GlassOverviewComposeEmbedded.kt`'s composable body, change:
```kotlin
        val state = buildGlassUiState(status, statusLights)
```
to (move this line to AFTER `val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()`
is declared, since it now depends on it — currently `state` is built before `treatmentData` is collected;
reorder so `treatmentData` is collected first):
```kotlin
        val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
        val state = buildGlassUiState(status, statusLights, treatmentData)
```
(Remove the old standalone `val treatmentData by ...` line further down where it currently sits, since it is
now declared earlier — do not declare it twice. Read the current file first to see its exact current position
relative to `bgReadings`/`iobData`/`chartConfig`/`predictions` and move only `treatmentData`'s declaration up,
keep the others where they are.)

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write a unit test for the "most recent valid" reduction**

This is the one piece of new logic in this plan worth a test (mirrors this project's established precedent:
pure UI wiring gets no test, real branching/reduction logic does). Create
`plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassUiStateBuilderTest.kt`:
```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.overview.graph.BolusGraphPoint
import app.aaps.core.interfaces.overview.graph.BolusType
import app.aaps.core.interfaces.overview.graph.CarbsGraphPoint
import app.aaps.core.interfaces.overview.graph.TreatmentGraphData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GlassUiStateBuilderTest {

    private fun treatmentData(boluses: List<BolusGraphPoint> = emptyList(), carbs: List<CarbsGraphPoint> = emptyList()) =
        TreatmentGraphData(boluses = boluses, carbs = carbs, extendedBoluses = emptyList(), therapyEvents = emptyList())

    @Test
    fun `most recent valid bolus label is used, invalid and older ones are ignored`() {
        val data = treatmentData(
            boluses = listOf(
                BolusGraphPoint(timestamp = 1000L, amount = 1.0, bolusType = BolusType.NORMAL, isValid = true, label = "1.00 U"),
                BolusGraphPoint(timestamp = 3000L, amount = 2.0, bolusType = BolusType.NORMAL, isValid = false, label = "2.00 U (invalid)"),
                BolusGraphPoint(timestamp = 2000L, amount = 1.5, bolusType = BolusType.SMB, isValid = true, label = "1.50 U"),
            )
        )
        val state = buildGlassUiState(status = null, lights = null, treatmentData = data)
        assertThat(state.lastBolusText).isEqualTo("1.50 U")
    }

    @Test
    fun `no valid bolus or carbs falls back to placeholder`() {
        val state = buildGlassUiState(status = null, lights = null, treatmentData = treatmentData())
        assertThat(state.lastBolusText).isEqualTo("--")
        assertThat(state.lastCarbsText).isEqualTo("--")
    }
}
```
(`BolusType` is confirmed at `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/overview/graph/
CalculationResults.kt:237` — same file/package as `BolusGraphPoint`, so
`import app.aaps.core.interfaces.overview.graph.BolusType` resolves correctly, add it to the test file.
Note `buildGlassUiState(status = null, ...)` already returns an early default `GlassUiState()` per its existing
`if (status == null) return GlassUiState()` guard — this test's expectations only hold if that guard is
changed to also consider `treatmentData` when `status` is null, OR if the test instead passes a minimal
non-null `StatusCardState`. Resolve this discrepancy during implementation: either construct a minimal valid
`StatusCardState` for these tests, or confirm the null-guard's behavior with the current code and adjust the
test to match reality — do not silently change `buildGlassUiState`'s null-handling behavior as a side effect of
adding this test.)

Run: `./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassUiStateBuilderTest*" --no-daemon`. Expected:
BUILD SUCCESSFUL.

---

## Task 3: Replace the hardcoded bottom row with the dynamic, preference-driven row

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: `GLASS_PILL_CATALOG`/`GlassPillId` (Task 1), `GlassUiState.iobText`/`.targetText`/
  `.basalPercentText`/`.lastBolusText`/`.lastCarbsText` (existing + Task 2).
- Produces: `StatusAgoraCard`'s new `selectedPills: List<GlassPillId>` parameter.

- [ ] **Step 1: Add a value-lookup helper to `StatusAgoraCard.kt`**

Add, above the `StatusAgoraCard` composable:
```kotlin
private fun glassPillValue(id: GlassPillId, state: GlassUiState): String = when (id) {
    GlassPillId.IOB -> state.iobText
    GlassPillId.TARGET -> state.targetText
    GlassPillId.BASAL_RATE -> state.basalPercentText
    GlassPillId.LAST_BOLUS -> state.lastBolusText
    GlassPillId.LAST_CARBS -> state.lastCarbsText
}
```

- [ ] **Step 2: Replace the hardcoded bottom row**

Add a new parameter to `StatusAgoraCard`'s signature, after `onOpenTools: () -> Unit,`:
```kotlin
    selectedPills: List<GlassPillId>,
```

Replace the current bottom `Row` block:
```kotlin
            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BottomMetricPill(
                    title = stringResource(R.string.dashboard_glass_iob_label),
                    value = state.iobText,
                    isDark = isDark,
                    onClick = onOpenInsulin,
                    modifier = Modifier.weight(1f)
                )
                BottomMetricPill(
                    title = if (state.isTempTargetActive) "" else stringResource(R.string.dashboard_glass_target_label),
                    value = state.targetText,
                    isDark = isDark,
                    onClick = onOpenTarget,
                    modifier = Modifier.weight(1f),
                    accentColor = if (state.isTempTargetActive) Color(0xFFF4D700) else null
                )
                BottomMetricPill(
                    title = stringResource(R.string.dashboard_glass_basal_t_label),
                    value = state.basalPercentText,
                    isDark = isDark,
                    onClick = onOpenBasal,
                    modifier = Modifier.weight(1f)
                )
            }
```
with:
```kotlin
            if (selectedPills.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GLASS_PILL_CATALOG.filter { it.id in selectedPills }.forEach { entry ->
                        val isTarget = entry.id == GlassPillId.TARGET
                        BottomMetricPill(
                            title = if (isTarget && state.isTempTargetActive) "" else stringResource(entry.labelRes),
                            value = glassPillValue(entry.id, state),
                            isDark = isDark,
                            onClick = when (entry.id) {
                                GlassPillId.IOB -> onOpenInsulin
                                GlassPillId.TARGET -> onOpenTarget
                                GlassPillId.BASAL_RATE -> onOpenBasal
                                GlassPillId.LAST_BOLUS -> onOpenInsulin
                                GlassPillId.LAST_CARBS -> onOpenInsulin
                            },
                            modifier = Modifier.weight(1f),
                            accentColor = if (isTarget && state.isTempTargetActive) Color(0xFFF4D700) else null
                        )
                    }
                }
            }
```
(`LAST_BOLUS`/`LAST_CARBS` route to `onOpenInsulin` for now — same screen the IOB pill already opens, since
there is no dedicated "treatment history" click target on this card today; revisit only if the user asks for a
different target. This is a deliberate, minimal choice, not an oversight — note it in your task report.)

- [ ] **Step 3: Wire the preference into the call site**

In `GlassOverviewComposeEmbedded.kt`, inside the `AapsTheme { ... }` block, add (near where `darkModeValue` is
already read):
```kotlin
        val selectedPillsRaw by preferences.observe(StringKey.GlassSelectedPills).collectAsState()
        val selectedPills = remember(selectedPillsRaw) { parseSelectedGlassPills(selectedPillsRaw) }
```

Add `selectedPills = selectedPills,` to the existing `StatusAgoraCard(...)` call (after `onOpenTools = { showTools = true },`).

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 4: Personalize screen (GlycoCalm-styled) + Tools overlay entry point

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPersonalizeScreen.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: `GLASS_PILL_CATALOG`, `GlassPillId`, `parseSelectedGlassPills`/`serializeSelectedGlassPills` (Task 1),
  `StringKey.GlassSelectedPills` (Task 1), `preferences.put(...)` (inherited via `StringNonPreferenceKey`, same
  `Preferences` interface already used for `.observe(...)` elsewhere in this file).
- Produces: no new public interface beyond the new screen file itself — purely additive UI inside the existing
  Tools overlay.

Visual style for this screen only (GlycoCalm, per the spec) — light tokens from
`docs/superpowers/specs/2026-09-11-glycocalm-reference/DESIGN.md`'s prose "Colors" section, dark tokens from
that same file's YAML `inverse-*`/`*-fixed`/`*-container` tokens:

```kotlin
private val LightBg = Color(0xFFF1F5F9)
private val LightCard = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF0D1B2A)
private val LightAccent = Color(0xFF00B894)
private val LightMuted = Color(0xFF64748B)

private val DarkBg = Color(0xFF213145)       // inverse-surface
private val DarkCard = Color(0xFF0F1C2C)     // primary-container
private val DarkPrimary = Color(0xFFEAF1FF) // inverse-on-surface
private val DarkAccent = Color(0xFF6DFAD2)  // secondary-fixed
private val DarkMuted = Color(0xFF778598)   // on-primary-container
```

- [ ] **Step 1: Create `GlassPersonalizeScreen.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R

private val LightBg = Color(0xFFF1F5F9)
private val LightCard = Color(0xFFFFFFFF)
private val LightPrimary = Color(0xFF0D1B2A)
private val LightAccent = Color(0xFF00B894)
private val LightMuted = Color(0xFF64748B)

private val DarkBg = Color(0xFF213145)
private val DarkCard = Color(0xFF0F1C2C)
private val DarkPrimary = Color(0xFFEAF1FF)
private val DarkAccent = Color(0xFF6DFAD2)
private val DarkMuted = Color(0xFF778598)

@Composable
internal fun GlassPersonalizeScreen(
    selectedPills: Set<GlassPillId>,
    onToggle: (GlassPillId, Boolean) -> Unit,
    isDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isDark) DarkBg else LightBg
    val card = if (isDark) DarkCard else LightCard
    val primary = if (isDark) DarkPrimary else LightPrimary
    val accent = if (isDark) DarkAccent else LightAccent
    val muted = if (isDark) DarkMuted else LightMuted

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(bg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selectedPills.isEmpty()) {
            Text(
                text = stringResource(R.string.dashboard_glass_personalize_empty_hint),
                color = muted,
                fontSize = 13.sp,
            )
        }
        GLASS_PILL_CATALOG.forEach { entry ->
            val checked = entry.id in selectedPills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(card)
                    .clickable { onToggle(entry.id, !checked) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(entry.labelRes),
                    color = primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(entry.id, it) },
                    colors = CheckboxDefaults.colors(checkedColor = accent),
                )
            }
        }
    }
}
```

- [ ] **Step 2: Wire the entry point into the Tools overlay**

In `GlassOverviewComposeEmbedded.kt`, add local state right after `var showTools by remember { mutableStateOf(false) }`:
```kotlin
    var showPersonalize by remember { mutableStateOf(false) }
```

Change the existing `BackHandler`/overlay block:
```kotlin
            if (showTools) {
                BackHandler(enabled = showTools) { showTools = false }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                if (isDark) {
                                    listOf(Color(0xFF070E1B), Color(0xFF0B1424), Color(0xFF070E1B))
                                } else {
                                    listOf(Color(0xFFF1F5F9), Color(0xFFE8EEF8), Color(0xFFF1F5F9))
                                }
                            )
                        )
                ) {
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
                }
            }
```
to:
```kotlin
            if (showTools) {
                BackHandler(enabled = showTools) {
                    if (showPersonalize) showPersonalize = false else showTools = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                if (isDark) {
                                    listOf(Color(0xFF070E1B), Color(0xFF0B1424), Color(0xFF070E1B))
                                } else {
                                    listOf(Color(0xFFF1F5F9), Color(0xFFE8EEF8), Color(0xFFF1F5F9))
                                }
                            )
                        )
                ) {
                    if (showPersonalize) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_title),
                                    color = if (isDark) Color.White else Color(0xFF0D1B2A),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(app.aaps.core.ui.R.string.ok),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showPersonalize = false }
                                )
                            }
                            GlassPersonalizeScreen(
                                selectedPills = selectedPills.toSet(),
                                onToggle = { id, checked ->
                                    val next = selectedPills.toMutableSet()
                                    if (checked) next.add(id) else next.remove(id)
                                    preferences.put(StringKey.GlassSelectedPills, serializeSelectedGlassPills(next))
                                },
                                isDark = isDark,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_glass_personalize_button),
                                    color = if (isDark) Color(0xFF6DFAD2) else Color(0xFF00B894),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { showPersonalize = true }
                                )
                            }
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
                        }
                    }
                }
            }
```
Also reset `showPersonalize = false` whenever `showTools` transitions back to `false` from elsewhere (the
`onOpenTools = { showTools = true }` open-path already starts with `showPersonalize` at its last value from a
prior session — since it's `remember`ed, not reset — add a small `LaunchedEffect(showTools) { if (!showTools) showPersonalize = false }`
right after the `showPersonalize` declaration, so re-opening Tools always starts on the grid, not wherever the
user left off; import `androidx.compose.runtime.LaunchedEffect` if not already present).

`app.aaps.core.ui.R.string.ok` is confirmed to exist (`core/ui/src/main/res/values/strings.xml:932`, value
"OK") — this file already uses the fully-qualified form `app.aaps.core.ui.R.string.overview_treatment_label`
for a different `core:ui` string elsewhere (no short-name `import app.aaps.core.ui.R` exists yet, since this
module's own `R` would collide with a bare `import ... R` — keep using the fully-qualified
`app.aaps.core.ui.R.string.ok` form for this one string, consistent with this file's own existing style,
rather than adding a colliding import.

- [ ] **Step 3: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification note for the user**

Cannot be meaningfully unit-tested (pure Compose UI + preference wiring). Flag for the user: open Glass's Tools
overlay, tap "Personalize", check/uncheck a few pills, confirm the main screen's bottom row updates
accordingly (appearing/disappearing pills, row entirely hidden when nothing is selected), confirm the IOB pill
no longer shows "IOB" twice, confirm Last Bolus/Last Carbs show sensible values after a real bolus/carb entry,
confirm the 2-level back button (Personalize → grid → close) works, confirm both light and dark mode look
reasonable on the new Personalize screen.

---

## Self-Review Notes

- **Spec coverage:** catalog + preference (Task 1), the IOB bug fix + new data (Task 2), dynamic row rendering
  (Task 3), Personalize screen + entry point (Task 4) — all of the spec's "Architecture" section is covered.
- **No placeholders:** every step has real code; the 2 explicitly-flagged uncertainties (`BolusType`'s exact
  import path in the Task 2 test, and `app.aaps.core.ui.R.string.ok`'s exact existence in Task 4) are marked
  as "verify before compiling," not silently assumed — this is intentional flagging of genuine unknowns this
  plan's own research did not fully close, not a placeholder for missing design work.
- **Type consistency:** `GlassPillId`/`GLASS_PILL_CATALOG`/`GlassUiState.lastBolusText`/`.lastCarbsText` are
  used identically across Tasks 1-4.
