# DASHBOARD_V2 visual + functional parity

Status: draft, approved by user in conversation (2026-09-10), pending self-review only.

## Context

DASHBOARD_V2 is the new Compose home screen (`plugins/main/.../dashboard/DashboardV2ComposeEmbedded.kt`,
`DashboardV2ToolsScreen.kt`, `ui/.../main/DashboardV2NavigationBar.kt`). The user wants it to visually match
a reference screenshot ("STATUS AGORA" card) and to regain functional richness that the older AIMI dashboard
(`AimiDashboardComposeEmbedded.kt` / `DashboardCircleTopCompose.kt`) and the classic pre-Compose `OverviewFragment`
already have.

Two false leads were ruled out during investigation, worth recording so nobody re-treads them:

- The reference project the user pointed at (`~/Downloads/OpenApsAIMI-Tarciso-test-v235`) contains **no Compose
  UI at all** (verified independently three ways: agent search, direct `grep`, zip-listing). Its home screen is
  the classic View/XML `OverviewFragment`. It cannot be the visual source of the screenshot, but its interaction
  patterns (tap-to-detail dialogs, quick-action buttons) are a legitimate functional reference.
- The "CGM button with adaptive smoothing doesn't return data" report is **not a data bug**. The badge
  (`HeroCgmCompactBadge`) and its whole data path work correctly in the OLD dashboard
  (`AimiDashboardComposeEmbedded` → `DashboardCircleTopCompose`). DASHBOARD_V2's own status card
  (`DashboardV2StatusCard` in `DashboardV2ComposeEmbedded.kt`) simply never received this badge when it was
  built — confirmed by `grep -i "cgm\|smoothing"` returning zero hits in that file. It is a migration gap, not
  a broken data pipeline.

## Goals

1. Match the reference screenshot's layout: pill-style time-range selector, Stats/Treatment quick actions.
2. Port the working CGM/adaptive-smoothing badge into DASHBOARD_V2.
3. Port tap-to-detail status dialogs (pump, sensor, reservoir, battery, openaps, uploader, BG quality) into
   DASHBOARD_V2 — currently these values are read-only text with nothing behind the tap.
4. Add the missing quick-action shortcuts (Carbs, Wizard, Quick Wizard, Temp Target) directly on the dashboard.
5. Fix two confirmed mis-wirings while touching this code: `onStatsClicked` opens the wrong screen, and
   `onAdjustClicked`/`onAimiPulseClicked` both route to the same handler.

## Non-goals

- No changes to the reference project.
- No changes to the smoothing algorithms themselves (Phase "CGM badge" is a UI port, not new scoring logic).
- No changes to the classic (non-DASHBOARD_V2) `OverviewFragment` or `AimiDashboardComposeEmbedded` — they stay
  as the working source we port FROM, untouched.
- No new inter-module Gradle dependencies — all touched files already live in `plugins:main` and `:ui`, which
  already depend on each other appropriately.

## Phase 1 — Time-range pills + Stats/Treatment buttons

**Files:** `plugins/main/.../dashboard/compose/DashboardGraphComposeControls.kt`,
`plugins/main/.../dashboard/DashboardV2ComposeEmbedded.kt`,
`plugins/main/.../dashboard/compose/DashboardHeroCommands.kt`,
`plugins/main/.../dashboard/DashboardShellController.kt`.

- Replace the `OutlinedButton` + `DropdownMenu` range picker with a row of 4 toggle pills (6h/12h/18h/24h,
  dropping the current 9h option to match the reference). Same state/callback
  (`composeState.graphUiState.rangeHours`, `onSelectRange`) — pure UI swap.
- Add `openStatsScreen()` and `openTreatmentDialog()` to `DashboardHeroCommands` (+ no-op defaults on
  `NoopDashboardHeroCommands`). Implement in `DashboardShellController`:
  - `openStatsScreen()` → `uiInteraction.openComposeMainAtRoute(host.context, AppRoute.Stats.route)`
  - `openTreatmentDialog()` → `uiInteraction.openComposeMainAtRoute(host.context, AppRoute.TreatmentDialog.route)`
  - Fix `onStatsClicked()` (currently `launchContextActivity()` — wrong target) to call the new
    `openStatsScreen()` instead, OR leave `onStatsClicked` as-is (whatever the old dashboard's Stats concept was
    for) and only wire the NEW Main-tab "Stats" button to `openStatsScreen()`. Decide during implementation by
    checking what UI currently calls `onStatsClicked` — do not silently repurpose a command another caller
    depends on.
