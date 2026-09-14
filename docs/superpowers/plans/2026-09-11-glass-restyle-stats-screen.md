# Glass restyle — shared kit + StatsScreen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a shared, reusable Glass-styled component kit in `:core:ui`, then apply it to `StatsScreen.kt`
and all of its content composables — including its two chart composables — with zero behavior change.

**Architecture:** A small kit (`GlassColors`, `isGlassDarkMode()`, `GlassScreenBackground`, `GlassSectionCard`)
lives in `:core:ui` (the module both `:ui` and `:plugins:main` already depend on) so it never needs a new
inter-module dependency and is reusable by every future Glass-restyle plan (Treatments' 8 screens, planned
separately). `StatsScreen.kt`'s `Scaffold` + 6× repeated `AapsCard{header+AnimatedVisibility}` pattern is
replaced by `GlassScreenBackground` + 6× `GlassSectionCard`. `GlassSectionCard`'s content slot establishes the
Glass text color via `CompositionLocalProvider(LocalContentColor provides ...)`, so the plain, colorless
`Text()` calls already used throughout `TddStatsCompose.kt`/`TirStatsCompose.kt`/`DexcomTirStatsCompose.kt`/
`ActivityStatsCompose.kt` inherit the right color automatically — verified during research, those 4 files need
zero code changes. Only the two files that explicitly reference `MaterialTheme.colorScheme.*` for chart colors
(`TddCyclePatternCompose.kt`, a Vico chart; `GlucosePentagonCompose.kt`, a Canvas-drawn chart) need targeted
color-token swaps.

**Tech Stack:** Android, Kotlin, Jetpack Compose (Material3), Vico (cartesian chart library, already used
elsewhere in this codebase — do not decompile it, its Compose API surface used here is unchanged from what
`TddCyclePatternCompose.kt` already calls).

**Spec:** `docs/superpowers/specs/2026-09-11-glass-restyle-stats-treatments-design.md`

## Global Constraints

- No `git commit` during this plan's execution (standing user rule).
- Zero behavior change: no ViewModel, use-case, or calculator file is touched. `StatsViewModel.kt` is
  explicitly OUT OF SCOPE.
- Dark mode is sourced ONLY via the new `isGlassDarkMode()` helper (itself backed by
  `LocalPreferences.current.observe(StringKey.GeneralDarkMode)` → `UiMode`) — never `isSystemInDarkTheme()`
  directly in any restyled screen. This exact bug class (dark mode from the OS instead of the app's own
  preference) has been a Critical review finding once already in this project's earlier phases.
- No new inter-module Gradle dependency — the shared kit's placement in `:core:ui` is exactly what avoids
  needing one; `:core:ui` already depends on `:core:keys` (confirmed, `core/ui/build.gradle.kts:38`).
- Explicit imports only — no fully-qualified names inline.
- Existing tests for `StatsViewModel` (if any) must keep passing unmodified — this plan adds no test file of
  its own (there is no new business logic to unit-test; visual-layer-only Composables in this codebase are not
  unit-tested, matching the precedent set by `StatusAgoraCard`/`BgChartCard`/`GlassLoopDashboardScreen`, none
  of which have their own tests).

---

## Task 1: Shared Glass component kit (`:core:ui`)

**Files:**
- Create: `core/ui/src/main/kotlin/app/aaps/core/ui/compose/glass/GlassColors.kt`
- Create: `core/ui/src/main/kotlin/app/aaps/core/ui/compose/glass/GlassScreenKit.kt`
- Create: `core/ui/src/main/kotlin/app/aaps/core/ui/compose/glass/GlassSectionCard.kt`

**Interfaces:**
- Produces: `GlassColors` (object with color constants + dark/light token functions), `isGlassDarkMode()`
  (`@Composable fun (): Boolean`), `GlassScreenBackground` (`@Composable` wrapper), `GlassSectionCard`
  (`@Composable` wrapper) — all consumed by Task 2 (StatsScreen.kt).

- [ ] **Step 1: `GlassColors.kt`**

