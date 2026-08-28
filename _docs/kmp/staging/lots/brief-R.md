# Lot R — T1 peel: Lot Q leftovers + dest-type TPO / AutoDrive / basal / context / OREF

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f94b504ebb` (Lot Q)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot Q skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; `wcycle/*` adjusters need File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot Q named copy-safe leftovers for a later wave: `TpoLadderSupport` + `TpoDeltaBuilder`, `AutoDriveModels`, `OrefReasonParser` (`java.util.regex.Pattern` → Kotlin `Regex`). `TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot Q landed tube advisor / thermal engine / TAP-G PKPD math; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: the Lot Q leftovers (types already dest after Lot Q), plus dest-type TPO keys, AutoDrive CBF/PSE/learner, basal planner (now that `AIMIAdaptiveBasal` is dest), context parser/deserializer/influence, OREF report DTO, and virtual-glucose math. Cap ~15; this list is 15.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (15 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these fifteen exist at dest (checked 2026-08-28, HEAD `f94b504ebb`). Dest has no `autodrive/models/`, no `autodrive/safety/`, no `autodrive/estimator/`, no `autodrive/learning/` except `AutodriveDatasetSchema.kt`. Dest `tpo/` has `TpoModels.kt` / `TpoEpisodeLedger.kt` only. Dest `advisor/oref/` has calibrator / features / gate / outcome / reason-suffix only. Dest `basal/` has no `BasalPlanner` / `BasalHistoryUtils`. Dest `context/` has `ContextIntent.kt` only (includes `ContextSnapshot`). Dest `comparison/` has KPI / scorer / `ComparisonData` only.

Already in dest and **must not be copied**: Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); Lot P files (`physio/gate/CosineTrajectoryGate.kt`, `AIMIPhysioFeatureExtractorMTR.kt`, `AIMIPhysioBaselineModelMTR.kt`, `AIMIPhysioContextEngineMTR.kt`, `PhysioAggregator.kt`, `physio/thyroid/*`); Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `tpo/TpoModels.kt`, `advisor/tuning/TuningContextModels.kt`, `pkpd/PkpdSmbTailDamping.kt`, `autodrive/InsulinActionModel.kt`, `context/ContextIntent.kt`, `model/Models.kt` (`BasalPlan` / `LoopContext`), `release/HyperSeverityTier.kt`, `wcycle/WCycleTypes.kt`. TPO / tube / tail-damping **keys** already exist in `:core:keys`. Do not add keys.

Dump scan on these 15: no `android.*`, `java.io.File`, `org.json`, Compose, plugin, or `PkPdIntegration` **as a type**. `ControlBarrierShield` comments mention `PkPdIntegration` — comment only; backticks. `ContextIntent.Activity` / JSON type `"Activity"` / parser `Activity(...)` are dest nested types, **not** `android.app.Activity`. `VirtualGlucoseEngine.tickMinutes` and `ContinuousStateEstimator.tickId` are Autodrive/sim interval ids, **not** `DetermineBasalAIMI2`. `OrefReasonParser` is the only `java.util.regex.Pattern` file in this list — rewrite in this lot. Thermal narrative “Health Connect” is **not** in this list.

**Name clash:** dest `context.ContextIntent` (user intents: `Activity`, `Illness`, …) is **not** dest `model.ContextIntent` (`MealSupport` / `HighCorrection`). Context files in this list must import `app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent`. Do **not** import the model sealed class. Nested `Activity` must stay `ContextIntent.Activity`.

| rel | why |
|---|---|
| `tpo/TpoLadderSupport.kt` | ladder math; dest `PkpdSmbTailDamping`; keys already in `:core:keys` |
| `tpo/TpoDeltaBuilder.kt` | TPO apply-plan builder; dest `TpoProposal` / `TpoApplyPlan` / `TuningChange` / this-lot ladder |
| `tpo/TpoPreferenceKeys.kt` | whitelist map of existing `DoubleKey` / `BooleanKey` |
| `advisor/oref/OrefReasonParser.kt` | oref reason-string parse; no dump types. Rewrite `Pattern` → `Regex` |
| `advisor/oref/OrefAnalysisReport.kt` | OREF window DTO + enums; dest `OrefPersonalSignalGate` is same-package KDoc only |
| `autodrive/models/AutoDriveModels.kt` | `AutoDriveState` / `AutoDriveCommand`; dest `InsulinActionModel`; `SourceSensor` is `core:data` commonMain |
| `autodrive/safety/ControlBarrierShield.kt` | CBF filter; dest `InsulinActionModel` + this-lot `AutoDriveState` / `AutoDriveCommand` |
| `autodrive/estimator/ContinuousStateEstimator.kt` | PSE Ra/SI; dest `InsulinActionModel` + this-lot `AutoDriveState`. Rewrite clock in this lot |
| `autodrive/learning/OnlineLearner.kt` | gradient learner; this-lot `AutoDriveState`. Rewrite atomics in this lot |
| `basal/BasalHistoryUtils.kt` | TBR history provider; dest `TB`. Rewrite clock + `@Volatile` in this lot |
| `basal/BasalPlanner.kt` | pre-filter basal plan; dest Lot Q `AIMIAdaptiveBasal` + dest `BasalPlan` / `LoopContext` + this-lot history utils |
| `context/ContextIntentDeserializer.kt` | Nightscout JSON → dest `ContextIntent`; already uses `aimiWallClockMs` |
| `context/ContextParser.kt` | offline regex parser; dest `ContextIntent` nested types. Rewrite clock in this lot |
| `context/ContextInfluenceEngine.kt` | SMB/basal influence; dest `ContextSnapshot` / `ContextIntent`. `ContextMode` lives in this file |
| `comparison/VirtualGlucoseEngine.kt` | comparator BG deviation math; dest `OapsProfile` only |

Copy `TpoLadderSupport` **before** `TpoDeltaBuilder`. Copy `AutoDriveModels` **before** CBF / PSE / learner. Copy `BasalHistoryUtils` **before** `BasalPlanner`. Context parser / deserializer / influence share dest `ContextIntent` only (no order between them). OREF files and virtual-glucose do not depend on the others.

Do **not** edit dest `InsulinActionModel` / `ContextIntent` / `TpoModels` / `TuningContextModels` / `AIMIAdaptiveBasal` / `PkpdSmbTailDamping` KDoc to retarget links.

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

Lot Q said this stays blocked. **Do not copy:**

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier` |
| `recursive/RecursiveBeliefModels.kt` | `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump pattern / Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dump `HyperTrajectoryReleaseEvaluator` |
| `recursive/RecursiveBeliefAuthorityGate.kt` | classifier / UAM Compose / `PatientMode` / pattern snapshot |

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier`. Catalog / Id / Detector / Policy / Hysteresis / CapHold / Export hang off that. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. `HyperTrajectoryReleasePreferences` calls dump `HyperSeverityClassifier`. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). Adjusters need those. `WCycleEstimator`: `java.time.LocalDate`. `EndocrineAmplitudeGovernor`: `java.time.LocalTime.now()`. `EndometriosisAdjuster` still needs learner/File. `WCyclePreferences` already dest (Lot M). |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal clients | `HcRecoveryProxyThermalSource.kt`: Health Connect / `java.time` / clock. `OuraApiThermalClient.kt`: `org.json` + `java.time`. Not this list. |
| AutoDrive engine graph | `MpcController` needs dump `HyperTrajectoryMpcFeedForward`. `AutodriveEngine` needs MPC + `AutodriveDataLake` / File + `System.currentTimeMillis`. `AutoDriveGater` needs dump `HealthContextRepository`. `MechanismAttentionGate`: `org.json` + `AimiStorageHelper`. `PhysiologicalStressMaskBuilder` needs classifier / pattern snapshot. Do **not** pull that graph. |
| remaining TPO | `TpoTriggerEngine` needs dump `PatientMode`. `TpoUiSupport`: `R.string`. `TpoLlmValidator` / session / orchestrator / persistence / notification: clock / Locale / advisor history. Not this list. |
| copy-safe leftovers (not this wave) | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics` in `AdvisorModels`, which needs `HarmoniaDecision`). `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation` in the same dump file as the UAM/classifier resolver). `comparison/VirtualSmbState.kt` (`List.removeIf` JVM + comparator simulator). `AutodriveDatasetLock` (`ReentrantLock`). `KalmanFilter` (`AtomicBoolean` + async TDD). `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()`). Do not mix into this leftovers lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `KalmanFilter.kt`, `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), `pkpd/PkpdSoftFloorPathMin.kt` (`DoseTerminalSnapshot`), anything else with `android.*`, `File`, `org.json`, Compose, `Activity` (Android), tick, or plugin.

Do **not** copy dest-already-present recursive / patient / physio / pkpd / tpo types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `BasalPlanner`, `ContextParser`, `ContextInfluenceEngine`, `VirtualGlucoseEngine`. The other eight have no `@Inject`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` on CBF, PSE, learner, context parser / deserializer / influence. Prefer `LTag.AIMI`. Do not add log calls to files that do not log. `BasalPlanner` injects `AAPSLogger` and does not log — leave it.
3. **Time** — every `System.currentTimeMillis()` in this list must become `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`:
   - `ContinuousStateEstimator.updateAndPredict` default `nowMs`. Keep the `nowMs` parameter (do not delete it).
   - `ContextParser.parse` and `parsePreset` local `now`.
   - `BasalHistoryUtils.FetcherProvider` default `nowProvider`. Keep the `nowProvider` parameter.
   No `System.currentTimeMillis()` left. `OnlineLearner.learnAndUpdate` already takes `currentEpochMs` — do not add a wall-clock call there. `ContextIntentDeserializer` already uses `aimiWallClockMs` — keep it.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0` (etc.). Do **not** add `aimiFmt3`. For local `Double.format(digits)` / `Float.format(decimals)` helpers (CBF, PSE, learner, influence), rewrite the helper to `NumberFormat.withDecimals(digits).format(this.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)` with explicit `import app.aaps.core.data.format.NumberFormat` and `import app.aaps.core.data.format.NumberFormatPlatform`. Keep call sites `x.format(2)` / `x.format(3)`.
   - `OrefAnalysisReport`: `"%.0f".format` → `aimiFmt0`, `"%.1f".format` → `aimiFmt1`, `"%.2f".format` → `aimiFmt2`.
   - `BasalPlanner`: delete `fmt1` / `fmt2` (`String.format(Locale.US, …)`). Call sites → `aimiFmt1` / `aimiFmt2`.
   - `OnlineLearner`: `"%.2f".format(currentState.bgVelocity)` → `aimiFmt2`. Keep the rewritten `format(3)` helper for sensitivity logs.
   - `ContextInfluenceEngine`: `"%.1fU".format(it)` → `"${aimiFmt1(it)}U"`. Rewrite both `Float.format` / `Double.format` helpers as above.
5. **`java.util.regex.Pattern`** (`OrefReasonParser`) — `Pattern.compile(…, Pattern.CASE_INSENSITIVE)` → `Regex(…, RegexOption.IGNORE_CASE)`. `matcher(reason).find()` / `group(1)` → `find(reason)` / `groupValues[1]`. Drop `java.util.regex.Pattern`. Keep parse behaviour (one number after `Target:`, EU comma decimals).
6. **`Math.max` / `Math.abs`** (`OnlineLearner`) — `java.lang.Math` is JVM-only. `import kotlin.math.abs` and `import kotlin.math.max`. Call sites `Math.max` / `Math.abs` → `max` / `abs`.
7. **`@JvmStatic`** (`AutoDriveModels.createSafe`) — drop the annotation. Keep the function. No `kotlin.jvm.JvmStatic`.
8. **`@Volatile`** (`BasalHistoryUtils._provider`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
9. **JVM-only collections / atomics** (will fail iOS):
   - `OnlineLearner`: `AtomicLong` / `AtomicReference` → one `AapsLock` (`import app.aaps.core.interfaces.concurrent.AapsLock`, `import app.aaps.core.interfaces.concurrent.withLock`) around every read/write of counters, `predictionHistory`, and the status snapshot (same as Lot Q thermal store). Replace `incrementAndGet` with `count += 1` under the lock. Replace `statusRef.get()` / `set` with a `var snapshot`. No `java.util.concurrent`. `predictionHistory.entries.removeIf { … }` is JVM — rewrite to collect stale keys then `remove` them. Keep `learnAndUpdate` / `statusSnapshot` signatures.
10. **Explicit imports** — no fully qualified names at use site. `AutoDriveModels`: `import app.aaps.core.data.model.SourceSensor` then `SourceSensor?`, never `app.aaps.core.data.model.SourceSensor` at the property. Context files: `import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent` (and nested types as needed). Do **not** star-import `model.ContextIntent`. Nested `Activity` / `Illness` / `Stress` / `HypoRecovery` stay context nested types. `TpoDeltaBuilder` unused `kotlin.math.roundToInt` — drop that import. `AIMIDecisionOrchestratorShadowMTR` is **not** in this list.
11. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. `AutoDriveModels` `[HyperSeverityTier.ordinal]` cannot resolve from `autodrive.models` — backticks. `ControlBarrierShield` comment `` `PkPdIntegration` `` stays backticks. Dump `[docs/…]` paths that are not Kotlin symbols → backticks. Do not edit dest `InsulinActionModel` / `ContextIntent` / TAP-G KDoc.
12. **School English** — new or changed comments only. Do not mass-translate French dump comments (`ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `BasalPlanner`, `ContextParser`).
13. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. Keep `Preferences` + typed keys on TPO ladder / delta / preference-keys. `ContextIntentDeserializer` keeps `OrgJsonCompat` + kotlinx.serialization (already dest-style). `TextResolver` is not needed here.

`OrefAnalysisReport.toPromptSection` ONNX / assets notes are T1 product text, not an ONNX runtime. Keep them.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `HyperTrajectoryMpcFeedForward`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `PatientMode`, `AdvisorModels`, `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/TickContext/Models/adapters, `AutodriveEngine`, `MpcController`, `TpoTriggerEngine`, `HcRecoveryProxyThermalSource`, `OuraApiThermalClient`) to unblock Skip.
- Overwrite dest recursive / patient / physio / pkpd / tpo types listed as already present. Do not overwrite Lot O / Lot P / Lot Q files. Do not overwrite dest `InsulinActionModel` / `ContextIntent` / `TpoModels` / `TuningContextModels` / `AIMIAdaptiveBasal` / `PkpdSmbTailDamping` / `HyperSeverityTier`.
- Import `model.ContextIntent` into `context/*`. Do not merge the two `ContextIntent` types. Do not treat `ContextIntent.Activity` as `android.app.Activity`.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-R.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, `aimiFmt0` / `aimiFmt1` / `aimiFmt2`, `NumberFormat.withDecimals` helpers, `Pattern` → `Regex`, `Math.max`/`abs` → Kotlin, drop `@JvmStatic`, `AtomicLong`/`AtomicReference` → `AapsLock`, `removeIf` → key collect, `kotlin.concurrent.Volatile`), compile result. State that the recursive engine is still blocked. State that context `Activity` is `ContextIntent.Activity`, not Android `Activity`. State that thermal `CyclePhase` remains `wcycle.CyclePhase` (not touched this lot).

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
