# Glass dashboard port

Status: draft, approved by user in conversation (2026-09-10), pending self-review only.

## Context

Throughout the DASHBOARD_V2 parity work in this same session, the user repeatedly showed a screenshot of a
"STATUS AGORA" home screen that did not match any code we could find — including a first reference project
(`OpenApsAIMI-Tarciso-test-v235`) that turned out to have **no Compose UI at all** (classic View/XML only).

A second, newer reference project, `~/Downloads/OpenApsAIMI-Tarciso-test-v242`, was checked this time with the
same rigor and **does** contain the real source: a self-contained "Glass" UI package at
`plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/glass/` (19 files, ~7,200 lines) with a
`StatusAgoraCard` composable (the literal card from the screenshot) plus four more screens matching every other
screenshot shown in this session:

- `GlassOverviewScreen.kt` + `GlassOverviewViewModel.kt` + `GlassOverviewFragment.kt` — the main dashboard
  (StatusAgoraCard, BG chart, IOB chart).
- `GlassLoopDashboardScreen.kt` + `GlassLoopDashboardViewModel.kt` + `GlassLoopDashboardModels.kt` +
  `GlassLoopDashboardFragment.kt` — "Glucose & Insulin Dynamics" + "Key Factors" cards. Confirmed **live-only**:
  there is no history/persistence/scrubber anywhere in this fork's code (`GlassLoopDashboardModels.kt` is a
  single flat state, `refreshData()` overwrites it from `activePlugin.activeAPS.lastAPSResult` — the "most
  recent" result only). The play/rewind/fast-forward controls visible in the user's earlier screenshots are the
  screen-*recording* player's own UI chrome (confirmed by the user: those 4 screenshots are from a demo video,
  not a live build) — there is no time-travel feature to build.
- `GlassTreatmentsScreen.kt` + `GlassTreatmentsViewModel.kt` + `GlassTreatmentsFragment.kt` — a category-filter
  pill row (state-only, no navigation) over a treatments/history list built from plain repository queries.
- `GlassSensorInsertScreen.kt` + `GlassSensorInsertViewModel.kt` + `GlassSensorInsertFragment.kt` — a manual
  sensor-change logging form, a thin wrapper over inserting a `TherapyEvent.Type.SENSOR_CHANGE`.
- `GlycoStatsScreen.kt` + `GlycoStatsViewModel.kt` + `GlycoStatsFragment.kt` — 7-day TDD bars + 7-day TIR bars,
  fed by plain `TddCalculator`/`TirCalculator`.

None of the five screens depend on data this codebase lacks. Every field in the reference's `GlassUiState` (and
the other screens' state classes) was traced to a concrete source expression during investigation, and every one
has a direct equivalent already in our `PersistenceLayer`, `TddCalculator`, `TirCalculator`,
`IobCobCalculator`, `GlucoseStatusProvider`, `ActivePlugin`, or `ProfileFunction` — the reference fork just
reaches them through an older RxJava/`AppRepository`/`DialogFragment` idiom where our codebase already uses
Kotlin coroutines, `PersistenceLayer`, and Compose-route navigation (`UiInteraction.openComposeMainAtRoute` +
`AppRoute`). This is a translation job, not a missing-feature job.

## Goals

1. Add a new, selectable "Glass" home-screen skin that visually matches the reference screenshots exactly:
   `StatusAgoraCard` + BG/IOB charts (main), a live Glucose/Insulin Dynamics + Key Factors screen, a sensor
   insert form.
2. Do this without duplicating screens we already have in native Compose with the same underlying data
   (`TreatmentsScreen`, `StatsScreen`) — restyle those instead of porting `GlassTreatments`/`GlycoStats`
   wholesale.
3. Keep DASHBOARD_V1/DASHBOARD_V2 working and selectable exactly as today — Glass is additive.

## Non-goals

- No "replay past loop cycles" feature — confirmed not to exist in the reference; `GlassLoopDashboardScreen` is
  a live-only view of the single most recent APS result, same as this codebase's own live overview.
- No changes to the reference project.
- No new inter-module Gradle dependencies.
- No new `DialogFragment`s — every reference `runXDialog(fragmentManager)` call becomes a call to
  `uiInteraction.openComposeMainAtRoute(context, route)` against an existing `AppRoute`, matching the pattern
  already used and reviewed earlier in this session for DASHBOARD_V2's own quick actions.

## Architecture

