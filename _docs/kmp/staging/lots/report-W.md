# Lot W — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `dff20cbd17` (Lot V BLOCKED)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `HyperSeverityTier` / `HyperTrajectoryReleaseResult` were **not** overwritten. Dest `ClampPkpdScenarioReconcile` KDoc was **not** edited. No keys added.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Builder / Authority stay dump.** Dest `DoseTerminalSnapshot` is the data class + companion constants + `formatLogLine` only.

**CircadianMealProfileStore:** File persist (`AimiStorageHelper` / `org.json` / `java.time`) stays dump. Dest has the **in-memory prior math only** (`priorForHour` + default table + empty snapshot). With zero samples this matches dump before the first file load.

**Compose-graph wall after this lot:** recursive engine still needs dump `physio/pattern/*`. Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. Tick / plugin stay parked.

---

## Copied (13) — dest did not exist

| rel | notes |
|---|---|
| `orchestration/DoseTerminalSnapshot.kt` | DTO + companion constants + `formatLogLine` only. Builder omitted. Dropped unused `DecisionPredictionAuthority` / `ClampPkpdScenarioReconcile` / `kotlin.math`. KDoc: builder stays dump until Authority is T1-clean. `SafetyNet` in backticks (no unused import). |
| `release/HyperTrajectoryHypoCredibility.kt` | dest `ClampPkpdScenarioReconcile` + this-lot DTS constants |
| `release/HyperSeverityClassifier.kt` | dest `MealAbsorptionPhase` / `TrajectoryType` / `HyperSeverityTier`; this-lot `highBgBandMgdl`. Dropped unused `kotlin.math.min`. Docs path in backticks. |
| `release/HyperTrajectoryReleasePreferences.kt` | dest `BooleanKey` / `DoubleKey`; this-lot `establishedDevMgdl`. Import order aligned with dest prefs files. |
| `release/HyperTrajectoryMpcFeedForward.kt` | this-lot `HyperSeverityClassifier.Output`. Dropped unused `kotlin.math.min`. |
| `release/HyperTrajectoryReleaseEvaluator.kt` | dest `BehavioralRiskPolicy` / `MealAbsorptionPhase` / `TrajectoryType`; this-lot classifier + hypo-credibility. `"%.2f".format` → `aimiFmt2`. |
| `physio/MealAbsorptionMemory.kt` | dest `MealAbsorptionPhase`; this-lot engine `Output`. `import kotlin.concurrent.Volatile` |
| `physio/MealAbsorptionPhaseHysteresis.kt` | this-lot engine `Output`. `import kotlin.concurrent.Volatile` |
| `physio/MealAbsorptionPhaseEngine.kt` | this-lot memory + hysteresis + hypo-credibility. `"%.2f".format` → `aimiFmt2`. |
| `physio/CircadianMealProfileStore.kt` | **in-memory prior only** (not dump File store). `priorForHour` + default table. No `AimiStorageHelper` / `org.json` / persist. |
| `physio/EndogenousCounterRegulatoryDetector.kt` | this-lot classifier `Input` + `HyperSeverityClassifier`. Docs path in backticks. |
| `physio/PhysiologicalPhaseClassifier.kt` | this-lot hypo-credibility + severity classifier + endogenous detector; dest WCycle types. Dropped unused `kotlin.math.abs`. Unresolvable `[bestT]` → backticks. |
| `physio/EndogenousPhaseHysteresis.kt` | this-lot classifier `Output`. `import kotlin.concurrent.Volatile` |
| `physio/PhysioPhaseFusion.kt` | dest `PhysioMultipliersMTR`; this-lot `classifyWithHysteresis`. FQ use-site names → explicit `CycleTrackingMode` / `CyclePhase` / `HyperSeverityTier` imports. `[ScenarioProjectionEngine]` → dest FQ KDoc link. |

No dest file was overwritten. Dest `release/` already had `HyperSeverityTier.kt` / `HyperTrajectoryReleaseResult.kt` only. Dest `orchestration/` had `AimiLoopPhase.kt` / `AimiTickContext.kt` — no `DoseTerminalSnapshot`. Dest `physio/` had phase enums / Lot O DTOs — no classifier / meal engine / hysteresis.

Zero dest-exists skips. File persist for the circadian store was **not** copied.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `orchestration` builder in dump `DoseTerminalSnapshot.kt` | **Omitted on purpose.** Needs dump `DecisionPredictionAuthority` + `PredictionAuthorityApplyResult` (UAM / tree / `PkPdRuntime`) |
| `risk/DecisionPredictionAuthority.kt` | UAM Compose |
| `physio/UamHypothesisState.kt` | Compose `AimiBehaviorRuntimeProfile` |
| `pkpd/PkPdIntegration.kt` | Compose `readAimiBehaviorRuntimeProfile` |
| `physio/CircadianMealProfileStore.kt` dump File path | **Parked.** Dest has in-memory prior math only. |
| `physio/pattern/*` | recursive engine wall |
| recursive engine / TickContext / Models / adapters | still needs pattern catalog |
| `TpoTriggerEngine` / `PatientModeOrchestrator` | dump `PatientMode` |
| `AuditorDataStructures` / dual-brain auditor helpers | dump `AuditorVerdict` |
| `WCycleLearner` / File | `android.*` + `File` |
| tick / `OpenAPSAIMIPlugin` | parked |

Five Lot L skips still need Compose / tree / `PkPdRuntime`. **Not copied.**

---

## Rewrite notes

- Metro: none of these 13 have `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`.
- Log: none of these 13 call `aapsLogger`. No log calls added. `formatLogLine` stays a string builder.
- Time: no `System.currentTimeMillis()`. Meal engine `Input.nowMs` is a parameter. Kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`.
  - `HyperTrajectoryReleaseEvaluator` reason: `"%.2f".format(smbFloorU)` / v3 before/after → `aimiFmt2` with explicit import.
  - `MealAbsorptionPhaseEngine.buildReason`: `"%.2f".format` on belief / chrono / kinetic / trajectory / physio → `aimiFmt2`.
- `@Volatile` (`MealAbsorptionMemory`, `MealAbsorptionPhaseHysteresis`, `EndogenousPhaseHysteresis`): `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`. Fields kept.
- Explicit imports: no fully qualified names at use site. Same-package `HyperSeverityTier` / `MealAbsorptionPhase` / `PhysiologicalPhase` stay short. `PhysioPhaseFusion.buildClassifierInput` no longer uses FQ `wcycle` / `release` types at the parameter list.
- KDoc: `[docs/…]` paths in the two files that named dump docs → backticks. `[DoseTerminalSnapshotBuilder]` → backticks (parked). Dest-resolvable `[PhysiologicalPhase]` / `[PhysioMultipliersMTR]` / `[DoseTerminalSnapshot]` stay links. Dest `ClampPkpdScenarioReconcile` KDoc not retargeted.
- School English: new or changed comments only. No mass-translate of dump comments.
- Therapy math unchanged except format / import / KDoc / builder omit. `chronoPrior` uses dest in-memory `CircadianMealProfileStore.priorForHour` (empty snapshot = dump before first file load).

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-W.log` | **BUILD FAILED in 27s**. `MealAbsorptionPhaseEngine` Unresolved reference `CircadianMealProfileStore`. |
| 2 | `/tmp/aimi-lot-W.log` | **BUILD SUCCESSFUL in 58s** (EXIT 0). After in-memory `CircadianMealProfileStore`. Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **14** (13 brief + in-memory circadian prior). Builder and Authority stay dump. Recursive engine still needs `physio/pattern/*`. Compile **BUILD SUCCESSFUL**.
