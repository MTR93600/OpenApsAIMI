# Lot V — T1 peel: BLOCKED (no dest-type leftovers after Lot U)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `1c024140e1` (Lot U)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot U skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; remaining `wcycle/*` File learners still File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot U leftovers still blocked: `TpoTriggerEngine` (dump `PatientMode`), `MpcController` (dump `HyperTrajectoryMpcFeedForward`), `TuningContextEngine` (dump `AdvisorMetrics`), `AdvancedPredictionEngine` (dump `PredictionPhysioModulation`), `DualBrainHelpers` / `DecisionModulator` / `AuditorStableContextGuard` / `AuditorPromptBuilder` (dump `AuditorVerdict`), `HormonitorLabels` (device locale). `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake. Lot U did **not** unblock those named leftovers. Dest now includes Lot U: `AuditorStatusTracker`, `TrainingCircuitBreaker`, `AIMIPhysioOutcomes`, `HcRecoveryProxyThermalSource`.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot U landed auditor tracker / ML breaker / physio outcome DTOs / RHR/HRV thermal proxy; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is **BLOCKED**. After Lot U there is **no** copy-safe dest-type leftover in the dump. Dump 324 / dest 212 / dump-not-in-dest **231**. Ban filter then import + same-package type check: **zero** files compile against dest types only. Copy count **0** is not a skip of remaining work — the next T1 wave does not exist until a dump-only type on the Compose-graph wall lands (or a file is split, which this lot must **not** do).

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list (empty). Skip the **Skip** list. Do not add extra dump files to make Skip files compile. Do not split dump files to extract a leaf DTO.

---

## Copy (0 files) — BLOCKED

No dump file is copy-safe against dest types after Lot U.

Lot U already took the last dest-type leaves (`AuditorStatusTracker`, `TrainingCircuitBreaker`, `AIMIPhysioOutcomes`, `HcRecoveryProxyThermalSource`). Those four exist at dest (HEAD `1c024140e1`). Do **not** overwrite them.

### Hunted dest-type leaves (not copy-safe — do not copy)

Same class of error as Lot S grouping `EndometriosisAdjuster` with File adjusters / Lot T grouping the thermal proxy with Health Connect: looked again. These are **not** that class of error.

