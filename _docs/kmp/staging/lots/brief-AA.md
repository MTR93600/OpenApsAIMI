# Lot AA — T1 peel: BLOCKED (no dest-type leftovers after Lot Z)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `7ec2d46cec` (Lot Z)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot Z landed dest `compose/PkpdPresetProfiles.kt` and `compose/PkpdSettingsSupport.kt` (package `app.aaps.plugins.aps.openAPSAIMI.compose`, **not** `androidx.compose`). **`pkpdPrefsSnapshotFrom` omitted** — dump `PkpdPrefsSnapshot` stays in `AdvisorModels.kt`. Cap ~15; this list is **0**.

**TickContext spot-check (do not copy):** `recursive/RecursiveBeliefTickContext.kt` is **not** copy-safe. Lot Z types do **not** appear on TickContext. Still dump-only:

- `AimiRiskEnvelope` — dest has `AimiRiskPhase` / `IobDecisionSource` / `AimiRiskConstants` / `PredictionPathBounds`. The **data class** still uses dump `DecisionPredictionSource` from `risk/DecisionPredictionAuthority.kt`. The **file** also calls dump `SafetyPredictionTerminalsResolver`, dump `MealCertainty`, and dump `DecisionPredictionAuthority`. Do **not** split. Do **not** copy Authority.
- `SafetyPredictionTerminals` lives in dump `risk/SafetyPredictionTerminalsResolver.kt` (not its own file). That file needs dump `HarmoniaDecisionEngine` / `MealCertainty` / `DecisionPredictionAuthority`. Do **not** split.

**`RecursiveBeliefModels`:** still needs dump `HarmoniaSmbAuthorityDecision`. `PatternCapKind` is dest, but `HarmoniaSmbArbiter` uses same-package dump `HarmoniaAction` in `patient/HarmoniaDecision.kt` (tree). Do **not** copy Models. Do **not** copy `HarmoniaSmbAuthorityDecision` / `HarmoniaDecision`.

**Compose-graph wall after Lot Z:** Lot Z copied PKPD **settings math** that lived in dump `compose/` with no `@Composable` / `androidx.compose`. That does **not** unblock UAM (`AimiBehaviorRuntimeProfile` still needs dump `AimiAutonomyMode` + `R.string` in `AimiControlCenterSupport.kt`). Recursive engine still needs dump TickContext (risk types) / Models (`HarmoniaAction` / tree). Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked. Dump `compose/` **screens** stay T2.

Lot Z dest types (`PkpdInsulinPreset` / `PkpdLearningPace` / `PkpdCorrectionPrudence` / `PkpdTailPrudence` / `detectPkpdInsulinPreset` / `applyPkpdInsulinPreset` / `applyPkpdPreferenceUpdate`) only appear on dump leftovers `compose/PkpdSettingsUi.kt` / `compose/AimiPkpdSettingsScreen.kt` (`@Composable`) and parked `OpenAPSAIMIPlugin`. Those are **not** this class of leftover.

This lot is **BLOCKED**. After Lot Z there is **no** copy-safe dest-type leftover in the dump. Dump 324 / dest 241 / dump-not-in-dest **202**. Ban filter (`android.*`, `File`, `org.json`, `@Composable` / `androidx.compose`, Android Activity, tick, `OpenAPSAIMIPlugin`, `PkPdIntegration`) then dump-only type check: **zero** files compile against dest types only. Copy count **0** is not a skip of remaining work — the next T1 wave does not exist until a dump-only type on the Compose-graph wall lands (or a file is split, which this lot must **not** do).

Four Lot L skips stay four. **Do not copy them.**

**Do not copy the whole dump.** Copy only the **Copy** list (empty). Skip the **Skip** list. Do not add extra dump files to make Skip files compile. Do not split dump files to extract a leaf DTO.

---

## Copy (0 files) — BLOCKED

No dump file is copy-safe against dest types after Lot Z.

Lot Z already took the last dest-type leaves (`compose/PkpdPresetProfiles.kt`, `compose/PkpdSettingsSupport.kt` with `pkpdPrefsSnapshotFrom` omitted). Those two exist at dest (HEAD `7ec2d46cec`). Do **not** overwrite them.

