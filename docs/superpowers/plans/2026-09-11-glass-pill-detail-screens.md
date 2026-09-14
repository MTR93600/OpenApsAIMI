# Glass Pill Detail Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give all 8 tappable pills on the Glass main dashboard their own Glass-branded, GlycoCalm-styled detail
screen.

**Architecture:** For the 6 pills that already have a working shared screen (Insulin, Cannula, Basal, Target,
Loop, Sensor Insert), this plan does NOT reimplement any business logic — it wraps the EXACT SAME ViewModel
each shared screen already uses in a new, Glass-styled Compose UI, calling the identical state fields and
action methods the shared screen already calls. This is a re-skin, not a rewrite: batch-executor calls,
confirmation dialogs, and protection checks are all inherited for free by construction, not re-derived. For
Pump and Battery (no existing screen at all — just a plain `OKDialog`), this plan builds small new
info screens reading the same single data field (`StatusCardState.reservoirText`/`.pumpBatteryText`) the
current `OKDialog` already reads, via the same `overviewViewModel.statusCardState` access pattern
`GlassSensorQualityScreen` already established.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI, this repo's `AppRoute`/`AppNavGraph` navigation.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-pill-detail-screens-design.md`

## Global Constraints

- No `git commit` during this plan's execution.
- No install/on-device testing without the user explicitly asking.
- Dark mode sourced ONLY via `LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode`.
- Explicit imports only. No hardcoded user-facing strings; no string concatenation for user-facing text.
- Do NOT modify any of the 6 underlying shared screens/ViewModels (`InsulinDialogScreen`/`ViewModel`,
  `FillDialogScreen`/`ViewModel`, `TempBasalDialogScreen`/`ViewModel`, `TempTargetManagementScreen`/
  `ViewModel`, `RunningModeScreen`/`RunningModeManagementViewModel`, `CareDialogScreen`/`ViewModel`) — Glass
  gets NEW screen files that consume the SAME ViewModel types, the shared screens themselves are untouched and
  keep serving DASHBOARD_V2/other skins exactly as today.
- Preserve exact current protection behavior for every command: `openTarget`/`openBasal`/`openCannula`/
  `openSensorInsert` keep their `withBolusProtection` wrapping (`Protection.BOLUS`); `openInsulin`/`openLoop`
  keep their `Protection.BOLUS` check (currently via `openBolus()`/`openLoopDialog()`); `openPump`/
  `openBattery` currently have NO protection at all — do not add any, this plan is not the place to change
  that.
