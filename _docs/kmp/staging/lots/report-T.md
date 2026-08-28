# Lot T — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c71a283cca` (Lot S)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio / pkpd / tpo / autodrive / wcycle types already present were **not** overwritten. Dest `WCycleBelief` / `WCycleTypes` / `WCyclePreferences` / `DecisionModels` / `InflammationAdjuster` / `AimiPhysioInputs` KDoc were **not** edited.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot S landed AutoDrive auditor / NGR monitor / PKPD learner / activity / utils; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

Gestation / estimator `LocalDate` is **`kotlinx.datetime.LocalDate`**, not `java.time.LocalDate`. Governor hour uses `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour`.

`AimiDecisionPlugin` / `AimiPluginManager` are in-engine decision extensions on dest `AimiPluginContext`. They are **not** `OpenAPSAIMIPlugin`. No `@IntKey(225)`. No tick. No APS plugin.

`EndometriosisAdjuster` does **not** need `WCycleLearner` or `File`. It uses dest `WCyclePreferences` / `ContraceptiveType` / `AimiPhysioInputs` and keys already in `:core:keys`.

`TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

---

## Copied (7) — dest did not exist

| rel | notes |
|---|---|
| `wcycle/WCycleEstimator.kt` | dest `WCyclePreferences` / `CyclePhase` / `CycleTrackingMode`; `java.time.LocalDate` → kotlinx.datetime |
| `wcycle/EndocrineAmplitudeGovernor.kt` | dest `WCycleInfo` / `WCycleBelief` / `WCycleDefaults`; `EndocrineAmpAxis` declared here; `LocalTime.now()` + `String.format("%.3f")` rewritten |
| `wcycle/EndometriosisAdjuster.kt` | dest `WCyclePreferences` / `ContraceptiveType` / `AimiPhysioInputs`; unused `LTag` dropped; `calculateFactors(..., inputs)` signature kept |
| `advisor/gestation/GestationalAutopilot.kt` | no dump types; Metro kept; `java.time.LocalDate` + `Math.round` rewritten |
| `plugins/AimiPluginSystem.kt` | dest `AimiAction` / `AimiPluginContext`; copied **before** impls; `removeIf` rewritten; `LTag.AIMI`; Metro kept |
| `plugins/impl/SafetyAggressionPlugin.kt` | dest `AimiDecisionPlugin` + dest `GlucoseStatusAIMI` / `DoubleKey.OApsAIMIMaxSMB` |
| `plugins/impl/StableControlPlugin.kt` | dest `AimiDecisionPlugin` + dest `DoubleKey.OApsAIMILunchFactor` |

No dest file was overwritten. Dest `wcycle/` already had `WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt` only — no estimator, governor, or endometriosis adjuster. Dest had no `advisor/gestation/`. Dest had no `plugins/` folder. Dest `model/DecisionModels.kt` already had `AimiAction` / `AimiDomain` / `AimiPriority` / `AimiPluginContext`.

Already in dest and **not** copied: Lot S files (`autodrive/learning/AutodriveAuditor.kt`, `comparison/VirtualSmbState.kt`, `pkpd/AdaptivePkPdEstimator.kt`, `activity/ActivityManager.kt`, `advisor/auditor/LocalSentinel.kt`, `utils/JsonSafeLogger.kt` / `AimiLogger.kt` / `ContextExtensions.kt`, `KalmanFilter.kt`, `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt`, `trajectory/TrajectoryHistoryProvider.kt`, `therapy.kt`, `NightGrowthResistanceMonitor.kt`); Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `wcycle/WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt`, `inflammatory/InflammationAdjuster.kt`, `physio/AIMIPhysioDataModelsMTR.kt` (`AimiPhysioInputs`), `model/DecisionModels.kt`. Endometriosis / WCycle / SMB keys already in `:core:keys`. Do not add keys.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

None of the 7 Copy files already existed at dest. Zero dest-exists skips. No Skip file was copied to unblock compile.

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Do **not** copy `HyperSeverityClassifier` / `HyperTrajectoryMpcFeedForward` |
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File`. `WCycleAdjuster` / `WCycleFacade` need those. This lot copied estimator / governor / endometriosis only |
| rest of `patient/*` | tree / orchestrator / `PatternCapKind` |
| meal / endogenous hysteresis | engines / classifier |
| remaining thermal clients | `HcRecoveryProxyThermalSource` (Health Connect / `java.time`); `OuraApiThermalClient` (`org.json` + `java.time`) |
| AutoDrive engine graph | `MpcController` needs dump `HyperTrajectoryMpcFeedForward`; `AutodriveEngine` needs MPC + File; `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake |
| remaining TPO | `TpoTriggerEngine` needs dump `PatientMode` |
| Lot S leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics`); `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation`); `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()`); dual-brain auditor helpers (dump `AuditorVerdict`) |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/`OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/AIMIPhysioManagerMTR.kt`, anything else with `android.*`, `File`, `org.json`, Compose, Android `Activity`, tick, or `OpenAPSAIMIPlugin`.