### Hunted dest-type leaves (not copy-safe — do not copy)

Looked again after Lot Z. Same class of error as Lot Z grouping PKPD settings math with Compose screens / Lot Y finding `ExerciseHyperOverridePolicy` after HTR / Lot U finding `HcRecoveryProxyThermalSource` grouped with Health Connect: **none left**. These are **not** that class of error.

| rel | why not copy-safe |
|---|---|
| `compose/PkpdSettingsUi.kt` / `AimiPkpdSettingsScreen.kt` / `AimiControlCenterScreen.kt` | `@Composable` / `androidx.compose`. Do **not** copy |
| `compose/AimiBehaviorRuntimeProfile.kt` | dump `AimiAutonomyMode` in `AimiControlCenterSupport.kt` (`@StringRes` + `R.string`). Do **not** split the enum |
| `compose/AimiControlCenterSupport.kt` / `AimiControlCenterSnapshot.kt` / `AimiControlCenterAdvisor.kt` | `R.string` / `@StringRes` / dump `AimiStringKey` / dump history readers |
| `compose/AimiBehaviorFamilyRegistry.kt` | dump `AimiBehaviorFamilyId` (in Snapshot, `@StringRes`) + dump `AimiStringKey`. Do **not** split the enum |
| `keys/AimiStringKey.kt` | `titleResId` + `R.string` + dump `UnifiedActivityProviderMTR` |
| `DetermineBasalInvocationCaches.kt` | **tick** caches for `determine_basal` + `androidx.collection.LongSparseArray` + `System.currentTimeMillis()`. Dest already has `AsyncDataState`. Do **not** copy |
| `advisor/auditor/model/AuditorUIState.kt` | `@ColorRes` / `CoreR` (Android UI) |
| `hormonitor/viewer/HormonitorLabels.kt` | `Locale.getDefault()` language (FR vs other). No commonMain locale without `iosMain` |
| `physio/AIMIHealthConnectPermissions.kt` | Health Connect SDK (`androidx.health.connect`). T2 |
| `steps/AIMIStepsProviderMTR.kt` / `AIMIDatabaseStepsProviderMTR.kt` / `AIMICompositeStepsProviderMTR.kt` | `java.time.Instant` + Health Connect / phone / `StepService` chain. T2. Dest already has `ActivityVitalsProvider` |
| `autodrive/AutodriveEngine.kt` | dest `MpcController` is not enough; dump data lake / `MechanismAttentionGate` / backfiller (`org.json` / `File`) |
| `patient/PatientStateRuntimeRefresher.kt` | dump UAM / latent |
| `advisor/tuning/TuningContextEngine.kt` | dump `AdvisorMetrics` in `AdvisorModels.kt`. Do **not** split |
| `advisor/PkpdAdvisor.kt` | dump `AdvisorMetrics` / `PkpdPrefsSnapshot` + `ResourceHelper` / `R.string`. Do **not** split `AdvisorModels` |
| `pkpd/AdvancedPredictionEngine.kt` | dump `PredictionPhysioModulation` (resolver still UAM / `PkPdRuntime`). Do **not** split |
| `pkpd/PredictionPhysioModulation.kt` | DTO is primitives; **same file** has resolver (`UamHypothesisState` / `PhysioLatentState` / `PkPdRuntime`). Do **not** split |
| `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` / `AuditorVerdictCache.kt` | dump `AuditorVerdict` / `AuditorInput` in `AuditorDataStructures.kt` (Harmonia / tree). Do **not** split |
| `utils/RtInstrumentationHelpers.kt` | dest `AuditorStatusTracker` is not enough; still dump `AuditorVerdict` / `AuditorVerdictCache` |
| `tpo/TpoTriggerEngine.kt` | dump `PatientMode` + `CausalStateId`. Do **not** split orchestrator / posterior |
| `tpo/TpoSessionManager.kt` | dump `AdvisorHistoryRepository` (`android` + clock) + `System.currentTimeMillis()` |
| `tpo/TpoPersistence.kt` / `TpoLlmValidator.kt` / `TpoOrchestrator.kt` / `TpoNotificationManager.kt` / `TpoUiSupport.kt` | `File` / `org.json` / `android` / Compose / `R.string` |
| `autodrive/safety/AutoDriveGater.kt` | dump `HealthContextRepository` + dump `MealChannelHint` (in Models) |
| `autodrive/learning/MechanismAttentionGate.kt` | `org.json` + dump `AimiStorageHelper` |
| `safety/PostHypoDeliveryAuthority.kt` | dump `PatientMode` |
| `quality/ReplayQualityExport.kt` | UAM / `PatientMode` / Models / authority gate |
| `physio/UamHypothesisState.kt` | **Compose** `AimiBehaviorRuntimeProfile` |
| `physio/PhysioLatentState.kt` | dump `UamHypothesisState` (same package, no import). Do **not** split |
| `patient/PatientStateLoopCache.kt` / `PatientStateSnapshot.kt` / `CausalStatePosterior.kt` | UAM / latent / tree / meal-certainty graph |
| `wcycle/WCycleAdjuster.kt` | dest `WCycleEstimator` is not enough; dump `WCycleLearner` (`android.*` + `File` + `org.json`) |
| `basal/BasalDecisionEngine.kt` | `android.content.Context` + `R.string` + `System.currentTimeMillis()` |
| `llm/LlmHttpRetry.kt` | `android.util.Log` + `Thread.sleep` |
| `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` | tick lock (`ReentrantLock` / dump telemetry) |
| `orchestration/AimiDetermineBasalTickOrchestrator.kt` | dump `DetermineBasalaimiSMB2` (tick) |
| `orchestration/IntelligenceSnapshotJson.kt` | dump `AimiIntelligenceSnapshot` (`PkpdLearningDiagnostics` → `CausalStatePosterior`) |
| `orchestration/AimiAdaptationStatusBuilder.kt` | dump `BasalLearner` / `BasalNeuralLearner` / `UnifiedReactivityLearner` (`android` / `File`) |
| `DoseTerminalSnapshotBuilder` (in dump `orchestration/DoseTerminalSnapshot.kt`) | dump Authority. Dest DTO already exists — do **not** overwrite dest |
| `patient/PatientModeOrchestrator.kt` | not T1-clean |
| `pkpd/PkPdIntegration.kt` | Compose |
| `advisor/oref/OrefFeatureBuilder.kt` | dump `AimiProfileSnapshot` in `AdvisorModels.kt`. Do **not** split |
| `advisor/meal/MealAdvisorResponseSanitizer.kt` / `MealVisionJsonParser.kt` / `MealVisionChatCompletionsParser.kt` | same-package dump `EstimationResult` / `FoodAnalysisPrompt` in `AIVisionProvider.kt` (`android.graphics.Bitmap` + `org.json`) |
| `autodrive/learning/AutodriveDatasetLock.kt` | `ReentrantLock.tryLock` — T2 file lake |
| `ISF/SensitivityRatioEstimator.kt` | `org.json` + dump `AimiStorageHelper` |
| tick / `OpenAPSAIMIPlugin` | parked |