**New skin registration.** `DashboardHomeVariant` (`plugins/main/.../skins/DashboardHomeVariant.kt`) gains a
`GLASS` entry. A new `SkinGlass : SkinInterface` (sibling of `SkinMinimal`/`SkinDashboardV2`) overrides
`dashboardHomeVariant` to `GLASS`, selectable the same way `DASHBOARD_V1`/`DASHBOARD_V2` already are (via
`StringKey.GeneralSkin` → `DashboardHomeVariantResolver`). `AimiDashboardComposeRootView.kt`'s `when` on
`DashboardHomeVariant` (currently `DASHBOARD_V2 -> DashboardV2ComposeEmbedded(...)`,
`DASHBOARD_V1, OVERVIEW -> AimiDashboardComposeEmbedded(...)`) gets a third branch,
`GLASS -> GlassOverviewComposeEmbedded(...)`.

**Per-screen structure**, mirroring the reference's separation of pure-UI-state from data-wiring, but replacing
Fragment+RxJava glue with a plain Hilt `ViewModel` (matching this codebase's own `OverviewViewModel`/
`GraphViewModel` idiom — no Fragments are added):

- `GlassOverviewScreen.kt` (Composable, `plugins/main/.../dashboard/glass/`) — ported near-verbatim from the
  reference (it's pure Compose, already portable); `GlassUiState` data class ported near-verbatim.
- `GlassOverviewViewModel.kt` (new, Hilt `@Inject constructor`) — replaces `GlassOverviewFragment.kt`'s RxJava
  subscriptions with coroutine `Flow`s (`rxBus.toObservable(...).asFlow()` — the existing bridge already used
  elsewhere in this codebase for RxBus→Flow — or direct `StateFlow` collection from `IobCobCalculator`/
  `GlucoseStatusProvider` where those already expose Flow/StateFlow). Field-by-field source mapping (traced
  during investigation, not re-derived at implementation time):

  | `GlassUiState` field | Our source |
  |---|---|
  | currentBg / rawBg | `IobCobCalculator.ads.actualBg()`/`lastBg()`, same as `OverviewViewModel` already reads |
  | delta | `GlucoseStatusProvider.glucoseStatusData?.delta` |
  | trend | `TrendCalculator.getTrendArrow(iobCobCalculator.ads)` |
  | sensorReservoir / batteryLife | `activePlugin.activePump.reservoirLevel` / `.batteryLevel` — same fields
    `StatusCardState.reservoirText`/`pumpBatteryText` already compute |
  | sensorAge/insulinAge/cannulaAge/batteryAge (+colors) | `persistenceLayer.getLastTherapyRecordUpToNow(TE.Type.X)`
    (suspend, exact name match) + the same `sp`-backed warning/critical thresholds `StatusViewModel` already
    reads for the classic Overview's status lights |
  | isLoopActive / loopStatusText | `Loop.isEnabled()`/`closedLoopEnabled`, same as `OverviewViewModel` |
  | iob / cob | `iobCobCalculator.calculateIobFromBolus()` + `calculateIobFromTempBasalsIncludingConvertedExtended()`;
    `iobCobCalculator.getCobInfo(...)` |
  | targetBg | `profileFunction.getProfile()?.getTargetLowMgdl()` |
  | basalPercent | `processedTbrEbData`/`overviewData` temp-basal text, same source `OverviewViewModel` uses |
  | bgReadings | `persistenceLayer.getBgReadingsDataFromTime(timestamp, ascending)` (suspend) |
  | iobReadings | sampled every 5 min from `iobCobCalculator`, same approach as reference |
  | treatments (chart markers) | `persistenceLayer.getBolusesFromTime(...)` + `getCarbsFromTime(...)` (suspend) |
  | notifications | this codebase's own notification source (`NotificationStore`/`AapsNotification` list, already
    plumbed into `DashboardNotificationsComposeList`) — reuse that, do not port a second notification path |

  `stats` is dropped from `GlassUiState` entirely — GlycoStats becomes a restyle of the existing `StatsScreen`
  (Goal 2), not a field this ViewModel populates.

