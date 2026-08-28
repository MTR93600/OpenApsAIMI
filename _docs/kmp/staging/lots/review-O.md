# Lot O — Quality Review

Reviewer: code-reviewer (senior architect + KMP engineer)  
Date: 2026-08-28  
Files reviewed: 7 new files in `plugins/aps/src/commonMain/.../openAPSAIMI/` (`physio/`, `patient/`, `recursive/`)

---

## Scope check

| brief requirement | result |
|---|---|
| Copy exactly the 7 listed files | ✅ — `SleepLiveDetector`, `HealthContextSnapshot`, `AIMIPhysioDataModelsMTR`, `AIMIVectorModels`, `PhysioLiveDigest`, `RecursiveBeliefPreferences`, `RbtEpisodeMemory` |
| Do not overwrite pre-existing dest files (15 listed) | ✅ — verified by report + compile success (no duplicate declarations) |
| Do not copy any Skip file | ✅ — no recursive engine / TickContext / Models / adapters / pattern/* / Lot L skips |
| No `@IntKey(225)`, no tick, no plugin | ✅ — zero matches across all 7 files |
| Compose-graph wall acknowledged | ✅ — recursive engine still blocked; report states it correctly |

---

## KMP bans

Inspected all 7 files for banned APIs.

| banned pattern | result |
|---|---|
| `android.*` | ✅ none |
| `java.io.File` | ✅ none |
| `org.json` | ✅ none — reads use `OrgJsonCompat.*Compat` per PIPELINE rule 8 |
| `@Composable` / Compose imports | ✅ none |
| `Activity` | ✅ none |
| `System.currentTimeMillis` | ✅ none |
| `kotlin.jvm.Volatile` | ✅ none — `RbtEpisodeMemory` uses `kotlin.concurrent.Volatile` |
| `javaClass` | ✅ none — replaced in `TrajectoryKernelRef.equals` |
| `String.format` / `"%.nf".format(...)` | ✅ none |
| `java.util.Locale` | ✅ none |
| `javax.inject` / Hilt | ✅ none |
| `R.string` / `ResourceHelper` | ✅ none |

**All clear — zero matches.** ✅

---

## Rewrite rules

| rule | check | result |
|---|---|---|
| `aimiWallClockMs()` (not `System.currentTimeMillis`) | `SleepLiveDetector.Input.nowMs` default; `GateInput.timestamp` default; `AIMIPhysioDataModelsMTR` + `HealthContextSnapshot` already used it | ✅ |
| `aimiFmt2` (not `String.format`, not `aimiFmt3`) | `SleepLiveDetector.wearableSummary` line 96 | ✅ — import explicit; `aimiFmt2` defined `internal` in `AimiFmt.kt`, visible within same module |
| `kotlin.concurrent.Volatile` | `RbtEpisodeMemory.kt` line 3 | ✅ |
| `javaClass` → `other !is TrajectoryKernelRef` | `AIMIVectorModels.TrajectoryKernelRef.equals` lines 36–41 | ✅ — then compares `name` + `referenceVector.contentEquals`, matches brief spec exactly |
| No Metro `@Inject` / `AppScope` / `SingleIn` | — | ✅ — none of the 7 use injection |
| `PhysioLiveDigest.from()` takes `nowMs` (no wall clock in file) | `patient/PhysioLiveDigest.kt` line 54 | ✅ |
| No `org.json` — reads via `OrgJsonCompat` | `AIMIPhysioDataModelsMTR.kt` imports `opt*Compat` extensions | ✅ |
| Writes use `kotlinx.serialization.json` builders | `buildJsonObject { put(...) }` everywhere | ✅ |

---

## Explicit imports

All types and functions use short names with explicit `import` statements. Notable checks:

| use site | resolution |
|---|---|
| `PhysiologicalPhase` in `PhysioMultipliersMTR` (AIMIPhysioDataModelsMTR line 458) | same package `physio/` — no import needed ✓ |
| `HealthContextSnapshot` in `InflammationLatentEstimatorMTR.estimate()` | same package `physio/` — no import needed ✓ |
| `PhysioStateMTR` in `AIMIVectorModels.GateInput` | same package `physio/` — no import needed ✓ |
| `SleepLiveDetector` in `HealthContextSnapshot` field default | same package `physio/` — no import needed ✓ |
| `HealthContextSnapshot` / `SleepLiveDetector` in `PhysioLiveDigest` | cross-package → explicit imports lines 7–8 ✓ |
| `aimiWallClockMs` / `aimiFmt2` | explicit imports in each file that uses them ✓ |
| `ThermalBeliefDigest` in `HealthContextSnapshot` | explicit import from `physio.thermal` package ✓ |

No fully qualified names at use site anywhere in the 7 files. ✅

---

## KDoc resolvability

| file | KDoc reference | verdict |
|---|---|---|
| `SleepLiveDetector` | `` `isNight` `` — backtick (plain clock helper, not a `[Symbol]` KDoc link) | ✅ |
| `RbtEpisodeMemory` | `` `DetermineBasalAIMI2` `` — backtick as required (parked, cross-module) | ✅ |
| `RbtEpisodeMemory` | `[RecursiveBeliefMemory]` — same `recursive/` package, file exists in dest | ✅ |
| `RbtEpisodeMemory` | `[app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoAggressiveRiseExit]` — FQ link; `PostHypoAggressiveRiseExit.kt` confirmed present in same `plugins:aps` module | ✅ |

---

## Dependency resolution

All types referenced by the 7 files verified present in `commonMain` dest:

| type | source |
|---|---|
| `PhysiologicalPhase` | `physio/PhysiologicalPhase.kt` ✅ |
| `ThermalBeliefDigest` / `ThermalHypothesis` | `physio/thermal/ThermalBeliefDigest.kt` — `.hypothesis.name`, `.deltaVsBaselineC`, `.inflammationIndex`, `.narrative` all resolve ✅ |
| `RecursiveBeliefMemory` | `recursive/RecursiveBeliefMemory.kt` ✅ |
| `PostHypoAggressiveRiseExit` | `safety/PostHypoAggressiveRiseExit.kt` ✅ |
| `BooleanKey.OApsAIMIRecursiveBeliefShadow` etc. | `:core:keys` (compile success confirms) ✅ |
| `Preferences` | `core.keys.interfaces` ✅ |
| `OrgJsonCompat.opt*Compat` | `core.data.json.OrgJsonCompat` ✅ |
| `aimiWallClockMs` | `AimiWallClock.kt` (same module) ✅ |
| `aimiFmt2` | `AimiFmt.kt` (`internal`, same module) ✅ |

---

## Therapy math check

Inspected each file for logic changes beyond clock / format / equals / KDoc:

- **SleepLiveDetector**: constants (`ASLEEP_THRESHOLD = 0.55`, `HC_ONGOING_CONFIDENCE = 0.88`, `MAX_HEURISTIC_CONFIDENCE = 0.72`, step/HR thresholds) unchanged. Score accumulation logic (`score = 0.45 + bonuses`) unchanged. Priority ladder (therapy → HC session → wearable heuristic) intact. Only change: `wearableSummary` now calls `aimiFmt2(confidence)` instead of `"%.2f".format(confidence)`.
- **HealthContextSnapshot**: pure DTO with `toJSON()` / `toSNSDominance()`. Math (HR elevation, HRV suppression, step activity scoring) unchanged.
- **AIMIPhysioDataModelsMTR**: DTOs + JSON round-trip. `PhysioContextMTR.isValid()` / `ageSeconds()` use `aimiWallClockMs()` correctly. `toRiskAversionFactor()` mapping (OPTIMAL/UNKNOWN → 1.0, RECOVERY → 0.9, STRESS/INFECTION → 0.8) unchanged. `InflammationLatentEstimatorMTR` weighted sum (weights 0.30/0.35/0.20/0.15) unchanged.
- **AIMIVectorModels**: pure DTOs; only change is `TrajectoryKernelRef.equals` (`javaClass` → `!is` check).
- **PhysioLiveDigest**: pure DTO + `from()` projection. No math.
- **RecursiveBeliefPreferences**: pure pref read. No math.
- **RbtEpisodeMemory**: TTL constants (LIGHT 30 min, DEEP 45 min, CHAOS 45 min) unchanged. Episode state machine (start / refresh / expire logic) unchanged. Only change: `kotlin.concurrent.Volatile` import.

**No silent therapy math change found.** ✅

---

## Compile

Log `/tmp/aimi-lot-O.log` confirmed present and spot-checked.

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-O.log` | **BUILD SUCCESSFUL in 44s** (EXIT 0). Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain` completed. |

Warnings in log are pre-existing Metro `@Inject` constructor notes in unrelated files (`RunningModeExpiryWorker`, `RunningModeExpiryJob`, `RunningModeReconciler`, `DeltaCalculator`). Zero errors. No retry.

Compile success is not "AIMI runs on iOS". ✅

---

## Concerns

### Pre-existing: dead meal-extend branch in `RbtEpisodeMemory.tick()` 🟡

Lines 104–113 in `RbtEpisodeMemory.kt`:

```kotlin
current.kind == EpisodeKind.POST_HYPO_REBOUND &&
    candidateKind == EpisodeKind.POST_HYPO_REBOUND &&
    mealProb >= MEAL_EXTEND_POST_HYPO_MEAL_PROB -> { ... }
```

This branch is unreachable. The preceding branch (line 94) matches `current.kind == candidateKind`, which is true whenever both are `POST_HYPO_REBOUND`. The meal-extension intent (refresh `lastSeenAtMs` when meal probability is high) therefore silently never fires.

This is a pre-existing logic bug in the dump, faithfully ported as required by the brief ("therapy math unchanged"). It is **not introduced by this lot**. Flagged here for the record; the fix would be to reorder the branches (meal-extend check before the generic same-kind branch) — but that is a dump-level change outside this lot's scope.

### Pre-existing: `RbtEpisodeMemory` non-atomic read-modify-write 🟢

`@Volatile` on `active` provides visibility but not atomicity. The `tick()` function reads `active` multiple times then writes it. Under concurrent calls from two threads the state could be inconsistent. This was the dump design; the brief explicitly specified `kotlin.concurrent.Volatile` as the only required change. No action needed this lot.

### Pre-existing: `InflammationLatentEstimatorMTR` stateless class 🟢

The class has no fields and could be an `object`. Pre-existing dump pattern; out of scope for this lot.

---

APPROVE