```kotlin
package app.aaps.core.ui.compose.glass

import androidx.compose.ui.graphics.Color

/**
 * Shared color tokens for the Glass visual language, used by both the Glass dashboard skin
 * (`:plugins:main`) and Glass-restyled screens in `:ui`. Values match the dashboard's existing
 * Glass files exactly (GlassContainer, GlassLoopDashboardScreen) — kept here so screens outside
 * `:plugins:main` can reuse them without a new inter-module dependency.
 */
object GlassColors {
    val skyBlue = Color(0xFF38BDF8)
    val skyBlueDark = Color(0xFF0284C7)
    val emerald = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
    val indigo = Color(0xFF6366F1)

    fun textBright(isDark: Boolean): Color = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    fun textMuted(isDark: Boolean): Color = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    fun cardBgStart(isDark: Boolean): Color = if (isDark) Color(0x24FFFFFF) else Color(0xF5FFFFFF)
    fun cardBgEnd(isDark: Boolean): Color = if (isDark) Color(0x0EFFFFFF) else Color(0xE0EEF2FA)
    fun borderCard(isDark: Boolean): Color = if (isDark) Color(0x26FFFFFF) else Color(0xB8CBD5E1)
    fun screenBgTop(isDark: Boolean): Color = if (isDark) Color(0xFF070E1B) else Color(0xFFF1F5F9)
    fun screenBgBottom(isDark: Boolean): Color = if (isDark) Color(0xFF0B1424) else Color(0xFFE8EEF8)
}
```

- [ ] **Step 2: `GlassScreenKit.kt`**

```kotlin
package app.aaps.core.ui.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.UiMode
import app.aaps.core.ui.compose.LocalPreferences

/**
 * The single source of "is this screen in dark mode" for every Glass-styled screen: the app's own
 * GeneralDarkMode preference, never the raw OS theme. Mirrors the identical chain already used in
 * `GlassOverviewComposeEmbedded.kt` (`:plugins:main`) and the Loop Dashboard's nav destination
 * (`AppNavGraph.kt`) — this is the third use of the same chain, now shared instead of re-derived.
 */
@Composable
fun isGlassDarkMode(): Boolean {
    val preferences = LocalPreferences.current
    val darkModeValue by preferences.observe(StringKey.GeneralDarkMode).collectAsState()
    return when (UiMode.fromString(darkModeValue)) {
        UiMode.LIGHT  -> false
        UiMode.DARK   -> true
        UiMode.SYSTEM -> isSystemInDarkTheme()
    }
}

/**
 * The vertical gradient background every Glass screen uses, wrapping the screen's scrollable content.
 */
@Composable
fun GlassScreenBackground(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(GlassColors.screenBgTop(isDark), GlassColors.screenBgBottom(isDark), GlassColors.screenBgTop(isDark))
                )
            ),
        content = content
    )
}
```

- [ ] **Step 3: `GlassSectionCard.kt`**

```kotlin
package app.aaps.core.ui.compose.glass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Glass-styled replacement for the `AapsCard { header row + AnimatedVisibility(content) }` pattern
 * already repeated across `StatsScreen.kt`. Sets [LocalContentColor] for its whole subtree, so plain
 * `Text()` calls inside [content] that don't set an explicit color automatically render in the
 * correct Glass text color for the given [isDark] mode — no need to touch every child composable
 * individually.
 *
 * @param onToggleExpanded null means the card is not collapsible (always shows [content]); non-null
 *   makes the whole header row clickable and shows the expand/collapse chevron.
 */
@Composable
fun GlassSectionCard(
    title: String,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(GlassColors.cardBgStart(isDark), GlassColors.cardBgEnd(isDark))))
            .border(1.dp, GlassColors.borderCard(isDark), shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides GlassColors.textBright(isDark)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onToggleExpanded != null) Modifier.clickable { onToggleExpanded() } else Modifier)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = GlassColors.textBright(isDark),
                    modifier = Modifier.weight(1f)
                )
                trailingAction?.invoke()
                if (onToggleExpanded != null) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = GlassColors.textMuted(isDark)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    content = content
                )
            }
        }
    }
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :core:ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 2: `StatsScreen.kt` + `LoadingSection.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/stats/StatsScreen.kt`
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/stats/LoadingSection.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassScreenBackground`/`GlassSectionCard` (Task 1).
- Produces: no new public interface — `StatsScreen`'s own signature
  (`fun StatsScreen(viewModel: StatsViewModel, onNavigateBack: () -> Unit)`) is UNCHANGED, so its
  `AppRoute.Stats` call site in `AppNavGraph.kt` needs no edit.

