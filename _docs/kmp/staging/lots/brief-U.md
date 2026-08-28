# Lot U — T1 peel: dest-type leftovers (auditor tracker, ML breaker, physio outcomes, thermal RHR/HRV proxy)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f06d626dcc` (Lot T)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot T skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; remaining `wcycle/*` File learners still File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot T leftovers still blocked: `TpoTriggerEngine` (dump `PatientMode`), `MpcController` (dump `HyperTrajectoryMpcFeedForward`), `TuningContextEngine` (dump `AdvisorMetrics`), `AdvancedPredictionEngine` (dump `PredictionPhysioModulation`), `DualBrainHelpers` / `DecisionModulator` / `AuditorStableContextGuard` / `AuditorPromptBuilder` (dump `AuditorVerdict`), `HormonitorLabels` (device locale). `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake. Lot T did **not** unblock those named leftovers. Dest now includes Lot T: `WCycleEstimator`, `EndocrineAmplitudeGovernor`, `EndometriosisAdjuster`, `GestationalAutopilot`, `AimiPluginSystem`, `SafetyAggressionPlugin`, `StableControlPlugin`.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot T landed WCycle estimator / endocrine amps / endometriosis / gestational autopilot / in-engine decision plugins; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: dump files whose types already exist in dest **after Lot T** (and earlier lots). Lot T did not unblock its named leftovers. Four dest-type leaves were parked as “not this wave”: `AuditorStatusTracker` (clock only), `TrainingCircuitBreaker` (atomics + clock), `AIMIPhysioOutcomes` (clock default on a DTO — Lot T parked it as a Health Connect fetch enum, but the file has **no** `android.*`), `HcRecoveryProxyThermalSource` (Lot T grouped it with Health Connect clients by mistake — same class of error as Lot S grouping `EndometriosisAdjuster` with File adjusters). The proxy does **not** import Health Connect; it builds dest `ThermalSampleMTR` from dest `RHRDataMTR` / `HRVDataMTR`. Cap ~15; this list is 4.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (4 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these four exist at dest (checked 2026-08-28, HEAD `f06d626dcc`). Dest `advisor/auditor/` has `LocalSentinel.kt` only — no status tracker. Dest has no `ml/` folder. Dest `physio/` has no `AIMIPhysioOutcomes.kt`. Dest `physio/thermal/` has baseline / cache / belief / `ThermalDataMTR` / origins — no recovery proxy.

Already in dest and **must not be copied**: Lot T files (`wcycle/WCycleEstimator.kt` / `EndocrineAmplitudeGovernor.kt` / `EndometriosisAdjuster.kt`, `advisor/gestation/GestationalAutopilot.kt`, `plugins/AimiPluginSystem.kt`, `plugins/impl/SafetyAggressionPlugin.kt` / `StableControlPlugin.kt`); Lot S files (`autodrive/learning/AutodriveAuditor.kt`, `comparison/VirtualSmbState.kt`, `pkpd/AdaptivePkPdEstimator.kt`, `activity/ActivityManager.kt`, `advisor/auditor/LocalSentinel.kt`, `utils/JsonSafeLogger.kt` / `AimiLogger.kt` / `ContextExtensions.kt`, `KalmanFilter.kt`, `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt`, `trajectory/TrajectoryHistoryProvider.kt`, `therapy.kt`, `NightGrowthResistanceMonitor.kt`); Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `wcycle/WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt`, `inflammatory/InflammationAdjuster.kt`, `physio/AIMIPhysioDataModelsMTR.kt` (`AimiPhysioInputs` / `HRVDataMTR` / `RHRDataMTR`), `physio/thermal/ThermalDataMTR.kt` (`ThermalSampleMTR`) / `ThermalDataOrigins.kt`, `physio/SleepLiveDetector.kt`, `model/DecisionModels.kt`, `AimiWallClock.kt` (`aimiWallClockMs`). Do not add keys.

