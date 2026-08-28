# Lot W — Architecture + Kotlin review

Reviewer: senior architect + senior Kotlin/KMP  
Branch: `kmp-aimi-migration-study`  
HEAD at review: `dff20cbd17` (Lot V) + 14 uncommitted working-tree files  
Compile log: `/tmp/aimi-lot-W.log` attempt 2 — **BUILD SUCCESSFUL in 58s**

---

## Spec checks (bright-line pass/fail)

| Check | Result |
|---|---|
| `DoseTerminalSnapshot` contains `DoseTerminalSnapshotBuilder` | ❌ absent — PASS |
| `DecisionPredictionAuthority` / `UamHypothesisState` / `PkPdIntegration` in any dest file | ❌ absent — PASS |
| `android.*` / `org.json` / Compose imports in any dest file | ❌ absent — PASS |
| `CircadianMealProfileStore` uses `AimiStorageHelper` / `File` / `org.json` / `java.time` | ❌ absent — PASS |
| `@Volatile` uses `kotlin.concurrent.Volatile` (not `kotlin.jvm.Volatile`) | ✅ all 4 files — PASS |
| `priorForHour` with empty snapshot = `defaultPriorForHour` | ✅ blend=0.0, all slots 0.0, dawn penalty 0.0 → base only — PASS |
| Dest `HyperSeverityTier` / `HyperTrajectoryReleaseResult` overwritten | ❌ not touched — PASS |
| Attempt 2 `compileKotlinIosSimulatorArm64` | ✅ BUILD SUCCESSFUL — PASS |
| Attempt 2 `compileAndroidMain` | ✅ BUILD SUCCESSFUL — PASS |

---

## Critical issues 🔴

None.

---

## Important issues 🟡

None. All rewrite rules from the brief are satisfied. No prohibited type slipped through.

---

## Suggestions 🟢

The items below are all **preserved dump behavior**. They were not introduced by Lot W. Do not change them now; they are recorded so future lots know what came from the dump.

### S1 — `isMinPredictedCredible` line 49 is dead code

`HyperTrajectoryHypoCredibility.kt`, lines 49–50:

```kotlin
if (dev < band * 0.75 && tier < HyperSeverityTier.EMERGING) return true
if (tier < HyperSeverityTier.EMERGING && dev < band * 0.85) return true
```

Line 49 is subsumed by line 50 (`0.75 < 0.85`, same tier guard). No reachable input reaches line 49 that would not also satisfy line 50. Behavior is identical with or without line 49. Dump-preserved; do not change in this lot.

### S2 — `MealAbsorptionMemory.update`: `prev == NONE` arm unreachable

`MealAbsorptionMemory.kt`, inner `else if`:

```kotlin
} else if (prev == MealAbsorptionPhase.NONE || prev == MealAbsorptionPhase.INTER_WAVE) {
```

This arm is inside `if (prev.isActive && ...)`. `NONE.isActive` is false, so `prev == NONE` here is dead code. The `INTER_WAVE` arm is reachable and correct. Dump-preserved.

### S3 — Hardcoded TDD=55 in gap-min calls

`EndogenousCounterRegulatoryDetector.kt` line 55 and `PhysiologicalPhaseClassifier.kt` line 256 both call `HyperSeverityClassifier.gapMinMgdl(55.0)`. With `55.0 * 0.07 = 3.85`, `coerceIn(25.0, 45.0)` yields `25.0` — the minimum floor. This is intentional: these detectors use a fixed reference TDD rather than the patient's live TDD, so the gap gate is always 25 mg/dL. Dump-preserved.

### S4 — Global mutable singletons with non-atomic multi-field @Volatile

`MealAbsorptionMemory`, `MealAbsorptionPhaseHysteresis`, `EndogenousPhaseHysteresis`, `CircadianMealProfileStore` are `object` types with multiple `@Volatile` fields. Compound operations across several fields are not atomically guaranteed on the JVM. On Kotlin/Native the memory model is stricter (each @Volatile is sequentially consistent), so the iOS path is fine. This is the existing project pattern; no change here.

---

## What looks good ✅

- **DoseTerminalSnapshot** is a clean DTO-only file. KDoc correctly uses backticks for `DoseTerminalSnapshotBuilder`, `SafetyNet`, and `DecisionPredictionAuthority`. No builder, no Authority types, no unused imports.
- **CircadianMealProfileStore** is a well-written pure in-memory prior. The empty-snapshot equivalence to the dump is algebraically correct: `blend=0.0` collapses to `defaultPriorForHour`, which matches the dump before the first file load.
- **`@Volatile` import** — `kotlin.concurrent.Volatile` is used consistently across all four files that need it (`MealAbsorptionMemory`, `MealAbsorptionPhaseHysteresis`, `EndogenousPhaseHysteresis`, `CircadianMealProfileStore`). No `kotlin.jvm.Volatile` anywhere.
- **Format strings** — `"%.2f".format(…)` was correctly replaced with `aimiFmt2(…)` (with explicit import) in `HyperTrajectoryReleaseEvaluator` and `MealAbsorptionPhaseEngine`. No `aimiFmt3`, no `String.format`, no `java.util.Locale`.
- **Explicit imports** — no fully qualified names at use sites. `CycleTrackingMode`, `CyclePhase`, `HyperSeverityTier` all have explicit imports in `PhysioPhaseFusion`. Same-package short names used correctly.
- **KDoc links** — dump `[docs/…]` paths correctly in backticks. `[DoseTerminalSnapshotBuilder]` in backticks. `[app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionEngine]` is a fully-qualified dest-resolvable link (same module). `[PhysiologicalPhase]` / `[PhysioMultipliersMTR]` / `[DoseTerminalSnapshot]` stay as links.
- **No metro / no Hilt** — none of the 14 files have `@Inject`, `@IntKey`, `@Provides`, or any Hilt/Dagger/Metro annotation.
- **No new inter-module dependencies** — all references are to the same `:plugins:aps` module.
- **Therapy math preserved** — classifier thresholds, TDD-scaled `establishedDevMgdl` / `deepDevMgdl`, `tierWeight`, `riseUrgencyFactor`, `absorptionDoseFactor`, `plateauDwellUrgencyFactor`, meal belief fusion coefficients, chrono-prior slot Gaussians — all unchanged from dump logic.
- **Compile warnings** — the 15 `w:` lines in the log are all in pre-existing files (`NightGrowthResistanceMonitor`, `AIMIAdaptiveBasal`, `DetermineBasalCoordinator`, etc.). Zero new warnings introduced by Lot W files.
- **`EndogenousPhaseHysteresis` hold-bypass** — `breaksEndogenousHold` flag is correctly set only when `classify` (not the cortisol path) produces a steep-rise result, and `stabilize` correctly resets the hold when that flag is set. The logic prevents the cortisol 0.75 U cap from persisting into the first ticks of a genuine meal.
- **`isMaleCircadianProfile` fallback** — returns `true` when `wCycleEnabled=false`, correctly treating absence of tracking as the circadian (non-hormonal) default.

---

## Compile

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-W.log` | BUILD FAILED — `MealAbsorptionPhaseEngine` unresolved `CircadianMealProfileStore` |
| 2 | `/tmp/aimi-lot-W.log` | **BUILD SUCCESSFUL in 58s** (EXIT 0). Both `compileKotlinIosSimulatorArm64` and `compileAndroidMain`. Zero new errors. 15 pre-existing warnings only. |

Compile success is **not** "AIMI runs on iOS".

---

## Verdict

APPROVE