Do **not** treat any row above as Lot AA Copy.

---

## Skip — do not copy this lot

### TickContext / Models / Harmonia (checked, still blocked)

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority file) + dump `SafetyPredictionTerminals` (resolver file) |
| `risk/AimiRiskEnvelope.kt` | dump Authority + `MealCertainty` + `SafetyPredictionTerminalsResolver`. Do **not** split the data class |
| `risk/SafetyPredictionTerminalsResolver.kt` | dump `HarmoniaDecisionEngine` / `MealCertainty` / Authority. Do **not** split the DTO |
| `risk/DecisionPredictionAuthority.kt` | UAM / tree / `PhysioLatentState` / `CausalStatePosterior`. Do **not** split `DecisionPredictionSource` |
| `recursive/RecursiveBeliefModels.kt` | dump `HarmoniaSmbAuthorityDecision` |
| `patient/HarmoniaSmbAuthorityDecision.kt` | dest `PatternCapKind` is not enough; same-package dump `HarmoniaAction` in `HarmoniaDecision.kt` |
| `patient/HarmoniaDecision.kt` | dump `PhysiologicalTreeSnapshot` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dest HTR evaluator now, but still dump TickContext |
| `recursive/RecursiveBeliefAuthorityGate.kt` | dump UAM / `PatientMode` / `PhysioLatentState` / snapshot |

