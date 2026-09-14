# Glass Loop Dashboard Expansion — Design Spec

**Status:** Approved by user, ready for implementation planning.

## Goal

Add 4 new AIMI-specific sections to the Glass "Loop Dashboard" screen (`GlassLoopDashboardScreen.kt`):
Trajectory (longer-horizon BG prediction), Physio (physiological model state summary), ML Learner/Training
status, and ML Model Files info. Restyle the whole screen to the "GlycoCalm" visual language while doing so,
so the screen doesn't end up mixing two visual styles.

## Background

The Glass Loop Dashboard screen currently has 3 sections: "Glucose & Insulin Dynamics" (glucose, delta, IOB,
target, TDD), "Key Factors" (max IOB, max SMB, ISF sensitivity, BG stable duration), "Safety & Alarms" (low
TIR, recent activity/steps). On-device feedback (2026-09-11) asked for this screen to grow with more of what
makes this AIMI fork distinctive: trajectory, physio, ML training status, ML model files.

A research pass across the codebase (`plugins/aps`, `plugins/main`, `ui`) found a wide effort gap between the
4 items — summarized here so the plan can sequence work honestly:

| Section | Existing reactive source? | Effort |
|---|---|---|
| Trajectory (short, ~2h) | Yes — `GraphViewModel.predictionsFlow`, already used by Glass elsewhere | trivial |
| Trajectory (long, ~4h) | No — `AdvancedPredictionEngine.predictCurves()` computed inline by the loop, not exposed reactively | medium: new repository |
| Physio | Partial — `PatientStateRuntimeRepository` (`plugins/aps`) is reactive but `internal`, only consumer today is a legacy XML `ContextActivity` | small-medium: widen visibility + new Compose presentation |
| ML learner/training | No — status lives only in private/internal atomics inside the training loop itself (`TrainingCircuitBreaker`, `BasalMlTrainingCoordinator`) | large: net-new public status object |
| ML model files | No — model stores (`AimiNeuralModelStore`, `AimiSmbModelStore`) only do raw file save/load/delete, no metadata accessor | large: net-new accessor methods |

## Decisions already made with the user

- **Scope:** all 4 sections included in this plan, despite the effort gap above (user's explicit choice over
  a smaller "trajectory + physio only" first cut).
- **Trajectory horizon:** the longer ~4h `AdvancedPredictionEngine` curve, not a duplicate of the ~2h
  `predictionsFlow` already shown elsewhere — user chose richness over reuse-for-free here.
- **Physio scope:** a light summary (a handful of key fields) with a button/link to open the existing "AIMI
  Context" Tools screen for full detail — NOT a full duplicate of that screen's content. (Research found "AIMI
  Context" already shows a fairly complete physio/patient-state panel via
  `PatientStateRuntimeRepository`/`PatientStatePresentationBuilder`/`ContextActivity` — avoid redundant UI
  investment.)
- **Visual style:** the WHOLE screen (all 3 existing sections + the 4 new ones) is restyled to "GlycoCalm" as
  part of this work — user explicitly chose full-screen consistency over a faster but visually mixed result.
  Reference: `docs/superpowers/specs/2026-09-11-glycocalm-reference/DESIGN.md` + `code.html`.

## Risk flagged to the user (accepted, not a blocker)

This session's project memory notes an in-progress effort to unify 2 duplicated ML training pipelines
(SMB/basal) — increments 1-2 done, pipeline/trigger unification still pending. Building the ML training status
section now (item 4 below) means integrating with the CURRENT (pre-unification) shape of that code; if the
unification changes internal structure later, this section's data source may need rework. Documented here so
it's a known, accepted risk — not something this plan's tasks need to solve.

## Architecture

### 1. Trajectory (new repository + Compose card)

New reactive repository in `plugins/aps` (e.g. `TrajectoryRuntimeRepository`), mirroring the existing
`PatientStateRuntimeRepository` shape (`SharedFlow<Unit>` "updated" signal + `getLatest(): TrajectorySnapshot?`
pull, same pattern for consistency) — captures the latest `AdvancedPredictionCurves` snapshot whenever the loop
computes one (called from wherever `DetermineBasalAIMI2`/`ScenarioProjectionEngine.build(...)` already invoke
`AdvancedPredictionEngine.predictCurves(...)`, horizon 240 min). Declared public (not `internal`) so
`plugins:main` can consume it — `plugins:main` already depends on `plugins:aps` (used today for the Auditor
UI), so this is a visibility change, not a new module dependency.

