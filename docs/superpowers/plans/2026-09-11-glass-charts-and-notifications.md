# Glass Charts, Range Selector & Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the Glass home screen (started in `2026-09-10-glass-status-agora-card.md`) by adding the BG
and IOB charts, the 6/12/18/24h range selector, Stats/Treatment shortcuts, pump-status banner, and system
notifications — so selecting the Glass skin shows a fully functional home screen, not just the status card.

**Architecture:** Same pattern as the prior plan: port the reference's pure-Compose chart/action composables
near-verbatim (they take only primitive types, no reference-fork dependency), and build ONE new pure mapping
function (`buildGlassChartState`, parallel to the already-shipped `buildGlassUiState`) that derives real,
correctly-unit-converted data from `GraphViewModel`'s existing flows — the same ones DASHBOARD_V2's own BG/IOB
Vico charts already read, just re-windowed into flat `progress: Float` lists instead of a Vico viewport.
Notifications reuse the exact binding DASHBOARD_V2 already has active on `DashboardEmbeddedComposeState`
(`DashboardShellController.attachShell()` already calls `notificationUiBinder.bindCompose(...)` into
`host.embeddedComposeState.notifications` for BOTH dashboard variants) — Glass just needs to read that same
already-live state, not build a new binding.

**Tech Stack:** Kotlin, Jetpack Compose (Canvas-based custom chart drawing — no Vico here, matching the
reference's exact visual style), Hilt (existing ViewModels only).

**Spec:** `docs/superpowers/specs/2026-09-10-glass-dashboard-port-design.md`

## Global Constraints

- No new inter-module Gradle dependencies.
- No hardcoded unit strings ("mg/dL"/"U") where a real, already-computed unit-aware value exists — this
  exact mistake (C4) was found and fixed in the prior plan's final review; do not reintroduce it. Use
  `GraphViewModel.formatBgChartAxisTick(...)`/`GlucoseUnit.displayLabel` for BG, plain `"U"` suffix is
  acceptable for IOB (units never vary — insulin is always in "U" regardless of the glucose-unit preference).
- No hardcoded literal English/Portuguese pill/button labels where a string resource already exists — reuse
  `app.aaps.plugins.main.R.string.stats_button` and `app.aaps.core.ui.R.string.overview_treatment_label`,
  confirmed as the exact two resources DASHBOARD_V2's own Stats/Treatment pills already use
  (`plugins/main/.../dashboard/compose/DashboardGraphComposeControls.kt:33,54`) — do not add new ones.
- No `git commit` unless the user explicitly asks — leave all work in the working tree.
- Compile after every task; this UI has no automated Compose test harness in this module, so manual on-device
  verification is the final step, same as the prior plan.
- Simple, plain English in any new user-facing string, KDoc, or comment.
- Every new pure-logic function (mirroring `buildGlassUiState`) must be `internal` (not `private`) and get a
  unit test, per the lesson from the prior plan's I5 finding.

---

### Task 1: Chart data models

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartModels.kt`

**Interfaces:**
- Produces: `data class BgReadingPoint(val progress: Float, val value: Float)`,
  `data class IobReadingPoint(val progress: Float, val iob: Float)`,
  `data class TreatmentPoint(val progress: Float, val isCarb: Boolean, val label: String, val timestamp: Long = 0L)`,
  `data class GlassChartState(...)` — consumed by Task 2 (chart composables) and Task 6 (the mapping function).

`progress` is always `0f..1f` across the currently-selected visible window (0 = window start, 1 = now) — this
matches the reference's own convention exactly, so the ported chart-drawing code in Task 2 needs no coordinate
changes.

- [ ] **Step 1: Write the file**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

data class BgReadingPoint(val progress: Float, val value: Float)
data class IobReadingPoint(val progress: Float, val iob: Float)
data class TreatmentPoint(val progress: Float, val isCarb: Boolean, val label: String, val timestamp: Long = 0L)

/**
 * Chart-section state for the Glass overview screen — trimmed to what BgChartCard/IobChartCard/TimeFilterBar
 * render. `currentBgValue` is in the SAME space as `bgReadings`' values and `lowLine`/`highLine` — the
 * user's display unit (mg/dL or mmol/L), never raw mg/dL — so the chart never needs its own unit conversion;
 * only display TEXT formatting is the caller's job, via BgChartCard's `formatValue` callback.
 */
data class GlassChartState(
    val bgReadings: List<BgReadingPoint> = emptyList(),
    val iobReadings: List<IobReadingPoint> = emptyList(),
    val treatments: List<TreatmentPoint> = emptyList(),
    val currentBgValue: Float = 0f,
    val currentIob: Float = 0f,
    val lowLine: Float = 70f,
    val highLine: Float = 180f,
    val rangeHours: Int = 6,
    val pumpStatusText: String = "",
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Port `BgChartCard` and `IobChartCard`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartComponents.kt`

**Interfaces:**
- Consumes: `BgReadingPoint`/`IobReadingPoint`/`TreatmentPoint` (Task 1), `GlassContainer` (already shipped,
  `plugins/main/.../dashboard/glass/GlassOverviewComponents.kt`).
- Produces: `@Composable internal fun BgChartCard(readings: List<BgReadingPoint>, treatments: List<TreatmentPoint>, timeRangeHours: Int, currentBgText: String, currentBgValue: Float, lowLine: Float, highLine: Float, bgUnitSuffix: String, isDark: Boolean)`
  and `@Composable internal fun IobChartCard(iobReadings: List<IobReadingPoint>, currentIob: Float, timeRangeHours: Int, isDark: Boolean)` — consumed by Task 6.

Port the reference's `BgChartCard` (source: `~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/glass/GlassOverviewScreen.kt:616-958`)
and `IobChartCard` (same file, lines 963-1192) as close to verbatim as possible — they are pure `Canvas`-drawn
charts with only primitive parameters, no reference-fork dependency. Apply these deliberate adaptations:

1. **Unit label fix (this is the important one — matches the prior plan's C4 fix).** The reference hardcodes
   `"$currentBg mg/dL"` (line 685) and `"%.0f mg/dL"` (line 661) in `BgChartCard`. Replace both with a
   `bgUnitSuffix: String` parameter (added to the function signature) and build the text as
   `"$currentBgText $bgUnitSuffix"` / using the already-formatted point value text passed in — since the
   real BG values need unit-aware formatting (not a raw `%.0f`), change the touched-point display to use a
   pre-formatted string rather than reformatting a raw float: add a `readings: List<BgReadingPoint>` entry's
   `value` is still used for the Y-axis math (mg/dL-space is fine internally for the chart geometry — only the
   **displayed text** needs the real unit), so keep `BgReadingPoint.value` in mg/dL for drawing, but for the
   little "touched point" label at the top of the card, call the same axis-tick formatter Task 6 will already
   have on hand — simplest correct approach: **add a `formatValue: (Float) -> String` parameter** to
   `BgChartCard` instead of `bgUnitSuffix`, and use `formatValue(pt.value)` / `formatValue(readings.last().value)`
   wherever the reference builds `"$bgStr"`/`"$currentBg mg/dL"`. This defers ALL unit logic to the caller
   (Task 6), which already has `GraphViewModel.formatBgChartAxisTick` available — the chart composable itself
   then has zero unit knowledge, which is the safer design (no chance of a second hardcoded-unit bug in this
   file). Use this `formatValue` approach instead of the `bgUnitSuffix` field described above; drop
   `bgUnitSuffix`/`currentBgText` params in favor of `formatValue: (Float) -> String` and `currentBgValue: Float`
   only.
2. Function signature becomes:
   ```kotlin
   @Composable
   internal fun BgChartCard(
       readings: List<BgReadingPoint>,
       treatments: List<TreatmentPoint>,
       timeRangeHours: Int,
       currentBgValue: Float,
       lowLine: Float,
       highLine: Float,
       formatValue: (Float) -> String,
       isDark: Boolean,
   )
   ```
   Replace every `"%.0f mg/dL".format(...)`/`"$currentBg mg/dL"` occurrence with `formatValue(...)` calls on the
   relevant raw float (`pt.value`, `currentBgValue`).
3. `@Composable fun` → `@Composable internal fun` for both (not public, matching this package's convention).
4. Both files' reference imports (`androidx.compose.foundation.*`, `androidx.compose.material3.*` wildcard
   imports) must become explicit imports per this project's convention — list only the symbols actually used
   (`Canvas`, `AlertDialog`, `TextButton`, `Text`, etc.), matching how Task 3 of the prior plan resolved the
   same issue for `GlassOverviewComponents.kt`.
5. Keep the "Treatment Details" `AlertDialog` in `BgChartCard` as-is (English text is fine, no translation
   infrastructure needed for a debug/detail popup — but if you want extra polish, `"Treatment Details"`/`"Type:"`/
   `"Date:"`/`"Time:"`/`"Amount:"`/`"OK"` could become string resources; this is optional polish, not required
   for this task — do not block on it).
6. All Canvas drawing code, gesture handling (`pointerInput`/`awaitEachGesture`), colors, and layout (`height(210.dp)`/`height(145.dp)`, gradients, stroke widths) transfer unchanged — this is deliberate: it is the exact
   visual/interaction design the user has been trying to match.

- [ ] **Step 1: Write the file** with both composables, applying adaptations 1-5 above. Read the reference
  file's exact line ranges cited above for the full source to transcribe.

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Port the range selector and action pills

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassRangeSelector.kt`

**Interfaces:**
- Produces: `@Composable internal fun TimeFilterBar(selectedHours: Int, onSelectHours: (Int) -> Unit, onOpenStats: () -> Unit, onOpenTreatment: () -> Unit, statsLabel: String, treatmentLabel: String, isDark: Boolean)`,
  `@Composable internal fun GlassHourDock(...)`, `@Composable internal fun GlassActionPill(...)` — consumed by
  Task 6.

Port verbatim from the reference (`GlassOverviewScreen.kt:1197-1349` — `TimeFilterBar`, `GlassHourDock`,
`GlassActionPill`), with these adaptations:

1. `TimeFilterBar`'s hardcoded `"Stats"`/`"Treatment"` labels (lines 1212, 1227) become parameters
   `statsLabel: String`/`treatmentLabel: String` instead — Task 6 passes
   `stringResource(app.aaps.plugins.main.R.string.stats_button)` and
   `stringResource(app.aaps.core.ui.R.string.overview_treatment_label)`, the exact two resources confirmed to
   already exist and already be used for DASHBOARD_V2's own Stats/Treatment pills
   (`plugins/main/.../dashboard/compose/DashboardGraphComposeControls.kt:33,54`) — do not add new strings.
2. `${hours}h` labels inside `GlassHourDock` (line 1271) stay as inline string interpolation — this is not
   user-facing translatable text needing a resource (it's a numeric unit label like "6h", consistent with how
   DASHBOARD_V2's own pill row already handles this exact same range-hours labeling without resources).
3. All three composables `@Composable fun` → `@Composable internal fun`.
4. Explicit imports only (no wildcard `androidx.compose.foundation.*`/`material3.*`).

- [ ] **Step 1: Write the file** per the adaptations above.
- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Port the pump-status banner and notifications list

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassNotifications.kt`

**Interfaces:**
- Consumes: `app.aaps.plugins.main.general.overview.notifications.NotificationStore.NotificationComposeItem`
  (existing type: `id: Int, text: String, dismissText: String, level: Int`).
- Produces: `@Composable internal fun PumpStatusNotification(status: String, isDark: Boolean)` and
  `@Composable internal fun NotificationsSection(notifications: List<NotificationStore.NotificationComposeItem>, isDark: Boolean, onDismiss: (Int) -> Unit, modifier: Modifier = Modifier)` — consumed by Task 6.

Port verbatim from the reference (`GlassOverviewScreen.kt:1354-1496`), with these adaptations:

1. `NotificationsSection` currently takes `List<GlassNotificationItem>` (a reference-only type with
   `id, text, level, date`); change the parameter type to
   `List<app.aaps.plugins.main.general.overview.notifications.NotificationStore.NotificationComposeItem>` —
   our own already-existing notification model, so no new data class or mapping layer is needed. The reference
   reads `notification.text`/`notification.level`; both exist identically on `NotificationComposeItem`. Drop
   any reference to `date` (not in our model, and not rendered by this composable anyway — check the reference
   body, it isn't used in the visible UI).
2. The reference hardcodes `"Dismiss"` (line 1485) as the dismiss-button label. Our `NotificationComposeItem`
   already carries a real, purpose-built `dismissText: String` field for exactly this — use
   `notification.dismissText` instead of the literal.
3. `@Composable fun` → `@Composable internal fun` for both.
4. Explicit imports only.
5. Do NOT add a `GlassNotificationItem` data class anywhere (the one in the reference's
   `GlassLoopDashboardViewModel.kt`-adjacent models is not part of this port — we're using the real
   `NotificationComposeItem` type directly, per adaptation 1).

- [ ] **Step 1: Write the file** per the adaptations above.
- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 5: Stats/Treatment commands on `GlassHeroCommands`

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`

**Interfaces:**
- Consumes: `uiInteraction.openComposeMainAtRoute(context, route)` (already used throughout this file).
- Produces: `GlassHeroCommands.openStatsScreen(): Unit` and `GlassHeroCommands.openTreatmentsScreen(): Unit` —
  consumed by Task 6 (wired to `TimeFilterBar`'s `onOpenStats`/`onOpenTreatment`).

These exactly mirror `DashboardHeroCommands.openStatsScreen()`/`openTreatmentsScreen()` (already shipped and
reviewed earlier this session, in the same file) — same route strings (`"stats"`, `"treatments"`), same
no-protection-needed reasoning (Stats and the treatments history browser have no `ElementType`/protection tier
associated, unlike the bolus-tier actions).

- [ ] **Step 1: Add to the interface**

In `GlassHeroCommands.kt`, add to the interface and `NoopGlassHeroCommands`:
```kotlin
fun openStatsScreen()
fun openTreatmentsScreen()
```
with empty-body overrides on the Noop object, following the exact pattern already there for the other 9 methods.

- [ ] **Step 2: Implement in `DashboardShellController.kt`**

In `createGlassHeroCommands()`, add:
```kotlin
override fun openStatsScreen() =
    uiInteraction.openComposeMainAtRoute(host.context, "stats")

override fun openTreatmentsScreen() =
    uiInteraction.openComposeMainAtRoute(host.context, "treatments")
```
(Same route-string-inlining rule already documented by the comment above `createHeroCommands()`'s own
`openStatsScreen`/`openTreatmentsScreen` in this file — no need to repeat the comment for the Glass copies,
one explanation in the file is enough; place these two new overrides near the other `createGlassHeroCommands()`
members.)

- [ ] **Step 3: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Real data wiring — the chart-state mapper and screen assembly

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/AimiDashboardComposeRootView.kt`
- Test: `plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassChartStateMapperTest.kt`

**Interfaces:**
- Consumes: `GraphViewModel.bgReadingsFlow: StateFlow<List<BgDataPoint>>`,
  `GraphViewModel.iobGraphFlow: StateFlow<IobGraphData>` (`.iob: List<GraphDataPoint>`),
  `GraphViewModel.treatmentGraphFlow: StateFlow<TreatmentGraphData>` (`.boluses: List<BolusGraphPoint>`,
  `.carbs: List<CarbsGraphPoint>`), `GraphViewModel.chartConfigFlow: StateFlow<ChartConfig>` (`.highMark`,
  `.lowMark`, already in display units), `GraphViewModel.glucoseMgdlToChartY(Double): Double`,
  `GraphViewModel.formatBgChartAxisTick(Double): String`, `GraphViewModel.formatIobChartValue(Double): String`
  (all already exist, confirmed this session), `DashboardEmbeddedComposeState.notifications`/
  `.onDismissNotification` (already populated for both dashboard variants by `DashboardShellController.attachShell()` — no new binding needed).
- Produces: the fully assembled Glass home screen (StatusAgoraCard + BgChartCard + IobChartCard +
  TimeFilterBar + PumpStatusNotification + NotificationsSection).

**Do not** re-implement `DashboardShellController.syncGraphRange`'s Vico-viewport machinery — Glass's charts
are flat lists, not a Vico viewport, so range changes here are a pure re-filter of already-cached ~24h data
(confirmed this session: the underlying flows cache a fixed 24h window regardless of the selected display
range, so 6/12/18h is always a strict subset already available — no re-fetch is ever needed).

- [ ] **Step 1: Add the new parameters and local range state**

In `GlassOverviewComposeEmbedded.kt`, change the function signature from:
```kotlin
internal fun GlassOverviewComposeEmbedded(
    overviewViewModel: OverviewViewModel,
    statusViewModel: StatusViewModel,
    modifier: Modifier = Modifier,
)
```
to:
```kotlin
internal fun GlassOverviewComposeEmbedded(
    overviewViewModel: OverviewViewModel,
    statusViewModel: StatusViewModel,
    graphViewModel: GraphViewModel,
    embeddedState: DashboardEmbeddedComposeState,
    modifier: Modifier = Modifier,
)
```
(`GraphViewModel` from `app.aaps.ui.compose.overview.graphs.GraphViewModel`, `DashboardEmbeddedComposeState`
from the same package as this file's own parent, `app.aaps.plugins.main.general.dashboard` — add both imports.)

Inside the composable, add local state for the selected range:
```kotlin
var rangeHours by remember { mutableStateOf(6) }
```
(add `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.setValue` if not
already imported — check the existing import block first, `getValue`/`remember` are already there.)

- [ ] **Step 2: Write `buildGlassChartState`**

Add this new `internal` pure function (same file, alongside the existing `buildGlassUiState`):

```kotlin
internal fun buildGlassChartState(
    rangeHours: Int,
    nowEpochMs: Long,
    bgReadings: List<BgDataPoint>,
    iobPoints: List<GraphDataPoint>,
    boluses: List<BolusGraphPoint>,
    carbs: List<CarbsGraphPoint>,
    chartConfig: ChartConfig,
    mgdlToChartY: (Double) -> Double,
    pumpStatusText: String,
): GlassChartState {
    val windowStart = nowEpochMs - rangeHours * 3_600_000L
    fun progress(timestamp: Long): Float =
        ((timestamp - windowStart).toFloat() / (nowEpochMs - windowStart).toFloat()).coerceIn(0f, 1f)

    val windowedBg = bgReadings.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedIob = iobPoints.filter { it.timestamp in windowStart..nowEpochMs }.sortedBy { it.timestamp }
    val windowedBoluses = boluses.filter { it.timestamp in windowStart..nowEpochMs }
    val windowedCarbs = carbs.filter { it.timestamp in windowStart..nowEpochMs }

    // BgDataPoint.value is always mg/dL; chartConfig.lowMark/highMark are already in the user's DISPLAY
    // unit (UnitDoubleKey preferences are unit-aware). Convert the readings to display-unit space via
    // mgdlToChartY so everything drawn (curve, low/high band, current value) agrees with what
    // formatValue() will show — do not mix mg/dL and display-unit values in the same chart.
    val bgReadingPoints = windowedBg.map {
        BgReadingPoint(progress = progress(it.timestamp), value = mgdlToChartY(it.value).toFloat())
    }
    val iobReadingPoints = windowedIob.map {
        IobReadingPoint(progress = progress(it.timestamp), iob = it.value.toFloat())
    }
    val treatmentPoints = buildList {
        windowedBoluses.forEach { b ->
            add(TreatmentPoint(progress = progress(b.timestamp), isCarb = false, label = b.label, timestamp = b.timestamp))
        }
        windowedCarbs.forEach { c ->
            add(TreatmentPoint(progress = progress(c.timestamp), isCarb = true, label = c.label, timestamp = c.timestamp))
        }
    }

    return GlassChartState(
        bgReadings = bgReadingPoints,
        iobReadings = iobReadingPoints,
        treatments = treatmentPoints,
        currentBgValue = bgReadingPoints.lastOrNull()?.value ?: 0f,
        currentIob = iobReadingPoints.lastOrNull()?.iob ?: 0f,
        lowLine = chartConfig.lowMark.toFloat(),
        highLine = chartConfig.highMark.toFloat(),
        rangeHours = rangeHours,
        pumpStatusText = pumpStatusText,
    )
}
```

`chartConfig.lowMark`/`.highMark` are used directly, unconverted — they're already in display-unit space
(confirmed: `preferences.get(UnitDoubleKey.OverviewHighMark)`, a unit-aware preference), the same space
`bgReadingPoints` was just converted into above via `mgdlToChartY`. `currentBgValue`/`currentIob` are read from
the already-built point lists (last one, by timestamp order) rather than re-deriving them from the raw
`windowedBg`/`windowedIob` lists a second time, avoiding two sources of truth for "the latest value."

- [ ] **Step 3: Wire the composable body**

Inside `GlassOverviewComposeEmbedded`, after the existing `val state = buildGlassUiState(status, statusLights)`
line, add:

```kotlin
val bgReadings by graphViewModel.bgReadingsFlow.collectAsStateWithLifecycle()
val iobData by graphViewModel.iobGraphFlow.collectAsStateWithLifecycle()
val treatmentData by graphViewModel.treatmentGraphFlow.collectAsStateWithLifecycle()
val chartConfig by graphViewModel.chartConfigFlow.collectAsStateWithLifecycle()
val nowEpochMs = System.currentTimeMillis()
val chartState = remember(rangeHours, bgReadings, iobData, treatmentData, chartConfig, status?.pumpStatusText) {
    buildGlassChartState(
        rangeHours = rangeHours,
        nowEpochMs = nowEpochMs,
        bgReadings = bgReadings,
        iobPoints = iobData.iob,
        boluses = treatmentData.boluses,
        carbs = treatmentData.carbs,
        chartConfig = chartConfig,
        mgdlToChartY = graphViewModel::glucoseMgdlToChartY,
        pumpStatusText = status?.pumpStatusText.orEmpty(),
    )
}
```

Then replace the current `StatusAgoraCard(...)` call (which is the last thing in the `AapsTheme { }` block) so
it's followed by the rest of the screen, inside a scrollable `Column`:

```kotlin
androidx.compose.foundation.layout.Column(
    modifier = modifier
        .fillMaxSize()
        .verticalScroll(androidx.compose.foundation.rememberScrollState())
        .padding(12.dp),
    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
) {
    StatusAgoraCard(
        state = state,
        isDark = isDark,
        onOpenLoop = commands::openLoop,
        onOpenTarget = commands::openTarget,
        onOpenInsulin = commands::openInsulin,
        onOpenPump = commands::openPump,
        onOpenCannula = commands::openCannula,
        onOpenBattery = commands::openBattery,
        onOpenBasal = commands::openBasal,
        onOpenSensorInsert = commands::openSensorInsert,
        onOpenPreferences = commands::openPreferences,
    )
    BgChartCard(
        readings = chartState.bgReadings,
        treatments = chartState.treatments,
        timeRangeHours = chartState.rangeHours,
        currentBgValue = chartState.currentBgValue,
        lowLine = chartState.lowLine,
        highLine = chartState.highLine,
        formatValue = { v -> graphViewModel.formatBgChartAxisTick(v.toDouble()) },
        isDark = isDark,
    )
    IobChartCard(
        iobReadings = chartState.iobReadings,
        currentIob = chartState.currentIob,
        timeRangeHours = chartState.rangeHours,
        isDark = isDark,
    )
    TimeFilterBar(
        selectedHours = rangeHours,
        onSelectHours = { rangeHours = it },
        onOpenStats = commands::openStatsScreen,
        onOpenTreatment = commands::openTreatmentsScreen,
        statsLabel = stringResource(R.string.stats_button),
        treatmentLabel = stringResource(app.aaps.core.ui.R.string.overview_treatment_label),
        isDark = isDark,
    )
    if (chartState.pumpStatusText.isNotBlank()) {
        PumpStatusNotification(status = chartState.pumpStatusText, isDark = isDark)
    }
    if (embeddedState.notifications.isNotEmpty()) {
        NotificationsSection(
            notifications = embeddedState.notifications,
            isDark = isDark,
            onDismiss = { id -> embeddedState.onDismissNotification?.invoke(id) },
        )
    }
}
```

(Remove the old bare `StatusAgoraCard(...)` call that currently sits directly inside `AapsTheme { }` — it moves
inside this new `Column`, per above. Add real (non-fully-qualified) imports for: `androidx.compose.foundation.layout.Column`,
`androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.verticalScroll`,
`androidx.compose.foundation.rememberScrollState`, `androidx.compose.ui.res.stringResource`,
`app.aaps.plugins.main.R` (for `R.string.stats_button` — confirmed this file doesn't already import it, since
it hasn't needed string resources before this task), plus these confirmed package paths (verified against the
live source, not guessed): `app.aaps.core.interfaces.overview.graph.BgDataPoint`,
`app.aaps.core.interfaces.overview.graph.GraphDataPoint`, `app.aaps.core.interfaces.overview.graph.BolusGraphPoint`,
`app.aaps.core.interfaces.overview.graph.CarbsGraphPoint` (all four in the same file,
`core/interfaces/src/main/kotlin/app/aaps/core/interfaces/overview/graph/CalculationResults.kt`),
`app.aaps.ui.compose.overview.graphs.GraphViewModel` and `app.aaps.ui.compose.overview.graphs.ChartConfig`
(both in `ui/src/main/kotlin/app/aaps/ui/compose/overview/graphs/GraphViewModel.kt` — `ChartConfig` is a
top-level `data class` in that same file, not a separate file, despite the name suggesting one).
`BolusGraphPoint`/`CarbsGraphPoint` both have a `timestamp: Long` and a pre-formatted `label: String` field —
use those two fields exactly as shown in Step 2's code, no other fields are needed.)

- [ ] **Step 4: Thread the new parameters from `AimiDashboardComposeRootView.kt`**

Change the `GLASS` branch's call:
```kotlin
GlassOverviewComposeEmbedded(
    overviewViewModel = viewModel,
    statusViewModel = statusViewModel,
)
```
to:
```kotlin
GlassOverviewComposeEmbedded(
    overviewViewModel = viewModel,
    statusViewModel = statusViewModel,
    graphViewModel = graphViewModel,
    embeddedState = embeddedComposeState,
)
```
(`graphViewModel` and `embeddedComposeState` are both already in scope in this file — `graphViewModel` from
line 81's `val graphViewModel = ViewModelProvider(act)[GraphViewModel::class.java]`, `embeddedComposeState`
from the class's own `private val embeddedComposeState = DashboardEmbeddedComposeState()` field, the exact
same instance DASHBOARD_V2 already uses for its own notifications.)

- [ ] **Step 5: Regression test for `buildGlassChartState`**

Add `GlassChartStateMapperTest.kt` (matching this module's existing test conventions, see
`GlassOverviewComposeEmbeddedKtTest.kt` from the prior plan for the style/base class to follow) covering at
least:
1. A reading outside the window (timestamp before `windowStart`) is excluded from `bgReadings`.
2. A reading at exactly `nowEpochMs` maps to `progress == 1f`; a reading at exactly `windowStart` maps to
   `progress == 0f`.
3. `lowLine`/`highLine` come through unconverted from `chartConfig` (display-unit passthrough, per Step 2's
   unit-space decision) — NOT run through `mgdlToChartY` a second time.
4. A bolus and a carb entry in the same window both appear in `treatments`, with `isCarb` set correctly for
   each.
5. Empty input lists produce a `GlassChartState` with empty chart lists and no exception.

Run the test module's test task (same command pattern as the prior plan's Task 9,
`./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassChartStateMapperTest*"` or equivalent) and
confirm it passes.

- [ ] **Step 6: Compile everything**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
then: `./gradlew :app:compileFullDebugKotlin --no-daemon`
Expected: both `BUILD SUCCESSFUL`.

- [ ] **Step 7: Manual verification**

Select the Glass skin on-device (or describe the check to the user if this session isn't driving a device) and
confirm: BG and IOB charts render with real data, tapping the range pills (6h/12h/18h/24h) changes what's
shown, tapping a BG chart point shows its value+time, tapping "Stats"/"Treatment" opens the real screens, the
pump-status banner appears only when there's something to show, and any active system notifications appear
with working Dismiss buttons.

## Self-Review Notes

- **Spec coverage:** this plan completes the remainder of the spec's Phase 1 (`BgChartCard`/`IobChartCard`/
  `TimeFilterBar`/notifications) that the prior plan explicitly deferred. Phases 2-4 of the spec (Loop
  Dashboard, Sensor Insert, Treatments/Stats restyle) remain out of scope, per the Scope Check rule.
- **Placeholder scan:** an earlier draft of this plan had a dead `currentBgText`/`bgUnitSuffix` field pair and
  a "check the exact resource" hedge for `treatmentLabel` — both were caught during this same planning pass
  (before dispatch) and resolved: `GlassChartState` (Task 1) no longer defines those dead fields at all, and
  `treatmentLabel`/`statsLabel` (Task 3/6) now cite the exact two verified string resources
  (`app.aaps.plugins.main.R.string.stats_button`, `app.aaps.core.ui.R.string.overview_treatment_label`,
  confirmed against `DashboardGraphComposeControls.kt:33,54`) rather than leaving them to be found later.
- **Type consistency:** `GlassChartState`/`BgReadingPoint`/`IobReadingPoint`/`TreatmentPoint` field names are
  used identically across Task 1 (definition), Task 2 (consumption in chart composables), and Task 6
  (construction in `buildGlassChartState` and the screen assembly) — cross-checked while writing this plan.
- **Package paths verified, not guessed:** `BgDataPoint`/`GraphDataPoint`/`BolusGraphPoint`/`CarbsGraphPoint`
  (all four in `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/overview/graph/CalculationResults.kt`)
  and `ChartConfig`/`GraphViewModel` (both in `ui/src/main/kotlin/app/aaps/ui/compose/overview/graphs/GraphViewModel.kt`)
  were each confirmed by reading the live source during this planning pass, including `BolusGraphPoint`'s and
  `CarbsGraphPoint`'s exact `timestamp`/`label` field names — no unverified import paths remain in Task 6.