Read `StatsScreen.kt`'s current full content before editing — every `viewModel.xxx()` call, every
`state.xxxExpanded`/`state.xxxLoading`/`state.xxxData` read, and every child composable call
(`TddStatsCompose`, `TirStatsCompose`, `DexcomTirStatsCompose`, `ActivityStatsCompose`,
`TddCyclePatternCompose`, `GlucosePentagonCompose`, `LoadingSection`, the two `OkCancelDialog`s) MUST be
preserved exactly — only the visual chrome around them changes.

- [ ] **Step 1: Replace `StatsScreen.kt`'s `Scaffold` + `AapsTopAppBar` with `GlassScreenBackground` + a
  custom header**

At the top of the file, replace the `Scaffold(topBar = { AapsTopAppBar(...) }) { paddingValues -> Column(...) }`
structure. The exact pattern to follow (a plain header Row with a back button + title, inside
`GlassScreenBackground`, no `Scaffold`) is the SAME pattern already shipped and working in
`GlassLoopDashboardScreen.kt` (`plugins/main/.../dashboard/glass/GlassLoopDashboardScreen.kt`) — read that
file for the exact header-Row shape (IconButton with `Icons.AutoMirrored.Filled.ArrowBack` + Text title) and
replicate it here, sourcing `isDark` from the new `isGlassDarkMode()` instead of a passed-in parameter (this
screen has no `isDark` parameter today and none should be added — call `isGlassDarkMode()` directly inside
`StatsScreen`, same as any other `@Composable`).

New imports needed (remove `Scaffold`/`AapsTopAppBar`-related imports that become unused):
```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.GlassSectionCard
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```
(`Icon`/`IconButton`/`Text` may already be imported — check before adding duplicates.)

The overall new shape:
```kotlin
@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    onNavigateBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.loadAllStats()
        }
    }

    val isDark = isGlassDarkMode()

    GlassScreenBackground(isDark = isDark) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(app.aaps.core.ui.R.string.back),
                        tint = GlassColors.textBright(isDark)
                    )
                }
                Text(
                    text = stringResource(app.aaps.core.ui.R.string.statistics),
                    color = GlassColors.textBright(isDark),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ... the 6 GlassSectionCard blocks from Step 2 go here ...
        }
    }

    if (state.showRecalculateDialog) {
        OkCancelDialog(
            message = stringResource(R.string.do_you_want_recalculate_tdd_stats),
            onConfirm = { viewModel.confirmRecalculateTdd() },
            onDismiss = { viewModel.dismissRecalculateDialog() }
        )
    }

    if (state.showResetActivityDialog) {
        OkCancelDialog(
            message = stringResource(R.string.do_you_want_reset_stats),
            onConfirm = { viewModel.confirmResetActivityStats() },
            onDismiss = { viewModel.dismissResetActivityDialog() }
        )
    }
}
```
Leave the two `OkCancelDialog` blocks and their conditions exactly as in the original file — unchanged, not
part of the restyle (they're the app's own shared dialog component, already used consistently everywhere).

- [ ] **Step 2: Replace each of the 6 `AapsCard{...}` blocks with `GlassSectionCard`**

