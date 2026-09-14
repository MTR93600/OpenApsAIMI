# Glass visual restyle: Stats + Treatments screens

Status: draft, approved-in-conversation scope (full restyle, including internal charts), pending self-review only.

## Context

Phase 1 and Phase 2 of the Glass dashboard port (both shipped, committed at `5032cb329d`) delivered a new,
additive "Glass" home-screen skin with its own visual language: gradient glassmorphism cards
(`GlassContainer`/`GlassCardInner`), pill-shaped metric badges, dark/light color pairs sourced from the app's
own `StringKey.GeneralDarkMode` preference, and specific accent colors (sky blue `#38BDF8`, emerald `#10B981`,
amber `#F59E0B`, red `#EF4444`).

The original Glass spec's Goal 2 said: "Do this without duplicating screens we already have in native Compose
with the same underlying data (`TreatmentsScreen`, `StatsScreen`) — restyle those instead of porting
`GlassTreatments`/`GlycoStats` wholesale." This spec covers that restyle.

**Scope discovery, corrected before this spec was written:** `StatsScreen.kt` (403 lines) is a thin shell that
delegates to 6 sibling composables for its actual content — `TddStatsCompose.kt` (251),
`TddCyclePatternCompose.kt` (254, Canvas-drawn chart), `TirStatsCompose.kt` (273),
`DexcomTirStatsCompose.kt` (309), `ActivityStatsCompose.kt` (120), `GlucosePentagonCompose.kt` (411,
Canvas-drawn chart), plus `LoadingSection.kt` (47) — 2098 lines of UI code total (excluding
`StatsViewModel.kt`, which is out of scope). `TreatmentsScreen.kt` (296 lines) is a similar thin tabbed shell
delegating to 8 child screens — `BolusCarbsScreen.kt` (491), `CareportalScreen.kt` (303),
`ExtendedBolusScreen.kt` (275), `ProfileSwitchScreen.kt` (327), `RunningModeScreen.kt` (253),
`TempBasalScreen.kt` (323), `TempTargetScreen.kt` (255), `UserEntryScreen.kt` (315), plus small shared pieces
(`TreatmentLazyColumn.kt` 77, `WizardInfoDialog.kt` 209) — roughly 2830 lines of UI code total (excluding
`TreatmentsViewModel.kt` and each child's own ViewModel, all out of scope). The user was told the smaller,
wrong estimate once and explicitly re-confirmed "full restyle including the charts" after the correction.

**Module boundary constraint, discovered while scoping this spec:** `StatsScreen`/`TreatmentsScreen` and all
their children live in the `:ui` Gradle module. `GlassContainer`/`GlassCardInner`/`GlassPill` (the existing
Glass component kit) live in `:plugins:main`. Dependency direction is `:plugins:main` → `:ui` (confirmed,
`plugins/main/build.gradle.kts:32`), never the reverse — `:ui` cannot import from `:plugins:main`. Both
modules already depend on `:core:ui` (confirmed in both modules' `build.gradle.kts`), which is also where the
existing `AapsCard`/`AapsTheme`/`AapsTopAppBar` that these screens already use live
(`core/ui/src/main/kotlin/app/aaps/core/ui/compose/`). **A shared Glass component kit must live in
`:core:ui`**, not be duplicated a third time or reached via a new inter-module dependency (which the project's
own rules discourage adding without discussion — this spec resolves that discussion by choosing the module
both consumers already share).

## Goals

1. Build ONE shared, reusable Glass-styled component kit in `:core:ui` — a themed screen background, a
   section-card replacement for `AapsCard`'s collapsible-section pattern (already used identically 6 times in
   `StatsScreen.kt` and similar patterns in several Treatments children), and shared color tokens — so each
   screen-restyle plan consumes it instead of re-deriving Glass's look.
2. Apply that kit to `StatsScreen.kt` and all 6 of its content composables, including the two Canvas-drawn
   charts (`TddCyclePatternCompose`, `GlucosePentagonCompose`) — re-theming their color palette to Glass's
   accent colors, not rewriting their drawing/layout logic.
3. Apply the same kit to `TreatmentsScreen.kt`'s tab shell and its 8 child screens.
4. Zero behavior change: no ViewModel, data-fetching, or business-logic file is touched in this work. Every
   existing test for these screens' ViewModels keeps passing unmodified. This is a visual-layer-only change,
   verified by (a) the existing test suites still passing and (b) manual visual verification flagged to the
   user (per this project's own rule: build success is not feature-complete until the user confirms runtime
   behavior).
5. `AapsTheme`'s existing dark/light resolution (`StringKey.GeneralDarkMode`) is the single source of dark
   mode for every restyled screen — never `isSystemInDarkTheme()` directly. This is the same rule already
   enforced twice in the dashboard work (a Critical finding once, re-verified at three later review gates).

## Non-goals

- No change to any ViewModel, use case, calculator, or data class in `:ui`'s `stats`/`treatments` packages.
- No new screens, no new navigation routes — `StatsScreen`/`TreatmentsScreen` keep their existing
  `AppRoute.Stats`/`AppRoute.Treatments` entry points and `onNavigateBack` contracts unchanged.
- No new inter-module Gradle dependency — the shared kit's placement in `:core:ui` is exactly what avoids
  needing one.
- No change to what data is shown or how it's calculated — only how it's drawn (colors, card shapes,
  backgrounds, typography weight/spacing already established by Glass).
- `GlassSensorInsertScreen` (the original spec's Phase 3) is explicitly OUT of scope for this whole
  restyle project — cancelled in conversation: the Glass dashboard's Sensor pill already routes to the
  existing `CareportalScreen` (`DashboardShellController.openSensorInsert()` →
  `"care_dialog/${CareportalEventType.SENSOR_INSERT.ordinal}"`), so building a dedicated duplicate form would
  violate this same spec's Goal 1 (don't duplicate a screen with the same underlying data). `CareportalScreen`
  IS one of the 8 Treatments children this restyle already covers (Goal 3), so the sensor-insert flow gets the
  Glass look "for free" as part of that work — no separate phase needed.

## Shared component kit design (`:core:ui`, package `app.aaps.core.ui.compose.glass`)

Ported/generalized from the existing `plugins:main` Glass kit, kept visually identical (same color hexes,
same shape radii) but decoupled from any `plugins:main`-specific type:

- **`GlassScreenBackground`** — the vertical gradient background Box (`#070E1B`→`#0B1424`→`#070E1B` dark,
  `#F1F5F9`→`#E8EEF8`→`#F1F5F9` light) every Glass screen already uses
  (`GlassLoopDashboardScreen.kt`/`GlassSensorInsertScreen` reference both use this exact gradient). Wraps
  screen content, replaces a plain `Scaffold` background.
- **`GlassSectionCard`** — replaces the `AapsCard { Column { Row(header + expand icon) ... AnimatedVisibility
  { content } } }` pattern already repeated 6 times in `StatsScreen.kt` (and, with minor variations, in several
  Treatments children) with one parameterized composable: `title: String`, `expanded: Boolean`,
  `onToggleExpanded: () -> Unit`, `trailingAction: (@Composable () -> Unit)? = null` (for the "Recalculate"/
  "Reset" buttons), `content: @Composable ColumnScope.() -> Unit`. Internally: the same gradient-bordered card
  shape already established by `GlassCardInner` (`RoundedCornerShape(16.dp)`, vertical gradient fill, 1dp
  border), a header `Row` with the title + optional trailing action + expand/collapse chevron, and the content
  wrapped in the same `AnimatedVisibility` the screens already use (behavior unchanged, only the chrome around
  it changes).
- **`GlassColors`** — a small object exposing the established accent palette as named `Color` vals (skyBlue,
  emerald, amber, red, indigo, plus the dark/light text-bright/text-muted/card-bg/border tokens already
  duplicated ad hoc across every existing Glass file) so future files reference `GlassColors.skyBlue` instead
  of repeating the literal hex — this is new (the existing dashboard Glass files each redeclare these locally;
  not touching those existing files in this plan, only using the shared object in NEW/restyled code from here
  on, per the project's "avoid duplication" rule applied going forward without an unrelated refactor of
  already-shipped, already-reviewed files).
- **`isGlassDarkMode(): Boolean`** (a small `@Composable` helper) — wraps the exact
  `LocalPreferences.current.observe(StringKey.GeneralDarkMode)` → `UiMode.fromString(...)` →
  `LIGHT`/`DARK`/`SYSTEM` chain already used identically in `GlassOverviewComposeEmbedded.kt` and
  `AppNavGraph.kt`'s Loop Dashboard destination, so every restyled screen calls this one function instead of
  re-deriving the chain a fourth time.

None of these four pieces read from or write to any ViewModel — they are pure, stateless (besides the
`expanded`/`onToggleExpanded` callback pair, which the caller's existing ViewModel state already drives) visual
components.

## Phased plan sequence

Each phase is its own implementation plan (`docs/superpowers/plans/...`), executed via
`superpowers:subagent-driven-development` like every prior phase of this project, compiled and (existing
tests) verified before the next starts. No `git commit` until the user explicitly asks, per this session's
standing rule.

1. **Shared kit + StatsScreen full restyle** (`GlassScreenBackground`/`GlassSectionCard`/`GlassColors`/
   `isGlassDarkMode` in `:core:ui`, then `StatsScreen.kt` + its 6 content composables + `LoadingSection.kt`
   re-themed to use them). This phase proves the kit works end-to-end on real data before it's reused 8 more
   times.
2. **TreatmentsScreen tab shell + its 3 smallest/lowest-risk children** (`TempTargetScreen`, `RunningModeScreen`,
   `ActivityStatsCompose`-equivalent-simplicity screens) — establishes the pattern for form-heavy screens
   (distinct from Stats' read-only display cards).
3. **Remaining 5 Treatments children** (`BolusCarbsScreen`, `CareportalScreen`, `ExtendedBolusScreen`,
   `ProfileSwitchScreen`, `TempBasalScreen`, `UserEntryScreen` — the largest and most form/dialog-heavy
   screens, done last once the pattern is proven on 4 already-restyled screens).

Phase 1 (this session) covers only item 1 above. Items 2-3 get their own plans in later continuations,
following this same spec — no need to re-derive the component kit design each time.

## Self-Review Notes

- **Spec coverage**: every goal has a corresponding phase; the shared-kit design directly answers the module-
  boundary constraint discovered while scoping. `GlassSensorInsertScreen`'s cancellation is recorded here so a
  future reader doesn't wonder why the original spec's Phase 3 never got a restyle plan of its own.
- **No placeholders**: the component kit's four pieces are each described down to their exact prop shape, not
  "similar to X" — task-level exact code is deferred to Phase 1's own implementation plan (matching this
  project's usual spec/plan split), not to a lower-effort description here.
- **Ambiguity check**: "full restyle including charts" was explicit user confirmation after a corrected size
  estimate — recorded verbatim in Context so a future reader trusts the scope wasn't silently reduced again.
