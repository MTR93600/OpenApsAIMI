# Glass restyle — TreatmentsScreen shell + 3 list screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the Glass visual language to `TreatmentsScreen.kt`'s tabbed shell and its 3 smallest/lowest-
risk child screens (`RunningModeScreen`, `TempTargetScreen`, `ExtendedBolusScreen`), plus the two small shared
utilities all 8 Treatments children use (`ContentContainer.kt`, `TreatmentLazyColumn.kt`), with zero behavior
change.

**Architecture:** Treatments' children are architecturally different from Stats: each is a `LazyColumn`-based
list screen (via the shared `TreatmentLazyColumn`), with per-item cards built from `AapsCard` (a widely-used
shared component, NOT restyled itself — used by other, non-Glass screens too). This plan adds a NEW shared
`GlassCard` to the kit (a bare, non-titled card, unlike `GlassSectionCard`'s titled/collapsible design) as a
drop-in Glass-styled replacement for `AapsCard` in restyled per-item rows. `ContentContainer`/
`TreatmentLazyColumn` are used by screens OUTSIDE this plan's scope too (`ProfileManagementScreen`,
`TempTargetManagementScreen`, `QuickWizardManagementScreen`, `BgSourceScreen`, and 4 more Treatments children
not yet restyled) — both get NEW OPTIONAL color parameters defaulting to their EXACT current Material
behavior, so every existing, unrestyled caller is visually unaffected; only this plan's 3 restyled screens
pass Glass colors explicitly.

**Tech Stack:** Android, Kotlin, Jetpack Compose (Material3).

**Spec:** `docs/superpowers/specs/2026-09-11-glass-restyle-stats-treatments-design.md` (phase 2 of the phased
sequence).

## Global Constraints

- No `git commit` during this plan's execution (standing user rule).
- Zero behavior change: no ViewModel file is touched (`RunningModeViewModel.kt`, `TempTargetViewModel.kt`,
  `ExtendedBolusViewModel` and its host `TreatmentsViewModel.kt` are all OUT OF SCOPE).
- Dark mode is sourced ONLY via `isGlassDarkMode()` (`app.aaps.core.ui.compose.glass`, already shipped) —
  never `isSystemInDarkTheme()` directly.
- `ContentContainer`/`TreatmentLazyColumn`'s new color parameters MUST default to their current exact Material
  values — this is not optional, it is what keeps this plan from silently changing 4+ unrelated, non-Glass
  screens' appearance.
- `AapsCard` itself is NOT modified — it is a shared, widely-used component outside this restyle's scope;
  `GlassCard` is an ADDITION, not a replacement of `AapsCard`.