Each of the six sections (TDD, TIR, Dexcom TIR, Activity, TDD Cycle Pattern, CGP) currently follows:
```kotlin
AapsCard(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { viewModel.toggleXExpanded() }.padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = stringResource(...), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.weight(1f))
            /* optional FilledTonalButton trailing action */
            Icon(imageVector = if (expanded) ExpandLess else ExpandMore, ...)
        }
        AnimatedVisibility(visible = state.xExpanded) { /* ...content... */ }
    }
}
```
Replace each with:
```kotlin
GlassSectionCard(
    title = stringResource(/* same string resource as before */),
    isDark = isDark,
    modifier = Modifier.fillMaxWidth(),
    expanded = state.xExpanded,
    onToggleExpanded = { viewModel.toggleXExpanded() },
    trailingAction = /* only for the TDD and Activity sections, which have a FilledTonalButton — see below */
) {
    /* the EXACT SAME inner content that was inside the old AnimatedVisibility block — the
       Crossfade/LoadingSection/XxxStatsCompose calls, byte-for-byte unchanged */
}
```
For the **TDD section** and **Activity section** (the two with a conditional `FilledTonalButton` trailing
action — "Recalculate" and "Reset" respectively), pass:
```kotlin
trailingAction = if (state.xExpanded && !state.xLoading) {
    { FilledTonalButton(onClick = { viewModel.showXDialog() }) { Text(text = stringResource(...)) } }
} else null
```
using the exact same condition and button content the original file had inline in its header `Row`.

