# Glass Tools Grid Personalization — Design Spec

**Status:** Ready for self-review then user review.

## Goal

Let the user pick which plugin tiles appear in the Glass dashboard's Tools screen (currently: Actions, Rapid
Acting insulin, Profile, Automation, NSClient, Tidepool, xDrip+, Maintenance, xDrip+ BG source, Advisor, Meal
Advisor, AIMI Context, Auditor Report — 13 fixed tiles, all always rendered).

## Background

On-device feedback (2026-09-11): the Tools grid should be personalizable the same way the main-screen pills
are — "je devrais pouvoir choisir les plugins que je veux afficher à cet endroit" (I should be able to choose
which plugins I want to show there). Confirmed via `AskUserQuestion`: user approved adding Tools-tile
selection as its own chantier.

## Current state (researched, not guessed)

- `plugins/main/.../dashboard/DashboardV2ToolsScreen.kt` defines `DashboardV2ToolAction`, a 13-entry enum
  (lines 79-138), each carrying a `section: DashboardV2ToolSection` (`GENERAL`/`AIMI`) and a `destination:
  DashboardV2ToolDestination` (sealed: `Actions`, `Element(ElementType)`, `Plugin(className)`,
  `AimiActivity(protection)`). Icon and label are NOT enum properties — they're computed by 2 **private**
  `when`-based extension functions at the bottom of the same file: `DashboardV2ToolAction.icon(): ImageVector`
  (lines 261-276) and `@StringRes DashboardV2ToolAction.labelRes(): Int` (lines 278-294).
- The grid (`DashboardV2ToolsScreen` composable, lines 144-205) is a `LazyVerticalGrid` that iterates 2
  **fixed, complete** companion lists: `DashboardV2ToolAction.general`/`.aimi` (lines 135-136), each just
  `entries.filter { it.section == ... }`. There is no filtering parameter today.
- `availablePluginClassNames: Set<String>` (already a parameter) confirmed to control **only** the
  `enabled`/greyed visual state via `isAvailable()` (lines 140-141) — every tile in `general`/`aimi` is always
  composed; `isAvailable()` never removes a tile from the grid, only dims it. This is the exact gap the user
  is asking to close (dim vs. hidden are different things; the user wants "hidden," not "dimmed").
- Glass wraps this shared screen directly, with no filtering layer of its own today:
  `GlassOverviewComposeEmbedded.kt:253-262` calls `DashboardV2ToolsScreen(paddingValues = ..., availablePluginClassNames
  = availablePluginClassNames, onAction = { action -> showTools = false; onToolAction(action) }, ...)`. A
  header row above it already has a "Personalize" text button (lines 233-252) that flips a local
  `showPersonalize` boolean, swapping in `GlassPersonalizeScreen` (pill personalization) INSTEAD of
  `DashboardV2ToolsScreen` — i.e. the existing Personalize entry point already lives inside the Tools overlay,
  it just doesn't cover tool tiles yet.
- `plugins/main/src/test/kotlin/.../DashboardV2ToolActionTest.kt` (untracked, pre-existing in this branch)
  regression-locks `entries.size == 13`, the `general`/`aimi` split and order, each entry's `destination`, and
  `isAvailable()` gating. None of it touches selection/visibility — safe to build on top of as long as this
  chantier does not reorder, rename, or remove any `DashboardV2ToolAction` entry, and does not change
  `isAvailable()`'s existing semantics.
- The existing pill-personalization pattern (`GlassPillId`/`GLASS_PILL_CATALOG`/`GlassPersonalizeScreen`/
  `StringNonKey.GlassSelectedPills`, all in `plugins/main/.../dashboard/glass/`) is the template to follow —
  see Architecture below for exactly how it's reused vs. where a new, parallel piece is needed.

## Decisions

- **No new ID enum for tool tiles.** `DashboardV2ToolAction` already exists as a stable, tested 13-entry
  enum — reuse its `.name` directly for persistence (`GlassSelectedTools` stores a comma-joined list of
  `DashboardV2ToolAction` names), exactly like `GlassSelectedPills` stores `GlassPillId` names. No parallel
  catalog data class is needed either: `DashboardV2ToolAction.entries` already carries everything needed
  (`section`, `destination`) except a public label/icon, addressed below.