---

## Rewrite notes

- Metro: kept `@Inject` / `AppScope` / `SingleIn` on `GestationalAutopilot` and `AimiPluginManager`. The other five have no Metro. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`.
- Log: `LTag.APS` → `LTag.AIMI` on `AimiPluginSystem` (`register` / `collectActions`). Unused `LTag` import dropped on `EndometriosisAdjuster` (no log calls added).
- Time: no `System.currentTimeMillis()`. No `java.time`. Same device-local zone as Lot Q thermal store / Lot S NGR.
  - `GestationalAutopilot`: `dateProvider` = `Clock.System.todayIn(TimeZone.currentSystemDefault())`. `ChronoUnit.DAYS.between(today, dueDate)` → `today.daysUntil(dueDate)`. `calculateState(dueDate: LocalDate)` is **`kotlinx.datetime.LocalDate`**. 280-day / week math kept.
  - `WCycleEstimator`: `estimate(now: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()))`. `lengthOfMonth()` → `LocalDate(year, month, 1).daysUntil(…plus(DatePeriod(months = 1)))`. `withDayOfMonth(d)` → `LocalDate(year, month, d)`. `minusMonths(1)` → `minus(DatePeriod(months = 1))`. `ChronoUnit.DAYS.between(cycleStart, now)` → `cycleStart.daysUntil(now)`. `candidate.isAfter(now)` → `candidate > now` (`<= now` at the keep-candidate branch). Day-in-cycle / phase bands kept.
  - `EndocrineAmplitudeGovernor.from`: default `hourOfDay` = `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour`. Luteal dawn window `4..7` kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`. Governor `fmt`: `NumberFormat.withDecimals(3).format(x, NumberFormatPlatform.SEPARATOR_DOT)`. Private `fmt` name kept.
- `Math.round` (`GestationalAutopilot.calculateState`): `kotlin.math.round(factor * 100.0) / 100.0`. Two-decimal resistance factor kept.
- `removeIf` (`AimiPluginSystem.unregister`): collect matching plugins then `removeAll`. Unregister-by-id kept.
- Explicit imports: WCycle files use short dest names in the same `wcycle` package. Plugin impls import `AimiDecisionPlugin` then short name. `OpenAPSAIMIPlugin` not imported. Unused dump `IntKey` / `kotlin.math.min` on endometriosis dropped with `LTag`. `AimiPhysioInputs` kept on `calculateFactors`.
- KDoc: dest `WCycleBelief` / `DecisionModels` not retargeted. Dump `[WCycleAdjuster]` KDoc on dest `WCycleBelief` left as-is.
- School English: no mass-translate of dump comments (`GestationalAutopilot`, `EndometriosisAdjuster`).
- Strings / JSON / prefs: no `R.string`, `ResourceHelper`, or `org.json`. Keys already in `:core:keys` (`BooleanKey.AimiEndometriosisEnable` / `AimiEndometriosisPainFlare`, `DoubleKey.AimiEndometriosisBasalMult` / `AimiEndometriosisSmbDampen`, `DoubleKey.OApsAIMIMaxSMB`, `DoubleKey.OApsAIMILunchFactor`).
- Therapy math unchanged except datetime / format / round / import rewrites above.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-T.log` | **BUILD SUCCESSFUL in 50s** (EXIT 0). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact. Metro compiler warnings on `@Inject` constructors (`GestationalAutopilot`, `AimiPluginManager`) match existing dest Metro files; dump constructor annotations kept.

---

## Return

**DONE** — copied **7** / dest-exists skip **0**. Compile: attempt 1 **BUILD SUCCESSFUL** (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`). Recursive engine still blocked. Gestation / estimator `LocalDate` is `kotlinx.datetime.LocalDate`. `AimiDecisionPlugin` is not the APS plugin. `EndometriosisAdjuster` does not need `WCycleLearner` / `File`. `TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.