- GlycoCalm visual tokens (light + dark, dark from `DESIGN.md`'s `inverse-*` tokens) — same token set as the
  other 2 plans in this round, reused verbatim, not redefined per-screen:
  ```kotlin
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
  ```

---

## Task 1: Pump + Battery — new info screens (no existing ViewModel to reuse)

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassPumpDetailScreen.kt`
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassBatteryDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `OverviewViewModel.statusCardState` (`StatusCardState.reservoirText`/`.pumpBatteryText`, both
  already computed, `OverviewViewModel.kt:638-640`/`697`) — the exact same fields
  `DashboardShellController.openPump()`/`.openBattery()` already read via `statusCardStateSnapshot()`.
- Produces: routes `glass_pump_detail`/`glass_battery_detail`.

This is the smallest task in this plan — both screens show exactly one text field each, matching what the
current `OKDialog` already shows, just as a real Glass-styled screen instead of a system dialog.

- [ ] **Step 1: Add string resources**

```xml
<string name="dashboard_glass_pump_detail_title">Pump</string>
<string name="dashboard_glass_battery_detail_title">Battery</string>
```
(`dashboard_v2_reservoir`/`dashboard_v2_battery` already exist and are reused for the VALUE labels inside each
screen — check they exist before adding new ones, do not duplicate.)

- [ ] **Step 2: `GlassPumpDetailScreen.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel

@Composable
fun GlassPumpDetailScreen(
    overviewViewModel: OverviewViewModel,
    onBack: () -> Unit,
    isDark: Boolean,
) {
    val status by overviewViewModel.statusCardState.observeAsState()
    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val muted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = primary)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_pump_detail_title),
                    color = primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(app.aaps.plugins.main.R.string.dashboard_v2_reservoir),
                    color = muted,
                    fontSize = 12.sp,
                )
                Text(
                    text = status?.reservoirText ?: "--",
                    color = primary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
```

- [ ] **Step 3: `GlassBatteryDetailScreen.kt`**

Same structure as `GlassPumpDetailScreen.kt` above, with these substitutions: file/function name
`GlassBatteryDetailScreen`, title string `dashboard_glass_battery_detail_title`, value label
`app.aaps.plugins.main.R.string.dashboard_v2_battery`, value `status?.pumpBatteryText ?: "--"`. Write it out
in full (do not leave as "same as above" in the actual file — this brief's abbreviation is only to avoid
repeating identical code twice in this document).

- [ ] **Step 4: Add the 2 routes**

In `AppRoute.kt`, next to `AppRoute.GlassSensorQuality`:
```kotlin
data object GlassPumpDetail : AppRoute("glass_pump_detail")
data object GlassBatteryDetail : AppRoute("glass_battery_detail")
```

In `AppNavGraph.kt`, next to the existing `AppRoute.GlassSensorQuality.route` destination block (mirror its
exact dark-mode-sourcing chain):
```kotlin
composable(AppRoute.GlassPumpDetail.route) {
    val preferences = LocalPreferences.current
    val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
    val isDark = when (UiMode.fromString(darkModeValue)) {
        UiMode.LIGHT -> false
        UiMode.DARK -> true
        UiMode.SYSTEM -> isSystemInDarkTheme()
    }
    GlassPumpDetailScreen(
        overviewViewModel = overviewViewModel,
        onBack = { navController.safePopBackStack() },
        isDark = isDark,
    )
}

composable(AppRoute.GlassBatteryDetail.route) {
    val preferences = LocalPreferences.current
    val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
    val isDark = when (UiMode.fromString(darkModeValue)) {
        UiMode.LIGHT -> false
        UiMode.DARK -> true
        UiMode.SYSTEM -> isSystemInDarkTheme()
    }
    GlassBatteryDetailScreen(
        overviewViewModel = overviewViewModel,
        onBack = { navController.safePopBackStack() },
        isDark = isDark,
    )
}
```
Add imports `import app.aaps.plugins.main.general.dashboard.glass.GlassPumpDetailScreen` and
`...GlassBatteryDetailScreen` (check they're not already present).

- [ ] **Step 5: Rewrite `openPump()`/`openBattery()` in `DashboardShellController.kt`**

Change:
```kotlin
            override fun openPump() {
                // Reservoir detail — same OKDialog pattern as DASHBOARD_V2's Reservoir badge.
                OKDialog.show(
                    host.context,
                    resourceHelper.gs(R.string.dashboard_v2_reservoir),
                    statusCardStateSnapshot()?.reservoirText ?: "--"
                )
            }

            override fun openBattery() {
                OKDialog.show(
                    host.context,
                    resourceHelper.gs(R.string.dashboard_v2_battery),
                    statusCardStateSnapshot()?.pumpBatteryText ?: "--"
                )
            }
```
to:
```kotlin
            override fun openPump() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_pump_detail")

            override fun openBattery() =
                uiInteraction.openComposeMainAtRoute(host.context, "glass_battery_detail")
```
(No protection wrapping — matches today's behavior exactly, these 2 commands have never had any. The
`statusCardStateSnapshot()` helper may now be unused if nothing else calls it — check with a grep before
removing it; if something else still uses it, leave it in place.)

- [ ] **Step 6: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 2: Insulin + Cannula — reskin `InsulinDialogViewModel`/`FillDialogViewModel`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassInsulinDetailScreen.kt`
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassCannulaDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`

**Interfaces:**
- Consumes: `InsulinDialogViewModel` (`ui/.../insulinDialog/InsulinDialogViewModel.kt`) via `hiltViewModel()` —
  same as the shared `InsulinDialogScreen` — reading `uiState: StateFlow<InsulinDialogUiState>` and calling
  `updateInsulin`/`addInsulin`/`updateTimeOffset`/`updateEatingSoonTt`/`updateRecordOnly`/`selectInsulinType`/
  `updateNotes`/`updateEventTime`/`prepareAndConfirm`/`commit` exactly as the shared screen does.
  `FillDialogViewModel` (`ui/.../fillDialog/FillDialogViewModel.kt`) similarly, via its own
  `updateInsulin`/`updateSiteChange`/`updateCartridgeChange`/`selectInsulin`/`updateNotes`/`updateEventTime`/
  `updateSiteLocation`/`updateSiteArrow`/`buildConfirmationSummary`/`confirmAndSave`.
- Produces: routes `glass_insulin_detail`, `glass_cannula_detail`.

**Read the shared screens first** (`InsulinDialogScreen.kt`, `FillDialogScreen.kt`) — this task is a re-skin,
so the new Glass screens must call the SAME sequence of methods for the SAME user actions (e.g. tapping
"confirm" calls `prepareAndConfirm()`, which triggers a `ShowConfirmation`/`ShowNoActionDialog`/
`ShowDeliveryError` side effect the new screen must also collect and react to — do not skip the confirmation
step, it is what actually delivers the bolus/prime via the batch executor).

`InsulinDialogScreen` needs external `bgInfoState`/`iobUiState`/`cobUiState` (from `chipsViewModel`, already in
scope in `AppNavGraph.kt`'s `appNavGraph(...)` parameters) and an `insulinButtonsDef: PreferenceSubScreenDef`
(via the in-scope `findScreenDef(key)` — find the exact key string the shared screen's own call site passes to
`findScreenDef` and reuse it verbatim). `FillDialogScreen` needs an analogous `fillButtonsDef` the same way.
Confirm these exact call patterns by reading how `AppNavGraph.kt`'s OWN existing `InsulinDialog`/`FillDialog`
destination blocks obtain them, and replicate identically for the new Glass destinations — do not guess the
`findScreenDef` key strings, read them from the existing call sites.

- [ ] **Step 1: `GlassInsulinDetailScreen.kt`**

Build a new Composable `GlassInsulinDetailScreen(viewModel: InsulinDialogViewModel = hiltViewModel(), bgInfoState: StateFlow<BgInfoUiState>, iobUiState: StateFlow<IobUiState>, cobUiState: StateFlow<CobUiState>, insulinButtonsDef: PreferenceSubScreenDef, onNavigateBack: () -> Unit, onShowDeliveryError: (String) -> Unit, isDark: Boolean)`.
Structure: a GlycoCalm-styled `Column` with — current BG/IOB/COB summary row (from the 3 external state flows,
same data the shared screen's `DialogStatusBar` already shows), an insulin-amount stepper bound to
`uiState.insulin`/`updateInsulin`/`addInsulin`, quick-amount buttons from `uiState.insulinButtonIncrement1/2/3`
calling `addInsulin`, an "eating soon" toggle bound to `eatingSoonTtChecked`/`updateEatingSoonTt`, a
"record only" toggle bound to `recordOnlyChecked`/`updateRecordOnly` (respect `forcedRecordOnly`/
`recordOnlyEnabled`, i.e. disable the toggle when the shared screen's own logic would), a notes field bound to
`notes`/`updateNotes` (only shown when `showNotesFromPreferences`), a confirm button (enabled per
`confirmEnabled`) calling `prepareAndConfirm()`. Collect `viewModel`'s side-effect channel (same mechanism the
shared `InsulinDialogScreen.kt` uses — read its exact collection pattern, e.g. a `LaunchedEffect` +
`Flow.collect` on a `sideEffect`/`events` property) and react to `ShowConfirmation(bolusId, lines)` by showing
a confirmation UI that calls `commit(bolusId)` on accept, `ShowNoActionDialog` by informing the user nothing
was submitted, `ShowDeliveryError(comment)` by calling `onShowDeliveryError(comment)`.

Use the GlycoCalm token set from this plan's Global Constraints for backgrounds/cards/text; match this
project's existing Glass screens' structural conventions (a top `Row` with back arrow + title, a
`verticalScroll` `Column` body) rather than inventing a new page shell.

- [ ] **Step 2: `GlassCannulaDetailScreen.kt`**

Same approach for `FillDialogViewModel`: amount stepper (`insulin`/`updateInsulin`, respecting
`insulinAfterConstraints`/`constraintApplied`), site-change/cartridge-change toggles, insulin-type selector
(`availableInsulins`/`selectedInsulin`/`selectInsulin`), notes, confirm button calling `confirmAndSave()`
directly (this ViewModel has no separate prepare/commit split — `confirmAndSave()` does the whole thing,
confirmed by its own doc/behavior in the shared screen). Site-location picker round-trip
(`onPickSiteLocation`/`siteLocationResult`) — preserve this exact mechanism from the shared screen's own
signature rather than dropping it; check how the shared `FillDialogScreen`'s own call site in `AppNavGraph.kt`
wires the site-location picker navigation and replicate identically for the new Glass route.

- [ ] **Step 3: Add the 2 routes**

Same pattern as Task 1 Step 4. `AppRoute.GlassInsulinDetail = "glass_insulin_detail"`,
`AppRoute.GlassCannulaDetail = "glass_cannula_detail"`. `AppNavGraph.kt` destinations mirror the shared
`InsulinDialog`/`FillDialog` destinations' own parameter wiring (bgInfoState/iobUiState/cobUiState/
insulinButtonsDef for Insulin; fillButtonsDef/site-location round-trip for Cannula) plus the standard
`isDark` chain.

- [ ] **Step 4: Rewrite `openInsulin()`/`openCannula()` in `DashboardShellController.kt`**

`openCannula()` currently:
```kotlin
            override fun openCannula() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "fill_dialog/0")
            }