- **Widen `icon()`/`labelRes()` from `private` to `internal`** in `DashboardV2ToolsScreen.kt` — a
  same-module, same-file visibility change (`plugins:main`), so the new Tools-selection checklist can render
  each tile with its real icon and label instead of duplicating a second 13-entry `when` block (this
  codebase's own convention is to avoid duplicating logic across files — see `CLAUDE.md`). No behavior
  change, no new dependency.
- **Default-visible, not default-hidden, for all 13 tiles** — they are all always shown today; making any
  default-hidden would silently regress an existing user. Uses the exact same fix Chantier B needed:
  `StringNonKey`'s `GlassSelectedTools` class default is a literal, comma-joined list of all 13
  `DashboardV2ToolAction` names (not `""`), so "never touched Personalize" (falls back to the class default)
  is distinguishable from "explicitly cleared every tile" (persisted value is the literal empty string).
- **Extend `GlassPersonalizeScreen.kt` with a second, labeled section** ("Tools" below "Main screen," or
  similar — exact copy at plan-writing time) rather than adding a whole separate screen. Rationale: the
  existing "Personalize" button that reaches this screen already lives INSIDE the Tools overlay (not on the
  main dashboard), so a user who taps it is already primed to expect "what's on this Tools screen" as much as
  "what's on my main screen" — one screen with 2 clearly labeled sections is simpler than 2 separate screens
  behind the same button, and reuses 100% of the existing screen's chrome/theming.
- **`DashboardV2ToolsScreen.kt` needs one small, additive, backward-compatible change**: add an optional
  parameter `visibleActions: Set<DashboardV2ToolAction>? = null` (default `null` = show everything, i.e.
  today's exact behavior, so **no DASHBOARD_V2 call site needs to change**). Internally, change
  `items(DashboardV2ToolAction.general, ...)`/`.aimi` to filter through
  `.filter { visibleActions == null || it in visibleActions }` first. This was weighed against building a
  Glass-only parallel grid to avoid touching a shared file at all — rejected because it would duplicate the
  `LazyVerticalGrid`/tile-rendering logic (a real maintenance cost and a source of visual drift from
  DASHBOARD_V2's Tools screen, which Round 1 of this project specifically avoided by reusing this exact
  screen) for a change that, as designed, cannot affect any existing caller.
- **Separate preference key, `StringNonKey.GlassSelectedTools`**, not merged into `GlassSelectedPills` — the
  user asked for these as 2 distinct catalogs (pills vs. tool tiles), and they already are architecturally
  distinct (different enum, different screen, different data source).

## Architecture

### `DashboardV2ToolsScreen.kt` (shared file, minimal additive change)

```kotlin
internal fun DashboardV2ToolAction.icon(): ImageVector = ...       // was `private fun`
@StringRes internal fun DashboardV2ToolAction.labelRes(): Int = ... // was `private fun`
```

Composable signature gains one optional parameter (default preserves current behavior for every existing
caller):
```kotlin
fun DashboardV2ToolsScreen(
    paddingValues: PaddingValues,
    fabBottomOffset: Dp,
    availablePluginClassNames: Set<String>,
    onAction: (DashboardV2ToolAction) -> Unit,
    modifier: Modifier = Modifier,
    visibleActions: Set<DashboardV2ToolAction>? = null,
)
```
The 2 `items(...)` calls filter their source list through `visibleActions` before rendering (exact code left
to plan-writing, since the precise `items(...)` call shape needs to be read fresh at that time).

### `core/keys/src/main/kotlin/app/aaps/core/keys/StringNonKey.kt`

New entry, same style as `GlassSelectedPills`:
```kotlin
// Default lists all 13 DashboardV2ToolAction entries (all tiles are visible today). plugins:main's
// DashboardV2ToolAction is the source of truth; keep this literal list in sync with it by hand (core:keys
// cannot depend on plugins:main).
GlassSelectedTools(
    key = "glass_selected_tools",
    defaultValue = "ACTIONS,RAPID_ACTING,PROFILE,AUTOMATION,NSCLIENT,TIDEPOOL,XDRIP,MAINTENANCE,XDRIP_BG,ADVISOR,MEAL_ADVISOR,AIMI_CONTEXT,AUDITOR_REPORT",
    exportable = false
),
```

### `GlassPersonalization.kt` (parse/serialize reused generically, or duplicated narrowly)

`parseSelectedGlassPills`/`serializeSelectedGlassPills` are specific to `GlassPillId`. Add 2 small mirror
functions for tools (kept separate per the "no merged catalog" decision above, and because
`DashboardV2ToolAction.valueOf`/`.name` are a different enum, not because the logic actually differs):
```kotlin
fun parseSelectedGlassTools(rawValue: String): List<DashboardV2ToolAction> =
    rawValue.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .mapNotNull { name -> runCatching { DashboardV2ToolAction.valueOf(name) }.getOrNull() }

fun serializeSelectedGlassTools(actions: Set<DashboardV2ToolAction>): String =
    actions.joinToString(",") { it.name }
```
(Needs an import of `DashboardV2ToolAction` from `app.aaps.plugins.main.general.dashboard`.)

### `GlassPersonalizeScreen.kt`

Add a second section below the existing pill checklist, iterating `DashboardV2ToolAction.entries` (13 items)
the same way the pill list iterates `GLASS_PILL_CATALOG` — a `Row` per entry with `Text(stringResource(it.labelRes()))`
+ `Checkbox(checked = it in selectedTools, onCheckedChange = { onToolToggle(it, it_checked) })`. Signature
gains `selectedTools: Set<DashboardV2ToolAction>` and `onToolToggle: (DashboardV2ToolAction, Boolean) -> Unit`.
A small section-header `Text` ("Main screen" / "Tools") separates the 2 groups — the first concrete use of
the "light visual grouping" idea Chantier B's spec deferred as cosmetic.

### `GlassOverviewComposeEmbedded.kt`

- Read: `val selectedToolsRaw by preferences.observe(StringNonKey.GlassSelectedTools).collectAsState()`, then
  `parseSelectedGlassTools(selectedToolsRaw).toSet()` — same pattern as `selectedPills`.
- Write: extend the existing `GlassPersonalizeScreen(...)` call with `selectedTools = selectedTools,
  onToolToggle = { action, checked -> val next = selectedTools.toMutableSet(); if (checked) next.add(action)
  else next.remove(action); preferences.put(StringNonKey.GlassSelectedTools,
  serializeSelectedGlassTools(next)) }` — mirrors the existing `onToggle` for pills exactly.
- Extend the existing `DashboardV2ToolsScreen(...)` call (line ~253) with `visibleActions = selectedTools`.

## Non-goals / deferred

- No change to `isAvailable()`'s existing enabled/disabled (greyed) behavior — a tile can still be selected
  (visible) but disabled if its backing plugin isn't active; selection and availability are orthogonal, as
  they already are for the DASHBOARD_V2 Tools screen today.
- No change to `DashboardV2ToolActionTest.kt`'s existing assertions — this chantier adds a filter parameter
  with a backward-compatible default, it does not change the enum's entries, order, or `isAvailable` logic.
- No visual redesign of the Tools grid tiles themselves.

## Open items for plan-writing

- Exact current code of the 2 `items(...)` calls in `DashboardV2ToolsScreen.kt` (lines ~179, ~197) — needs a
  fresh read at plan-writing time to write the precise filter-insertion diff (this spec describes the
  approach, not the byte-for-byte edit).
- Exact section-header string resources and their wording for the 2 new `GlassPersonalizeScreen.kt` groups.
- Whether `GlassPersonalizeScreen`'s existing `if (selectedPills.isEmpty())` empty-hint block needs an
  equivalent for an all-tools-deselected state, or one shared hint covering both sections — a small UX
  decision, not a functional blocker, to settle at plan-writing time.
