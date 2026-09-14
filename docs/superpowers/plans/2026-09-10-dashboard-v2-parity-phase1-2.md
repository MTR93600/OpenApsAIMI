# DASHBOARD_V2 parity — Phase 1 & 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give DASHBOARD_V2's status card a pill-style time-range selector, working Stats/Treatment shortcuts,
and the CGM/adaptive-smoothing badge that already exists (and works) in the older AIMI dashboard.

**Architecture:** All changes stay inside the `plugins:main` Gradle module (no new inter-module dependencies).
Phase 1 replaces one internal composable's implementation behind its existing public signature, and adds two new
no-op-by-default commands to the existing `DashboardHeroCommands` interface, implemented in
`DashboardShellController`. Phase 2 promotes two already-working `private` composables/functions to `internal`
so a second screen in the same module can call them, instead of duplicating their logic.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Dagger-injected plugin architecture (no interface change
outside `plugins:main`).

**Spec:** `docs/superpowers/specs/2026-09-10-dashboard-v2-parity-design.md`

## Global Constraints

- No new inter-module (`project(":...")`) Gradle dependencies (project convention).
- Always use explicit imports — no fully-qualified names inline, except the one pre-existing exception in this
  file (`android.content.Context` parameter type, matching the existing pattern in `DashboardCircleTopCompose.kt`
  and `DashboardShellController.kt`) which stays as-is; do not "fix" untouched lines.
- Never build user-facing text by string concatenation; reuse existing string resources
  (`R.string.stats_button`, `app.aaps.core.ui.R.string.overview_treatment_label`) — do not add new ones for
  Phase 1/2.
- No comments except where a hidden constraint or non-obvious WHY exists (e.g. the route-string / `AppRoute`
  coupling below).
- Skip a build only for genuinely trivial edits; every task in this plan changes function signatures/visibility
  or adds new interface members, so each one gets a compile check.
- No `git commit` unless the user explicitly asks — leave all work in the working tree.
- Simple, plain English in any new user-facing string, KDoc, or comment.

---

### Task 1: Pill-style time-range selector

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/compose/DashboardGraphComposeControls.kt`

**Interfaces:**
- Consumes: `DashboardEmbeddedComposeState.graphUiState.rangeHours: Int` and
  `DashboardEmbeddedComposeState.graphCommands.onSelectRange: ((Int) -> Unit)?` — both already exist and are
  already wired end-to-end from `DashboardShellController.syncGraphRange`. Do not touch either.
- Produces: no change to the public signature of `DashboardGraphComposeControls(composeState, modifier)` — its
  caller in `DashboardGraphComposeCard.kt` needs no edit.

The current implementation is an `OutlinedButton` + `DropdownMenu` offering 6/9/12/18/24h. Replace it with a row
of 4 toggle pills (6/12/18/24h — drop 9h to match the reference screenshot), each a `FilterChip` showing
`selected = (hours == selected)`.

- [ ] **Step 1: Replace the composable body**

Replace the entire file content with:

```kotlin
package app.aaps.plugins.main.general.dashboard.compose

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.plugins.main.R
import app.aaps.plugins.main.general.dashboard.DashboardEmbeddedComposeState

