# Lot O — T1 peel: independent physio/patient DTOs + File-free recursive prefs/memory

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f5eb48553a` (Lot N)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot N skip notes queued `recursive/*` without File, then `patient/*` without runtime repos.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysioContextMTR`, `PhysiologicalPatternSnapshot`, `PhysioMultipliersMTR`, and `HyperSeverityClassifier`. `RecursiveBeliefModels` needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph.

This lot is the smallest still-valid set: independent physio/patient DTOs whose types already exist in dest, plus the two File-free recursive files that compile against dest today. Cap ~15; this list is 7.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*` (except nothing in this Copy list), remaining `release/*`, `wcycle/*` adjusters, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (7 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these seven exist at dest (checked 2026-08-28). Already in dest and **must not be copied**: `recursive/BeliefLeafId.kt`, `BeliefParadoxId.kt`, `RecursiveBeliefMemory.kt`, `WaveletBelief.kt`, `RbtExtendedSignals.kt`, `ChannelInterferenceOptimizer.kt`, `patient/PatientEventMemory.kt`, `BodyKineticsDigest.kt`, `AimiCascadeArbitrationArtifacts.kt`, `HarmoniaSensorTelemetry.kt`, `physio/MealAbsorptionPhase.kt`, `PhysiologicalPhase.kt`, `BehavioralRiskPolicy.kt`, `HormonalScenarioTerminalCap.kt`, `thermal/ThermalBeliefDigest.kt`. BooleanKeys for recursive prefs already exist in `:core:keys`.

Dump scan on these 7: no `android.*`, `java.io.File`, `org.json`, Compose, `Activity`, plugin, or `PkPdIntegration`. `SleepLiveDetector.Source.HEALTH_CONNECT` is an enum name, not Health Connect APIs. `RbtEpisodeMemory.tick` is episodic memory, not the DetermineBasal tick.

| rel | why |
|---|---|
| `physio/AIMIPhysioDataModelsMTR.kt` | `PhysioContextMTR` / `PhysioMultipliersMTR` / `PhysioStateMTR` DTOs; types already dest (`PhysiologicalPhase`, `aimiWallClockMs`, `OrgJsonCompat`) |
| `physio/AIMIVectorModels.kt` | cosine-gate DTOs; `GateInput.physioState` is `PhysioStateMTR` from the file above |
| `physio/SleepLiveDetector.kt` | asleep heuristic; `HealthContextSnapshot` / `PhysioLiveDigest` read `Source` |
| `physio/HealthContextSnapshot.kt` | live physio snapshot; dest `ThermalBeliefDigest` + this lot `SleepLiveDetector` |
| `patient/PhysioLiveDigest.kt` | wearable digest DTO; dest-safe JSON; `from()` takes `nowMs` (no wall clock in the file) |
| `recursive/RecursiveBeliefPreferences.kt` | prefs DTO; BooleanKeys already in `:core:keys` |
| `recursive/RbtEpisodeMemory.kt` | post-hypo / chaos episode hold; uses dest `RecursiveBeliefMemory` only as KDoc |

Copy `AIMIPhysioDataModelsMTR` **before** `AIMIVectorModels` (same lot, `PhysioStateMTR`). Copy `SleepLiveDetector` **before** `HealthContextSnapshot` / `PhysioLiveDigest`.

---

## Skip — do not copy this lot

### Remaining Lot L skips (still not T1-clean)

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` (`patient/PhysiologicalTree.kt` also needs `PatientModeOrchestrator` / `InsulinIntent`) |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Recursive engine (File-free but not copy-safe)

Lot N called this the next wave. Almost none of it compiles without the Compose/classifier graph. **Do not copy:**

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysioContextMTR` (this lot lands the DTO, not the engine), `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier` |
| `recursive/RecursiveBeliefModels.kt` | `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump pattern / Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dump `HyperTrajectoryReleaseEvaluator` |
| `recursive/RecursiveBeliefAuthorityGate.kt` | classifier / UAM Compose / `PatientMode` / pattern snapshot |

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` even after `PhysioContextMTR` lands. Catalog / Id / Detector / Policy / Hysteresis / CapHold / Export hang off that. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). Adjusters need those. `WCyclePreferences` already dest (Lot M). |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaSmbAuthorityDecision` needs `PatternCapKind`. `PatientStateRuntimeRepository` stays parked. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

Do **not** copy dest-already-present recursive / patient / physio types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 7 use `@Inject`. If one appears, keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — prefer `LTag.AIMI`. These 7 do not log. Do not add log calls.
3. **Time** — `SleepLiveDetector.Input.nowMs` default and `AIMIVectorModels.GateInput.timestamp` default: `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`. No `System.currentTimeMillis()`. `AIMIPhysioDataModelsMTR` and `HealthContextSnapshot` already use `aimiWallClockMs` — keep that. `PhysioLiveDigest.from` already takes `nowMs`; do not add a clock there.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. `SleepLiveDetector` wearable summary: `"%.2f".format(confidence)` → `aimiFmt2` with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2`. Do **not** add `aimiFmt3`.
5. **`@Volatile`** (`RbtEpisodeMemory`) — `import kotlin.concurrent.Volatile` (same as `IsfSourceTelemetry`). Not `kotlin.jvm.Volatile`.
6. **`javaClass`** (`AIMIVectorModels.TrajectoryKernelRef.equals`) — JVM-only; will fail iOS. Replace the `javaClass` check with `other !is TrajectoryKernelRef` (then compare `name` + `referenceVector.contentEquals` as today). No `java.lang.Class`.
7. **Explicit imports** — no fully qualified names at use site. `AIMIPhysioDataModelsMTR` / `AIMIVectorModels` / `SleepLiveDetector` / `HealthContextSnapshot` share package `physio` with dest `PhysiologicalPhase` — do not write a fully qualified `PhysiologicalPhase`. `PhysioLiveDigest` already imports `HealthContextSnapshot` / `SleepLiveDetector`; keep those imports.
8. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. `RbtEpisodeMemory`: `[app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2]` → backticks. Dest `[RecursiveBeliefMemory]` and `[PostHypoAggressiveRiseExit]` may stay as links if they resolve; otherwise backticks. Dump FQ name in that KDoc: add `import` and use the short name, or backticks.
9. **School English** — new or changed comments only. Do not mass-translate French dump comments.
10. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. Keep `OrgJsonCompat.opt*Compat` reads on `AIMIPhysioDataModelsMTR` (PIPELINE rule 8). Keep kotlinx.serialization JSON writes. Keep `Preferences` + `BooleanKey` on `RecursiveBeliefPreferences`. `TextResolver` is not needed here.

`SleepLiveDetector.Source.HEALTH_CONNECT` is T1 naming, not a Health Connect client. Keep it.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect logs; do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/TickContext/Models/adapters) to unblock Skip.
- Overwrite dest recursive / patient / physio types listed as already present.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-O.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, `aimiFmt2`, `@Volatile`, `javaClass`), compile result. State that the recursive engine is still blocked.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