```
becomes:
```kotlin
            override fun openCannula() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_cannula_detail")
            }
```

`openInsulin()` currently:
```kotlin
            override fun openInsulin() {
                openBolus()
            }
```
where `openBolus()` is a shared private helper (also used elsewhere — check callers before changing it; if it
has other callers, do NOT modify `openBolus()` itself, instead change ONLY `openInsulin()`'s body) that does:
```kotlin
    private fun openBolus(): Boolean {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED) uiInteraction.openInsulinScreen(activity)
            }
        }
        return true
    }
```
Change `openInsulin()` to inline its own protection check rather than reuse `openBolus()` (since `openBolus()`
routes to the legacy `uiInteraction.openInsulinScreen(activity)`, not a Compose route, and other callers of
`openBolus()` should keep that legacy behavior unchanged):
```kotlin
            override fun openInsulin() {
                host.activity?.let { activity ->
                    protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                        if (result == ProtectionResult.GRANTED) {
                            uiInteraction.openComposeMainAtRoute(host.context, "glass_insulin_detail")
                        }
                    }
                }
            }
```
(This preserves the exact same `Protection.BOLUS` gate `openBolus()` already applied, just targets the new
Glass route instead of the legacy screen — confirm `protectionCheck`/`ProtectionCheck`/`ProtectionResult` are
already imported in this file, they should be since `withBolusProtection` already uses them.)

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 3: Basal + Sensor Insert — reskin `TempBasalDialogViewModel`/`CareDialogViewModel`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassBasalDetailScreen.kt`
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassSensorInsertDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`

**Interfaces:**
- Consumes: `TempBasalDialogViewModel` (`ui/.../tempBasalDialog/TempBasalDialogViewModel.kt`) via
  `hiltViewModel()` — the simplest of the 8 (no extra external state flows) — reading `uiState:
  StateFlow<TempBasalDialogUiState>`, calling `updateBasalPercent`/`updateBasalAbsolute`/`updateDuration`/
  `prepareAndConfirm`/`commit`. `CareDialogViewModel` (`ui/.../careDialog/CareDialogViewModel.kt`) via
  `hiltViewModel()`, pre-selected to `CareportalEventType.SENSOR_INSERT` via its `SavedStateHandle`-read
  `eventTypeOrdinal` nav arg (same route-arg mechanism the shared `care_dialog/{ordinal}` route already uses —
  the new Glass route needs the SAME `{eventTypeOrdinal}` path segment, fixed to
  `CareportalEventType.SENSOR_INSERT.ordinal`, so `CareDialogViewModel`'s existing `SavedStateHandle` reading
  code works unmodified).
- Produces: routes `glass_basal_detail`, `glass_sensor_insert_detail/{eventTypeOrdinal}`.

- [ ] **Step 1: `GlassBasalDetailScreen.kt`**

`GlassBasalDetailScreen(viewModel: TempBasalDialogViewModel = hiltViewModel(), onNavigateBack: () -> Unit, onShowDeliveryError: (String) -> Unit, isDark: Boolean)`.
Bind: a percent/absolute rate stepper (respecting `isPercentPump`, `maxTempPercent`/`tempPercentStep` or
`maxTempAbsolute`/`tempAbsoluteStep`) calling `updateBasalPercent`/`updateBasalAbsolute`, a duration stepper
(`tempDurationStep`/`tempMaxDuration`) calling `updateDuration`, a confirm button (disabled while
`isPreparing`) calling `prepareAndConfirm()`, same side-effect handling pattern as Task 2
(`ShowConfirmation`→`commit(bolusId)`, `ShowNoActionDialog`, `ShowDeliveryError`→`onShowDeliveryError`).

- [ ] **Step 2: `GlassSensorInsertDetailScreen.kt`**

`GlassSensorInsertDetailScreen(viewModel: CareDialogViewModel = hiltViewModel(), onNavigateBack: () -> Unit, onPickSiteLocation: () -> Unit = {}, siteLocationResult: Pair<String?, String?>? = null, isDark: Boolean)`.
Since this pill is hardcoded to Sensor Insert (unlike the shared, multi-event-type `CareDialogScreen`), the
new Glass screen can render a NARROWER UI than the shared one — only the `showSiteRotationSection`/
`showNotesSection` parts are relevant for `SENSOR_INSERT` (per `CareDialogUiState`'s own derived visibility
flags), the BG/duration sections (`showBgSection`/`showDurationSection`) are for OTHER event types and can be
omitted entirely from this screen's layout (the underlying `uiState.eventType` is already fixed to
`SENSOR_INSERT` via the route arg, so those sections would never show anyway — this is a legitimate
simplification, not a scope cut, since the shared screen only conditionally renders them too). Bind: site
location/arrow pickers (`siteLocation`/`siteArrow`/`updateSiteLocation`/`updateSiteArrow`,
`onPickSiteLocation`/`siteLocationResult` round-trip preserved exactly as the shared screen's own mechanism),
a notes field (`notes`/`updateNotes`, shown per `showNotesSection`), event time (`eventTime`/`eventTimeChanged`/
`updateEventTime`), a confirm button calling `confirmAndSave()`.

- [ ] **Step 3: Add the 2 routes**

`AppRoute.GlassBasalDetail = "glass_basal_detail"`. For Sensor Insert, since `CareDialogViewModel` reads the
event type via a `{eventTypeOrdinal}` path segment, the new route needs the same shape:
```kotlin
data object GlassSensorInsertDetail : AppRoute("glass_sensor_insert_detail/{eventTypeOrdinal}") {
    fun createRoute(eventTypeOrdinal: Int) = "glass_sensor_insert_detail/$eventTypeOrdinal"
}
```
(Match whatever `createRoute`-style helper pattern, if any, the existing `care_dialog/{ordinal}` route already
uses in `AppRoute.kt` — read its current declaration first and mirror it exactly rather than inventing a
different convention.) `AppNavGraph.kt` destinations mirror the shared `TempBasalDialog`/`CareDialog`
destinations' own site-location-picker/nav-arg wiring, plus the standard `isDark` chain.

- [ ] **Step 4: Rewrite `openBasal()`/`openSensorInsert()` in `DashboardShellController.kt`**

```kotlin
            override fun openBasal() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_basal_detail")
            }

            override fun openSensorInsert() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(
                    host.context,
                    "glass_sensor_insert_detail/${CareportalEventType.SENSOR_INSERT.ordinal}",
                )
            }
