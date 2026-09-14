# Glass dashboard feedback round 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Address 5 pieces of on-device user feedback on the shipped Glass dashboard's main screen
(`StatusAgoraCard` + `BgChartCard`): drop the Preferences pill, add a combined Steps+Heart-Rate pill, split the
Sensor pill's gesture (short tap → a new sensor-quality screen, long press → the existing sensor-insert form),
draw BG predictions on the chart, and add a Tools entry point reusing DASHBOARD_V2's existing Tools screen.

**Architecture:** Every piece of data this plan needs already exists as a real, working, typed source — this
is wiring, not new plumbing:
- Steps/HR: `OverviewViewModel.StatusCardState.stepsText`/`.hrText` (already computed from Health Connect/
  Garmin/Wear/DB sources) — `GlassOverviewComposeEmbedded.kt` already observes this same `StatusCardState`.
- Sensor quality: `StatusCardState.adaptiveSmoothingQualityTier`/`.adaptiveSmoothingQualityBadgeText`/
  `.adaptiveSmoothingQualityDialogMessage` — same `StatusCardState`, already populated, currently only shown
  in a DASHBOARD_V2 dialog. The new screen reads the SAME `OverviewViewModel` instance (shared, activity-
  scoped) other Glass nav destinations already use — no new ViewModel.
- BG predictions: `GraphViewModel.predictionsFlow: StateFlow<List<BgDataPoint>>` already exists on the exact
  `GraphViewModel` instance Glass already collects `bgReadingsFlow`/`iobGraphFlow`/etc. from.
- Tools: `DashboardV2ToolsScreen` (already shipped, stateless, reusable as-is) plus its existing `onAction`
  handler logic in `ComposeMainActivity.kt` (unchanged, just also pointed at Glass).

**Tech Stack:** Android, Kotlin, Jetpack Compose (Material3), custom Canvas drawing (BG chart).

**Spec:** builds on `docs/superpowers/specs/2026-09-10-glass-dashboard-port-design.md` (the original Glass
port) — this plan is direct user feedback on that already-shipped work, not a new spec-level design.

## Decisions already made with the user (do not re-litigate)

1. **Tools entry point**: a dedicated pill/button on the Glass main screen (not a repurposed gesture on an
   existing pill), opening `DashboardV2ToolsScreen` as a local full-screen overlay — no changes to
   `MainScreen.kt`'s nav-bar/tab mechanism.
2. **BG chart predictions layout**: extend the chart with a fixed future zone after "now" (not compress the
   existing historical view to make room) — a vertical "now" line separates history from predictions.

## Global Constraints

- No `git commit` during this plan's execution (standing user rule).
- No `git install`/on-device testing without the user explicitly asking (standing rule).
- Dark mode is sourced ONLY via the existing `isDark` value already computed in `GlassOverviewComposeEmbedded.kt`
  (itself via `LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode`) — never `isSystemInDarkTheme()` directly
  in any new screen.
- Every glucose value (readings AND predictions) must go through `graphViewModel.glucoseMgdlToChartY(...)` —
  `BgDataPoint.value` (including prediction points) is always mg/dL. This exact bug class has been Critical
  twice already in this feature.
- No hardcoded user-facing strings; no string concatenation for user-facing text — use positional
  format-string resources, reusing existing ones where they fit exactly
  (`app.aaps.plugins.main.R.string.dashboard_v2_heart_rate_value` = `"%1$s bpm"` already exists and is reused
  here verbatim).
- Explicit imports only.
- No new test file for pure Canvas-drawing/UI-wiring changes — matches the precedent already set by every
  prior Glass task (`StatusAgoraCard`/`BgChartCard`/`GlassLoopDashboardScreen` have no dedicated tests); DO add
  a test for the one piece of this plan with real branching logic worth covering: the prediction/history
  progress-mapping math (Task 4).

---

## Task 1: Remove Preferences pill, add combined Steps + Heart Rate pill

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Removes: `GlassHeroCommands.openPreferences()` (and its `NoopGlassHeroCommands`/`DashboardShellController`
  implementations), `StatusAgoraCard`'s `onOpenPreferences` parameter.
- Produces: `GlassUiState.stepsText: String`/`.hrText: String`, consumed by `StatusAgoraCard`'s new Activity
  pill.

- [ ] **Step 1: Add string resources**

```xml
<string name="dashboard_glass_activity_label">Activity</string>
<string name="dashboard_glass_activity_value" comment="%1$s=step count e.g. 4200, %2$s=heart rate in bpm e.g. 72">%1$s · %2$s bpm</string>
```

- [ ] **Step 2: `GlassOverviewModels.kt` — add fields, remove nothing here**

Add two fields to `GlassUiState`, right after `basalPercentText`:
```kotlin
val stepsText: String = "--",
val hrText: String = "--",
```

- [ ] **Step 3: `GlassOverviewComposeEmbedded.kt` — populate the new fields, drop the Preferences wiring**

In `buildGlassUiState(status, lights)`, add:
```kotlin
stepsText = status.stepsText ?: "--",
hrText = status.hrText ?: "--",
```
(both are `String?` on `StatusCardState` — default to `"--"` the same way every other missing-data field in
this function already does).

