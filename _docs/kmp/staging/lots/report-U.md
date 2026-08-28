# Lot U — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f06d626dcc` (Lot T)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio / pkpd / tpo / autodrive / wcycle types already present were **not** overwritten. Dest `ThermalDataMTR` / `ThermalDataOrigins` / `AIMIPhysioDataModelsMTR` / `LocalSentinel` KDoc were **not** edited.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot T landed WCycle estimator / endocrine amps / endometriosis / gestational autopilot / in-engine decision plugins; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

`HcRecoveryProxyThermalSource` does **not** import Health Connect and does **not** need `OuraApiThermalClient`. It builds dest `ThermalSampleMTR` from dest `RHRDataMTR` / `HRVDataMTR`.

`AIMIPhysioOutcomes` is dest-type DTOs (`FetchOutcome` / `ProbeResult` / `PhysioPipelineOutcome`), **not** the HC permissions SDK (`AIMIHealthConnectPermissions`).

`TrainingCircuitBreaker` does **not** pull dump trainers (`AimiSmbTrainer` / `BasalMlTrainingCoordinator`).

`AuditorStatusTracker` does **not** need dump `AuditorVerdict`.

`TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

---

## Copied (4) — dest did not exist

| rel | notes |
|---|---|
| `advisor/auditor/AuditorStatusTracker.kt` | primitives only; `System.currentTimeMillis()` → `aimiWallClockMs()`; `import kotlin.concurrent.Volatile` |
| `ml/TrainingCircuitBreaker.kt` | no dump types; `AtomicInteger` / `AtomicLong` → `AapsLock` + `var`; default clock `{ aimiWallClockMs() }` |
| `physio/AIMIPhysioOutcomes.kt` | enums / DTO only; `ProbeResult.probeTimestamp` default → `aimiWallClockMs()` |
| `physio/thermal/HcRecoveryProxyThermalSource.kt` | dest `HRVDataMTR` / `RHRDataMTR` / `ThermalSampleMTR` / `ThermalDataOrigins`; `java.time` → kotlinx.datetime; kept `internal` |

No dest file was overwritten. Dest `advisor/auditor/` already had `LocalSentinel.kt` only — no status tracker. Dest had no `ml/` folder. Dest `physio/` had no `AIMIPhysioOutcomes.kt`. Dest `physio/thermal/` had baseline / cache / belief / `ThermalDataMTR` / origins — no recovery proxy.

Already in dest and **not** copied: Lot T files (`wcycle/WCycleEstimator.kt` / `EndocrineAmplitudeGovernor.kt` / `EndometriosisAdjuster.kt`, `advisor/gestation/GestationalAutopilot.kt`, `plugins/AimiPluginSystem.kt`, `plugins/impl/SafetyAggressionPlugin.kt` / `StableControlPlugin.kt`); Lot S files (`autodrive/learning/AutodriveAuditor.kt`, `comparison/VirtualSmbState.kt`, `pkpd/AdaptivePkPdEstimator.kt`, `activity/ActivityManager.kt`, `advisor/auditor/LocalSentinel.kt`, `utils/JsonSafeLogger.kt` / `AimiLogger.kt` / `ContextExtensions.kt`, `KalmanFilter.kt`, `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt`, `trajectory/TrajectoryHistoryProvider.kt`, `therapy.kt`, `NightGrowthResistanceMonitor.kt`); Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `wcycle/WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt`, `inflammatory/InflammationAdjuster.kt`, `physio/AIMIPhysioDataModelsMTR.kt` (`AimiPhysioInputs` / `HRVDataMTR` / `RHRDataMTR`), `physio/thermal/ThermalDataMTR.kt` (`ThermalSampleMTR`) / `ThermalDataOrigins.kt`, `physio/SleepLiveDetector.kt`, `model/DecisionModels.kt`, `AimiWallClock.kt` (`aimiWallClockMs`). Do not add keys.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

None of the 4 Copy files already existed at dest. Zero dest-exists skips. No Skip file was copied to unblock compile.

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Do **not** copy `HyperSeverityClassifier` / `HyperTrajectoryMpcFeedForward` |
| remaining `wcycle/*` File path | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File`. `WCycleAdjuster` / `WCycleFacade` need those |
| rest of `patient/*` | tree / orchestrator / `PatternCapKind` |
| meal / endogenous hysteresis | engines / classifier |
| remaining thermal client | `OuraApiThermalClient.kt`: `org.json` + `java.time` + `OkHttp` + dump `AimiStringKey`. This lot copied the File-free RHR/HRV **proxy** only |
| AutoDrive engine graph | `MpcController` needs dump `HyperTrajectoryMpcFeedForward`; `AutodriveEngine` needs MPC + File; `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake |
| remaining TPO | `TpoTriggerEngine` needs dump `PatientMode` |
| Lot T leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics`); `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation`); `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()`); dual-brain auditor helpers (dump `AuditorVerdict`); `utils/RtInstrumentationHelpers.kt` still needs dump `AuditorVerdict` even after this lot copies `AuditorStatusTracker` |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/`OpenAPSAIMIPlugin`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `physio/AIMIHealthConnectPermissions.kt` (Health Connect SDK — T2; **not** the same as dest-type `AIMIPhysioOutcomes`), anything else with `android.*`, `File`, `org.json`, Compose, Android `Activity`, tick, or `OpenAPSAIMIPlugin`.

---

## Rewrite notes

- Metro: none of these four have Metro. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`.
- Log: none of these four log. No log calls added.
- Time: no `System.currentTimeMillis()`. No `java.time`. Same device-local zone as Lot Q thermal store / Lot S NGR / Lot T gestation.
  - `AuditorStatusTracker.updateStatus` / `getStatus`: `System.currentTimeMillis()` → `aimiWallClockMs()`. `@Volatile` fields and 300_000 ms stale window kept.
  - `TrainingCircuitBreaker` default `clock`: `System::currentTimeMillis` → `{ aimiWallClockMs() }`. Injectable `clock` parameter kept.
  - `AIMIPhysioOutcomes.ProbeResult.probeTimestamp` default: `System.currentTimeMillis()` → `aimiWallClockMs()`.
  - `HcRecoveryProxyThermalSource.build` default `nowMs`: `System.currentTimeMillis()` → `aimiWallClockMs()`. `nowMs` parameter kept.
- `java.time` (`HcRecoveryProxyThermalSource` only) → kotlinx.datetime. Device-local zone: `TimeZone.currentSystemDefault()` (dump `ZoneId.systemDefault()` — not UTC).
  - Group-by local date: `Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(zone).date`.
  - Day start: `date.atStartOfDayIn(zone).toEpochMilliseconds()`.
  - Next day: `date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()`.
  - 08:00 sample stamp: `LocalDateTime(date, LocalTime(8, 0)).toInstant(zone).toEpochMilliseconds()`. Wrist / `HC_INFERRED` origin labels and RHR/HRV proxy math (`RHR_BPM_TO_DELTA_C`, 0.65/0.35 mix, `coerceIn(-1.2, 1.2)`) kept. `inferOriginLabel` `.lowercase()` kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`.
- `@Volatile` (`AuditorStatusTracker` `currentStatus` / `lastUpdateMs`): `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`. Fields kept.
- JVM-only atomics (`TrainingCircuitBreaker`): one `AapsLock` around every read/write of `failures` and `coolingUntilMs` (same as Lot S `AdaptivePkPdEstimator` / `KalmanFilter`). `incrementAndGet` → `failures += 1` under the lock. `get()` / `set` → `var` fields. `isOpen` / `recordFailure` / `reset` / companion constants (3 failures, 6 h) kept. No `java.util.concurrent`. KDoc “atomic counters” retargeted to `` `AapsLock` `` (implementation change).
- Explicit imports: no fully qualified names at use site. Proxy shares package `physio.thermal` with dest `ThermalSampleMTR` / `ThermalDataOrigins` — short names. `HRVDataMTR` / `RHRDataMTR` imported from dest `physio`. `internal` kept on the proxy and on `TrainingCircuitBreaker`. `OpenAPSAIMIPlugin` not imported.
- KDoc: dest `ThermalDataMTR` / `ThermalDataOrigins` / `LocalSentinel` not retargeted. Health Connect names in comments on `AIMIPhysioOutcomes` / the proxy stay as comments — no HC SDK types.
- School English: no mass-translate of dump comments.
- Strings / JSON / prefs: no `R.string`, `ResourceHelper`, or `org.json`. No keys added.
- Therapy math unchanged except datetime / clock / lock / import rewrites above.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-U.log` | **BUILD SUCCESSFUL in 49s** (EXIT 0). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **4** / dest-exists skip **0**. Compile: attempt 1 **BUILD SUCCESSFUL** (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`). Recursive engine still blocked. `HcRecoveryProxyThermalSource` does not import Health Connect and does not need `OuraApiThermalClient`. `AIMIPhysioOutcomes` is dest-type DTOs, not the HC permissions SDK. `TrainingCircuitBreaker` does not pull dump trainers. `AuditorStatusTracker` does not need dump `AuditorVerdict`. `TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.
