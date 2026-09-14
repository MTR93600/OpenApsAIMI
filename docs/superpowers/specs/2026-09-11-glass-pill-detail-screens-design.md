# Glass Pill Detail Screens — Design Spec

**Status:** Approved by user, ready for implementation planning.

## Goal

Give all 8 tappable pills on the Glass main dashboard their own Glass-branded, GlycoCalm-styled detail
screen, replacing today's mix of shared (DASHBOARD_V2-also-used) Compose screens and, for 2 pills, a plain
native dialog with no dedicated UI at all.

## Background

On-device feedback (2026-09-11) asked to "update the cards behind the taps" too, alongside the Personalization
and Loop Dashboard expansion work already specified. A research pass across our own repo's
`DashboardShellController.kt` (`createGlassHeroCommands()`) found today's actual targets:

| Pill / command | Today's target | Glass-branded? |
|---|---|---|
| Insulin (`openInsulin`) | `InsulinDialogScreen` (`ui/.../insulinDialog`), route `insulin_dialog` | no — shared with DASHBOARD_V2 |
| Pump (`openPump`) | plain `OKDialog.show(...)`, text from `StatusCardState.reservoirText` | no — not Compose at all |
| Cannula (`openCannula`) | `FillDialogScreen` (`ui/.../fillDialog`), route `fill_dialog/0` | no — shared |
| Battery (`openBattery`) | plain `OKDialog.show(...)`, text from `StatusCardState.pumpBatteryText` | no — not Compose at all |
| Basal (`openBasal`) | `TempBasalDialogScreen` (`ui/.../tempBasalDialog`), route `temp_basal_dialog` | no — shared |
| Target (`openTarget`) | `TempTargetManagementScreen` (`ui/.../tempTarget`), route `temp_target_management?mode=EDIT` | no — shared |
| Loop, short-press (`openLoop`) | `RunningModeScreen` (`ui/.../runningMode`), route `running_mode` | no — shared |
| Sensor, long-press (`openSensorInsert`) | `CareDialogScreen` (`ui/.../careDialog`), route `care_dialog/{ordinal}` | no — shared |

All 6 "shared" screens are reasonably modern (Material3 `Scaffold`/`Card`), just not Glass-branded and also
used by other dashboard skins — nothing is broken today, this is a branding/consistency gap, not a
functionality gap, for those 6. Pump and Battery are the real functional gap: no dedicated screen exists at
all, just a plain OS-style `OKDialog` — though the DATA they'd need is already computed and available
(`StatusCardState.reservoirText`/`.pumpBatteryText`, the exact same fields the current `OKDialog` calls
already read).

A collaborator (Tarciso)'s reference fork (`https://github.com/TarcisoSilva/OpenApsAIMISMBonly`, branch
`test/v242`, commit `dee8a05`) independently built Glass-branded dialogs for 6 of these 8 pills — Insulin,
Cannula (his "Prime Fill"), Battery, Sensor Insert, Loop control, Target — reference copies saved at
`docs/superpowers/specs/2026-09-11-glycocalm-reference/tarciso-v242-detail-dialogs/` (see that folder's
`README.md` for exact provenance and how to read these files: structural/functional reference, NOT a visual
source of truth — his screens do not implement a consistent GlycoCalm-like system, see below). He did NOT
build anything for Pump or Basal — those 2 have no reference to port from in either fork.

**Visual style finding (from the same research pass):** Tarciso's dialogs are a partial, inconsistent step
toward a lighter look — white/soft-slate cards, generous rounding, diffused shadows — but only 2 accidental
hex-token overlaps with our own GlycoCalm reference, no Plus Jakarta Sans font anywhere, inconsistent corner
radii (12dp/16dp/32dp mixed), and every screen still branches on `isDark` to a fully separate darker Material
scheme rather than a token-driven light/dark pair. Treat his files as reference for dialog STRUCTURE and
FUNCTIONAL LOGIC only.

## Decisions already made with the user

- **Scope:** all 8 pills get a new Glass-branded detail screen in this plan (not just the 2 with a true
  functional gap) — user's explicit choice over a smaller "Pump + Battery only" first cut.
- **Approach for the 6 with a Tarciso reference:** port structure/functional logic from the saved reference
  files, adapted to our own ViewModels/`PersistenceLayer`/data sources (same translation pattern used for the
  original Glass main-screen port from an earlier reference fork) — NOT a verbatim copy, and NOT reusing his
  visual styling (ours follows our own GlycoCalm token set instead, see below).
- **Approach for Pump and Basal (no reference):** designed fresh, matching the same structural/visual pattern
  as the other 6 once ported, sourcing data from what's already available
  (`StatusCardState.reservoirText`/`.pumpBatteryText` for Pump; `TempBasalDialogScreen`'s existing underlying
  data source for Basal, presented in Glass's own shell instead of the shared screen's).
- **The 6 underlying SHARED screens stay untouched** — `InsulinDialogScreen`, `FillDialogScreen`,
  `TempBasalDialogScreen`, `TempTargetManagementScreen`, `RunningModeScreen`, `CareDialogScreen` keep serving
  DASHBOARD_V2/other skins exactly as today. Glass gets its OWN new screens; this plan does not modify any
  shared screen (same "don't touch shared components" precedent as `DashboardV2ToolsScreen` in the
  Personalization plan).
- **Visual style:** GlycoCalm, same reference as the other 2 plans
  (`docs/superpowers/specs/2026-09-11-glycocalm-reference/DESIGN.md` + `code.html`), WITH a proper dark-mode
  variant (see "Dark mode" below) — unlike Tarciso's inconsistent `isDark`-branch-to-different-scheme approach.