In the `StatusAgoraCard(...)` call inside `GlassOverviewComposeEmbedded`, remove the line
`onOpenPreferences = commands::openPreferences,`.

- [ ] **Step 4: `StatusAgoraCard.kt` — remove the Preferences pill, add the Activity pill**

Remove the `onOpenPreferences: () -> Unit,` parameter from `StatusAgoraCard`'s signature.

Remove this whole `GlassPill` block (the last one in the RIGHT column):
```kotlin
GlassPill(
    label = stringResource(CoreUiR.string.nav_preferences),
    value = "—",
    isDark = isDark,
    modifier = Modifier.width(100.dp).clickable { onOpenPreferences() },
    leadingIcon = {
        Icon(
            painter = painterResource(id = R.drawable.ic_glyco_settings),
            contentDescription = null,
            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.size(14.dp)
        )
    }
)
```
replace it with:
```kotlin
GlassPill(
    label = stringResource(R.string.dashboard_glass_activity_label),
    value = stringResource(R.string.dashboard_glass_activity_value, state.stepsText, state.hrText),
    isDark = isDark,
    modifier = Modifier.width(100.dp),
    leadingIcon = {
        Icon(
            painter = painterResource(id = R.drawable.ic_glyco_settings),
            contentDescription = null,
            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.size(14.dp)
        )
    }
)
```
This pill is intentionally NOT clickable (no `.clickable { ... }` on its `modifier`) — steps/HR have no
associated detail screen in this plan; it is purely informational, matching how several other pills already
render.

The `ic_glyco_settings` drawable is reused here as a placeholder icon (same one Preferences used) — if a more
step/heart-specific icon is wanted later, that is a separate, purely cosmetic follow-up, not blocking this
task. Note this reuse explicitly in your implementation report so the controller can decide whether a follow-up
icon swap is worth a separate task.

If `CoreUiR` (the `app.aaps.core.ui.R as CoreUiR` import) is no longer used anywhere else in this file after
removing the Preferences pill, remove that import too — check first.

- [ ] **Step 5: Remove `openPreferences()` from `GlassHeroCommands.kt`**

Remove `fun openPreferences()` from the `GlassHeroCommands` interface and `override fun openPreferences() {}`
from `NoopGlassHeroCommands`.

- [ ] **Step 6: Remove the implementation and now-dead helper from `DashboardShellController.kt`**

Remove:
```kotlin
override fun openPreferences() = withPreferencesProtection {
    uiInteraction.openComposeMainAtRoute(host.context, "preferences")
}
```
from `createGlassHeroCommands()`.

Then check whether `withPreferencesProtection` (a private fun in this same file) has any OTHER caller left
after this removal (search the whole file). If this was its only caller, remove the now-dead
`withPreferencesProtection` function too — do not leave unused private functions behind.

- [ ] **Step 7: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 2: `GlassSensorQualityScreen` + navigation wiring

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassSensorQualityScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: route string `"glass_sensor_quality"`, a real `AppRoute.GlassSensorQuality`. Consumed by Task 3
  (not yours — that task wires the Loop-pill-style gesture that opens this route; you only need to make the
  route resolve to a working screen).

This screen reads the SAME `OverviewViewModel` instance already used elsewhere in this app (obtained via
`ViewModelProvider(activity)`, the exact same pattern `AimiDashboardComposeRootView.kt` already uses for
`OverviewViewModel`/`GraphViewModel`/`StatusViewModel`) — no new ViewModel is created by this task.

- [ ] **Step 1: Add string resources**

```xml
<string name="dashboard_glass_sensor_quality_title">Sensor Quality</string>
<string name="dashboard_glass_sensor_quality_empty">No smoothing quality data available. This screen shows data only when the active smoothing plugin reports it.</string>
```

- [ ] **Step 2: Write `GlassSensorQualityScreen.kt`**

This follows the exact same shell pattern already shipped and working in `GlassLoopDashboardScreen.kt` (header
Row with back arrow + title, `GlassScreenBackground`-equivalent gradient — note: this file lives in
`plugins:main`, which does NOT import `:core:ui`'s shared `GlassScreenBackground`/`GlassColors` kit (that kit
is used by `:ui`'s restyled screens; `plugins:main`'s own Glass files use their own local hex-color constants
directly, exactly like `GlassLoopDashboardScreen.kt` does — follow that file's local-constants style here, do
not import `app.aaps.core.ui.compose.glass.*`).

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.core.interfaces.rx.events.EventAdaptiveSmoothingQuality.AdaptiveSmoothingQualityTier
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel

@Composable
internal fun GlassSensorQualityScreen(
    overviewViewModel: OverviewViewModel,
    onBack: () -> Unit,
    isDark: Boolean,
) {
    val status by overviewViewModel.statusCardState.observeAsState()

    val bgColorTop = if (isDark) Color(0xFF070E1B) else Color(0xFFF1F5F9)
    val bgColorBot = if (isDark) Color(0xFF0B1424) else Color(0xFFE8EEF8)
    val cardBgStart = if (isDark) Color(0x24FFFFFF) else Color(0xF5FFFFFF)
    val cardBgEnd = if (isDark) Color(0x0EFFFFFF) else Color(0xE0EEF2FA)
    val textBright = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)

    val tier = status?.adaptiveSmoothingQualityTier
    val tierColor = when (tier) {
        AdaptiveSmoothingQualityTier.OK -> Color(0xFF10B981)
        AdaptiveSmoothingQualityTier.UNCERTAIN -> Color(0xFFF59E0B)
        AdaptiveSmoothingQualityTier.BAD -> Color(0xFFEF4444)
        null -> textMuted
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgColorTop, bgColorBot, bgColorTop)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = textBright)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_sensor_quality_title),
                    color = textBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(cardBgStart, cardBgEnd)))
                    .border(1.dp, tierColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (status?.adaptiveSmoothingQualityBadgeText.isNullOrBlank() && status?.adaptiveSmoothingQualityDialogMessage.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.dashboard_glass_sensor_quality_empty),
                        color = textMuted,
                        fontSize = 13.sp
                    )
                } else {
                    if (!status?.adaptiveSmoothingQualityBadgeText.isNullOrBlank()) {
                        Text(
                            text = status?.adaptiveSmoothingQualityBadgeText.orEmpty(),
                            color = tierColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!status?.adaptiveSmoothingQualityDialogMessage.isNullOrBlank()) {
                        Text(
                            text = status?.adaptiveSmoothingQualityDialogMessage.orEmpty(),
                            color = textBright,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}
```
Add the missing `import androidx.compose.ui.draw.clip` (needed for `.clip(RoundedCornerShape(16.dp))` above —
check it is not already present before adding a duplicate).

- [ ] **Step 3: Add the route**

In `AppRoute.kt`, next to `AppRoute.GlassLoopDashboard`, add:
```kotlin
data object GlassSensorQuality : AppRoute("glass_sensor_quality")
```

- [ ] **Step 4: Add the nav destination**

In `AppNavGraph.kt`, next to the existing `composable(AppRoute.GlassLoopDashboard.route) { ... }` destination
(read that block first — this task follows the exact same dark-mode-sourcing pattern it already uses:
`LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode`, never `isSystemInDarkTheme()` directly except in the
`SYSTEM` branch), add:
```kotlin
composable(AppRoute.GlassSensorQuality.route) {
    val preferences = LocalPreferences.current
    val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
    val isDark = when (UiMode.fromString(darkModeValue)) {
        UiMode.LIGHT -> false
        UiMode.DARK -> true
        UiMode.SYSTEM -> isSystemInDarkTheme()
    }
    GlassSensorQualityScreen(
        overviewViewModel = overviewViewModel,
        onBack = { navController.safePopBackStack() },
        isDark = isDark,
    )
}
```
This destination needs an `OverviewViewModel` instance in scope. Check how the `GlassLoopDashboard` destination
(or any other destination in this file) obtains a ViewModel scoped to the current activity — if `AppNavGraph`'s
`appNavGraph(...)` function does not already receive an `OverviewViewModel` parameter, add one
(`overviewViewModel: OverviewViewModel`) to its parameter list, matching the exact style of the existing
`graphViewModel: GraphViewModel` parameter already there, and thread it through from `ComposeMainActivity.kt`'s
`appNavGraph(...)` call the same way `graphViewModel = graphViewModel` is already passed (obtain it in
`ComposeMainActivity.kt` via `ViewModelProvider(this, deps.overviewViewModelFactory)` if not already available
as a property — check `AimiDashboardComposeRootView.kt`'s `onAttachedToWindow()` for the exact factory/key
pattern already used there, `deps.overviewViewModelFactory` + a `VIEW_MODEL_KEY` constant, and replicate
whichever access pattern is simplest given what `ComposeMainActivity.kt` already has in scope — if
`ComposeMainActivity.kt` already holds an `OverviewViewModel` property for another purpose, reuse it instead of
creating a second instance).

Add the import `import app.aaps.plugins.main.general.dashboard.glass.GlassSensorQualityScreen` and
`import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel` (check neither is already
present).

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 3: Sensor pill gesture split

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: route string `"glass_sensor_quality"` (Task 2).
- Produces: `GlassHeroCommands.openSensorQuality()`, wired end-to-end from the Sensor pill's short-tap gesture,
  exactly mirroring the Loop pill's existing short-tap/long-press split (`onOpenLoopDashboard`/`onOpenLoop`).

- [ ] **Step 1: Add the command**

In `GlassHeroCommands.kt`, add `fun openSensorQuality()` to the interface and `NoopGlassHeroCommands`, right
next to `openSensorInsert()`.

- [ ] **Step 2: Implement it in `DashboardShellController.kt`**

Next to `openSensorInsert()` in `createGlassHeroCommands()`, add:
```kotlin
override fun openSensorQuality() =
    uiInteraction.openComposeMainAtRoute(host.context, "glass_sensor_quality")
```
(No protection check — this is a read-only info screen, same as `openStatsScreen()`/`openTreatmentsScreen()`,
not a therapy-affecting action like `openSensorInsert()`, which correctly keeps its `withBolusProtection`.)

- [ ] **Step 3: Split the Sensor pill's gesture in `StatusAgoraCard.kt`**

Add a new parameter to `StatusAgoraCard`'s signature, next to `onOpenSensorInsert: () -> Unit,`:
```kotlin
onOpenSensorQuality: () -> Unit,
```

Change the Sensor `GlassPill`'s modifier from:
```kotlin
modifier = Modifier.width(100.dp).clickable { onOpenSensorInsert() },
```
to:
```kotlin
modifier = Modifier.width(100.dp).combinedClickable(
    onClick = onOpenSensorQuality,
    onLongClick = onOpenSensorInsert
),
```
(`combinedClickable` and `@OptIn(ExperimentalFoundationApi::class)` are already imported/applied on this file
from the Loop pill's earlier gesture split — no new opt-in needed.)

- [ ] **Step 4: Thread the new command through the call site**

In `GlassOverviewComposeEmbedded.kt`, the `StatusAgoraCard(...)` call currently passes
`onOpenSensorInsert = commands::openSensorInsert,` — add, right after it:
```kotlin
onOpenSensorQuality = commands::openSensorQuality,
```

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

- [ ] **Step 6: Manual verification note for the user**

Cannot be meaningfully unit-tested (pure gesture wiring). Flag for the user: short tap on the Sensor pill opens
the new Sensor Quality screen, long-press still opens the sensor-insert form exactly as before.

---

## Task 4: BG chart predictions — data model + mapper

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`
- Test: `plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartStateMapperTest.kt`

**Interfaces:**
- Produces: `PredictionPoint(progress: Float, value: Float, type: PredictionType)`, `PredictionType` enum,
  `GlassChartState.predictions: List<PredictionPoint>`, `GlassChartState.historyFraction: Float` — consumed by
  Task 5 (BgChartCard's drawing, not yours to modify).

A fixed 2-hour prediction horizon is used (`PREDICTION_HORIZON_HOURS = 2`) — long enough to show the typical
AIMI/oref SMB prediction curve, short enough that it doesn't dominate the chart at the shortest (6h) range
selection.

- [ ] **Step 1: Add `PredictionPoint`/`PredictionType` to `GlassChartModels.kt`**

```kotlin
enum class PredictionType { IOB, COB, A_COB, UAM, ZT }
data class PredictionPoint(val progress: Float, val value: Float, val type: PredictionType)
```
Add `predictions: List<PredictionPoint> = emptyList()` and `historyFraction: Float = 1f` to `GlassChartState`,
right after `treatments`. Update the class's KDoc to note: `predictions`' `progress` is on a SEPARATE 0..1
scale (0 = now, 1 = end of the fixed prediction horizon), NOT the same window-relative scale as `bgReadings`/
`treatments` — `historyFraction` is what a renderer uses to place both scales on one chart width (history
occupies `[0, historyFraction]`, predictions occupy `[historyFraction, 1]` of the total chart width).

- [ ] **Step 2: Extend `buildGlassChartState` in `GlassOverviewComposeEmbedded.kt`**

Add two new parameters to `buildGlassChartState`: `predictions: List<BgDataPoint>` and
`predictionHorizonHours: Int = 2`.

Inside the function, after the existing `windowedCarbs` computation, add:
```kotlin
val predictionWindowEnd = nowEpochMs + predictionHorizonHours * 3_600_000L
val windowedPredictions = predictions
    .filter { it.timestamp in nowEpochMs..predictionWindowEnd }
    .sortedBy { it.timestamp }

fun predictionProgress(timestamp: Long): Float =
    ((timestamp - nowEpochMs).toFloat() / (predictionWindowEnd - nowEpochMs).toFloat()).coerceIn(0f, 1f)

fun predictionType(type: BgType): PredictionType? = when (type) {
    BgType.IOB_PREDICTION   -> PredictionType.IOB
    BgType.COB_PREDICTION   -> PredictionType.COB
    BgType.A_COB_PREDICTION -> PredictionType.A_COB
    BgType.UAM_PREDICTION   -> PredictionType.UAM
    BgType.ZT_PREDICTION    -> PredictionType.ZT
    else                    -> null
}

val predictionPoints = windowedPredictions.mapNotNull { p ->
    val type = predictionType(p.type) ?: return@mapNotNull null
    PredictionPoint(
        progress = predictionProgress(p.timestamp),
        value = mgdlToChartY(p.value).toFloat(),
        type = type,
    )
}

val historyFraction = rangeHours.toFloat() / (rangeHours + predictionHorizonHours).toFloat()
```
Add the import `import app.aaps.core.interfaces.overview.graph.BgType` (check it is not already present).

Add `predictions = predictionPoints,` and `historyFraction = historyFraction,` to the `GlassChartState(...)`
construction at the end of the function.

Then, in `GlassOverviewComposeEmbedded`'s composable body, collect the predictions flow next to the existing
flow collections:
```kotlin
val predictions by graphViewModel.predictionsFlow.collectAsStateWithLifecycle()
```
and add `predictions` to the `buildGlassChartState(...)` call's arguments (`predictions = predictions,`) and to
the surrounding `remember(...)` key list (so the chart recomputes when new predictions arrive):
```kotlin
val chartState = remember(rangeHours, bgReadings, iobData, treatmentData, chartConfig, predictions, status?.pumpStatusText) {
```

- [ ] **Step 3: Write the test**

Add to the existing `GlassChartStateMapperTest.kt` (read its current content first — it already has an
`identity`/`mgdlToMmol`-style converter pattern and a `bg(timestamp, value)` helper you should reuse):

```kotlin
@Test
fun `predictions outside the 2-hour horizon are excluded, and progress is 0 at now, 1 at horizon end`() {
    val nowEpochMs = 100_000_000L
    val rangeHours = 6

    val result = buildGlassChartState(
        rangeHours = rangeHours,
        nowEpochMs = nowEpochMs,
        bgReadings = emptyList(),
        iobPoints = emptyList(),
        boluses = emptyList(),
        carbs = emptyList(),
        chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
        mgdlToChartY = identity,
        pumpStatusText = "",
        predictions = listOf(
            BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            BgDataPoint(timestamp = nowEpochMs + 3_600_000L, value = 100.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            BgDataPoint(timestamp = nowEpochMs + 2 * 3_600_000L, value = 90.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
            BgDataPoint(timestamp = nowEpochMs + 3 * 3_600_000L, value = 80.0, range = BgRange.IN_RANGE, type = BgType.IOB_PREDICTION),
        ),
    )

    assertThat(result.predictions).hasSize(3)
    assertThat(result.predictions[0].progress).isEqualTo(0f)
    assertThat(result.predictions[2].progress).isEqualTo(1f)
    assertThat(result.historyFraction).isWithin(0.001f).of(6f / 8f)
}

@Test
fun `unrecognized BgType predictions are silently dropped, not crashed on`() {
    val nowEpochMs = 100_000_000L

    val result = buildGlassChartState(
        rangeHours = 6,
        nowEpochMs = nowEpochMs,
        bgReadings = emptyList(),
        iobPoints = emptyList(),
        boluses = emptyList(),
        carbs = emptyList(),
        chartConfig = ChartConfig(highMark = 180.0, lowMark = 70.0),
        mgdlToChartY = identity,
        pumpStatusText = "",
        predictions = listOf(
            BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.REGULAR),
            BgDataPoint(timestamp = nowEpochMs, value = 120.0, range = BgRange.IN_RANGE, type = BgType.BUCKETED),
        ),
    )

    assertThat(result.predictions).isEmpty()
}
```
Add `import app.aaps.core.interfaces.overview.graph.BgType` to the test file if not already present via a
wildcard/existing import — check first.

- [ ] **Step 4: Run the test and compile**

Run: `./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassChartStateMapperTest*" --no-daemon`.
Expected: BUILD SUCCESSFUL, all tests (existing + 2 new) passing.

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL (this task alone
leaves `BgChartCard` not yet accepting the new fields — that's fine, `GlassChartState.predictions`/
`.historyFraction` simply aren't consumed yet until Task 5; nothing about this task's own changes should fail
to compile on their own).

---

## Task 5: BG chart predictions — Canvas drawing

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartComponents.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: `GlassChartState.predictions`/`.historyFraction` (Task 4, already shipped).
- Produces: no new public interface — `BgChartCard` gains 2 new parameters, its call site is updated.

Read `GlassChartComponents.kt`'s current full content before editing — this task rescales EVERY existing
x-position computation in `BgChartCard` (both the touch-gesture block and the Canvas draw block) so history
occupies `[0, chartW * historyFraction]` and predictions occupy `[chartW * historyFraction, chartW]`. This is
the single most invasive change in this plan — go through the file systematically, do not skip any
`.progress * chartW` occurrence.

- [ ] **Step 1: Add the new parameters**

Add to `BgChartCard`'s signature, after `highLine: Float,` and before `axisMinValue: Float,` (or anywhere
convenient in the parameter list — exact position doesn't matter, named arguments are used at the call site):
```kotlin
predictions: List<PredictionPoint>,
historyFraction: Float,
```

- [ ] **Step 2: Add a history/prediction x-mapping helper, replace every existing `.progress * chartW`**

Inside the `Canvas(...)` draw block, right after `val chartW = w - paddingRight` is computed, add:
```kotlin
fun historyX(progress: Float): Float = progress * chartW * historyFraction
fun predictionX(progress: Float): Float = (historyFraction + progress * (1f - historyFraction)) * chartW
```

Then replace EVERY occurrence of `<something>.progress * chartW` in the Canvas draw block (the BG curve
segments, the current-reading circle, the treatment markers, the touched-point indicator — search for
`.progress * chartW` to find all of them) with `historyX(<something>.progress)`. Do the same inside the
`pointerInput { ... }` touch-gesture block above the Canvas (the `val p = (down.position.x / chartW)...` and
`val px = (event.changes.first().position.x / chartW)...` lines) — these compute a progress FROM an x
position, so they need the inverse: change
```kotlin
val p = (down.position.x / chartW).coerceIn(0f, 1f)
```
to
```kotlin
val p = (down.position.x / (chartW * historyFraction)).coerceIn(0f, 1f)
```
(and the same for the `px` line inside the `while (true)` loop) — this keeps touch-to-reading lookup scoped to
only the historical portion of the chart (tapping in the prediction zone simply won't match any reading, which
is correct — there is no historical reading to select there).

- [ ] **Step 3: Draw the "now" line**

Right after the existing "3. Vertical time lines and labels" block (`for (i in 0 until timeTicks) { ... }`),
add a new numbered block:
```kotlin
// 3b. "Now" line separating history from predictions
val nowX = chartW * historyFraction
drawLine(
    color = if (isDark) Color(0x40FFFFFF) else Color(0x30000000),
    start = Offset(nowX, 0f),
    end = Offset(nowX, chartH),
    strokeWidth = 1.5f,
    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
)
```

- [ ] **Step 4: Draw the predictions**

Right after the "5. Treatment markers" block (find it — draws carb/bolus circles), add a new numbered block:
```kotlin
// 5b. BG predictions (extend past "now" into the fixed prediction horizon)
if (predictions.isNotEmpty()) {
    fun predictionColor(type: PredictionType): Color = when (type) {
        PredictionType.IOB   -> Color(0xFF64B5F6)
        PredictionType.COB   -> Color(0xFFFFB74D)
        PredictionType.A_COB -> Color(0xFFFFB74D).copy(alpha = 0.5f)
        PredictionType.UAM   -> Color(0xFFE6D39A)
        PredictionType.ZT    -> Color(0xFF4DD4D4)
    }

    predictions.groupBy { it.type }.forEach { (type, points) ->
        val sorted = points.sortedBy { it.progress }
        if (sorted.size >= 2) {
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                drawLine(
                    color = predictionColor(type),
                    start = Offset(predictionX(prev.progress), mapY(prev.value)),
                    end = Offset(predictionX(curr.progress), mapY(curr.value)),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
            }
        }
        sorted.forEach { point ->
            drawCircle(
                color = predictionColor(type),
                radius = 4f,
                center = Offset(predictionX(point.progress), mapY(point.value))
            )
        }
    }
}
```
The dark-mode-tuned hex values above are the same "lighter" prediction colors DASHBOARD_V2 already uses in
dark mode (`AapsTheme.generalColors`'s dark-mode `iobPrediction`/`cobPrediction`/`aCobPrediction`/
`uamPrediction`/`ztPrediction`) — reused here as plain literals rather than importing `AapsTheme` into this
file, since every other color in `GlassChartComponents.kt` is already a plain hex literal, not a theme
reference; keep this file's existing style consistent. These are the SAME colors regardless of `isDark` in
this simplified version — if the light-mode variants are wanted too, that is a small, separate cosmetic
follow-up (the light-mode hex values are noted in this plan's own research: `iobPrediction = 0xFF1E88E5`,
`cobPrediction = 0xFFFB8C00`, `aCobPrediction = 0x80FB8C00`, `uamPrediction = 0xFFC9BD60`,
`ztPrediction = 0xFF00D2D2`), not blocking this task.

Add the import `import androidx.compose.ui.graphics.StrokeCap` if not already present (check — `StrokeCap` may
already be imported for the existing BG curve's `Stroke(... cap = StrokeCap.Round ...)`).

- [ ] **Step 5: Update the call site**

In `GlassOverviewComposeEmbedded.kt`'s `BgChartCard(...)` call, add:
```kotlin
predictions = chartState.predictions,
historyFraction = chartState.historyFraction,
```

- [ ] **Step 6: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Manual verification note for the user**

Cannot be meaningfully tested without live APS prediction data. Flag for the user: open the Glass dashboard
while the loop has a recent run with predictions, confirm the BG chart now shows a dashed "now" line and
colored prediction dots/lines extending past it (colors: blue=IOB, orange=COB, lighter orange=aCOB,
yellow=UAM, cyan=ZT), and confirm history still renders and scrolls/taps correctly in the (now narrower)
historical portion of the chart.

---

## Task 6: Tools entry point (reuse `DashboardV2ToolsScreen`)

**Files:**
- Modify: `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/dashboard/DashboardOverviewHost.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/AimiDashboardComposeRootView.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewModels.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `DashboardV2ToolsScreen(paddingValues, fabBottomOffset, availablePluginClassNames, onAction, modifier)`
  (already shipped, in `app.aaps.plugins.main.general.dashboard`), the exact existing `onAction` handler logic
  already written in `ComposeMainActivity.kt` for DASHBOARD_V2 (reused verbatim, not rewritten).
- Produces: a new "Tools" pill on the Glass main screen that renders `DashboardV2ToolsScreen` as a local
  full-screen overlay. No change to `MainScreen.kt` (per the user's explicit decision).

This task threads 2 new values from `ComposeMainActivity.kt` down to `GlassOverviewComposeEmbedded.kt`:
`availablePluginClassNames: Set<String>` and `onToolAction: (DashboardV2ToolAction) -> Unit`. Both are plain
(non-Composable) values, so they pass cleanly through `AimiDashboardComposeRootView`'s constructor (a plain
Android `View`, not a Composable function) the same way any other constructor parameter would.

- [ ] **Step 1: Add string resources**

```xml
<string name="dashboard_glass_tools_label">Tools</string>
```

- [ ] **Step 2: `ComposeMainActivity.kt` — broaden the plugin-class-names computation, pass 2 new values into `DashboardOverviewHost`**

Read the current `val dashboardV2PluginClassNames = if (dashboardHomeVariant == DashboardHomeVariant.DASHBOARD_V2) { ... } else { emptySet() }`
block (around line 683) and the `dashboardTools = if (dashboardHomeVariant == DashboardHomeVariant.DASHBOARD_V2) { ... } else { null }`
block (around line 872) — DO NOT modify either of these; they stay exactly as they are (DASHBOARD_V2's own
Tools-tab mechanism through `MainScreen.kt` is untouched, per the user's explicit decision not to touch that
file).

Instead, compute a SEPARATE value usable by BOTH dashboards' plugin-class-name needs, right next to the
existing `dashboardV2PluginClassNames` val:
```kotlin
val availablePluginClassNames = activePlugin.getPluginsList()
    .asSequence()
    .filter(PluginBase::hasComposeContent)
    .map { it.javaClass.simpleName }
    .toSet()
```
(This is the exact same computation `dashboardV2PluginClassNames` already does, minus the `dashboardHomeVariant`
gate — safe to compute unconditionally since it's a cheap, pure list filter with no side effects. Leave
`dashboardV2PluginClassNames` itself completely unchanged; both values can coexist, even though they'll be
identical when `dashboardHomeVariant == DASHBOARD_V2`.)

Find where `DashboardOverviewHost(...)` is called (inside the `dashboardOverview = if (showDashboardHome) { { pad, fab -> DashboardOverviewHost(...) } }`
block, around line 863) and add 2 new named arguments:
```kotlin
availablePluginClassNames = availablePluginClassNames,
onToolAction = { action ->
    when (val destination = action.destination) {
        DashboardV2ToolDestination.Actions -> manageSheetState.show()
        is DashboardV2ToolDestination.Element -> handleNavigationRequest(
            NavigationRequest.Element(destination.type),
            navController,
        )

        is DashboardV2ToolDestination.Plugin -> handleNavigationRequest(
            NavigationRequest.Plugin(destination.className),
            navController,
        )

        is DashboardV2ToolDestination.AimiActivity -> launchDashboardV2Aimi(action)
    }
},
```
This is a byte-for-byte copy of the `onAction` lambda already written inside the `dashboardTools` block a few
dozen lines below — same handler, same imports already present in this file, just also wired to
`DashboardOverviewHost` so Glass gets it too.

- [ ] **Step 3: `DashboardOverviewHost.kt` — accept and forward the 2 new parameters**

Add 2 new parameters to `DashboardOverviewHost`'s signature:
```kotlin
availablePluginClassNames: Set<String>,
onToolAction: (app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction) -> Unit,
```
(use a proper import + short name `DashboardV2ToolAction` instead of the fully-qualified form above — add
`import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction` at the top of the file).

Pass them into the `AimiDashboardComposeRootView(...)` constructor call inside the `factory = { ctx -> ... }`
lambda:
```kotlin
AimiDashboardComposeRootView(
    context = ctx,
    dashboardHomeVariant = dashboardHomeVariant,
    availablePluginClassNames = availablePluginClassNames,
    onToolAction = onToolAction,
)
```

- [ ] **Step 4: `AimiDashboardComposeRootView.kt` — accept the 2 new constructor parameters, thread into the GLASS branch**

Add 2 new constructor parameters to `AimiDashboardComposeRootView`'s `@JvmOverloads constructor(...)`, with
defaults so every OTHER call site of this View (if any exist beyond `DashboardOverviewHost.kt` — check) keeps
compiling unchanged:
```kotlin
private val availablePluginClassNames: Set<String> = emptySet(),
private val onToolAction: (DashboardV2ToolAction) -> Unit = {},
```
Add the import `import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction` (same package this file
already lives in — `app.aaps.plugins.main.general.dashboard` — so it may not even need an explicit import;
check first, since same-package references don't need one).

In the `when (dashboardHomeVariant) { ... }` block's `DashboardHomeVariant.GLASS -> { ... }` branch, add 2 new
named arguments to the `GlassOverviewComposeEmbedded(...)` call:
```kotlin
availablePluginClassNames = availablePluginClassNames,
onToolAction = onToolAction,
```

- [ ] **Step 5: `GlassOverviewComposeEmbedded.kt` — accept the 2 new parameters, render the Tools overlay**

Add 2 new parameters to `GlassOverviewComposeEmbedded`'s signature, after `embeddedState: DashboardEmbeddedComposeState,`:
```kotlin
availablePluginClassNames: Set<String>,
onToolAction: (DashboardV2ToolAction) -> Unit,
```
Add the import `import app.aaps.plugins.main.general.dashboard.DashboardV2ToolAction` (same package — check
whether an explicit import is actually needed, since this file is already in
`app.aaps.plugins.main.general.dashboard.glass`, a SUBPACKAGE of where `DashboardV2ToolAction` lives —
Kotlin requires an explicit import even for a parent package's classes, so add it) and
`import app.aaps.plugins.main.general.dashboard.DashboardV2ToolsScreen`.

Add local overlay state right after `var rangeHours by remember { mutableStateOf(6) }`:
```kotlin
var showTools by remember { mutableStateOf(false) }
```

Wrap the existing `Column(...) { ... }` (the whole current screen body) and the new Tools overlay in a `Box`,
so the overlay can render on top. Change:
```kotlin
Column(
    modifier = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    StatusAgoraCard(
        ...
    )
    ...
}
```
to:
```kotlin
Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusAgoraCard(
            ...
            onOpenTools = { showTools = true },
        )
        ...
    }
    if (showTools) {
        DashboardV2ToolsScreen(
            paddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
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
(the `...` markers above stand for this composable's EXISTING unchanged content — every other parameter
`StatusAgoraCard` already receives, and every other child composable already in the `Column`, stay exactly as
they are; only the outer `Column` → `Box { Column { ... } ; if (showTools) { ... } }` restructuring and the one
new `onOpenTools` argument are new). Add the import
`import androidx.compose.foundation.layout.Box` and use a proper import for `PaddingValues`
(`import androidx.compose.foundation.layout.PaddingValues`) instead of the fully-qualified form shown above —
the inline fully-qualified form is only to pin the exact type for this brief.

`DashboardV2ToolsScreen` has no built-in back/dismiss button of its own visible at zero padding — check its
actual rendered content (read `DashboardV2ToolsScreen.kt`) for whether it already includes some form of
back/close affordance; if it does not, that is an accepted gap for THIS task (a follow-up could add a close
button overlay) — do not invent new UI inside `DashboardV2ToolsScreen` itself, since it's a shared, already-
shipped component also used by DASHBOARD_V2 exactly as-is.

- [ ] **Step 6: Add the Tools pill to `StatusAgoraCard.kt`**

Add a new parameter `onOpenTools: () -> Unit,` to `StatusAgoraCard`'s signature (in the same parameter-list
area as the other `onOpenX` callbacks).

Add a new `GlassPill` in the LEFT column (pump-status column), right after the Battery pill (this column now
has 4 pills instead of 3 — the RIGHT column still has 2 after Task 1 removed Preferences, so the two columns
become visually balanced again):
```kotlin
GlassPill(
    label = stringResource(R.string.dashboard_glass_tools_label),
    value = "",
    isDark = isDark,
    modifier = Modifier.width(100.dp).clickable { onOpenTools() },
    leadingIcon = {
        Icon(
            painter = painterResource(id = R.drawable.ic_glyco_settings),
            contentDescription = null,
            tint = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B),
            modifier = Modifier.size(14.dp)
        )
    }
)
```
(`ic_glyco_settings` reused again, same reasoning/flag as Task 1's Activity pill — a dedicated "tools" icon is
a cosmetic follow-up, not blocking.)

- [ ] **Step 7: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

- [ ] **Step 8: Manual verification note for the user**

Cannot be meaningfully tested without a live device/emulator. Flag for the user: tapping the new Tools pill on
the Glass dashboard opens the same Tools screen DASHBOARD_V2 shows (Advisor, Meal Advisor, AIMI Context, and
the other existing entries), each entry behaves identically to its DASHBOARD_V2 counterpart, and confirm there
is SOME way to leave the Tools overlay back to the Glass dashboard (check whether `DashboardV2ToolsScreen`
itself provides one before assuming a gap).

---

## Self-Review Notes

- **Spec coverage**: all 5 feedback items have a task (Task 1: Preferences removal + Activity pill; Task 2-3:
  Sensor pill split + new screen; Task 4-5: BG predictions; Task 6: Tools entry point).
- **No placeholders**: every new composable's full code is given verbatim; the one deliberately-scoped-out
  detail (a dedicated Tools/Activity icon instead of reusing `ic_glyco_settings`) is explicitly flagged as a
  non-blocking cosmetic follow-up in the tasks that touch it, not silently glossed over.
- **Unit-space correctness**: Task 4's mapper explicitly reuses the SAME `mgdlToChartY` conversion already
  used for regular BG readings — this is called out as a Global Constraint given this bug class's history in
  this feature.
- **Type consistency**: `PredictionPoint`/`PredictionType`/`historyFraction` (Task 4) are used with identical
  names and shapes in Task 5's `BgChartCard` changes.
- **Known judgment calls, recorded rather than silently resolved**: (a) the "now" line and prediction dashing/
  colors are a reasonable, working first pass, not pixel-matched to any specific mockup — flagged for the
  user's visual review; (b) whether `DashboardV2ToolsScreen` has its own back affordance is unverified at
  plan-writing time — Task 6 tells the implementer to check rather than assume either way.
- **Manual verification notes**: Tasks 3, 5, and 6 each cannot be meaningfully unit-tested (gesture wiring,
  live prediction data, on-device navigation) — each names exactly what the user should check after this plan
  ships, matching the precedent set by every prior Glass plan's final "Self-Review Notes" section.