- **Click-handler wiring** (`onOpenX` callbacks): each becomes a call into a small `GlassHeroCommands` interface
  (same pattern as `DashboardHeroCommands`, reviewed and working elsewhere in this session), implemented via
  `uiInteraction.openComposeMainAtRoute(context, route)`:

  | Reference call | Our route |
  |---|---|
  | `runWizardDialog` | `AppRoute.WizardDialog.createRoute()` → `"wizard_dialog"` |
  | `runProfileSwitchDialog` | no direct route equivalent; use `uiInteraction.openProfileActivationScreen(activity, profileIndex)` |
  | `runLoopDialog` | `uiInteraction.openRunningModeScreen(activity)` (already used by DASHBOARD_V2) |
  | `runFillDialog` | `AppRoute.FillDialog.createRoute(preselect)` → `"fill_dialog/{preselect}"` |
  | `runCareDialog(..., BATTERY_CHANGE, ...)` | `AppRoute.CareDialog.createRoute(eventTypeOrdinal)` → `"care_dialog/{ordinal}"` |
  | `runTempTargetDialog` | `uiInteraction.openTempTargetManagementScreen(activity)` (already used by DASHBOARD_V2) |
  | `runTempBasalDialog` | `AppRoute.TempBasalDialog.route` → `"temp_basal_dialog"` |
  | `onOpenStats` | route to our restyled `StatsScreen` (Goal 2) instead of a ported `GlycoStatsFragment` swap |
  | `onOpenTreatments` | route to our restyled `TreatmentsScreen` (Goal 2) |
  | `onOpenSensorInsert` | new `GlassSensorInsertScreen` (ported, Phase 3) |

- **BgChartCard / IobChartCard**: ported as their own Composables in the same `dashboard/glass/` package,
  reading from `GlassUiState.bgReadings`/`iobReadings` — these are pure custom-drawn charts (the reference uses
  `drawscope`/`nativeCanvas`, not Vico), so no dependency on `DashboardGraphComposeCard`/Vico is introduced;
  porting them as-is keeps the exact visual match the user asked for.

**`GlassLoopDashboardScreen`** follows the identical pattern: `GlassLoopDashboardViewModel` (new Hilt VM)
replaces `GlassLoopDashboardFragment`'s RxBus subscriptions with Flow-based ones, reading
`activePlugin.activeAPS?.lastAPSResult`, `iobCobCalculator`, `glucoseStatusProvider`, and
`tddCalculator.calculateDaily(startHours, endHours)` (exact signature confirmed — hour offsets, not
timestamps) for the rolling 7-day TDD figure. No history/scrubber UI is built (Non-goal).

**`GlassSensorInsertScreen`** ports as a small, fully self-contained form + ViewModel; its `save()` becomes a
call to `persistenceLayer`'s transaction runner with `InsertIfNewByTimestampTherapyEventTransaction` (class
exists verbatim in our `database:impl` module already), `TherapyEvent.Type.SENSOR_CHANGE`.

**`TreatmentsScreen`/`StatsScreen` restyle** (Goal 2): apply Glass's visual language (colors, card shapes,
typography from `GlassOverviewTheme.kt`) to our existing screens' Compose code, without touching their
ViewModels or data-fetching — a styling-only change, scoped and reviewed separately from the new-screen phases
below since it touches already-working, already-tested screens.

## Phases

1. **Skin registration + `GlassOverviewScreen` (main dashboard)** — `SkinGlass`, `DashboardHomeVariant.GLASS`,
   the `AimiDashboardComposeRootView` branch, `GlassOverviewViewModel`, `StatusAgoraCard`, `BgChartCard`,
   `IobChartCard`, `GlassHeroCommands`. This alone delivers the screen the user has been asking to match since
   the start of this session.
2. **`GlassLoopDashboardScreen`** — live Glucose/Insulin Dynamics + Key Factors, reachable from a new entry
   point (a button/tile, exact placement to be confirmed with the user when this phase starts).
3. **`GlassSensorInsertScreen`** — sensor-change quick-entry form.
4. **`TreatmentsScreen`/`StatsScreen` visual restyle** — apply Glass's look to the screens we already have,
   closing out `onOpenTreatments`/`onOpenStats` from Phase 1 with a matching visual instead of the generic
   Material3 look they have today.

Each phase compiles and is manually verified before the next starts, per this session's established practice.
No `git commit` until the user explicitly asks.

## Open questions for implementation time (not blocking this spec)

- Exact placement of the entry point to `GlassLoopDashboardScreen` from the main dashboard (a tile? a swipe?) —
  ask the user when Phase 2 starts, once Phase 1's layout is in front of them to react to concretely.
- Whether `GLASS` should also get its own `DashboardV2ToolsScreen`-equivalent tools grid, or reuse the existing
  one — ask when Phase 1 is far enough along to show a concrete choice.