- Per-item semantic colors (`AapsTheme.generalColors.activeInsulinText`/`.futureRecord`/
  `.invalidatedRecord`, and each tab's own icon `colorGetter()` in `TreatmentsScreen.kt`) are established
  app-wide semantic tokens, not Glass chrome — do not touch them, same reasoning already applied consistently
  in the Stats restyle plan (`AapsTheme.generalColors.bgInRange`/`.cycleAverage` were left alone there for the
  identical reason).
- Explicit imports only.
- No new test file — this is a pure visual-layer change to already-tested screens, matching the precedent set
  by the Stats restyle plan (no new tests there either, for the same reason).

---

## Task 1: `GlassCard` (`:core:ui`)

**Files:**
- Create: `core/ui/src/main/kotlin/app/aaps/core/ui/compose/glass/GlassCard.kt`

**Interfaces:**
- Produces: `GlassCard(isDark, modifier, selected, content)`, consumed by Tasks 4-6.

- [ ] **Step 1: Write the file**

```kotlin
package app.aaps.core.ui.compose.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * Bare Glass-styled card — no title, no header, no collapse behavior (unlike [GlassSectionCard]).
 * A drop-in visual replacement for [app.aaps.core.ui.compose.AapsCard] in Glass-restyled screens,
 * matching its `selected` highlight behavior. Sets [LocalContentColor] for its subtree, same
 * mechanism as [GlassSectionCard].
 */
@Composable
fun GlassCard(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) GlassColors.skyBlue.copy(alpha = 0.6f) else GlassColors.borderCard(isDark)
    val backgroundBrush = if (selected) {
        Brush.verticalGradient(listOf(GlassColors.skyBlue.copy(alpha = 0.18f), GlassColors.skyBlue.copy(alpha = 0.08f)))
    } else {
        Brush.verticalGradient(listOf(GlassColors.cardBgStart(isDark), GlassColors.cardBgEnd(isDark)))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundBrush)
            .border(1.dp, borderColor, shape)
    ) {
        CompositionLocalProvider(LocalContentColor provides GlassColors.textBright(isDark)) {
            content()
        }
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :core:ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 2: `ContentContainer.kt` + `TreatmentLazyColumn.kt` — optional Glass-tint parameters

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/components/ContentContainer.kt`
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/TreatmentLazyColumn.kt`

**Interfaces:**
- Consumes: nothing new (colors passed as plain `Color` params, no direct `GlassColors` dependency in either
  file — keeps these two generic components decoupled from the Glass kit; callers compute the Glass color and
  pass it in).
- Produces: 2 new optional parameters on each function, both defaulting to the EXACT current Material
  behavior — existing callers (`ProfileManagementScreen`, `TempTargetManagementScreen`,
  `QuickWizardManagementScreen`, `BgSourceScreen`, and the 4 Treatments children NOT in this plan) need zero
  changes and render identically to before.

- [ ] **Step 1: `ContentContainer.kt`**

Add two new parameters with defaults matching the current hardcoded values exactly, and use them in place of
the two `MaterialTheme.colorScheme.onSurfaceVariant`-based reads:

```kotlin
@Composable
fun ContentContainer(
    isLoading: Boolean,
    isEmpty: Boolean,
    modifier: Modifier = Modifier,
    emptyIconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    emptyTextColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    content: @Composable () -> Unit
) {
```
Add the import `import androidx.compose.ui.graphics.Color`. Inside the `ContentState.Empty` branch, change:
```kotlin
Icon(
    imageVector = Icons.Outlined.SearchOff,
    contentDescription = null,
    modifier = Modifier.size(48.dp),
    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
)
Text(
    text = stringResource(R.string.no_records_available),
    style = MaterialTheme.typography.bodyLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```
to:
```kotlin
Icon(
    imageVector = Icons.Outlined.SearchOff,
    contentDescription = null,
    modifier = Modifier.size(48.dp),
    tint = emptyIconTint
)
Text(
    text = stringResource(R.string.no_records_available),
    style = MaterialTheme.typography.bodyLarge,
    color = emptyTextColor
)
```
Do not change anything else in this file (the `Loading` branch's `CircularProgressIndicator()` stays
untinted/default — out of scope for this task, not named in any finding).

- [ ] **Step 2: `TreatmentLazyColumn.kt`**

Add two new parameters with defaults matching the current hardcoded values exactly:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> TreatmentLazyColumn(
    items: List<T>,
    getTimestamp: (T) -> Long,
    getItemKey: (T) -> Any,
    rh: ResourceHelper,
    itemContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier,
    headerBackgroundColor: Color = MaterialTheme.colorScheme.surface,
    headerTextColor: Color = MaterialTheme.colorScheme.primary
) {
```
Add the import `import androidx.compose.ui.graphics.Color`. Change the sticky header's `Text`:
```kotlin
Text(
    text = dateUtil.dateStringRelative(getTimestamp(itemsForDay.first()), rh),
    modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.primary
)
```
to:
```kotlin
Text(
    text = dateUtil.dateStringRelative(getTimestamp(itemsForDay.first()), rh),
    modifier = Modifier
        .fillMaxWidth()
        .background(headerBackgroundColor)
        .padding(horizontal = 8.dp, vertical = 6.dp),
    textAlign = TextAlign.Center,
    style = MaterialTheme.typography.bodyMedium,
    fontWeight = FontWeight.Bold,
    color = headerTextColor
)
```

- [ ] **Step 3: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL. Since both new parameters
default to the exact prior hardcoded values, every existing call site across the whole repo keeps compiling
and rendering identically without any edit — confirm this by grepping for other call sites of
`ContentContainer(`/`TreatmentLazyColumn(` and confirming none of them need changes (they shouldn't).

---

## Task 3: `TreatmentsScreen.kt` shell restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/TreatmentsScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassScreenBackground` (already shipped).
- Produces: no interface change — `TreatmentsScreen(viewModel, onNavigateBack)`'s signature is unchanged, so
  `AppRoute.Treatments`'s call site in `AppNavGraph.kt` needs no edit.

Read `TreatmentsScreen.kt`'s current full content before editing. This task restyles ONLY the shell chrome:
the `Scaffold`/`AapsTopAppBar` → `GlassScreenBackground` + custom header, and `PrimaryScrollableTabRow`'s own
container/content color. It does NOT touch: the `tabs` list building, `toolbarConfigs` caching, `pagerState`,
`activeToolbar` derivation, the `when (tab.titleRes)` dispatch to each child screen, or any per-tab icon's
`colorGetter()` (those are established semantic colors, out of scope).

- [ ] **Step 1: Replace the `Scaffold` + `AapsTopAppBar` wrapper**

Replace:
```kotlin
Scaffold(
    topBar = {
        AapsTopAppBar(
            title = { Text(activeToolbar.title.ifEmpty { stringResource(app.aaps.core.ui.R.string.treatments_history) }) },
            navigationIcon = { activeToolbar.navigationIcon() },
            actions = { activeToolbar.actions(this) }
        )
    }
) { paddingValues ->
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        // Tab row
        PrimaryScrollableTabRow(selectedTabIndex = pagerState.currentPage) {
```
with:
```kotlin
val isDark = isGlassDarkMode()

GlassScreenBackground(isDark = isDark) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            activeToolbar.navigationIcon()
            Text(
                text = activeToolbar.title.ifEmpty { stringResource(app.aaps.core.ui.R.string.treatments_history) },
                color = GlassColors.textBright(isDark),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            activeToolbar.actions(this)
        }

        // Tab row
        PrimaryScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
            contentColor = GlassColors.textBright(isDark)
        ) {
```
The closing braces later in the function need adjusting to match (the old code closed with `}` for `Column`
then `}` for the `Scaffold`'s trailing lambda; the new code closes with `}` for `Column` then `}` for
`GlassScreenBackground`'s trailing lambda — same nesting depth, just renamed). Read the rest of the function
(the `HorizontalPager` block and its closing braces) and adjust only the outermost two closing braces to match
the new `GlassScreenBackground { Column { ... } }` nesting — do not touch anything inside the `HorizontalPager`
block itself.

Add imports:
```kotlin
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.GlassScreenBackground
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```
(check each isn't already imported before adding — `MaterialTheme`/`Alignment` in particular may already be
used elsewhere in the file). Remove the now-unused `import app.aaps.core.ui.compose.AapsTopAppBar` and
`androidx.compose.material3.Scaffold` imports if nothing else in the file uses them (check first).

- [ ] **Step 2: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 4: `RunningModeScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/RunningModeScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (Task 1), the new `ContentContainer`/
  `TreatmentLazyColumn` parameters (Task 2).
- No signature change on `RunningModeScreen(...)`.

Read the file's current full content before editing.

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: Pass Glass colors into `ContentContainer` and `TreatmentLazyColumn`**

Inside `RunningModeScreen`'s body, add `val isDark = isGlassDarkMode()` right after
`val uiState by viewModel.uiState.collectAsStateWithLifecycle()`.

Find:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.runningModes.isEmpty()
) {
```
replace with:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.runningModes.isEmpty(),
    emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f),
    emptyTextColor = GlassColors.textMuted(isDark)
) {
```

Find:
```kotlin
TreatmentLazyColumn(
    items = uiState.runningModes,
    getTimestamp = { it.timestamp },
    getItemKey = { it.id },
    rh = viewModel.rh,
    itemContent = { rm ->
```
replace with:
```kotlin
TreatmentLazyColumn(
    items = uiState.runningModes,
    getTimestamp = { it.timestamp },
    getItemKey = { it.id },
    rh = viewModel.rh,
    headerBackgroundColor = Color.Transparent,
    headerTextColor = GlassColors.skyBlue,
    itemContent = { rm ->
```
(`Color` should already be imported in this file — it already uses `androidx.compose.ui.graphics.Color` for
the semantic active/future colors.)

- [ ] **Step 3: `RunningModeItem` — `AapsCard` → `GlassCard`, explicit color swap**

Add `val isDark = isGlassDarkMode()` as the first line of `RunningModeItem`'s body (a separate private
composable).

Find:
```kotlin
AapsCard(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp)
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        ),
    selected = isSelected
) {
```
replace with:
```kotlin
GlassCard(
    isDark = isDark,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp)
        .combinedClickable(
            onClick = onClick,
            onLongClick = onLongPress
        ),
    selected = isSelected
) {
```
(remove the now-unused `import app.aaps.core.ui.compose.AapsCard` if nothing else in the file uses `AapsCard`
— check first).

Find the time `Text`'s color:
```kotlin
color = when {
    isActive -> Color(AapsTheme.generalColors.activeInsulinText.value)
    isFuture -> Color(AapsTheme.generalColors.futureRecord.value)
    else     -> MaterialTheme.colorScheme.onSurface
}
```
replace ONLY the `else` branch:
```kotlin
color = when {
    isActive -> Color(AapsTheme.generalColors.activeInsulinText.value)
    isFuture -> Color(AapsTheme.generalColors.futureRecord.value)
    else     -> GlassColors.textBright(isDark)
}
```
(the two semantic branches, `activeInsulinText`/`futureRecord`, are unchanged — same reasoning as every prior
semantic-color exception in this project). Leave the "Mode" `Text` (no explicit color, inherits from
`GlassCard`'s `LocalContentColor`) and the "Duration" `Text` (same) untouched — do not add a color to them.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 5: `TempTargetScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/TempTargetScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (Task 1), the new `ContentContainer`/
  `TreatmentLazyColumn` parameters (Task 2).
- No signature change on `TempTargetScreen(...)`.

Read the file's current full content before editing. This task is the SAME pattern as Task 4, applied to
`TempTargetScreen.kt`/`TempTargetItem` instead of `RunningModeScreen.kt`/`RunningModeItem` — follow the exact
same 4 steps:

1. Add the same 3 imports (`GlassCard`, `GlassColors`, `isGlassDarkMode`).
2. Add `val isDark = isGlassDarkMode()` after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`,
   and pass `emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f)`,
   `emptyTextColor = GlassColors.textMuted(isDark)` to `ContentContainer(...)`, and
   `headerBackgroundColor = Color.Transparent`, `headerTextColor = GlassColors.skyBlue` to
   `TreatmentLazyColumn(...)` — same as Task 4 Step 2, adjusted for this file's `uiState.tempTargets`
   field names.
3. In `TempTargetItem` (the private per-item composable), add `val isDark = isGlassDarkMode()` as its first
   line, change `AapsCard(...)` to `GlassCard(isDark = isDark, ...)` with all other arguments unchanged, and
   change the time-range `Text`'s color `when` block's `else` branch from `MaterialTheme.colorScheme.onSurface`
   to `GlassColors.textBright(isDark)` (leave the `isActive`/`isFuture` branches — `AapsTheme.generalColors.*`
   — unchanged). Remove the unused `AapsCard` import if nothing else in the file uses it.
4. Compile: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 6: `ExtendedBolusScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/ExtendedBolusScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (Task 1), the new `ContentContainer`/
  `TreatmentLazyColumn` parameters (Task 2).
- No signature change on `ExtendedBolusScreen(...)`.

Read the file's current full content before editing. Same pattern as Tasks 4-5, applied to
`ExtendedBolusScreen.kt`/`ExtendedBolusItem`:

1. Add the same 3 imports.
2. Add `val isDark = isGlassDarkMode()` after this file's `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`,
   pass the same `emptyIconTint`/`emptyTextColor` to `ContentContainer(...)` (using
   `uiState.extendedBoluses.isEmpty()`, this file's own field name), and the same `headerBackgroundColor`/
   `headerTextColor` to `TreatmentLazyColumn(...)`.
3. In `ExtendedBolusItem`, add `val isDark = isGlassDarkMode()` as its first line, change `AapsCard(...)` to
   `GlassCard(isDark = isDark, ...)`, and change the time/rate `Text`'s color `when` block's `else` branch
   from `MaterialTheme.colorScheme.onSurface` to `GlassColors.textBright(isDark)` (leave the `isActive`
   branch — `AapsTheme.generalColors.activeInsulinText` — unchanged; also leave the inline
   `SpanStyle(color = Color(AapsTheme.generalColors.activeInsulinText.value))` inside the `buildAnnotatedString`
   IOB span untouched, same semantic-color reasoning). Remove the unused `AapsCard` import if nothing else in
   the file uses it.
4. Compile: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Self-Review Notes

- **Spec coverage**: Phase 2 of the spec's phased sequence ("TreatmentsScreen tab shell + its 3 smallest/
  lowest-risk children") is fully covered: Task 3 is the shell, Tasks 4-6 are the 3 children (`TempTargetScreen`,
  `RunningModeScreen`, and `ExtendedBolusScreen` — the 3 smallest Treatments children by line count: 253, 255,
  275 lines respectively, replacing the spec's placeholder "ActivityStatsCompose-equivalent-simplicity
  screens" wording with the actual 3rd screen chosen once real file sizes were known).
- **No placeholders**: Tasks 5-6 are written as "same pattern as Task 4, applied to file X" rather than
  repeating the full code block twice more — this is not a placeholder, since Task 4's code IS the exact
  content, and Tasks 5-6 name every specific field/branch difference (state field names, which semantic
  branches to leave alone) rather than leaving anything to guesswork.
- **Type consistency**: `GlassCard`'s parameter names (`isDark`, `modifier`, `selected`, `content`) match
  `AapsCard`'s shape (`modifier`, `selected`, `content`) plus the one addition (`isDark`), so the swap in
  Tasks 4-6 is a mechanical 1:1 replacement.
- **Blast-radius check**: `ContentContainer`/`TreatmentLazyColumn`'s new parameters are verified (via a repo-
  wide grep, recorded in Task 2) to default to their EXACT prior values, so this plan's changes to two widely-
  shared files do not alter the visual output of any of the 4+ non-Treatments callers or the 4 Treatments
  children NOT in this plan's scope.
- **Manual verification note for the user** (this plan cannot be meaningfully unit-tested — pure visual
  restyle of already-tested screens): after this plan's tasks are done, open Treatments in both light and dark
  mode, confirm all 3 restyled tabs (Running Mode, Temp Target, Extended Bolus) show Glass-styled list item
  cards and empty/loading states, confirm the other 5 tabs (unrestyled) still look exactly as before, confirm
  the tab row and header restyled correctly, and confirm delete/selection-mode interactions still work on all
  3 restyled tabs.