Dump scan on these 4: no `android.*`, `java.io.File`, `org.json`, Compose, Android `Activity`, tick (`DetermineBasalAIMI2`), `OpenAPSAIMIPlugin`, or `PkPdIntegration`. `HcRecoveryProxyThermalSource` KDoc names Health Connect — comment only; it takes dest RHR/HRV lists. `AIMIPhysioOutcomes` names Health Connect in comments — enums/DTO only. `TrainingCircuitBreaker` does **not** pull dump `AimiSmbTrainer` / `BasalMlTrainingCoordinator`. `AuditorStatusTracker` does **not** pull dump `AuditorVerdict`. No `@IntKey(225)`.

The four files do not depend on each other. Copy in any order.

Do **not** edit dest `ThermalDataMTR` / `ThermalDataOrigins` / `AIMIPhysioDataModelsMTR` / `LocalSentinel` KDoc to retarget links.

| rel | why |
|---|---|
| `advisor/auditor/AuditorStatusTracker.kt` | auditor status machine; primitives only (no dump `AuditorVerdict`). Rewrite clock + `@Volatile` in this lot |
| `ml/TrainingCircuitBreaker.kt` | ML trainer cooldown breaker; no dump types. Rewrite atomics + clock in this lot |
| `physio/AIMIPhysioOutcomes.kt` | `FetchOutcome` / `ProbeResult` / `PhysioPipelineOutcome` DTOs. Rewrite clock default in this lot |
| `physio/thermal/HcRecoveryProxyThermalSource.kt` | RHR/HRV → dest `ThermalSampleMTR`; dest `HRVDataMTR` / `RHRDataMTR` / `ThermalDataOrigins`. Rewrite `java.time` + clock in this lot. Keep `internal` |

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

Lot T said this stays blocked. **Do not copy:**

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
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier`. `PhysiologicalPatternId.category` calls `PhysiologicalPatternCatalog`, which needs `PatternDefinition` / `PatternCapKind`. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. Do **not** copy `HyperSeverityClassifier` (calls dump `HyperTrajectoryHypoCredibility.highBgBandMgdl`). Do **not** copy `HyperTrajectoryMpcFeedForward` (needs classifier `Output`). Do **not** copy `HyperTrajectoryReleasePreferences` (calls classifier `establishedDevMgdl`). |
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). `WCycleAdjuster` still needs dump `WCycleLearner` even though dest now has `WCycleEstimator`. `WCycleFacade` needs adjuster + csv logger. |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. `PatientStatePresentation` needs dump snapshot / mode / tree / Harmonia. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal client | `OuraApiThermalClient.kt`: `org.json` + `java.time` + `OkHttp` + dump `AimiStringKey`. This lot copies the File-free RHR/HRV **proxy** only. |
| AutoDrive engine graph | `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`. `AutodriveEngine` needs MPC + `AutodriveDataLake` / File + `System.currentTimeMillis`. `AutoDriveGater` needs dump `HealthContextRepository`. `MechanismAttentionGate`: `org.json` + `AimiStorageHelper`. `PhysiologicalStressMaskBuilder` needs classifier / pattern snapshot. `AutodriveDatasetLock` is a file lock with `ReentrantLock.tryLock` (T2 data lake). Do **not** pull that graph. |
| remaining TPO | `TpoTriggerEngine` still needs dump `PatientMode`. `TpoUiSupport`: `R.string`. `TpoLlmValidator` / session / orchestrator / persistence / notification: clock / Locale / advisor history / Compose. Not this list. |
| Lot T leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics` in `AdvisorModels`, which needs `HarmoniaDecision`). `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation` — UAM / classifier / `PkPdRuntime`). `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()` language; no commonMain locale without `iosMain`). `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` (dump `AuditorVerdict` in `AuditorDataStructures`, which needs Harmonia / tree). `pkpd/PkpdLearningDiagnostics.kt` (dump `CausalStatePosterior`). `utils/RtInstrumentationHelpers.kt` still needs dump `AuditorVerdict` / `AuditorVerdictCache` even after this lot copies `AuditorStatusTracker`. `advisor/oref/OrefFeatureBuilder.kt` needs dump `AimiProfileSnapshot` in `AdvisorModels`. `advisor/auditor/model/AuditorUIState.kt`: `@ColorRes` / `CoreR` (Android UI). Do not mix into this dest-type leftovers lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/`OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `physio/AIMIHealthConnectPermissions.kt` (Health Connect SDK — T2; **not** the same as dest-type `AIMIPhysioOutcomes`), `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), `pkpd/PkpdSoftFloorPathMin.kt` (`DoseTerminalSnapshot`), `basal/BasalDecisionEngine.kt` (`android.content.Context` + `R.string`), `comparison/AimiSmbComparator.kt` (`android.Context` + `File`), `comparison/AimiSmbSimulator.kt` (tick), `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` (tick lock), `advisor/AdvisorModels.kt` (`HarmoniaDecision` + `titleResId`), `ml/AimiSmbTrainer.kt` / `learning/BasalMlTrainingCoordinator.kt` (android / File — do **not** copy to “use” `TrainingCircuitBreaker`), anything else with `android.*`, `File`, `org.json`, Compose, `Activity` (Android class), tick, or `OpenAPSAIMIPlugin`.