```
(Same `withBolusProtection` wrapping as today, only the target route string changes.)

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 4: Target — reskin `TempTargetManagementViewModel`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassTargetDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`

**Interfaces:**
- Consumes: the Activity-scoped `tempTargetManagementViewModel: TempTargetManagementViewModel` already threaded
  through `appNavGraph(...)`'s own parameters (NOT `hiltViewModel()` — this is the one ViewModel among the 8
  that is NOT route-scoped; reuse the SAME instance the shared `TempTargetManagementScreen` destination
  already receives, do not create a second instance).

This is the most complex of the 8 pills — a full preset-management CRUD/carousel screen, not a simple confirm
dialog (presets, reorder mode, activation, editing — see the ViewModel's ~25 public methods). Given this
complexity, the Glass version's SCOPE for v1 is deliberately narrower than the shared screen: implement the
PLAY-mode flow only (view active/preset targets, activate a preset, cancel the active target) — defer full
preset EDITING/reordering (`saveCurrentPreset`, `addNewPreset`, `deleteCurrentPreset`,
`enterReorderMode`/`moveReorderItem`/`commitReorder`) to a follow-up, since those are management actions a
user is unlikely to need from a quick dashboard tap, and the shared `TempTargetManagementScreen` (still fully
intact and reachable elsewhere, e.g. via Gestion/Management) remains available for that. This is a deliberate,
narrower-than-1:1 scope decision — flag it explicitly in your task report, do not silently drop functionality
without saying so.

- [ ] **Step 1: `GlassTargetDetailScreen.kt`**

`GlassTargetDetailScreen(viewModel: TempTargetManagementViewModel, onNavigateBack: () -> Unit = {})`.
On first composition, call `viewModel.setScreenMode(ScreenMode.PLAY)` then `viewModel.loadData()` (or
`refreshData()` — check which is the correct "load initial state" entrypoint vs. "reload" entrypoint by
reading the shared screen's own `LaunchedEffect`, and call the same one). Bind: current active target display
(`activeTT`/`remainingTimeMs`), a horizontal preset carousel (`presets`/`currentCardIndex`/
`updateCurrentCardIndex`/`selectedPreset`), an "Activate" button calling
`activateWithEditorValues(onSuccess = onNavigateBack)` for the selected preset, a "Cancel active" button
(shown only when `activeTT != null`) calling `cancelActive(onSuccess = onNavigateBack)`. Respect the master-
offline gating the shared screen already applies (`masterEditingEnabled()`-style check — read exactly how the
shared screen gates its own Activate/Cancel buttons and disable this screen's equivalents the same way,
including whatever banner/messaging it shows when editing is disabled).

- [ ] **Step 2: Add the route**

`AppRoute.GlassTargetDetail = "glass_target_detail"`. `AppNavGraph.kt` destination passes the SAME
`tempTargetManagementViewModel` param `appNavGraph(...)` already receives (do not call `hiltViewModel()` for
this one, follow the exact pattern the shared `TempTargetManagementScreen` destination block already uses for
obtaining this ViewModel).

- [ ] **Step 3: Rewrite `openTarget()` in `DashboardShellController.kt`**

```kotlin
            override fun openTarget() = withBolusProtection {
                uiInteraction.openComposeMainAtRoute(host.context, "glass_target_detail")
            }
```
(Was previously `withBolusProtection { activity -> uiInteraction.openTempTargetManagementScreen(activity) }` —
same protection, new route-based navigation instead of the legacy `UiInteraction` method call.)

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 5: Loop — reskin `RunningModeManagementViewModel`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDetailScreen.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`

**Interfaces:**
- Consumes: the Activity-scoped `runningModeManagementViewModel: RunningModeManagementViewModel` already
  threaded through `appNavGraph(...)` — same reuse-the-existing-instance rule as Task 4.

- [ ] **Step 1: `GlassLoopDetailScreen.kt`**

`GlassLoopDetailScreen(viewModel: RunningModeManagementViewModel, onNavigateBack: () -> Unit)`. Mirror the
shared `RunningModeScreen`'s own 15-second polling `LaunchedEffect` (`delay(15_000L)` + `loadState()` loop) —
this is existing, intentional behavior (the running mode can change from outside this screen, e.g. via
automation), not something to drop. Bind the 3 conditional sections exactly as the shared screen does
(`LoopControlSection` for CLOSED_LOOP/CLOSED_LOOP_LGS/OPEN_LOOP/DISABLED_LOOP, `SuspendSection` for
SUSPENDED_BY_USER durations/Resume, `PumpDisconnectSection` for DISCONNECTED_PUMP durations/Reconnect),
filtered by `allowedNextModes` the same way, each button calling `executeAction(targetMode, action,
durationMinutes)` then `onNavigateBack()` immediately after (the actual OK/Cancel confirmation is an app-level
`EventShowDialog.OkCancel` modal the ViewModel already triggers, not a screen-local dialog — do not build a
second confirmation UI, the existing app-level one already fires from `executeAction`).

- [ ] **Step 2: Add the route**

`AppRoute.GlassLoopDetail = "glass_loop_detail"`. `AppNavGraph.kt` destination passes the SAME
`runningModeManagementViewModel` param, same reuse pattern as Task 4.

- [ ] **Step 3: Rewrite `openLoop()` in `DashboardShellController.kt`**

`openLoop()` currently delegates to `openLoopDialog()`:
```kotlin
    private fun openLoopDialog() {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED && host.isBindingAttached()) uiInteraction.openRunningModeScreen(activity)
            }
        }
    }