| rel | why not copy-safe |
|---|---|
| `advisor/tuning/TuningContextEngine.kt` | dump `AdvisorMetrics` in `AdvisorModels.kt`. `AdvisorMetrics` itself is primitives, but the **file** also has `titleResId` + dump `HarmoniaDecision`. Do **not** split `AdvisorModels`. |
| `pkpd/AdvancedPredictionEngine.kt` | dump `PredictionPhysioModulation`. The data class is primitives, but the **same file** has `PredictionPhysioModulationResolver` (`MealAbsorptionPhaseEngine` / `UamHypothesisState` / `PhysioLatentState` / `PkPdRuntime`). Do **not** split. Clock rewrite would not be enough. |
| `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` / `AuditorVerdictCache.kt` | dump `AuditorVerdict` / `AuditorInput` in `AuditorDataStructures.kt` (Harmonia / tree). `DualBrainHelpers` IOB stubs are dest-type; line 70 still takes `AuditorVerdict?`. Do **not** split. |
| `tpo/TpoTriggerEngine.kt` | dump `PatientMode` + `CausalStateId`. `PatientMode` lives in `PatientModeOrchestrator.kt` (needs `PatientStateSnapshot`). Do **not** split the orchestrator. |
| `autodrive/controller/MpcController.kt` | dump `HyperTrajectoryMpcFeedForward` → dump `HyperSeverityClassifier.Output` → dump `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` |
| `hormonitor/viewer/HormonitorLabels.kt` | `Locale.getDefault()` language (FR vs other). No commonMain locale without `iosMain`. |
| `physio/MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | dest `MealAbsorptionPhase` is not enough; `update` takes dump `MealAbsorptionPhaseEngine.Output` |
| `physio/EndogenousPhaseHysteresis.kt` / `PhysioPhaseFusion.kt` | dump `PhysiologicalPhaseClassifier.Output` / `Input`. `PhysioPhaseFusion.previewBestTerminalMgdl` is dest-type math in a blocked file — do **not** split. |
| `physio/pattern/PhysiologicalPatternId.kt` / `PhysiologicalPatternCatalog.kt` / `PhysiologicalPatternHysteresis.kt` / `PhysiologicalPatternPolicy.kt` | `Id.category` calls Catalog; Catalog needs `PatternDefinition` in `PhysiologicalPatternModels.kt`; Models needs dump `PhysiologicalPhaseClassifier.Output?`. Dest has no `physio/pattern/`. |
| `release/HyperSeverityClassifier.kt` / `HyperTrajectoryMpcFeedForward.kt` / `HyperTrajectoryReleasePreferences.kt` | classifier calls dump `HyperTrajectoryHypoCredibility.highBgBandMgdl`; feed-forward needs classifier `Output`; prefs call classifier `establishedDevMgdl`. Hypo-credibility uses dump `DoseTerminalSnapshot` constants. |
| `orchestration/DoseTerminalSnapshot.kt` | DTO + constants are dest-shaped; **builder** needs dump `DecisionPredictionAuthority` + `PredictionAuthorityApplyResult`. Do **not** split. |
| `patient/MealCertainty.kt` | dest `MealAbsorptionPhase` / `ClampPkpdScenarioReconcile` / `PostHypoAggressiveRiseExit`; `fromTreeAndEnvironment` / `Input.trunkState` still need dump `PhysiologicalTreeSnapshot` / `GlobalPhysiologicalState` |
| `wcycle/WCycleAdjuster.kt` | dest now has `WCycleEstimator`; constructor still takes dump `WCycleLearner` (`android.*` + `File` + `org.json`) |
| `safety/PostHypoDeliveryAuthority.kt` | dest `CorrectionAggressionGate` / `PostHypoAggressiveRiseExit`; still dump `PatientMode` |
| `pkpd/PkpdAbsorptionGuard.kt` / `smb/SmbDampingUsecase.kt` | dump `PkPdRuntime` in `PkPdIntegration.kt` (Compose) |
| `ml/SmbRefinementFeatureSchema.kt` | dump `CausalStatePosterior` / `PatientModeOrchestrator.Decision` / `PhysioLatentState` |
| `advisor/meal/MealAdvisorResponseSanitizer.kt` / `MealVisionJsonParser.kt` / `MealVisionChatCompletionsParser.kt` | same-package dump `EstimationResult` / `FoodAnalysisPrompt` in `AIVisionProvider.kt` (`android.graphics.Bitmap` + `org.json`) |
| `advisor/auditor/AimiStateTransitionManager.kt` / `model/AuditorUIState.kt` | Android UI (`AuditorUIState` `@ColorRes` / `CoreR`) |
| `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` | tick lock (`ReentrantLock` / dump telemetry) |
| `autodrive/learning/AutodriveDatasetLock.kt` | `ReentrantLock.tryLock` — T2 file lake |
| `learning/BasalMlWorkerDelegate.kt` | `androidx.work` + dump `BasalMlTrainingCoordinator` |
| `utils/RtInstrumentationHelpers.kt` | dest `AuditorStatusTracker` is not enough; still dump `AuditorVerdict` / `AuditorVerdictCache` |
| `orchestration/IntelligenceSnapshotJson.kt` | dump `AimiIntelligenceSnapshot` |

Do **not** treat any row above as Lot V Copy.

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

Lot U said this stays blocked. **Do not copy:**

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier` |
| `recursive/RecursiveBeliefModels.kt` | `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump pattern / Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dump `HyperTrajectoryReleaseEvaluator` |
| `recursive/RecursiveBeliefAuthorityGate.kt` | classifier / UAM Compose / `PatientMode` / pattern snapshot |

### Other later T1 waves (not this list — still blocked)

| bucket | why blocked |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier`. `PhysiologicalPatternId.category` calls `PhysiologicalPatternCatalog`, which needs `PatternDefinition` / `PatternCapKind`. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. Do **not** copy `HyperSeverityClassifier` / `HyperTrajectoryMpcFeedForward` / `HyperTrajectoryReleasePreferences`. |
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). `WCycleAdjuster` still needs dump `WCycleLearner` even though dest has `WCycleEstimator`. `WCycleFacade` needs adjuster + csv logger. |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. `PatientStatePresentation` needs dump snapshot / mode / tree / Harmonia. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal client | `OuraApiThermalClient.kt`: `org.json` + `java.time` + `OkHttp` + dump `AimiStringKey`. Lot U already copied the File-free RHR/HRV **proxy**. |
| AutoDrive engine graph | `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`. `AutodriveEngine` needs MPC + `AutodriveDataLake` / File + `System.currentTimeMillis`. `AutoDriveGater` needs dump `HealthContextRepository`. `MechanismAttentionGate`: `org.json` + `AimiStorageHelper`. `PhysiologicalStressMaskBuilder` needs classifier / pattern snapshot. `AutodriveDatasetLock` is a file lock with `ReentrantLock.tryLock` (T2 data lake). Do **not** pull that graph. |
| remaining TPO | `TpoTriggerEngine` still needs dump `PatientMode`. `TpoUiSupport`: `R.string`. `TpoLlmValidator` / session / orchestrator / persistence / notification: clock / Locale / advisor history / Compose. |
| Lot U leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics`). `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation`). `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()`). Dual-brain auditor helpers (dump `AuditorVerdict`). `utils/RtInstrumentationHelpers.kt` still needs dump `AuditorVerdict` / `AuditorVerdictCache` even after Lot U copied `AuditorStatusTracker`. `advisor/oref/OrefFeatureBuilder.kt` needs dump `AimiProfileSnapshot` in `AdvisorModels`. `advisor/auditor/model/AuditorUIState.kt`: `@ColorRes` / `CoreR` (Android UI). |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/`OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `physio/AIMIHealthConnectPermissions.kt` (Health Connect SDK — T2; **not** the same as dest-type `AIMIPhysioOutcomes`), `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), `pkpd/PkpdSoftFloorPathMin.kt` (`DoseTerminalSnapshot`), `basal/BasalDecisionEngine.kt` (`android.content.Context` + `R.string`), `comparison/AimiSmbComparator.kt` (`android.Context` + `File`), `comparison/AimiSmbSimulator.kt` (tick), `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` (tick lock), `advisor/AdvisorModels.kt` (`HarmoniaDecision` + `titleResId`), `ml/AimiSmbTrainer.kt` / `learning/BasalMlTrainingCoordinator.kt` (android / File — dest already has `TrainingCircuitBreaker`; do **not** copy trainers to “use” it), anything else with `android.*`, `File`, `org.json`, Compose, `Activity` (Android class), tick, or `OpenAPSAIMIPlugin`.

Do **not** copy dest-already-present recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed above.

Already in dest and **must not be overwritten**: Lot U files (`advisor/auditor/AuditorStatusTracker.kt`, `ml/TrainingCircuitBreaker.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/thermal/HcRecoveryProxyThermalSource.kt`); Lot T files (`wcycle/WCycleEstimator.kt` / `EndocrineAmplitudeGovernor.kt` / `EndometriosisAdjuster.kt`, `advisor/gestation/GestationalAutopilot.kt`, `plugins/AimiPluginSystem.kt`, `plugins/impl/SafetyAggressionPlugin.kt` / `StableControlPlugin.kt`); Lot S files (`autodrive/learning/AutodriveAuditor.kt`, `comparison/VirtualSmbState.kt`, `pkpd/AdaptivePkPdEstimator.kt`, `activity/ActivityManager.kt`, `advisor/auditor/LocalSentinel.kt`, `utils/JsonSafeLogger.kt` / `AimiLogger.kt` / `ContextExtensions.kt`, `KalmanFilter.kt`, `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt`, `trajectory/TrajectoryHistoryProvider.kt`, `therapy.kt`, `NightGrowthResistanceMonitor.kt`); Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `wcycle/WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt`, `inflammatory/InflammationAdjuster.kt`, `physio/AIMIPhysioDataModelsMTR.kt` (`AimiPhysioInputs` / `HRVDataMTR` / `RHRDataMTR`), `physio/thermal/ThermalDataMTR.kt` (`ThermalSampleMTR`) / `ThermalDataOrigins.kt`, `physio/SleepLiveDetector.kt`, `model/DecisionModels.kt`, `AimiWallClock.kt` (`aimiWallClockMs`). Do not add keys.

---

## Rewrite on copy (Milos / merge rules)

No files to copy. No rewrites.

Do **not** invent splits (extract `AdvisorMetrics` from `AdvisorModels`, extract `PatientMode` from `PatientModeOrchestrator`, extract `DoseTerminalSnapshot` constants from the builder file, extract `PredictionPhysioModulation` data class from its resolver, extract `previewBestTerminalMgdl` from `PhysioPhaseFusion`) to manufacture a Copy list.

---

## Compile

Do **not** run a compile for an empty peel. There is nothing to add to `commonMain`.

If a later agent copies Skip files anyway, that is out of this brief.

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect logs; do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `HyperTrajectoryMpcFeedForward`, `HyperSeverityClassifier`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `PatientMode`, `AdvisorModels`, `AuditorDataStructures`, `AuditorVerdict`, `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/TickContext/Models/adapters, `AutodriveEngine`, `MpcController`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `OuraApiThermalClient`, `AIMIHealthConnectPermissions`, `HormonitorLabels`, `RtInstrumentationHelpers`, `AimiSmbTrainer`, `BasalMlTrainingCoordinator`, `OpenAPSAIMIPlugin`, `DetermineBasalAIMI2`) to unblock Skip.
- Split dump files to extract a leaf DTO so a Copy list appears.
- Overwrite dest recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed as already present. Do not overwrite Lot O / Lot P / Lot Q / Lot R / Lot S / Lot T / Lot U files.
- Treat `AimiDecisionPlugin` / `AimiPluginManager` as `OpenAPSAIMIPlugin`. Do not register `@IntKey(225)`. Do not move tick or the APS plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`.
- Commit. No push. (Commit agent later — nothing to commit for this lot.)

---

## Report

`_docs/kmp/staging/lots/report-V.md`: copied **0**, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (none), compile **not run**. State that the recursive engine is still blocked. State that Lot U dest-type leftovers did not unblock `TpoTriggerEngine` / `MpcController` / `TuningContextEngine` / `AdvancedPredictionEngine` / dual-brain auditor helpers / `HormonitorLabels`. State the Compose-graph wall: classifier / `MealAbsorptionPhaseEngine` / `DoseTerminalSnapshot` / `DecisionPredictionAuthority` / UAM Compose. Return **BLOCKED**.

Return BLOCKED.