For the **CGP section** (the one always-expanded section, no `toggleXExpanded`/chevron in the original), pass
`onToggleExpanded = null` (so `GlassSectionCard` renders it as a static, non-collapsible card, matching the
original's always-visible behavior) and no `trailingAction`.

Do not change the `title` string resources, the `Crossfade`/`LoadingSection` calls, or any `state.xxx`/
`viewModel.xxx()` reference — only the surrounding chrome (`AapsCard`→`GlassSectionCard`,
`Row`+`Icon`+manual `AnimatedVisibility`→`GlassSectionCard`'s own header+expand handling).

- [ ] **Step 3: `LoadingSection.kt` — tint the spinner**

Add an `accentColor: Color = GlassColors.skyBlue` parameter and apply it to the `CircularProgressIndicator`:

```kotlin
package app.aaps.ui.compose.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.compose.glass.GlassColors

/**
 * Composable displaying a loading state with title and message.
 * Shows a circular progress indicator with descriptive text.
 *
 * @param title The title of the section being loaded
 * @param message The loading message to display
 * @param accentColor Tint for the progress spinner
 */
@Composable
fun LoadingSection(
    title: String,
    message: String,
    accentColor: Color = GlassColors.skyBlue
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CircularProgressIndicator(color = accentColor)
            Text(
                text = "$title\n$message",
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}
```
Every existing call site of `LoadingSection(title = ..., message = ...)` in `StatsScreen.kt` keeps compiling
unchanged (the new parameter has a default), so no call site needs editing for this step alone — Step 2
already touches those call sites for the surrounding `GlassSectionCard` change.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 3: `TddCyclePatternCompose.kt` chart color restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/stats/TddCyclePatternCompose.kt`

**Interfaces:**
- Consumes: `GlassColors` (Task 1).
- No signature change — `TddCyclePatternCompose(data, offset, onOffsetChange, modifier)` stays identical, so
  its call site in `StatsScreen.kt` (already restyled in Task 2) needs no further edit.

This chart's structure (a Vico `CartesianChartHost` line chart) is NOT touched — only the 3 places it reads
`MaterialTheme.colorScheme.primary`/`.secondary` to color its "raw" vs "cleaned" data series.

- [ ] **Step 1: Add the import**

```kotlin
import app.aaps.core.ui.compose.glass.GlassColors
```

- [ ] **Step 2: Replace the 3 color references**

Line ~122 (`Crossfade` block, `baseColor` passed to `TddCycleChart`):
```kotlin
val baseColor = if (cleaned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
```
becomes:
```kotlin
val baseColor = if (cleaned) GlassColors.emerald else GlassColors.skyBlue
```

Line ~138 (legend swatch color):
```kotlin
val legendBaseColor = if (showCleaned) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
```
becomes:
```kotlin
val legendBaseColor = if (showCleaned) GlassColors.emerald else GlassColors.skyBlue
```

Do NOT change `AapsTheme.generalColors.cycleAverage` (the dashed average-line color) — that is an established,
semantically-named app-wide color token, not Glass chrome; leave both of its usages (in the legend swatch and
in `TddCycleChart`'s `averageColor`) exactly as they are.

Do NOT change `axisLabelComponent`'s `MaterialTheme.colorScheme.onSurface` (line ~235) or the other plain
`MaterialTheme.colorScheme.onSurfaceVariant`/`.onSurface` text-color reads (lines ~79, ~104, ~115, ~148,
~160) — these are the chart's own axis-label and helper-text colors, which are rendered INSIDE the
`GlassSectionCard` from Task 2 and already inherit the correct Glass text color via
`LocalContentColor`/`CompositionLocalProvider` for the ones that don't set an explicit `color` — but these 6
DO set an explicit `color = MaterialTheme.colorScheme.X`, so they do NOT automatically inherit. Read each one:
`MaterialTheme.colorScheme.onSurfaceVariant`-colored lines (79, 104, 148, 160) are secondary/muted text —
replace their `color = MaterialTheme.colorScheme.onSurfaceVariant` with
`color = GlassColors.textMuted(GlassColors.let { LocalGlassIsDark })`. There is no such
`LocalGlassIsDark` composition local in this kit (Task 1 deliberately did not add one, to keep the kit small)
— instead, call `isGlassDarkMode()` directly at the top of `TddCyclePatternCompose`'s function body (it is a
`@Composable` function, so this is valid) and reuse that single `val isDark = isGlassDarkMode()` for all 6
color reads in this file:

```kotlin
color = MaterialTheme.colorScheme.onSurfaceVariant  // (lines 79, 104, 148, 160)
```
becomes
```kotlin
color = GlassColors.textMuted(isDark)
```
and
```kotlin
color = MaterialTheme.colorScheme.onSurface  // (line 115)
```
becomes
```kotlin
color = GlassColors.textBright(isDark)
```
and line ~235's `axisLabelComponent`'s `TextStyle(color = MaterialTheme.colorScheme.onSurface)` becomes
`TextStyle(color = GlassColors.textBright(isDark))`.

Add the import `import app.aaps.core.ui.compose.glass.isGlassDarkMode` alongside the `GlassColors` import from
Step 1, and add `val isDark = isGlassDarkMode()` as the first line inside `TddCyclePatternCompose`'s function
body (NOT inside the private `TddCycleChart` helper — that helper receives colors as parameters already, it
does not need its own `isGlassDarkMode()` call; only the 6 direct `MaterialTheme.colorScheme.X` reads that
live directly in `TddCyclePatternCompose`'s own body need this — re-read the file after this edit to confirm
none of `TddCycleChart`'s own color parameters were accidentally left as `MaterialTheme.colorScheme.X`, since
`averageColor`/`axisLabelComponent` are computed inside `TddCycleChart`, which is a SEPARATE composable
function — give `TddCycleChart` its own `val isDark = isGlassDarkMode()` too, for the `axisLabelComponent`
line that lives inside it).

- [ ] **Step 3: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 4: `GlucosePentagonCompose.kt` chart color restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/stats/GlucosePentagonCompose.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode` (Task 1).
- No signature change on any public function in this file.

This file has TWO composables that each independently read `MaterialTheme.colorScheme.*`:
`GlucosePentagonCard` (the legend + PGR score text) and `GlucosePentagonChart` (the Canvas-drawn radar chart).
Give EACH its own `val isDark = isGlassDarkMode()` at the top of its body (they are separate `@Composable`
functions).

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: `GlucosePentagonCard` — legend + PGR text colors**

Add `val isDark = isGlassDarkMode()` as the first line of `GlucosePentagonCard`'s body.

Replace:
```kotlin
LegendItem(
    color = MaterialTheme.colorScheme.primary,
    text = stringResource(R.string.cgp_patient)
)
```
with:
```kotlin
LegendItem(
    color = GlassColors.skyBlue,
    text = stringResource(R.string.cgp_patient)
)
```
(Leave the other `LegendItem`'s `color = AapsTheme.generalColors.bgInRange` untouched — that is the
established semantic "in range" BG color, the same reasoning as `TddCyclePatternCompose`'s
`AapsTheme.generalColors.cycleAverage` in Task 3: an app-wide semantic token, not Glass chrome.)

Replace the `pgrColor` block:
```kotlin
val pgrColor = when {
    cgpData.pgr <= 2.0 -> AapsTheme.generalColors.bgInRange
    cgpData.pgr <= 3.0 -> AapsTheme.generalColors.bgInRange
    cgpData.pgr <= 4.0 -> MaterialTheme.colorScheme.tertiary
    cgpData.pgr <= 4.5 -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
    else               -> MaterialTheme.colorScheme.error
}
```
with:
```kotlin
val pgrColor = when {
    cgpData.pgr <= 2.0 -> AapsTheme.generalColors.bgInRange
    cgpData.pgr <= 3.0 -> AapsTheme.generalColors.bgInRange
    cgpData.pgr <= 4.0 -> GlassColors.amber
    cgpData.pgr <= 4.5 -> GlassColors.red.copy(alpha = 0.7f)
    else               -> GlassColors.red
}
```
(The two `bgInRange` branches are unchanged — same semantic-color reasoning as above.)

Replace the second `Text`'s color:
```kotlin
Text(
    text = pgrExplanation,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    ...
)
```
with:
```kotlin
Text(
    text = pgrExplanation,
    style = MaterialTheme.typography.labelSmall,
    color = GlassColors.textMuted(isDark),
    ...
)
```

- [ ] **Step 3: `GlucosePentagonChart` — Canvas chart colors**

Add `val isDark = isGlassDarkMode()` as the first line of `GlucosePentagonChart`'s body.

Replace:
```kotlin
val gridColor = MaterialTheme.colorScheme.outlineVariant
val axisColor = MaterialTheme.colorScheme.outline
val refColor = AapsTheme.generalColors.bgInRange
val patientColor = MaterialTheme.colorScheme.primary
val labelColor = MaterialTheme.colorScheme.onSurface
```
with:
```kotlin
val gridColor = GlassColors.borderCard(isDark)
val axisColor = GlassColors.textMuted(isDark)
val refColor = AapsTheme.generalColors.bgInRange
val patientColor = GlassColors.skyBlue
val labelColor = GlassColors.textBright(isDark)
```
(`refColor` unchanged — same semantic-color reasoning as above; it represents the reference/target BG range,
not chrome.) The rest of the function (the actual `Canvas { ... }` drawing math) reads these five `val`s as
already-computed colors — do not touch anything past this point in the function; the draw calls are unchanged.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Self-Review Notes

- **Spec coverage**: Goal 1 (shared kit) → Task 1. Goal 2 (StatsScreen + its 6 content composables including
  both charts) → Tasks 2-4 (`TddStatsCompose.kt`/`TirStatsCompose.kt`/`DexcomTirStatsCompose.kt`/
  `ActivityStatsCompose.kt` deliberately get no task of their own — verified during spec research that their
  `Text()` calls have no explicit `color =` and therefore inherit `GlassSectionCard`'s `LocalContentColor`
  automatically; this is not a placeholder, it's a researched, explained "zero tasks needed" conclusion,
  called out explicitly rather than silently omitted). Goal 4 (zero behavior change) → enforced throughout by
  "preserve every state/viewModel reference exactly" instructions in Tasks 2-4. Goal 5 (dark mode sourcing) →
  `isGlassDarkMode()` in Task 1, used everywhere, never `isSystemInDarkTheme()` directly.
- **No placeholders**: every color swap in Tasks 3-4 names the exact before/after line; the "these 4 files
  need no changes" claim is backed by the specific code-shape reasoning (no explicit `color =` on their
  `Text()` calls) that a task reviewer can independently verify by reading those files.
- **Type consistency**: `GlassSectionCard`'s parameter names (`title`, `isDark`, `expanded`,
  `onToggleExpanded`, `trailingAction`, `content`) are used identically in Task 2's call sites.
- **Manual verification note for the user** (this plan cannot be meaningfully unit-tested — it's a pure visual
  restyle of already-tested business logic): after this plan's tasks are done, open the Stats screen in both
  light and dark mode (toggle the app's own dark-mode preference, not the OS theme) and confirm all 6 sections
  render with the Glass card style, the two charts render with the new accent colors, and every
  expand/collapse/recalculate/reset interaction still works exactly as before.