```
Check whether `openLoopDialog()` has any OTHER callers besides `GlassHeroCommands.openLoop()` — if it does not,
change `openLoopDialog()`'s body in place (simplest); if it does, instead give `openLoop()` its own inlined
protection check (same pattern as Task 2's `openInsulin()` fix) targeting the new route, leaving
`openLoopDialog()` untouched for its other caller(s):
```kotlin
    private fun openLoopDialog() {
        host.activity?.let { activity ->
            protectionCheck.requestProtection(ProtectionCheck.Protection.BOLUS) { result ->
                if (result == ProtectionResult.GRANTED && host.isBindingAttached()) {
                    uiInteraction.openComposeMainAtRoute(host.context, "glass_loop_detail")
                }
            }
        }
    }
```
(Preserve the `host.isBindingAttached()` guard exactly — do not drop it.)

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Self-Review Notes

- **Spec coverage:** all 8 pills covered — Task 1 (Pump/Battery, net-new), Task 2 (Insulin/Cannula reskin),
  Task 3 (Basal/Sensor Insert reskin), Task 4 (Target reskin, narrowed scope flagged), Task 5 (Loop reskin).
- **Protection preserved exactly:** every task's final step documents the exact before/after of its
  `DashboardShellController.kt` command(s), confirming protection wrapping is unchanged except for Pump/
  Battery (which never had any).
- **No shared screen/ViewModel modified:** every task explicitly creates NEW Glass-only screen files that
  consume EXISTING ViewModel instances/types — zero edits to `InsulinDialogScreen.kt`/`FillDialogScreen.kt`/
  `TempBasalDialogScreen.kt`/`TempTargetManagementScreen.kt`/`RunningModeScreen.kt`/`CareDialogScreen.kt` or
  their ViewModels anywhere in this plan.
- **Known, flagged scope narrowing:** Task 4 (Target) explicitly narrows to PLAY-mode only (no preset editing/
  reordering) — documented as a deliberate decision with a reasoning line, not a silent gap, per this plan's
  own instruction to the implementer to report it explicitly.
- **Structural quirks preserved:** Task 2 and Task 5 both call out that `openInsulin()`/`openLoop()` currently
  route through `UiInteraction` methods rather than `openComposeMainAtRoute` string literals (unlike the other
  6 commands), and give exact before/after code so this quirk doesn't get silently normalized away or
  mishandled.