- Add the "Stats"/"Treatment" button row to `DashboardV2ComposeEmbedded.kt` in the position shown in the
  screenshot, reusing existing translated strings (find the existing "statistics"/"treatments" resource keys
  before adding new ones).

**Testing:** compile; manual check that the 4 pills change the graph range, Stats opens the real Statistics
screen, Treatment opens the treatment dialog.

## Phase 2 — Port the CGM / adaptive-smoothing badge

**Files:** `plugins/main/.../dashboard/DashboardV2ComposeEmbedded.kt` (add badge),
`plugins/main/.../dashboard/compose/DashboardCircleTopCompose.kt` (source, read-only reference).

- Extract or reuse `HeroCgmCompactBadge` (currently `private` in `DashboardCircleTopCompose.kt:711-800`) so it
  can be called from `DashboardV2StatusCard`. Prefer promoting it to an internal (non-private) shared composable
  in the same `compose` package over duplicating the code.
- Wire it into `DashboardV2StatusCard` next to (or replacing) the existing sensor-age badge, using the same
  `StatusCardState.adaptiveSmoothingQualityTier` / `adaptiveSmoothingQualityBadgeText` /
  `adaptiveSmoothingQualityDialogMessage` fields — these are already populated correctly by
  `OverviewViewModel` regardless of which screen reads them, since that pipeline was never the problem.
- No changes needed to `OverviewViewModel`, `AdaptiveSmoothingPlugin`, or the `Smoothing` interface.

**Testing:** compile; manual check that the badge appears in DASHBOARD_V2 and its tap opens a dialog with
sensor age + (when the active smoothing plugin populates it) the smoothing quality paragraph.

## Phase 3 — Tap-to-detail status dialogs

**Files:** `plugins/main/.../dashboard/DashboardV2ComposeEmbedded.kt` (add `onClick` + dialogs to the existing
reservoir/battery/sensor/site badges), reference implementations in the classic `OverviewFragment.kt` /
`overview_info_layout.xml` (Tarciso fork, read-only) for dialog content and copy.

- Add tap targets + `OKDialog`-style detail dialogs for: pump status, sensor status, reservoir/cannula, battery,
  openaps, uploader, BG quality — mirroring what the classic Overview already shows, sourced from state already
  available in `StatusCardState`/`OverviewViewModel` (these values are already computed for the read-only
  badges; this phase only adds the dialog and the click handler, no new data plumbing expected unless a specific
  dialog needs a field `StatusCardState` doesn't yet carry).
- Exact dialog copy/content to be finalized per badge during implementation, using the classic Overview's
  dialogs as the content reference (adapted to plain, simple English per project convention, since these are
  user-facing strings).

**Testing:** compile; manual tap-through of all 7 dialogs, confirming each shows real, current data (not
placeholder text).

## Phase 4 — Missing quick actions + wiring fixes

**Files:** `plugins/main/.../dashboard/DashboardV2ComposeEmbedded.kt` (or a new quick-action row component),
`plugins/main/.../dashboard/compose/DashboardHeroCommands.kt`, `DashboardShellController.kt`.

- Add direct dashboard-level shortcuts for Carbs, Wizard/Calculator, Quick Wizard, Temp Target — reusing
  existing dialogs/screens already implemented elsewhere in the app (e.g. the same ones the classic
  `MainNavigationBar`/`TreatmentBottomSheet` flow uses), not new dialog implementations.
- Fix: `onAimiPreferencesClicked` currently opens Meal Advisor (naming mismatch) — rename the command to match
  what it actually does, or point "Preferences" at the real AIMI Preferences destination; confirm which is
  intended before changing behavior a user might depend on.
- Fix: `onAdjustClicked` and `onAimiPulseClicked` both call `openAdjustmentDetails()` — confirm whether this is
  intentional (two entry points to the same screen) or a leftover duplicate before removing either.

**Testing:** compile; manual check of each new shortcut and the two fixed commands.

## Sequencing and execution

Implemented sequentially in the current session (Phase 1 → 2 → 3 → 4), not via parallel agents — phases share
`DashboardV2ComposeEmbedded.kt`, `DashboardHeroCommands.kt`, and `DashboardShellController.kt`, so concurrent
edits would conflict. A code-reviewer pass runs over the full diff at the end as the verification/control step.

No `git commit` until the user explicitly asks, per project convention.