Rendered on Glass as a compact card (numbers/short readout in the existing Key-Factors-style card language,
restyled to GlycoCalm — NOT a new mini-chart, to stay consistent with this screen's existing "cards of
numbers" convention rather than introducing a 3rd chart style into the Glass skin). Exact fields to surface
(e.g. predicted BG at +2h/+4h, predicted low/high risk flag) to be confirmed during plan-writing by reading
`AdvancedPredictionCurves`'s actual shape.

### 2. Physio (widen visibility + light summary card)

Widen `PatientStateRuntimeRepository`'s visibility from `internal` to public in `plugins/aps` (or add a small
public-facing wrapper/interface, whichever the plan-writer finds cleaner after reading the current declaration
— both achieve "plugins:main can consume this").

New Compose card (GlycoCalm-styled) showing 3-4 key fields drawn from `PatientStatePresentationBuilder`'s
output (exact fields TBD at plan-writing — candidates already known to exist from research: physiology phase,
thermal summary, detected intent), plus a button that opens the existing "AIMI Context" screen the same way
the Tools grid's own "AIMI Context" tile already does (reuse `launchDashboardV2Aimi`/`ContextActivity` launch
mechanism as-is — do not build a second navigation path to the same screen).

### 3. ML learner/training status (new status repository)

New small status object (e.g. `MlTrainingStatusRepository` in `plugins/aps`) exposing, reactively or via a
simple pull-based read (mirroring the pattern above): circuit-breaker open/closed state (from
`TrainingCircuitBreaker.isOpen()`), last-trained timestamp (from `BasalMlTrainingCoordinator`'s tracked
`lastTrainMs`), sample count (from `BasalNeuralLearner`'s `sampleCount`). This requires widening a few
currently-private fields' visibility within `plugins/aps` (or adding accessor methods) — exact shape to be
confirmed at plan-writing time by reading these classes' current internals directly (not assumed from this
spec). See the "Risk flagged" section above regarding the in-progress training-pipeline unification.

Rendered as a compact status card: a badge (open/closed), a relative "last trained" time, a sample count.

### 4. ML model files (new accessor methods)

Add metadata accessor methods to `AimiNeuralModelStore`/`AimiSmbModelStore` (or a small new wrapper around
both) exposing, per managed model file: exists?, last-modified timestamp, file size. These stores already
manage `File` objects internally for save/load/delete — this is additive read-only metadata, not a change to
the save/load/delete behavior itself.

Rendered as a compact list/card: one row per tracked model file (name, last-updated, size).

## Visual style — GlycoCalm (whole screen)

Full restyle of `GlassLoopDashboardScreen.kt` — background, card surfaces, typography, spacing — using the
same token set as the companion Personalization plan's Personalize screen (see
`2026-09-11-glass-dashboard-personalization-design.md`'s "Visual style" section for the concrete token list;
not repeated here to avoid drift between the two specs — both should read the same reference files:
`docs/superpowers/specs/2026-09-11-glycocalm-reference/DESIGN.md` + `code.html`). The 3 existing sections
(Glucose & Insulin Dynamics, Key Factors, Safety & Alarms) keep their current DATA/fields — only their visual
presentation changes.

**Dark mode:** same standing constraint as the companion Personalization plan — this screen already sources
`isDark` via `LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode` today (it must keep doing so), so the
GlycoCalm restyle needs both a light AND a dark variant, not a permanently-light screen. Use `DESIGN.md`'s
`inverse-*`/`*-container` tokens as the dark-variant starting point (see the Personalization spec's own note
on this for the exact token list — not repeated here to avoid drift).

## Non-goals / deferred

- Restyling any OTHER Glass screen (main dashboard, BG/IOB charts, Personalize screen aside) — scoped to this
  one screen only, per the user's explicit choice.
- A new mini-chart for Trajectory — a compact card/number readout instead, per the "stay consistent with this
  screen's existing convention" reasoning above; revisit only if the user asks for a chart after seeing the
  card version.
- Resolving the ML-training-pipeline-unification risk noted above — flagged, not solved, by this plan.

## Open items for plan-writing

- Exact shape of `AdvancedPredictionCurves` (which fields to surface as the Trajectory card's readout).
- Exact fields available on `PatientStatePresentationBuilder`'s output for the Physio card.
- Exact current visibility/shape of `TrainingCircuitBreaker`/`BasalMlTrainingCoordinator`/`BasalNeuralLearner`
  internals, to design the narrowest possible new status repository around them.
- Exact current `AimiNeuralModelStore`/`AimiSmbModelStore` API, to design the new metadata accessor methods
  without disturbing existing save/load/delete behavior.
- Exact hex `Color(...)` values and Compose component structure for the GlycoCalm restyle, translated from the
  reference `DESIGN.md`/`code.html`.
