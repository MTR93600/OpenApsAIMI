# Lot S — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `17d159b5b1` (Lot R)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio / pkpd / tpo / autodrive types already present were **not** overwritten. Dest `AutoDriveModels` / `PkPdCore` / `ActivityContext` / `NightGrowthResistanceLearner` / `PhaseSpaceModels` / `InsulinActionState` / `Models` KDoc were **not** edited.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot R landed TPO ladder / AutoDrive CBF-PSE-learner / basal planner / context parser / OREF report / virtual-glucose; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

NGR `Instant` on `evaluate(now: Instant, …)` is **`kotlinx.datetime.Instant`**, not `java.time.Instant`. Constructor default zone is `TimeZone.currentSystemDefault()`.

`ContextExtensions.ContextPlugin` is a dest `LoopContext` helper interface. It is **not** `OpenAPSAIMIPlugin`. No `@IntKey(225)`. No tick. No plugin.

`TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

---

## Copied (13) — dest did not exist

| rel | notes |
|---|---|
| `autodrive/learning/AutodriveAuditor.kt` | dest `AutoDriveState` / `AutoDriveCommand`; FQCN → short names; Metro kept |
| `comparison/VirtualSmbState.kt` | dest `BS` / `TB` / `RT` / `OapsProfile`; `removeIf` → collect/`removeAll`; unused Metro / `Profile` / `plus` dropped; `getPassedDurationToTimeInMinutes` from `core.data.model` |
| `pkpd/AdaptivePkPdEstimator.kt` | dest `Kernel` / `PkPdParams` / `InsulinActivityState`; `AtomicReference` / `AtomicLong` → `AapsLock`; `@JvmStatic` dropped; `kotlin.concurrent.Volatile` on ISF; `` `PkPdIntegration` `` backticks |
| `activity/ActivityManager.kt` | dest `ActivityContext` / `ActivityState`; `@Inject` kept (no `SingleIn`); `"%.1f".format` → `aimiFmt1` |
| `advisor/auditor/LocalSentinel.kt` | primitives only; `"%.nf".format` → `aimiFmt0` / `aimiFmt1` / `aimiFmt2`; `pkpdStage` stays `String?` |
| `utils/JsonSafeLogger.kt` | copied **before** `AimiLogger`; `formatUS` → `NumberFormat.withDecimals`; `Charsets.UTF_8` → `encodeToByteArray`/`decodeToString` |
| `utils/AimiLogger.kt` | `JsonSafeLogger.sanitizeForJson`; unused `formatUS` / `AtomicLong` dropped; `LTag.AIMI`; Metro kept (dump has `@Inject` / `SingleIn`); `measureTimeMillis` → `measureTimedValue` |
| `utils/ContextExtensions.kt` | dest `BgSnapshot` / `LoopContext`; `aimiFmt1`; `@JvmStatic` dropped; `ContextPlugin` is not the APS plugin |
| `KalmanFilter.kt` | dest `TddCalculator`; `AtomicBoolean` → `@Volatile` + `AapsLock`; `LTag.AIMI`; `Dispatchers.IO` → `aapsIoDispatcher` |
| `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt` | `key.lowercase()`; `java.util.Locale` dropped |
| `trajectory/TrajectoryHistoryProvider.kt` | dest `PhaseSpaceState` / `ActivityStage` / `InsulinWeibullCurve` / `TrajectoryBgDerivatives` + `IobCobCalculator.ads`; Metro kept; `LTag.AIMI`; `nowMillis` parameter kept |
| `therapy.kt` | dest `PersistenceLayer` / `TE`; `aimiWallClockMs`; `Pattern` → `Regex`; `TimeUnit` → `T.days` / `T.mins`; companion atomics → `AapsLock`; unused `Calendar` dropped; `Dispatchers.IO` → `aapsIoDispatcher` |
| `NightGrowthResistanceMonitor.kt` | dest `NightGrowthResistanceLearner` is the param helper (not copied); `java.time` → kotlinx.datetime; `aimiFmt1` / `aimiFmt2`; `AtomicReference` → `@Volatile var` |

No dest file was overwritten. Dest `autodrive/learning/` already had `AutodriveDatasetSchema.kt` / `OnlineLearner.kt` only. Dest `comparison/` already had KPI / scorer / `ComparisonData` / Lot R `VirtualGlucoseEngine`. Dest `pkpd/` already had `PkPdCore.kt`. Dest `activity/` already had `ActivityContext.kt` / `EffortActivityBelief.kt`. Dest `advisor/` had no `auditor/` and no `diag/`. Dest `utils/` already had `ValidationUtils.kt` only. Dest `trajectory/` already had models / guard / metrics. Dest had `NightGrowthResistanceLearner.kt` — no monitor. Dest had no `therapy.kt`. Dest had no `KalmanFilter.kt`.

Already in dest and **not** copied: Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `pkpd/PkPdCore.kt`, `activity/ActivityContext.kt`, `NightGrowthResistanceLearner.kt`, `trajectory/PhaseSpaceModels.kt` / `TrajectoryBgDerivatives.kt`, `pkpd/InsulinActionState.kt`, `model/Models.kt`, `utils/ValidationUtils.kt`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

None of the 13 Copy files already existed at dest. Zero dest-exists skips. No Skip file was copied to unblock compile.

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Do **not** copy `HyperSeverityClassifier` / `HyperTrajectoryMpcFeedForward` |
| `wcycle/*` adjusters | `android.*` + `File` (+ `org.json` on learner) |
| rest of `patient/*` | tree / orchestrator / `PatternCapKind` |
| meal / endogenous hysteresis | engines / classifier |
| remaining thermal clients | `HcRecoveryProxyThermalSource` (Health Connect / `java.time`); `OuraApiThermalClient` (`org.json` + `java.time`) |
| AutoDrive engine graph | `MpcController` needs dump `HyperTrajectoryMpcFeedForward`; `AutodriveEngine` needs MPC + File; `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake |
| remaining TPO | `TpoTriggerEngine` needs dump `PatientMode` |
| Lot R leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics`); `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation`); `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()`); dual-brain auditor helpers (dump `AuditorVerdict`) |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/AIMIPhysioManagerMTR.kt`, anything else with `android.*`, `File`, `org.json`, Compose, Android `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: kept `@Inject` / `AppScope` / `SingleIn` on `AutodriveAuditor`, `TrajectoryHistoryProvider`, and `AimiLogger` (dump has Metro). Kept `@Inject` on `ActivityManager` (dump has no `SingleIn`). `VirtualSmbState` unused Metro imports dropped. No Hilt. No `javax.inject`. No `@IntKey(225)`.
- Log: `LTag.APS` → `LTag.AIMI` on `AimiLogger`, `KalmanFilter`, `TrajectoryHistoryProvider`. No log calls added to files that do not log.
- Time: `therapy.kt` `refreshIfNeededAsync` / `buildSnapshot` / `getTimeElapsedSinceLastEvent` use `aimiWallClockMs()`. No `System.currentTimeMillis()` left. `VirtualIobCalculator` still uses `dateUtil.now()`. `AdaptivePkPdEstimator.update` still takes `epochMin`. `TrajectoryHistoryProvider.buildHistory` still takes `nowMillis`.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. `JsonSafeLogger.formatUS` → `NumberFormat.withDecimals(decimals).format(this.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)` (call-site name kept). `ContextExtensions.BgSnapshot.toShortString` → `aimiFmt1(delta5)`. NGR decay / active reason → `aimiFmt2` / `aimiFmt1`; persistence stays a Kotlin string `" (persistence %d×5')"`. `AimiDiagnosticsPrefExportPolicy`: `key.lowercase()`. `ActivityManager` / `LocalSentinel` `"%.nf".format` → `aimiFmt0` / `aimiFmt1` / `aimiFmt2` (needed for iOS). No `aimiFmt3`.
- `java.util.regex.Pattern` (`therapy.kt` `extractDateFromDeleteEvent`): `Pattern.compile("delete (\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE)` → `Regex(…, RegexOption.IGNORE_CASE)`. `find(note)` / `groupValues[1]`. Date after `delete ` kept.
- `java.util.concurrent.TimeUnit` (`therapy.kt`): `T.days(1).msecs()`, `T.mins(15).msecs()`, `T.mins(60).msecs()`. No `java.util.concurrent`.
- `@JvmStatic` (`AdaptivePkPdEstimator.IsfTddProvider.isfTdd` / `set`; `ContextExtensions` `ContextValidator` / `ContextSerializer` / `ContextPluginRegistry`): dropped. Functions kept. No `kotlin.jvm.JvmStatic`.
- `@Volatile` (`KalmanFilter` TDD cache; `AdaptivePkPdEstimator.IsfTddProvider.isf`; NGR `latestResult`): `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
- JVM-only collections / atomics / time:
  - `VirtualSmbState.pruneOldData`: collect matching items then `removeAll`. Prune behaviour kept.
  - `AdaptivePkPdEstimator`: one `AapsLock` around every read/write of `state`, `acceptedUpdateCount`, and the status snapshot. `incrementAndGet` → `count += 1` under the lock. `params` / `update` / `statusSnapshot` signatures kept. No `java.util.concurrent`.
  - `KalmanFilter.KalmanISFCalculator`: `AtomicBoolean` → `@Volatile var` plus in-flight guard under one `AapsLock`. `Dispatchers.IO` + `CoroutineScope` kept via `aapsIoDispatcher` (Native commonMain cannot see `Dispatchers.IO`).
  - `therapy.kt`: companion `AtomicReference` / `AtomicBoolean` → one `AapsLock` around snapshot + in-flight flag. `runBlocking` / async refresh kept. Unused `Calendar` dropped. `Dispatchers.IO` → `aapsIoDispatcher`.
  - `NightGrowthResistanceMonitor`: `java.time.Instant` / `LocalTime` / `Duration` / `ZoneId` → kotlinx.datetime. Night check: `time >= start && time <= end` (wrap: `time >= start || time <= end`). Duration minutes: epoch-ms `/ 60_000L`. `latestResultRef` → `@Volatile var`. No `java.time`. No `java.util.concurrent`.
- Extra commonMain fix (attempt 1): `Dispatchers.IO` is internal on Native commonMain → `aapsIoDispatcher` (same IO dispatcher on Android / iOS actuals). `AimiLogger.measure`: `measureTimeMillis` is deprecated-as-error and left `result` uninit on Native → `kotlin.time.measureTimedValue`. `JsonSafeLogger.isValidUtf8`: `Charsets.UTF_8` is JVM → `encodeToByteArray()` / `decodeToString()`.
- Explicit imports: `AutoDriveState` / `AutoDriveCommand` on `AutodriveAuditor`. No FQCN dest types in `ActivityManager` / `AdaptivePkPdEstimator`. `ContextPlugin` stays that name; `OpenAPSAIMIPlugin` not imported. `AimiLogger` unused `formatUS` / `AtomicLong` dropped.
- KDoc: `` `PkPdIntegration` `` backticks. Dest TAP-G / `PkPdCore` / `AutoDriveModels` KDoc not retargeted.
- School English: no mass-translate of French dump comments (`AutodriveAuditor`, `KalmanFilter`, `LocalSentinel`).
- Strings / JSON / prefs: no `R.string`, `ResourceHelper`, or `org.json`. `AimiDiagnosticsPrefExportPolicy` stays string-key redaction.
- Therapy math unchanged except log / import / lock / format / clock / Regex / `T` / dispatcher rewrites above.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-S.log` | **BUILD FAILED in 22m 44s**. `Dispatchers.IO` internal on Native commonMain (`KalmanFilter`, `therapy.kt`); `measureTimeMillis` deprecated-as-error + uninit `result` (`AimiLogger`). |
| 2 | `/tmp/aimi-lot-S.log` | **BUILD SUCCESSFUL in 54s** (EXIT 0). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **13** / dest-exists skip **0**. Compile: attempt 2 **BUILD SUCCESSFUL** (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`). Recursive engine still blocked. NGR `Instant` is `kotlinx.datetime.Instant`. `ContextPlugin` is not the APS plugin. `TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.