## Scope note: Glass's visual language is now split

After this plan (plus the already-specified Personalization and Loop Dashboard Expansion plans), Glass's
visual language will be: dark-gradient main dashboard + BG/IOB charts (unchanged, existing), GlycoCalm-styled
everywhere else (Personalize screen, Loop Dashboard, and now all 8 detail screens). This is a natural
consequence of "new screens get GlycoCalm" being chosen consistently across all 3 rounds of this brainstorm —
flagged here so it's a visible, intentional state rather than a surprise, not because it needs a decision now.
A future full-skin restyle (making the main dashboard/charts GlycoCalm too) is a plausible next question, not
part of this plan.

## Architecture

### New screens (Glass-only, one file each, mirroring this codebase's existing `Glass*Screen.kt` naming)

| New file | Ports from (Tarciso reference) | Pill |
|---|---|---|
| `GlassInsulinDetailScreen.kt` | `GlassInsulinDialogScreen.kt` | Insulin |
| `GlassPumpDetailScreen.kt` | — (no reference; new) | Pump |
| `GlassCannulaDetailScreen.kt` | `GlassPrimeFillDialogScreen.kt` | Cannula |
| `GlassBatteryDetailScreen.kt` | `GlassBatteryChangeDialogScreen.kt` | Battery |
| `GlassBasalDetailScreen.kt` | — (no reference; new) | Basal |
| `GlassTargetDetailScreen.kt` | `GlassTempTargetDialogScreen.kt` | Target |
| `GlassLoopDetailScreen.kt` | `GlassLoopControlDialogScreen.kt` | Loop (short-press) |
| `GlassSensorInsertDetailScreen.kt` | `GlassSensorInsertDialogScreen.kt` | Sensor (long-press) |

All 8 live in `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/` (our repo's
existing Glass package — NOT Tarciso's `general/overview/glass/` layout, which is specific to his fork).

Each screen: reads its data from whatever source the CURRENT shared screen/dialog already reads from (the 6
ported ones: read the shared screen's own ViewModel/ data source, same underlying data, new Glass-styled
presentation; Pump/Battery: `StatusCardState.reservoirText`/`.pumpBatteryText`, already computed, already
consumed by Glass's own `buildGlassUiState`/`GlassOverviewComposeEmbedded.kt` chain elsewhere — Basal: the
same data source `TempBasalDialogScreen` already uses). Exact per-screen field mapping is deferred to
plan-writing (one task per screen, following this project's established "task brief specifies exact fields
after reading the actual current shared screen/ViewModel" pattern — same as how the original Glass port's
field-mapping table was built).

Each screen keeps whatever ACTIONS its shared counterpart exposes (e.g. `TempTargetManagementScreen` lets the
user create/edit a temp target — the Glass version must too, not become read-only) — this is a re-skin +
re-brand, not a scope reduction of what the user can already do from these pills.

### Navigation wiring

New `AppRoute` entries (e.g. `glass_insulin_detail`, `glass_pump_detail`, etc. — one per screen, following the
exact naming/wiring pattern already used for `glass_sensor_quality`/`glass_loop_dashboard`) + matching
`AppNavGraph.kt` destinations. `DashboardShellController.kt`'s `createGlassHeroCommands()` updates each of the
8 commands to route to its new Glass screen instead of today's target — `openPump()`/`openBattery()` change
from `OKDialog.show(...)` to `uiInteraction.openComposeMainAtRoute(...)`, matching how every other Glass
command already works; the other 6 commands just change their route string.

## Dark mode

Same standing constraint as the other 2 plans in this round: every screen sources `isDark` via
`LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode`, never `isSystemInDarkTheme()` directly. Use
`DESIGN.md`'s `inverse-*`/`*-container` tokens as the dark-variant starting point for all 8 screens — do NOT
replicate Tarciso's approach of branching to an unrelated, differently-designed dark Material scheme.

## Non-goals / deferred

- Modifying any of the 6 shared underlying screens (`InsulinDialogScreen`, `FillDialogScreen`,
  `TempBasalDialogScreen`, `TempTargetManagementScreen`, `RunningModeScreen`, `CareDialogScreen`) — they stay
  exactly as they are, serving other skins.
- A full-skin restyle of Glass's main dashboard/charts to GlycoCalm — noted as a natural future question (see
  "Scope note" above), not part of this plan.
- Tarciso's `PumpStatusNotification`/dismissible-notification overlay system on his main screen — flagged by
  research as mildly interesting but unrelated to this plan's "detail screens" scope; not pursued here.

## Open items for plan-writing

- Per-screen exact data source/field mapping (8 separate small investigations, one per screen, reading either
  the relevant shared screen's ViewModel or, for Pump/Battery, confirming `StatusCardState`'s existing fields
  are sufficient or need extending).
- Exact new `AppRoute` string values (8 of them) and confirmation none collide with existing routes.
- Exact hex `Color(...)` values (light + dark) for the 8 new screens' GlycoCalm styling, translated from
  `DESIGN.md`/`code.html`, with the dark variant drawn from `DESIGN.md`'s `inverse-*` tokens.
- Whether any of the 8 screens' underlying actions require the same `withBolusProtection`/protection-check
  wrapping their current command already has (`openTarget`/`openSensorInsert` currently use
  `withBolusProtection` in `DashboardShellController.kt` — carry this over unchanged for those, don't add or
  remove protection from any command as part of this re-skin).