### Remaining Lot L skips (4 — still not T1-clean)

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose**) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Other later waves (not this list — still blocked)

| bucket | why blocked |
|---|---|
| remaining dump `compose/` (7 files) | screens are `@Composable`. Support / snapshot / advisor / registry / runtime profile need `R.string` / `AimiAutonomyMode` / `AimiStringKey`. Lot Z already took the two File-free PKPD math files |
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). `WCycleAdjuster` still needs dump `WCycleLearner`. `WCycleFacade` needs adjuster + csv logger |
| rest of `patient/*` | tree builder needs `PatientModeOrchestrator` / snapshot. `MealCertainty.fromTreeAndEnvironment` needs the tree. Harmonia needs tree. Runtime repos stay parked |
| AutoDrive engine graph | dest `MpcController` is not enough. Engine needs dump data lake / attention gate |
| remaining TPO | `TpoTriggerEngine` still needs dump `PatientMode` |
| File / android learners and trainers | `BasalLearner` / `BasalNeuralLearner` / `UnifiedReactivityLearner` / `AimiSmbTrainer` / `BasalMlTrainingCoordinator` — dest already has `TrainingCircuitBreaker`; do **not** copy trainers to “use” it |
| Health Connect / steps / SOS / camera / TFLite / ONNX | T2. Stay `androidMain` |

Also still parked: `keys/AimiStringKey.kt`, tick / `OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, dump `DoseTerminalSnapshot` **builder**, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `advisor/AdvisorModels.kt`, anything else with `android.*`, `File`, `org.json`, Compose, Android `Activity`, tick, or `OpenAPSAIMIPlugin`.

Do **not** copy dest-already-present Lot Z `compose/PkpdPresetProfiles.kt` / `PkpdSettingsSupport.kt`, Lot Y files, Lot X dest `physio/pattern/*`, or Lot W dest classifier / HTR / meal engine / DTS DTO.

---

## Rewrite on copy (Milos / merge rules)

No files to copy. No rewrites.

Do **not** invent splits (extract `AimiAutonomyMode` from `AimiControlCenterSupport`, extract `AimiBehaviorFamilyId` from `AimiControlCenterSnapshot`, extract `AuditorVerdict` from `AuditorDataStructures`, extract `AdvisorMetrics` / `PkpdPrefsSnapshot` from `AdvisorModels`, extract `PatientMode` from `PatientModeOrchestrator`, extract `PredictionPhysioModulation` data class from its resolver, extract `DecisionPredictionSource` from Authority) to manufacture a Copy list.

Do **not** restore `pkpdPrefsSnapshotFrom` on dest `PkpdSettingsSupport`.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, dump `DoseTerminalSnapshot` builder, `DecisionPredictionAuthority`, `UamHypothesisState`, `PatientMode`, `AdvisorModels`, `AuditorDataStructures`, `AuditorVerdict`, `AimiControlCenterSupport`, `AimiBehaviorRuntimeProfile`, Compose screens, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine / TickContext / Models / adapters, `AutodriveEngine`, `TpoTriggerEngine`, `DetermineBasalInvocationCaches`, `OpenAPSAIMIPlugin`, `DetermineBasalAIMI2`) to unblock Skip.
- Split dump files to extract a leaf DTO so a Copy list appears.
- Overwrite dest Lot Z `compose/` PKPD math, or Lot Y / Lot X / Lot W dest files.
- Treat `AimiDecisionPlugin` / `AimiPluginManager` as `OpenAPSAIMIPlugin`. Do not register `@IntKey(225)`. Do not move tick or the APS plugin.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`.
- Commit. No push. (Commit agent later — nothing to commit for this lot.)

---

## Report

`_docs/kmp/staging/lots/report-AA.md`: copied **0**, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (none), compile **not run**. State that TickContext is still blocked on dump `AimiRiskEnvelope` / `SafetyPredictionTerminals` (Lot Z types did not unblock it). State that dump `compose/` **screens** stay T2. State that Lot Z dest-type leftovers (`PkpdInsulinPreset` / prudence enums) only appear on Compose screens + parked plugin. Return **BLOCKED**.

Return BLOCKED.
