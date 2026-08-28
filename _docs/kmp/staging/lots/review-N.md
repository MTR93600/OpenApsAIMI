# Lot N — Quality Review

Reviewer: code-reviewer (senior architect + KMP engineer)  
Date: 2026-08-28  
Files reviewed: 5 new files in `plugins/aps/src/commonMain/.../openAPSAIMI/scenario/`

---

## Scope check

| brief requirement | result |
|---|---|
| Copy exactly the 5 listed files | ✅ — `ScenarioProjectionContext`, `ScenarioProjectionPair`, `InsulinSlopePreserveHysteresis`, `ScenarioProjectionEngine`, `ScenarioProjectionApplicator` |
| Do not overwrite 4 pre-existing dest files | ✅ — `ScenarioContributor`, `ScenarioContributorId`, `ScenarioProjectionCurve`, `ScenarioProjectionKind` all still exist unchanged; 9 files total in `scenario/` (4 pre + 5 new) |
| Do not copy any Skip file | ✅ — no `MealCorrectionContextResolver`, `ExerciseHyperOverridePolicy`, `T3cAutodriveBasalBridge`, `PkpdAbsorptionGuard`, `SmbDampingUsecase`, physio/pattern/*, release/*, wcycle/*, recursive/*, patient/* |
| No tick, no plugin, no `@IntKey(225)` | ✅ — grep across all 5 files confirms zero matches for `PluginBase`, `LoopPlugin`, `@IntKey`, tick references |

---

## KMP bans

Grepped all 5 files for `android\.`, `java\.io`, `org\.json`, `@Composable`, `Activity`, `System.currentTimeMillis`, `kotlin.jvm.Volatile`, `javax.inject`, `@Inject`, `String.format`, `java.util.Locale`.

**All clear — zero matches.** ✅

---

## Rewrite rules

| rule | check | result |
|---|---|---|
| `@Volatile` → `kotlin.concurrent.Volatile` | `InsulinSlopePreserveHysteresis.kt` line 3 | ✅ |
| `"%.0f".format` → `aimiFmt0` (explicit import) | Engine line 3 + line 178 | ✅ — `aimiFmt0` exists in `AimiFmt.kt`; brief says "do not add `aimiFmt3`", does not ban `aimiFmt0` |
| `"%.1f".format` → `aimiFmt1` (explicit import) | Engine line 4 + uses | ✅ |
| `"%.2f".format` → `aimiFmt2` (explicit import) | Engine line 5 + uses | ✅ |
| No `aimiFmt3` | — | ✅ |
| `kotlin.math.abs` / `max` / `min` explicit imports | Engine lines 11–13 | ✅ |
| No Metro `@Inject` / `AppScope` / `SingleIn` | — | ✅ |
| No `String.format`, no `java.util.Locale` | — | ✅ |
| No `R.string`, `ResourceHelper`, `org.json`, `Preferences` | — | ✅ |
| No `System.currentTimeMillis()` | — | ✅ |

---

## Explicit imports

All type and function references use short names with explicit import statements. No fully qualified names at use site anywhere in the 5 files. ✅

---

## KDoc resolvability

| file | KDoc reference | verdict |
|---|---|---|
| `InsulinSlopePreserveHysteresis` | `` `MealAbsorptionPhaseHysteresis` `` / `` `EndogenousPhaseHysteresis` `` — backtick (cross-module / dump-only) | ✅ |
| `InsulinSlopePreserveHysteresis` | `[ScenarioProjectionEngine.build]`, `[stabilize]`, `[HOLD_TICKS_DEFAULT]`, `[reset]`, `[holdTicksRemaining]` — same file/package, all resolvable | ✅ |
| `ScenarioProjectionApplicator` | `[ScenarioProjectionPair]`, `[ScenarioProjectionKind.CLINICAL_FLOOR]`, `[ScenarioProjectionKind.SCENARIO_BEST]` — same package, all resolvable | ✅ |
| `ScenarioProjectionApplicator` | `RT.predBGs` appears as plain bold markdown text, not a `[Symbol]` KDoc link — no lint risk | ✅ |
| `ScenarioProjectionEngine` | Doc link `docs/AIMI_SCENARIO_PROJECTION.md` appears as plain prose, not a checked link | ✅ |

---

## Dependency resolution

All types referenced in the 5 files were verified present in `commonMain` dest:

| type | source |
|---|---|
| `MealSafetyContext` | `safety/MealSafetyContext.kt` ✅ |
| `PhysiologicalPhase` | `physio/PhysiologicalPhase.kt` ✅ |
| `MealAbsorptionPhase` + `.isActive` | `physio/MealAbsorptionPhase.kt` ✅ |
| `TrajectoryAnalysis` + `.modulation.isSignificant()` | `trajectory/PhaseSpaceModels.kt` ✅ |
| `TrajectoryType` (all 7 cases covered) | same file ✅ |
| `TrajectoryMetrics.openness` / `.curvature` | same file ✅ |
| `AdvancedPredictionCurves` | `pkpd/` commonMain ✅ |
| `AimiRiskConstants.NUMERIC_FLOOR_MGDL` / `NUMERIC_CEILING_MGDL` | `risk/` commonMain ✅ |
| `PredictiveHypoConstants.RISING_MODERATE_DELTA` | `safety/` commonMain ✅ |
| `ScenarioContributor` / `ScenarioContributorId` / `ScenarioProjectionCurve` / `ScenarioProjectionKind` | same `scenario/` package, pre-existing ✅ |
| `Predictions`, `RT` | `core:interfaces` commonMain ✅ |
| `aimiFmt0` / `aimiFmt1` / `aimiFmt2` | `AimiFmt.kt` commonMain ✅ |

---

## Therapy math check

Pure math layers in `ScenarioProjectionEngine` inspected:

- `seedInsulinSlopeFromFloor` — `blendSeriesTowardFloor` at constant weight `INSULIN_SLOPE_SEED_WEIGHT = 0.38`. Blend formula `bestRaw[i] = bestRaw[i] * (1 - w) + floorRaw[i] * w` is correct.
- `restoreInsulinSlopeIfCollapsed` — gated on `bestMaxDev` / `floorMaxDev` thresholds; weight clamped to `[0.25, 0.45]`; `mealIntent` damp at `0.40`; all constants named. No silent change.
- `applyMealAbsorptionTerminalFloor` — terminal-anchored linear ramp from `i=1`; t=0 never written; gate `before >= floorTerminal` short-circuits correctly. ✅
- `applyActivityLayer` — rising BG: `coerceAtMost(bg + (pt - bg) * 0.65)`; falling: `-2.0 * (i / n)` linear cushion. No change from dump pattern.
- `applyTrajectoryLayer` — `TIGHT_SPIRAL` `SPIRAL_DAMPING = 0.94`; early-return when `preserveInsulinSlope`; `OPEN_DIVERGING` / `SLOW_DRIFT` ramp uses `openness.coerceIn(0.2, 1.0)`. Matches expected behavior.
- `blendTowardTarget` — `horizonWeight = 0.08`; skips for `OPEN_DIVERGING` / `SLOW_DRIFT`. No change.
- `clampSeriesToInts` — clamps to `[NUMERIC_FLOOR_MGDL, NUMERIC_CEILING_MGDL]`. Safe. ✅

**No silent therapy math change found.** Formatting-only substitution (`"%.nf".format` → `aimiFmtN`) confirmed.

---

## Concerns

None blocking. One minor observation for the record:

- `aimiFmt0` is used in the engine (replacing `"%.0f".format(ctx.effectiveCobG)`) but was not explicitly named in the brief's format rewrite rule (which listed only `aimiFmt1` and `aimiFmt2`). The function exists in dest `AimiFmt.kt`, the brief only bans `aimiFmt3`, and the substitution is mechanically correct. No issue.

---

## Compile

Report attests: `BUILD SUCCESSFUL in 13m 30s`, both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`, EXIT 0, one attempt, no retry. Consistent with clean dependency graph (all 5 files reference only already-present types).

---

APPROVE
