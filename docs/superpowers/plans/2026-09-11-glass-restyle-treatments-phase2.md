# Glass restyle — remaining 5 Treatments screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the Glass visual language to the remaining 5 Treatments child screens — `CareportalScreen`,
`UserEntryScreen`, `ProfileSwitchScreen`, `TempBasalScreen`, `BolusCarbsScreen` — completing the Treatments
tab (all 8 children now restyled, alongside the already-shipped shell), with zero behavior change.

**Architecture:** Same pattern already proven twice (`StatsScreen` phase, then `RunningModeScreen`/
`TempTargetScreen`/`ExtendedBolusScreen` phase): `AapsCard` → `GlassCard`, an `else`-branch
`MaterialTheme.colorScheme.onSurface` → `GlassColors.textBright(isDark)` swap wherever a time/value text has
one, `MaterialTheme.colorScheme.onSurfaceVariant` → `GlassColors.textMuted(isDark)` for secondary labels,
`ContentContainer`/`TreatmentLazyColumn` calls gain the same Glass color arguments already added to their
signatures in an earlier plan. Semantic colors (`AapsTheme.generalColors.*`, `ElementType.*.color()`) are
never touched — established app-wide tokens, not Glass chrome.

**One real deviation found during research**: `UserEntryScreen.kt` does NOT use the shared `ContentContainer`/
`TreatmentLazyColumn` composables — it has its own inline, duplicated copy of the same loading/empty/sticky-
header/grouped-list logic (pre-existing tech debt, not something this plan fixes — refactoring it to use the
shared components would be a behavior-risk change outside a pure visual restyle's scope). Task 2 below
restyles that screen's own inline copy directly.

**Tech Stack:** Android, Kotlin, Jetpack Compose (Material3).

**Spec:** `docs/superpowers/specs/2026-09-11-glass-restyle-stats-treatments-design.md` (completes phase 3 of
the phased sequence — item 3 originally said "remaining 5 Treatments children"; the actual 5 remaining after
this spec's phase 2 chose `ExtendedBolusScreen` as its 3rd screen are `CareportalScreen`, `UserEntryScreen`,
`ProfileSwitchScreen`, `TempBasalScreen`, `BolusCarbsScreen` — corrected here now that real file sizes and
phase-2 choices are known).

## Global Constraints

- No `git commit` during this plan's execution (standing user rule).
- Zero behavior change: no ViewModel file is touched (`CareportalViewModel.kt`, `UserEntryViewModel.kt`,
  `ProfileSwitchViewModel.kt`, `TempBasalViewModel.kt`, `TreatmentsViewModel.kt`'s bolus/carbs sub-VM are all
  OUT OF SCOPE).
- Dark mode is sourced ONLY via `isGlassDarkMode()` (already shipped) — never `isSystemInDarkTheme()`
  directly.
- `AapsCard` itself is NOT modified — `GlassCard` (already shipped) is used instead.
- Semantic colors are never touched: `AapsTheme.generalColors.activeInsulinText`/`.futureRecord`/
  `.invalidatedRecord`/`.bgInRange`/etc., `ElementType.*.color()`/`.icon()`, and
  `Action.ColorGroup.toElementColor()` (in `UserEntryScreen.kt`) are all established app-wide semantic tokens.
- `WizardInfoDialog.kt` (shown from `BolusCarbsScreen`'s calculator-result tap) and `OkCancelDialog` are
  shared dialog components — out of scope for this restyle, same as every prior phase's treatment of shared
  dialogs.
- Explicit imports only.
- No new test file — pure visual-layer change to already-tested screens, matching every prior phase's
  precedent.

---

## Task 1: `CareportalScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/CareportalScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (already shipped).
- No signature change on `CareportalScreen(...)`.

Read the file's current full content before editing. This screen's `TherapyEventItem` has NO explicit
`MaterialTheme.colorScheme.*` text color anywhere (every `Text()` is plain, inherits `LocalContentColor`) — the
only change needed beyond the standard `ContentContainer`/`TreatmentLazyColumn`/`AapsCard` swap is that swap
itself; there is no `else`-branch color to find.

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: Pass Glass colors into `ContentContainer` and `TreatmentLazyColumn`**

Add `val isDark = isGlassDarkMode()` right after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`.

Find:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.therapyEvents.isEmpty()
) {
```
replace with:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.therapyEvents.isEmpty(),
    emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f),
    emptyTextColor = GlassColors.textMuted(isDark)
) {
```

Find:
```kotlin
TreatmentLazyColumn(
    items = uiState.therapyEvents,
    getTimestamp = { it.timestamp },
    getItemKey = { it.id },
    rh = viewModel.rh,
    itemContent = { te ->
```
replace with:
```kotlin
TreatmentLazyColumn(
    items = uiState.therapyEvents,
    getTimestamp = { it.timestamp },
    getItemKey = { it.id },
    rh = viewModel.rh,
    headerBackgroundColor = GlassColors.screenBgTop(isDark),
    headerTextColor = GlassColors.skyBlue,
    itemContent = { te ->
```
(this file does not currently import `androidx.compose.ui.graphics.Color` for anything else at the top level —
check before assuming; if the compiler complains `Color` is unresolved after this edit, add
`import androidx.compose.ui.graphics.Color`, though it likely already is imported since
`AapsTheme.generalColors.invalidatedRecord.value` is wrapped in `Color(...)` later in the file).

- [ ] **Step 3: `TherapyEventItem` — `AapsCard` → `GlassCard`**

Add `val isDark = isGlassDarkMode()` as the first line of `TherapyEventItem`'s body (a separate private
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
(remove the now-unused `import app.aaps.core.ui.compose.AapsCard` if nothing else in the file uses it — check
first). Do not add a color to any of the plain `Text()` calls in this composable (time, event type, duration,
BG value, note) — none had an explicit color before, and none should gain one now; they correctly inherit from
`GlassCard`'s `LocalContentColor`. Leave `AapsTheme.generalColors.invalidatedRecord` (the invalid-record
delete-icon tint) untouched.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 2: `UserEntryScreen.kt` restyle (the deviating screen — no `ContentContainer`/`TreatmentLazyColumn`)

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/UserEntryScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (already shipped).
- No signature change on `UserEntryScreen(...)`.

Read the file's current full content before editing. This screen builds its OWN inline
`when { isLoading -> ...; isEmpty -> ...; else -> LazyColumn { stickyHeader { ... } } }` block instead of using
`ContentContainer`/`TreatmentLazyColumn` — restyle this inline copy directly; do NOT refactor it to use the
shared components (that would be a behavior-risk change beyond this plan's visual-only scope).

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: Add `val isDark = isGlassDarkMode()` and recolor the inline empty-state text and sticky
  header**

Add `val isDark = isGlassDarkMode()` right after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`
in `UserEntryScreen`'s body.

Find the empty-state branch:
```kotlin
uiState.userEntries.isEmpty() -> {
    Text(
        text = stringResource(R.string.no_records_available),
        modifier = Modifier
            .align(Alignment.Center)
            .padding(50.dp),
        style = MaterialTheme.typography.bodyLarge
    )
}
```
replace with:
```kotlin
uiState.userEntries.isEmpty() -> {
    Text(
        text = stringResource(R.string.no_records_available),
        modifier = Modifier
            .align(Alignment.Center)
            .padding(50.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = GlassColors.textMuted(isDark)
    )
}
```
(Leave the `isLoading -> { CircularProgressIndicator(...) }` branch above it untouched — no prior task in this
whole restyle project has ever tinted a loading spinner beyond `LoadingSection.kt`'s own dedicated case, and
this one is not that; not part of any finding, stays default.)

Find the sticky header:
```kotlin
stickyHeader(key = dateString) {
    Text(
        text = viewModel.dateUtil.dateStringRelative(itemsForDay.first().timestamp, viewModel.rh),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}
```
replace with:
```kotlin
stickyHeader(key = dateString) {
    Text(
        text = viewModel.dateUtil.dateStringRelative(itemsForDay.first().timestamp, viewModel.rh),
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassColors.screenBgTop(isDark))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = GlassColors.skyBlue
    )
}
```
(Matches exactly how the shared `TreatmentLazyColumn`'s own sticky header was fixed in an earlier task/fix
wave — same opaque-background reasoning: a transparent header would let scrolling rows show through the pinned
date text.)

- [ ] **Step 3: `UserEntryItem` — `AapsCard` → `GlassCard`**

Add `val isDark = isGlassDarkMode()` as the first line of `UserEntryItem`'s body (a separate private
composable).

Find:
```kotlin
AapsCard(
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp)
) {
```
replace with:
```kotlin
GlassCard(
    isDark = isDark,
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 2.dp)
) {
```
(this card has no `selected` parameter — this screen has no selection mode, confirmed by
`isRemovingMode = false, selectedCount = 0` in its `SelectableListToolbar(...)` call — do not add one; remove
the now-unused `import app.aaps.core.ui.compose.AapsCard` if nothing else in the file uses it — check first).
Do not add a color to any of the plain `Text()` calls in this composable (time, action, values, notes) — none
had an explicit color before. Leave `Action.ColorGroup.toElementColor()` and `actionToAnnotatedString(...)`
(both semantic per-action-type colorings) completely untouched.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 3: `ProfileSwitchScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/ProfileSwitchScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (already shipped), the `ContentContainer`/
  `TreatmentLazyColumn` Glass color parameters (already shipped).
- No signature change on `ProfileSwitchScreen(...)`.

Read the file's current full content before editing. This is the same pattern already applied to
`RunningModeScreen.kt`/`TempTargetScreen.kt`/`ExtendedBolusScreen.kt` in an earlier plan, with ONE addition:
a "Clone" text button colored `MaterialTheme.colorScheme.primary`.

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: Pass Glass colors into `ContentContainer` and `TreatmentLazyColumn`**

Add `val isDark = isGlassDarkMode()` right after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`.

Find the `ContentContainer(...)` call in this file (its `isEmpty` argument reads this file's own
`uiState.profileSwitches.isEmpty()` — confirm the exact field name by reading the file, then add the same two
new arguments used in every sibling task):
```kotlin
emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f),
emptyTextColor = GlassColors.textMuted(isDark)
```
Find the `TreatmentLazyColumn(...)` call and add:
```kotlin
headerBackgroundColor = GlassColors.screenBgTop(isDark),
headerTextColor = GlassColors.skyBlue,
```
(placed after `rh = viewModel.rh,` and before `itemContent = { ... },`, matching the exact position used in
every sibling task in this and the prior plan).

- [ ] **Step 3: `ProfileSwitchItem` — `AapsCard` → `GlassCard`, two color swaps**

Add `val isDark = isGlassDarkMode()` as the first line of `ProfileSwitchItem`'s body.

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
(remove the now-unused `import app.aaps.core.ui.compose.AapsCard` if nothing else in the file uses it).

Find the time/profile-name `Text`'s color `when` block:
```kotlin
color = when {
    isActive -> Color(AapsTheme.generalColors.activeInsulinText.value)
    isFuture -> Color(AapsTheme.generalColors.futureRecord.value)
    else     -> MaterialTheme.colorScheme.onSurface
}
```
replace ONLY the `else` branch with `GlassColors.textBright(isDark)` (the two semantic branches stay
unchanged).

Find the "Clone" button:
```kotlin
Text(
    text = stringResource(R.string.clone_label),
    modifier = Modifier
        .clickable {
            onClone(profileSwitch)
        }
        .padding(start = 5.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    fontSize = 14.sp,
    color = MaterialTheme.colorScheme.primary,
    fontWeight = FontWeight.Bold,
    textDecoration = TextDecoration.Underline
)
```
replace `color = MaterialTheme.colorScheme.primary` with `color = GlassColors.skyBlue` (leave every other
argument — `text`, `modifier`, `fontSize`, `fontWeight`, `textDecoration` — unchanged).

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 4: `TempBasalScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/TempBasalScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (already shipped), the `ContentContainer`/
  `TreatmentLazyColumn` Glass color parameters (already shipped).
- No signature change on `TempBasalScreen(...)`.

Read the file's current full content before editing. Same pattern as Task 3 (and the earlier
`RunningModeScreen`/`TempTargetScreen`/`ExtendedBolusScreen` plan), with NO extra color references beyond the
standard `else`-branch swap — follow these 4 steps:

1. Add the same 3 imports (`GlassCard`, `GlassColors`, `isGlassDarkMode`).
2. Add `val isDark = isGlassDarkMode()` after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`,
   and add `emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f)`,
   `emptyTextColor = GlassColors.textMuted(isDark)` to this file's `ContentContainer(...)` call, and
   `headerBackgroundColor = GlassColors.screenBgTop(isDark)`, `headerTextColor = GlassColors.skyBlue` to its
   `TreatmentLazyColumn(...)` call.
3. In `TempBasalItem` (the private per-item composable), add `val isDark = isGlassDarkMode()` as its first
   line, change `AapsCard(...)` to `GlassCard(isDark = isDark, ...)` with all other arguments unchanged, and
   change the time-range `Text`'s color `when` block's `else` branch from `MaterialTheme.colorScheme.onSurface`
   to `GlassColors.textBright(isDark)` (leave the `isActive`/`isFuture` branches —
   `AapsTheme.generalColors.activeInsulinText`/`.futureRecord` — and the `SpanStyle` inside the same `Text`'s
   `buildAnnotatedString` for the IOB span — also `activeInsulinText` — unchanged; same dual-usage pattern
   already handled correctly for `ExtendedBolusScreen.kt` in the prior plan). Leave the 3 type-flag `Text`s
   (`tbr_type_flag_extended`/`_suspended`/`_emulated_suspended`/`_superbolus`, all colored via
   `ElementType.TEMP_BASAL.color()`) completely untouched — semantic per-element-type color, not Glass chrome.
   Remove the unused `AapsCard` import if nothing else in the file uses it.
4. Compile: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 5: `BolusCarbsScreen.kt` restyle

**Files:**
- Modify: `ui/src/main/kotlin/app/aaps/ui/compose/treatments/BolusCarbsScreen.kt`

**Interfaces:**
- Consumes: `GlassColors`/`isGlassDarkMode`/`GlassCard` (already shipped), the `ContentContainer`/
  `TreatmentLazyColumn` Glass color parameters (already shipped).
- No signature change on `BolusCarbsScreen(...)`.

Read the file's current full content before editing. This is the largest and most complex of the 8 Treatments
children — its `MealLinkItem` renders up to 3 stacked rows per item (a bolus-calculator-result row, a bolus
row, a carbs row), but the color-swap surface is small and follows the established pattern.

- [ ] **Step 1: Add imports**

```kotlin
import app.aaps.core.ui.compose.glass.GlassCard
import app.aaps.core.ui.compose.glass.GlassColors
import app.aaps.core.ui.compose.glass.isGlassDarkMode
```

- [ ] **Step 2: Pass Glass colors into `ContentContainer` and `TreatmentLazyColumn`**

Add `val isDark = isGlassDarkMode()` right after `val uiState by viewModel.uiState.collectAsStateWithLifecycle()`.

Find:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.mealLinks.isEmpty()
) {
```
replace with:
```kotlin
ContentContainer(
    isLoading = uiState.isLoading,
    isEmpty = uiState.mealLinks.isEmpty(),
    emptyIconTint = GlassColors.textMuted(isDark).copy(alpha = 0.6f),
    emptyTextColor = GlassColors.textMuted(isDark)
) {
```

Find the `TreatmentLazyColumn(...)` call (its `items` argument is `uiState.mealLinks`) and add, in the same
position used by every sibling task:
```kotlin
headerBackgroundColor = GlassColors.screenBgTop(isDark),
headerTextColor = GlassColors.skyBlue,
```

- [ ] **Step 3: `MealLinkItem` — `AapsCard` → `GlassCard`, three color swaps**

Add `val isDark = isGlassDarkMode()` as the first line of `MealLinkItem`'s body.

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
(remove the now-unused `import app.aaps.core.ui.compose.AapsCard` if nothing else in the file uses it).

Find the bolus-row time `Text`'s inline conditional color:
```kotlin
color = if (bolus.timestamp > dateUtil.now()) Color(AapsTheme.generalColors.futureRecord.value) else MaterialTheme.colorScheme.onSurface
```
replace with:
```kotlin
color = if (bolus.timestamp > dateUtil.now()) Color(AapsTheme.generalColors.futureRecord.value) else GlassColors.textBright(isDark)
```
(only the `else` half changes — the `futureRecord` semantic branch stays unchanged).

Find the insulin-label `Text` (shown when the bolus's insulin type differs from the active profile's):
```kotlin
Text(
    text = bolus.iCfg.insulinLabel,
    modifier = Modifier.padding(start = 4.dp),
    fontSize = 11.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```
replace `color = MaterialTheme.colorScheme.onSurfaceVariant` with `color = GlassColors.textMuted(isDark)`.

Find the bolus-type-label `Text` (the "SMB"/"Meal Bolus"/"Prime/Fill"/Afrezza label):
```kotlin
Text(
    text = when {
        isAfrezzaDose                 -> stringResource(CoreUiR.string.afrezza_type_shortname)
        bolus.type == BS.Type.SMB     -> stringResource(CoreUiR.string.smb_shortname)
        bolus.type == BS.Type.NORMAL  -> stringResource(CoreUiR.string.careportal_mealbolus)
        bolus.type == BS.Type.PRIMING -> stringResource(CoreUiR.string.prime_fill)
        else                          -> stringResource(CoreUiR.string.careportal_mealbolus)
    },
    fontSize = 11.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant
)
```
replace `color = MaterialTheme.colorScheme.onSurfaceVariant` with `color = GlassColors.textMuted(isDark)`
(leave the `when` block that computes `text` completely unchanged — only this `Text`'s `color` argument
changes).

Leave the bolus-calculator-result row's `ElementType.BOLUS_WIZARD.color()` icon tint, the IOB `SpanStyle`'s
`AapsTheme.generalColors.activeInsulinText`, and every plain colorless `Text()` in the carbs row and the
bolus-notes/carbs-notes rows untouched — none of them had an explicit `MaterialTheme.colorScheme.*` color to
begin with.

- [ ] **Step 4: Compile**

Run: `./gradlew :ui:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Self-Review Notes

- **Spec coverage**: this plan completes the spec's phase 3 ("remaining Treatments children") — all 8
  Treatments tabs are now restyled once this plan ships, alongside the already-restyled shell. The 5 screens
  chosen match exactly what remained after phase 2's actual choice of `ExtendedBolusScreen` (not the spec's
  original placeholder guess) — corrected in this plan's own header rather than silently inherited wrong.
- **No placeholders**: `UserEntryScreen`'s deviation (no shared `ContentContainer`/`TreatmentLazyColumn` usage)
  was found by direct inspection before writing this plan, not assumed — Task 2 gives it fully custom,
  concrete instructions rather than the "same pattern as X" shorthand used for the 3 conforming screens (Tasks
  3-4 use the shorthand only where the file truly matches an already-proven pattern with named, exact
  differences called out).
- **Type consistency**: every `GlassCard`/`ContentContainer`/`TreatmentLazyColumn` call matches the exact
  parameter names and positions already established and proven across the prior 2 restyle plans.
- **Semantic-color audit**: every `AapsTheme.generalColors.*`/`ElementType.*`/`Action.ColorGroup.*` reference
  found during this plan's research across all 5 files is explicitly named in its task as "leave unchanged" —
  none were silently assumed safe without being read first.
- **Manual verification note for the user** (this plan cannot be meaningfully unit-tested — pure visual
  restyle of already-tested screens): after this plan's tasks are done, open Treatments in both light and dark
  mode and confirm ALL 8 tabs now show the Glass-styled list cards and empty/loading states consistently (no
  more visual split between restyled and unrestyled tabs), confirm `UserEntryScreen`'s sticky date header and
  empty state look consistent with the other 7 tabs despite its different code path, confirm the "Clone" button
  on Profile Switch and the calculator-result info dialog on Bolus/Carbs still work, and confirm delete/
  selection-mode interactions still work on all tabs that support them.
