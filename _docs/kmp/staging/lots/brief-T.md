# Lot T — T1 peel: dest-type WCycle math + gestational autopilot + in-engine decision plugins

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c71a283cca` (Lot S)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot S skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; `wcycle/*` File learners still File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot S leftovers still blocked: `TpoTriggerEngine` (dump `PatientMode`), `MpcController` (dump `HyperTrajectoryMpcFeedForward`), `TuningContextEngine` (dump `AdvisorMetrics`), `AdvancedPredictionEngine` (dump `PredictionPhysioModulation`), `DualBrainHelpers` / `DecisionModulator` / `AuditorStableContextGuard` / `AuditorPromptBuilder` (dump `AuditorVerdict`), `HormonitorLabels` (device locale). `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot S landed AutoDrive auditor / NGR monitor / PKPD learner / activity / utils; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: dump files whose types already exist in dest **after Lot S** (and earlier lots). Lot S did not unblock its named leftovers. Parked `java.time` WCycle math is now copy-safe with a same-lot datetime rewrite: dest already has `WCyclePreferences` / `WCycleTypes` / `WCycleBelief`. `EndometriosisAdjuster` does **not** need `WCycleLearner` or `File` (Lot S grouped it with File adjusters by mistake). Gestational autopilot is a leaf (`java.time.LocalDate` rewrite). In-engine `AimiDecisionPlugin` uses dest `AimiPluginContext` / `AimiAction` — this is **not** `OpenAPSAIMIPlugin`. Cap ~15; this list is 7.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (7 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these seven exist at dest (checked 2026-08-28, HEAD `c71a283cca`). Dest `wcycle/` has `WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt` only — no estimator, governor, or endometriosis adjuster. Dest has no `advisor/gestation/`. Dest has no `plugins/` folder. Dest `model/DecisionModels.kt` already has `AimiAction` / `AimiDomain` / `AimiPriority` / `AimiPluginContext`.

Already in dest and **must not be copied**: Lot S files (`autodrive/learning/AutodriveAuditor.kt`, `comparison/VirtualSmbState.kt`, `pkpd/AdaptivePkPdEstimator.kt`, `activity/ActivityManager.kt`, `advisor/auditor/LocalSentinel.kt`, `utils/JsonSafeLogger.kt` / `AimiLogger.kt` / `ContextExtensions.kt`, `KalmanFilter.kt`, `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt`, `trajectory/TrajectoryHistoryProvider.kt`, `therapy.kt`, `NightGrowthResistanceMonitor.kt`); Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `wcycle/WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt`, `inflammatory/InflammationAdjuster.kt`, `physio/AIMIPhysioDataModelsMTR.kt` (`AimiPhysioInputs`), `model/DecisionModels.kt` (`AimiAction` / `AimiPluginContext`). Endometriosis / WCycle / SMB **keys** already exist in `:core:keys`. Do not add keys.

Dump scan on these 7: no `android.*`, `java.io.File`, `org.json`, Compose, Android `Activity`, tick (`DetermineBasalAIMI2`), `OpenAPSAIMIPlugin`, or `PkPdIntegration`. `AimiPluginSystem` / `AimiDecisionPlugin` are dest-type decision extensions on dest `AimiPluginContext`. They are **not** `OpenAPSAIMIPlugin`. No `@IntKey(225)`. `EndocrineAmpAxis` is declared in dump `EndocrineAmplitudeGovernor.kt` (not dest `WCycleBelief`). `EndometriosisAdjuster.calculateFactors(..., inputs: AimiPhysioInputs?)` — dest `AimiPhysioInputs`; param may be unused, **keep** the signature. NGR `Instant` is not in this list.

Copy `plugins/AimiPluginSystem.kt` **before** the two `plugins/impl/` files. The four WCycle / gestation files do not depend on the plugin files (they share dest types only). `WCycleEstimator` does not depend on the governor.

Do **not** edit dest `WCycleBelief` / `WCycleTypes` / `WCyclePreferences` / `DecisionModels` / `InflammationAdjuster` KDoc to retarget links.

| rel | why |
|---|---|
| `wcycle/WCycleEstimator.kt` | cycle-day / phase from dest `WCyclePreferences` / `CyclePhase` / `CycleTrackingMode`. Rewrite `java.time.LocalDate` in this lot |
| `wcycle/EndocrineAmplitudeGovernor.kt` | production endocrine amps; dest `WCycleInfo` / `WCycleBelief` / `WCycleDefaults`. Rewrite `LocalTime.now()` + `String.format("%.3f")` in this lot |
| `wcycle/EndometriosisAdjuster.kt` | endo basal-first / SMB-sober; dest `WCyclePreferences` / `ContraceptiveType` / `AimiPhysioInputs`; keys already in `:core:keys`. Drop unused `LTag` import |
| `advisor/gestation/GestationalAutopilot.kt` | gestational-week resistance curve; no dump types. Rewrite `java.time.LocalDate` + `Math.round` in this lot. Keep Metro |
| `plugins/AimiPluginSystem.kt` | dest `AimiAction` / `AimiPluginContext`. Rewrite `removeIf`. `LTag.APS` → `LTag.AIMI`. Keep Metro. **Not** `OpenAPSAIMIPlugin` |
| `plugins/impl/SafetyAggressionPlugin.kt` | dest `AimiDecisionPlugin` + dest `GlucoseStatusAIMI` / `DoubleKey.OApsAIMIMaxSMB` |
| `plugins/impl/StableControlPlugin.kt` | dest `AimiDecisionPlugin` + dest `DoubleKey.OApsAIMILunchFactor` |

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

Lot S said this stays blocked. **Do not copy:**

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
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). `WCycleAdjuster` / `WCycleFacade` need those. This lot copies estimator / governor / endometriosis only. |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. `PatientStatePresentation` needs dump snapshot / mode / tree / Harmonia. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal clients | `HcRecoveryProxyThermalSource.kt`: Health Connect / `java.time` / clock. `OuraApiThermalClient.kt`: `org.json` + `java.time`. Not this list. |
| AutoDrive engine graph | `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`. `AutodriveEngine` needs MPC + `AutodriveDataLake` / File + `System.currentTimeMillis`. `AutoDriveGater` needs dump `HealthContextRepository`. `MechanismAttentionGate`: `org.json` + `AimiStorageHelper`. `PhysiologicalStressMaskBuilder` needs classifier / pattern snapshot. `AutodriveDatasetLock` is a file lock with `ReentrantLock.tryLock` (T2 data lake). Do **not** pull that graph. |
| remaining TPO | `TpoTriggerEngine` still needs dump `PatientMode`. `TpoUiSupport`: `R.string`. `TpoLlmValidator` / session / orchestrator / persistence / notification: clock / Locale / advisor history / Compose. Not this list. |
| Lot S leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics` in `AdvisorModels`, which needs `HarmoniaDecision`). `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation` — UAM / classifier / `PkPdRuntime`). `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()` language; no commonMain locale without `iosMain`). `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` (dump `AuditorVerdict` in `AuditorDataStructures`, which needs Harmonia / tree). `pkpd/PkpdLearningDiagnostics.kt` (dump `CausalStatePosterior`). Do not mix into this dest-type WCycle lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/`OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), `pkpd/PkpdSoftFloorPathMin.kt` (`DoseTerminalSnapshot`), `basal/BasalDecisionEngine.kt` (`android.content.Context` + `R.string`), `comparison/AimiSmbComparator.kt` (`android.Context` + `File`), `comparison/AimiSmbSimulator.kt` (tick), `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` (tick lock), `advisor/AdvisorModels.kt` (`HarmoniaDecision` + `titleResId`), anything else with `android.*`, `File`, `org.json`, Compose, `Activity` (Android class), tick, or `OpenAPSAIMIPlugin`.

Do **not** copy dest-already-present recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `GestationalAutopilot` and `AimiPluginManager`. The other five have no Metro. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` on `AimiPluginSystem` (`register` / `collectActions`). Prefer `LTag.AIMI`. `EndometriosisAdjuster` imports `LTag` but does not log — **drop** the unused `LTag` import. Do not add log calls to files that do not log.
3. **Time** — no `System.currentTimeMillis()` in this list. No `java.time`. Same device-local zone as Lot Q thermal store / Lot S NGR:
   - `GestationalAutopilot`: `java.time.LocalDate` / `ChronoUnit.DAYS` → kotlinx.datetime. `import kotlinx.datetime.LocalDate`, `import kotlinx.datetime.TimeZone`, `import kotlinx.datetime.todayIn`, `import kotlin.time.Clock`. `dateProvider` default: `Clock.System.todayIn(TimeZone.currentSystemDefault())`. `calculateState(dueDate: LocalDate)` keeps the `LocalDate` name — it is **kotlinx.datetime.LocalDate**, not `java.time.LocalDate`. `ChronoUnit.DAYS.between(today, dueDate)` → `today.daysUntil(dueDate)` (`import kotlinx.datetime.daysUntil`). Keep 280-day / week math.
   - `WCycleEstimator`: `java.time.LocalDate` / `ChronoUnit` → kotlinx.datetime (same imports as gestation). `estimate(now: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()))`. `lengthOfMonth()` → `LocalDate(year, month, 1).daysUntil(LocalDate(year, month, 1).plus(DatePeriod(months = 1)))` with `import kotlinx.datetime.DatePeriod`. `withDayOfMonth(d)` → `LocalDate(year, month, d)`. `minusMonths(1)` → `minus(DatePeriod(months = 1))`. `ChronoUnit.DAYS.between(cycleStart, now)` → `cycleStart.daysUntil(now)`. `candidate.isAfter(now)` → `candidate > now`. Keep day-in-cycle / phase bands.
   - `EndocrineAmplitudeGovernor.from(..., hourOfDay: Int = java.time.LocalTime.now().hour)` → default `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour` with `import kotlinx.datetime.toLocalDateTime`. Keep the `hourOfDay` parameter and luteal dawn window `4..7`.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Do **not** add `aimiFmt3`.
   - `EndocrineAmplitudeGovernor.fmt`: `String.format("%.3f", x)` → `NumberFormat.withDecimals(3).format(x, NumberFormatPlatform.SEPARATOR_DOT)` with explicit `import app.aaps.core.data.format.NumberFormat` and `import app.aaps.core.data.format.NumberFormatPlatform`. Keep the private `fmt` name.
5. **`Math.round`** (`GestationalAutopilot.calculateState`) — `Math.round(factor * 100) / 100.0` → `kotlin.math.round(factor * 100.0) / 100.0` with `import kotlin.math.round`. Keep two-decimal resistance factor.
6. **`removeIf`** (`AimiPluginSystem.unregister`) — JVM-only. Rewrite to collect matching plugins then `removeAll`. Keep unregister-by-id behaviour.
7. **Explicit imports** — no fully qualified names at use site. WCycle files share package `wcycle` with dest `WCyclePreferences` / `CyclePhase` / `WCycleBelief` — do not write fully qualified dest type names. Plugin impls: `import app.aaps.plugins.aps.openAPSAIMI.plugins.AimiDecisionPlugin` then short name. Do **not** import `OpenAPSAIMIPlugin`. `EndometriosisAdjuster` unused `LTag` import — drop it. Keep `AimiPhysioInputs` on the `calculateFactors` signature even if unused.
8. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Dump `[docs/…]` paths that are not Kotlin symbols → backticks. Do not edit dest `WCycleBelief` / `DecisionModels` KDoc. `WCycleBelief` KDoc that names dump `WCycleAdjuster` stays on dest — do not retarget it in this lot.
9. **School English** — new or changed comments only. Do not mass-translate dump comments (`GestationalAutopilot`, `EndometriosisAdjuster`).
10. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. Keep `Preferences` + typed keys already in `:core:keys` (`BooleanKey.AimiEndometriosisEnable` / `AimiEndometriosisPainFlare`, `DoubleKey.AimiEndometriosisBasalMult` / `AimiEndometriosisSmbDampen`, `DoubleKey.OApsAIMIMaxSMB`, `DoubleKey.OApsAIMILunchFactor`). `TextResolver` is not needed here.

`EndocrineAmplitudeGovernor` / `WCycleEstimator` / `EndometriosisAdjuster` / `GestationalAutopilot` therapy math unchanged except datetime / format / round / import rewrites above.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `HyperTrajectoryMpcFeedForward`, `HyperSeverityClassifier`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `PatientMode`, `AdvisorModels`, `AuditorDataStructures`, `physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, recursive engine/TickContext/Models/adapters, `AutodriveEngine`, `MpcController`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `HcRecoveryProxyThermalSource`, `OuraApiThermalClient`, `HormonitorLabels`, `OpenAPSAIMIPlugin`, `DetermineBasalAIMI2`) to unblock Skip.
- Overwrite dest recursive / patient / physio / pkpd / tpo / autodrive / wcycle types listed as already present. Do not overwrite Lot O / Lot P / Lot Q / Lot R / Lot S files. Do not overwrite dest `WCycleTypes` / `WCyclePreferences` / `WCycleBelief` / `InflammationAdjuster` / `DecisionModels` / `AimiPhysioInputs`.
- Treat `AimiDecisionPlugin` / `AimiPluginManager` as `OpenAPSAIMIPlugin`. Do not register `@IntKey(225)`. Do not move tick or the APS plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`. Do not keep `java.time` on gestation / WCycle estimator / governor (use kotlinx.datetime). Do not keep `removeIf` on `AimiPluginSystem`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-T.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`kotlinx.datetime.LocalDate` / `todayIn` / `daysUntil` / `DatePeriod`, governor hour via `toLocalDateTime`, `NumberFormat.withDecimals(3)` `fmt`, `kotlin.math.round`, `removeIf` → collect/`removeAll`, `LTag.AIMI`, drop unused `LTag` on endometriosis), compile result. State that the recursive engine is still blocked. State that gestation / estimator `LocalDate` is `kotlinx.datetime.LocalDate`, not `java.time`. State that `AimiDecisionPlugin` is not the APS plugin. State that `EndometriosisAdjuster` does not need `WCycleLearner` / `File`. State that `TpoTriggerEngine` still needs dump `PatientMode` and `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