Do **not** copy dest-already-present recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these four have Metro. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — none of these four log. Do not add log calls. Prefer `LTag.AIMI` if a later edit adds logs (not this lot).
3. **Time** — every `System.currentTimeMillis()` in this list must become `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`. No `java.time`. Same device-local zone as Lot Q thermal store / Lot S NGR / Lot T gestation:
   - `AuditorStatusTracker.updateStatus` / `getStatus`: `System.currentTimeMillis()` → `aimiWallClockMs()`. Keep `@Volatile` fields and 300_000 ms stale window.
   - `TrainingCircuitBreaker` default `clock`: `System::currentTimeMillis` → `{ aimiWallClockMs() }`. Keep the injectable `clock` parameter.
   - `AIMIPhysioOutcomes.ProbeResult.probeTimestamp` default: `System.currentTimeMillis()` → `aimiWallClockMs()`.
   - `HcRecoveryProxyThermalSource.build` default `nowMs`: `System.currentTimeMillis()` → `aimiWallClockMs()`. Keep the `nowMs` parameter.
4. **`java.time`** (`HcRecoveryProxyThermalSource` only) → kotlinx.datetime. Explicit imports: `import kotlinx.datetime.DatePeriod`, `import kotlinx.datetime.Instant`, `import kotlinx.datetime.LocalDateTime`, `import kotlinx.datetime.LocalTime`, `import kotlinx.datetime.TimeZone`, `import kotlinx.datetime.atStartOfDayIn`, `import kotlinx.datetime.plus`, `import kotlinx.datetime.toInstant`, `import kotlinx.datetime.toLocalDateTime`. Device-local zone: `val zone = TimeZone.currentSystemDefault()` (dump `ZoneId.systemDefault()` — do **not** switch to UTC).
   - Group-by local date: `Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(zone).date`.
   - Day start: `date.atStartOfDayIn(zone).toEpochMilliseconds()` (teacher: `core/data` `IsoDateParser` / `MidnightUtils`).
   - Next day: `date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()`.
   - 08:00 sample stamp: `LocalDateTime(date, LocalTime(8, 0)).toInstant(zone).toEpochMilliseconds()`. Keep wrist / `HC_INFERRED` origin labels and RHR/HRV proxy math (`RHR_BPM_TO_DELTA_C`, 0.65/0.35 mix, `coerceIn(-1.2, 1.2)`).
5. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Do **not** add `aimiFmt3`. None of these four format floats with `String.format`. `HcRecoveryProxyThermalSource.inferOriginLabel` already uses `.lowercase()` — keep it.
6. **`@Volatile`** (`AuditorStatusTracker` `currentStatus` / `lastUpdateMs`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`. Keep the fields.
7. **JVM-only atomics** (`TrainingCircuitBreaker`) — `AtomicInteger` / `AtomicLong` will fail iOS. One `AapsLock` (`import app.aaps.core.interfaces.concurrent.AapsLock`, `import app.aaps.core.interfaces.concurrent.withLock`) around every read/write of `failures` and `coolingUntilMs` (same as Lot S `AdaptivePkPdEstimator` / `KalmanFilter`). Replace `incrementAndGet` with `failures += 1` under the lock. Replace `get()` / `set` with `var` fields. Default clock `{ aimiWallClockMs() }`. Keep `isOpen` / `recordFailure` / `reset` / companion constants (3 failures, 6 h). No `java.util.concurrent`.
8. **Explicit imports** — no fully qualified names at use site. `HcRecoveryProxyThermalSource` shares package `physio.thermal` with dest `ThermalSampleMTR` / `ThermalDataOrigins` — short names. `HRVDataMTR` / `RHRDataMTR` live in dest `physio` — `import app.aaps.plugins.aps.openAPSAIMI.physio.HRVDataMTR` and `import app.aaps.plugins.aps.openAPSAIMI.physio.RHRDataMTR`. Keep `internal` on the proxy and on `TrainingCircuitBreaker`. Do **not** import `OpenAPSAIMIPlugin`.
9. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Dump `[docs/…]` paths that are not Kotlin symbols → backticks. Do not edit dest `ThermalDataMTR` / `ThermalDataOrigins` / `LocalSentinel` KDoc. Health Connect names in comments on `AIMIPhysioOutcomes` / the proxy stay as comments — do not pull HC SDK types.
10. **School English** — new or changed comments only. Do not mass-translate dump comments.
11. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. `TextResolver` is not needed here. Do not add keys.

`HcRecoveryProxyThermalSource` / `TrainingCircuitBreaker` therapy math unchanged except datetime / clock / lock / import rewrites above.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `HyperTrajectoryMpcFeedForward`, `HyperSeverityClassifier`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `PatientMode`, `AdvisorModels`, `AuditorDataStructures`, `AuditorVerdict`, `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/TickContext/Models/adapters, `AutodriveEngine`, `MpcController`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `OuraApiThermalClient`, `AIMIHealthConnectPermissions`, `HormonitorLabels`, `RtInstrumentationHelpers`, `AimiSmbTrainer`, `BasalMlTrainingCoordinator`, `OpenAPSAIMIPlugin`, `DetermineBasalAIMI2`) to unblock Skip.
- Overwrite dest recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed as already present. Do not overwrite Lot O / Lot P / Lot Q / Lot R / Lot S / Lot T files. Do not overwrite dest `WCycleTypes` / `WCyclePreferences` / `WCycleBelief` / `InflammationAdjuster` / `DecisionModels` / `AimiPhysioInputs` / `HRVDataMTR` / `RHRDataMTR` / `ThermalSampleMTR` / `ThermalDataOrigins` / `SleepLiveDetector` / `LocalSentinel`.
- Treat `AimiDecisionPlugin` / `AimiPluginManager` as `OpenAPSAIMIPlugin`. Do not register `@IntKey(225)`. Do not move tick or the APS plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`. Do not keep `java.time` on the thermal proxy. Do not keep `AtomicInteger` / `AtomicLong` / `System.currentTimeMillis` on these four. Do not copy Health Connect SDK types to “complete” `AIMIPhysioOutcomes`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-U.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, kotlinx.datetime local date / `atStartOfDayIn` / `DatePeriod(days = 1)` / `LocalDateTime` 08:00 `toInstant`, `AapsLock` on the circuit breaker, `kotlin.concurrent.Volatile` on the tracker), compile result. State that the recursive engine is still blocked. State that `HcRecoveryProxyThermalSource` does not import Health Connect and does not need `OuraApiThermalClient`. State that `AIMIPhysioOutcomes` is dest-type DTOs, not the HC permissions SDK. State that `TrainingCircuitBreaker` does not pull dump trainers. State that `AuditorStatusTracker` does not need dump `AuditorVerdict`. State that `TpoTriggerEngine` still needs dump `PatientMode` and `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