@Composable
internal fun DashboardGraphComposeControls(
    composeState: DashboardEmbeddedComposeState,
    modifier: Modifier = Modifier,
) {
    val selected = composeState.graphUiState.rangeHours
    val onSelect = composeState.graphCommands.onSelectRange ?: return
    val ranges = listOf(6, 12, 18, 24)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ranges.forEach { hours ->
            val label = when (hours) {
                6 -> stringResource(R.string.graph_long_scale_6h)
                12 -> stringResource(R.string.graph_long_scale_12h)
                18 -> stringResource(R.string.graph_long_scale_18h)
                24 -> stringResource(R.string.graph_long_scale_24h)
                else -> "${hours}h"
            }
            FilterChip(
                selected = hours == selected,
                onClick = { onSelect(hours) },
                label = { Text(text = label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}
```

Note: add the missing `androidx.compose.ui.unit.dp` import (used by `Arrangement.spacedBy(6.dp)`) — it is not
in the snippet above because it collides with the line-numbering of this document; add
`import androidx.compose.ui.unit.dp` alongside the other `androidx.compose.ui.*` imports.

- [ ] **Step 2: Compile the module**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` (macOS: drop the `.bat`; this repo's CLAUDE.md
Bash rules are Windows-specific and don't apply on this darwin session, so a plain `./gradlew` invocation is
fine here).
Expected: `BUILD SUCCESSFUL`. If it fails with an unresolved reference to `dp` or `FilterChip`, fix the missing
import — Material3 `FilterChip`/`FilterChipDefaults` live in `androidx.compose.material3`, already used
elsewhere in this same module (see `DashboardV2ComposeEmbedded.kt`'s `androidx.compose.material3.*` imports for
the exact artifact already on this module's classpath).

- [ ] **Step 3: Manual check (no automated UI test harness exists in this module for these composables)**

Run the app (only if the user has asked for an on-device check this session — otherwise leave this as a
described manual step for the user) and confirm: 4 pills show under the graph, tapping one changes the
selected pill and the BG/IOB graph range.

---

### Task 2: Add `openStatsScreen()` / `openTreatmentDialog()` commands

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/compose/DashboardHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt:1405-1426`

**Interfaces:**
- Consumes: `UiInteraction.openComposeMainAtRoute(context: Context, navRoute: String)` — already declared in
  `core/interfaces/src/main/kotlin/app/aaps/core/interfaces/ui/UiInteraction.kt:189` and already reachable from
  `DashboardShellController` via its existing `private val uiInteraction get() = deps.uiInteraction` accessor
  (line 105) — no new import of `UiInteraction` needed, it's already used in this file.
- Produces: `DashboardHeroCommands.openStatsScreen(): Unit` and
  `DashboardHeroCommands.openTreatmentDialog(): Unit`, callable from any Composable via
  `LocalDashboardHeroCommands.current.openStatsScreen()` / `.openTreatmentDialog()` (used by Task 3).

**Important constraint:** the real route strings live in `app.aaps.compose.navigation.AppRoute` inside the
`:app` Gradle module. `plugins:main` (where `DashboardShellController` lives) does **not** and must **not**
depend on `:app` — that would be a backwards/circular module dependency. `openComposeMainAtRoute` takes a plain
`String`, so pass the literal route value directly (`"stats"`, `"treatment_dialog"`) instead of importing
`AppRoute` — this mirrors the project's own guidance to inline a constant rather than add a cross-module
dependency. Because this couples two files by a magic string with no compiler check, this is exactly the kind
of hidden constraint that earns a one-line comment.

- [ ] **Step 1: Add the two methods to the interface**

In `DashboardHeroCommands.kt`, change:

```kotlin
interface DashboardHeroCommands : CircleTopActionListener {
    fun openLoopDialogFromHero()
    fun openContextFromBadge()
    fun onAimiAdaptationClicked()
}

object NoopDashboardHeroCommands : DashboardHeroCommands {
    override fun openLoopDialogFromHero() {}
    override fun openContextFromBadge() {}
    override fun onAimiAdaptationClicked() {}
    override fun onAimiAdvisorClicked() {}
    override fun onAdjustClicked() {}
    override fun onAimiPreferencesClicked() {}
    override fun onStatsClicked() {}
    override fun onAimiPulseClicked() {}
}
```

to:

```kotlin
interface DashboardHeroCommands : CircleTopActionListener {
    fun openLoopDialogFromHero()
    fun openContextFromBadge()
    fun onAimiAdaptationClicked()
    fun openStatsScreen()
    fun openTreatmentDialog()
}

object NoopDashboardHeroCommands : DashboardHeroCommands {
    override fun openLoopDialogFromHero() {}
    override fun openContextFromBadge() {}
    override fun onAimiAdaptationClicked() {}
    override fun onAimiAdvisorClicked() {}
    override fun onAdjustClicked() {}
    override fun onAimiPreferencesClicked() {}
    override fun onStatsClicked() {}
    override fun onAimiPulseClicked() {}
    override fun openStatsScreen() {}
    override fun openTreatmentDialog() {}
}
```

- [ ] **Step 2: Implement both in `DashboardShellController.createHeroCommands()`**

In `DashboardShellController.kt`, change the object at lines 1405-1426 from:

```kotlin
    private fun createHeroCommands(): DashboardHeroCommands =
        object : DashboardHeroCommands {
            override fun openLoopDialogFromHero() = openLoopDialog()

            override fun openContextFromBadge() = launchContextActivity()

            override fun onAimiAdaptationClicked() = launchAimiAdaptationStatusActivity()

            override fun onAimiAdvisorClicked() = launchAimiAdvisorActivity()

            override fun onAdjustClicked() {
                openAdjustmentDetails()
            }

            override fun onAimiPreferencesClicked() = launchMealAdvisorActivity()

            override fun onStatsClicked() = launchContextActivity()

            override fun onAimiPulseClicked() {
                openAdjustmentDetails()
            }
        }
```

to:

```kotlin
    private fun createHeroCommands(): DashboardHeroCommands =
        object : DashboardHeroCommands {
            override fun openLoopDialogFromHero() = openLoopDialog()

            override fun openContextFromBadge() = launchContextActivity()

            override fun onAimiAdaptationClicked() = launchAimiAdaptationStatusActivity()

            override fun onAimiAdvisorClicked() = launchAimiAdvisorActivity()

            override fun onAdjustClicked() {
                openAdjustmentDetails()
            }

            override fun onAimiPreferencesClicked() = launchMealAdvisorActivity()

            override fun onStatsClicked() = launchContextActivity()

            override fun onAimiPulseClicked() {
                openAdjustmentDetails()
            }

            // Route strings must match AppRoute.Stats.route / AppRoute.TreatmentDialog.route in the
            // :app module (app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt) — plugins:main
            // cannot depend on :app, so these are inlined literals, not a shared constant.
            override fun openStatsScreen() =
                uiInteraction.openComposeMainAtRoute(host.context, "stats")

            override fun openTreatmentDialog() =
                uiInteraction.openComposeMainAtRoute(host.context, "treatment_dialog")
        }
```

- [ ] **Step 3: Compile the module**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Add the Stats/Treatment button row to the status card

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardV2ComposeEmbedded.kt`

**Interfaces:**
- Consumes: `LocalDashboardHeroCommands.current.openStatsScreen()` / `.openTreatmentDialog()` from Task 2;
  `R.string.stats_button` (`plugins/main`, already exists, currently unused); `app.aaps.core.ui.R.string.overview_treatment_label`
  (`core/ui`, already exists, already reachable — `app.aaps.core.ui.R` is already imported indirectly via other
  `app.aaps.core.ui.*` references in this file).

The existing `commands` val (`val commands = LocalDashboardHeroCommands.current`, already present at line 82 of
`DashboardV2ComposeEmbedded.kt`) is reused — no new `CompositionLocal` read needed.

- [ ] **Step 1: Add a button row composable**

Add this private composable near the bottom of `DashboardV2ComposeEmbedded.kt`, after `DashboardV2LabelValue`:

```kotlin
@Composable
private fun DashboardV2QuickLinksRow(
    onStatsClick: () -> Unit,
    onTreatmentClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onStatsClick, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.stats_button))
        }
        OutlinedButton(onClick = onTreatmentClick, modifier = Modifier.weight(1f)) {
            Text(text = stringResource(app.aaps.core.ui.R.string.overview_treatment_label))
        }
    }
}
```

Add the two new imports this needs at the top of the file: `import androidx.compose.material3.OutlinedButton`.
(`Row`, `Arrangement`, `Text`, `stringResource`, `Modifier` are already imported in this file.)

- [ ] **Step 2: Place the row in the non-landscape and landscape layouts**

In the same file's `DashboardV2ComposeEmbedded` composable, the portrait branch currently ends with:

```kotlin
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
            ) {
                statusCard(Modifier.fillMaxWidth())
                DashboardNotificationsComposeList(
                    composeState = embeddedState,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                graphCard(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
```

Add the new row between `statusCard(...)` and `DashboardNotificationsComposeList(...)`:

```kotlin
        } else {
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(sectionSpacing),
            ) {
                statusCard(Modifier.fillMaxWidth())
                DashboardV2QuickLinksRow(
                    onStatsClick = commands::openStatsScreen,
                    onTreatmentClick = commands::openTreatmentDialog,
                    modifier = Modifier.fillMaxWidth(),
                )
                DashboardNotificationsComposeList(
                    composeState = embeddedState,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                graphCard(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }
```

Do the same in the landscape (`isLandscape`) branch immediately above it, inserting the row right after
`statusCard(Modifier.fillMaxWidth())` inside that branch's `Column`.

- [ ] **Step 3: Compile the module**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Promote the working CGM/adaptive-smoothing badge to `internal`

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/compose/DashboardCircleTopCompose.kt:711,914`

**Interfaces:**
- Produces: `internal fun HeroCgmCompactBadge(context: android.content.Context, state: StatusCardState, readingLine: String, sensorAge: String, smoothingLabel: String, compact: Boolean = false)`
  and `internal fun buildReadingLineOnly(context: android.content.Context, state: StatusCardState): String` —
  both now callable from `DashboardV2ComposeEmbedded.kt` (Task 5), same module, different package.

No behavior change — this task only widens visibility from `private` to `internal` on two functions that
already work correctly in the existing AIMI dashboard. Do not modify their bodies.

- [ ] **Step 1: Widen visibility**

Change line 711 from:

```kotlin
@Composable
private fun HeroCgmCompactBadge(
```

to:

```kotlin
@Composable
internal fun HeroCgmCompactBadge(
```

Change line 914 from:

```kotlin
private fun buildReadingLineOnly(context: android.content.Context, state: StatusCardState): String {
```

to:

```kotlin
internal fun buildReadingLineOnly(context: android.content.Context, state: StatusCardState): String {
```

- [ ] **Step 2: Compile the module**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL` (a visibility widening cannot break this file's own compilation; this step exists to
catch any accidental typo).

---

### Task 5: Wire the CGM/adaptive-smoothing badge into `DashboardV2StatusCard`

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardV2ComposeEmbedded.kt`

**Interfaces:**
- Consumes: `HeroCgmCompactBadge(...)` and `buildReadingLineOnly(...)` from Task 4;
  `StatusCardState.sensorAgeText: String?`, `StatusCardState.adaptiveSmoothingQualityTier`,
  `StatusCardState.adaptiveSmoothingQualityBadgeText: String`,
  `StatusCardState.adaptiveSmoothingQualityDialogMessage: String` — all already populated by
  `OverviewViewModel` for both dashboards; no ViewModel change needed.

`DashboardV2StatusCard` currently shows sensor age as a plain `DashboardV2Badge` (label/value pair, no tap
target) in the "Sensor" / "Site" row. Add the CGM badge as an additional, tappable element next to it — do not
remove the existing plain sensor badge, since it also carries the "Site" badge in the same row via
`Modifier.weight(1f)` pairing that Task 5 must not break.

- [ ] **Step 1: Add the new imports**

At the top of `DashboardV2ComposeEmbedded.kt`, add:

```kotlin
import app.aaps.plugins.main.general.dashboard.compose.HeroCgmCompactBadge
import app.aaps.plugins.main.general.dashboard.compose.buildReadingLineOnly
```

- [ ] **Step 2: Render the badge inside `DashboardV2StatusCard`**

Locate this block (the "Reservoir/Battery" then "Sensor/Site" rows inside `DashboardV2StatusCard`):

```kotlin
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_reservoir),
                            value = state?.reservoirText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_battery),
                            value = state?.pumpBatteryText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_sensor),
                            value = state?.sensorAgeText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                        DashboardV2Badge(
                            label = stringResource(R.string.dashboard_v2_site),
                            value = state?.infusionAgeText.dashboardV2ValueOr(unavailable),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
```

Add a third row right after it, still inside the same `Column`:

```kotlin
                    if (state != null && (state.sensorAgeText?.isNotBlank() == true || state.adaptiveSmoothingQualityTier != null)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 6.dp),
                        ) {
                            HeroCgmCompactBadge(
                                context = context,
                                state = state,
                                readingLine = buildReadingLineOnly(context, state),
                                sensorAge = state.sensorAgeText?.trim().orEmpty(),
                                smoothingLabel = state.adaptiveSmoothingQualityBadgeText.trim(),
                                compact = compact,
                            )
                        }
                    }
```

This must go inside the closing brace of the `Column(modifier = Modifier.weight(1f), ...)` block shown above —
i.e. immediately before that `Column`'s own closing `}`.

- [ ] **Step 3: Compile the module**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`
Expected: `BUILD SUCCESSFUL`. If `context` is unresolved at this call site, add
`val context = LocalContext.current` near the top of `DashboardV2StatusCard` (check first — line 167 of the
original file already declares `val context = LocalContext.current`, so this should already be in scope; only
add it if the compiler says otherwise).

- [ ] **Step 4: Manual check (describe to the user if no on-device check is requested this session)**

Confirm the CGM badge appears in DASHBOARD_V2's status card next to the sensor/site row, and tapping it opens a
dialog showing the reading line, sensor age, and (when the active smoothing plugin populates it) the smoothing
quality paragraph — matching the existing, working behavior already present in the older AIMI dashboard.

---

## Self-Review Notes (completed during authoring, not a separate pass)

- **Spec coverage:** Phase 1 (Task 1, 2, 3) and Phase 2 (Task 4, 5) of the spec are both fully covered here.
  Phases 3 and 4 are deliberately NOT in this plan — they depend on an in-flight investigation into whether the
  same-repo Compose `OverviewScreen` already has the dialogs/quick-actions to port from (better source than the
  external Tarciso fork's XML views). They will get their own follow-up plan once that investigation lands, per
  the "Scope Check" rule (each plan should stand on its own, working software).
- **Placeholder scan:** no TBD/TODO left; the one open decision flagged in the spec (whether to repurpose
  `onStatsClicked` or leave it alone) is resolved here — `onStatsClicked` is left untouched, a new
  `openStatsScreen()` command is added instead, so no existing caller's behavior changes.
- **Type consistency:** `openStatsScreen()`/`openTreatmentDialog()` signatures match between the interface
  (Task 2 Step 1), the implementation (Task 2 Step 2), and the call site (Task 3 Step 2). `HeroCgmCompactBadge`'s
  parameter list in Task 5 Step 2 matches its declaration exactly as read from the source file.
