# Lot R — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f94b504ebb` (Lot Q)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio / pkpd / tpo types already present were **not** overwritten. Dest `InsulinActionModel` / `ContextIntent` / `TpoModels` / `TuningContextModels` / `AIMIAdaptiveBasal` / `PkpdSmbTailDamping` KDoc were **not** edited.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot Q landed tube advisor / thermal engine / TAP-G PKPD math; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

Context `Activity` is `ContextIntent.Activity` (user intent), **not** Android `android.app.Activity`. Context files import `app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent`. They do **not** import `model.ContextIntent`. The two `ContextIntent` types were not merged.

Thermal `CyclePhase` remains `app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase`. It is **not** `ContextIntent.CyclePhase`. This lot did not touch thermal files.

---

## Copied (15) — dest did not exist

| rel | notes |
|---|---|
| `tpo/TpoLadderSupport.kt` | dest `PkpdSmbTailDamping`; keys already in `:core:keys` |
| `tpo/TpoDeltaBuilder.kt` | dest `TpoProposal` / `TpoApplyPlan` / `TuningChange`; unused `kotlin.math.roundToInt` dropped |
| `tpo/TpoPreferenceKeys.kt` | whitelist of existing `DoubleKey` / `BooleanKey` |
| `advisor/oref/OrefReasonParser.kt` | `Pattern` → `Regex` + `RegexOption.IGNORE_CASE`; KDoc `RT.reason` in backticks |
| `advisor/oref/OrefAnalysisReport.kt` | `aimiFmt0` / `aimiFmt1` / `aimiFmt2`; ONNX / assets notes kept as T1 product text |
| `autodrive/models/AutoDriveModels.kt` | dest `InsulinActionModel` + `SourceSensor`; dropped `@JvmStatic`; KDoc `HyperSeverityTier.ordinal` in backticks |
| `autodrive/safety/ControlBarrierShield.kt` | Metro kept; `LTag.AIMI`; `NumberFormat.withDecimals` helper; `` `PkPdIntegration` `` stays backticks |
| `autodrive/estimator/ContinuousStateEstimator.kt` | Metro kept; `LTag.AIMI`; `nowMs` default `aimiWallClockMs()`; `SourceSensor` import; `tickId` is Autodrive interval id |
| `autodrive/learning/OnlineLearner.kt` | Metro kept; `LTag.AIMI`; `AapsLock`; `aimiFmt2`; `kotlin.math.max` / `abs` |
| `basal/BasalHistoryUtils.kt` | dest `TB`; `nowProvider` default `aimiWallClockMs()`; `kotlin.concurrent.Volatile` |
| `basal/BasalPlanner.kt` | Metro kept; dest Lot Q `AIMIAdaptiveBasal` + dest `BasalPlan` / `LoopContext`; `aimiFmt1` / `aimiFmt2`; logger injected, no log calls added |
| `context/ContextIntentDeserializer.kt` | dest `ContextIntent`; `aimiWallClockMs` kept; `LTag.AIMI`; K2 elvis/`toFloat` parens |
| `context/ContextParser.kt` | Metro kept; `LTag.AIMI`; `parse` / `parsePreset` use `aimiWallClockMs()` |
| `context/ContextInfluenceEngine.kt` | Metro kept; `LTag.AIMI`; `ContextMode` lives here; `NumberFormat.withDecimals` helpers + `aimiFmt1` for ceiling text |
| `comparison/VirtualGlucoseEngine.kt` | Metro kept; dest `OapsProfile` only; `tickMinutes` is sim interval id |

No dest file was overwritten. Dest had no `autodrive/models/`, `autodrive/safety/`, or `autodrive/estimator/`. Dest `autodrive/learning/` already had `AutodriveDatasetSchema.kt` only. Dest `tpo/` already had `TpoModels.kt` / `TpoEpisodeLedger.kt` only. Dest `advisor/oref/` already had calibrator / features / gate / outcome / reason-suffix only. Dest `basal/` had no `BasalPlanner` / `BasalHistoryUtils`. Dest `context/` already had `ContextIntent.kt` only. Dest `comparison/` already had KPI / scorer / `ComparisonData` only.

Already in dest and **not** copied: Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); Lot P files (`physio/gate/CosineTrajectoryGate.kt`, `AIMIPhysioFeatureExtractorMTR.kt`, `AIMIPhysioBaselineModelMTR.kt`, `AIMIPhysioContextEngineMTR.kt`, `PhysioAggregator.kt`, `physio/thyroid/*`); Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `tpo/TpoModels.kt`, `advisor/tuning/TuningContextModels.kt`, `pkpd/PkpdSmbTailDamping.kt`, `autodrive/InsulinActionModel.kt`, `context/ContextIntent.kt`, `model/Models.kt`, `release/HyperSeverityTier.kt`, `wcycle/WCycleTypes.kt`. TPO / tube / tail-damping keys already in `:core:keys`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

None of the 15 Copy files already existed at dest. Zero dest-exists skips.

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot` |
| `wcycle/*` adjusters | `android.*` + `File` (+ `org.json` on learner) |
| rest of `patient/*` | tree / orchestrator / `PatternCapKind` |
| meal / endogenous hysteresis | engines / classifier |
| remaining thermal clients | `HcRecoveryProxyThermalSource` (Health Connect / `java.time`); `OuraApiThermalClient` (`org.json` + `java.time`) |
| AutoDrive engine graph | `MpcController` needs dump `HyperTrajectoryMpcFeedForward`; `AutodriveEngine` needs MPC + File |
| remaining TPO | `TpoTriggerEngine` needs dump `PatientMode` |
| copy-safe leftovers (not this wave) | `advisor/tuning/TuningContextEngine.kt`; `pkpd/AdvancedPredictionEngine.kt`; `comparison/VirtualSmbState.kt`; `AutodriveDatasetLock`; `KalmanFilter`; `hormonitor/viewer/HormonitorLabels.kt` |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/AIMIPhysioManagerMTR.kt`, `KalmanFilter.kt`, anything else with `android.*`, `File`, `org.json`, Compose, Android `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: kept `@Inject` / `AppScope` / `SingleIn` on `ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `BasalPlanner`, `ContextParser`, `ContextInfluenceEngine`, `VirtualGlucoseEngine`. The other eight have no `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`.
- Log: `LTag.APS` → `LTag.AIMI` on CBF, PSE, learner, context parser / deserializer / influence. No log calls added to files that do not log. `BasalPlanner` injects `AAPSLogger` and does not log — left as dump.
- Time: `ContinuousStateEstimator.updateAndPredict` default `nowMs` is `aimiWallClockMs()`. `ContextParser.parse` / `parsePreset` local `now` is `aimiWallClockMs()`. `BasalHistoryUtils.FetcherProvider` default `nowProvider` is `{ aimiWallClockMs() }`. `nowMs` / `nowProvider` parameters kept. No `System.currentTimeMillis()` left in code (`ContinuousStateEstimator` KDoc still mentions it in a French dump comment). `OnlineLearner.learnAndUpdate` already takes `currentEpochMs` — no wall-clock call added. `ContextIntentDeserializer` already used `aimiWallClockMs` — kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. `OrefAnalysisReport` `"%.0f"` → `aimiFmt0`, `"%.1f"` → `aimiFmt1`, `"%.2f"` → `aimiFmt2`. `BasalPlanner` deleted `fmt1` / `fmt2`; call sites → `aimiFmt1` / `aimiFmt2`. `OnlineLearner` `"%.2f".format(currentState.bgVelocity)` → `aimiFmt2`. `ContextInfluenceEngine` `"%.1fU".format(it)` → `"${aimiFmt1(it)}U"`. Local `Double.format(digits)` / `Float.format(decimals)` helpers (CBF, PSE, learner, influence) use `NumberFormat.withDecimals(digits).format(this.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)`. Call sites stay `x.format(2)` / `x.format(3)`. No `aimiFmt3`.
- `java.util.regex.Pattern` (`OrefReasonParser`): `Pattern.compile(…, Pattern.CASE_INSENSITIVE)` → `Regex(…, RegexOption.IGNORE_CASE)`. `matcher(reason).find()` / `group(1)` → `find(reason)` / `groupValues[1]`. No `java.util.regex.Pattern`. Parse behaviour kept (one number after `Target:`, EU comma decimals).
- `Math.max` / `Math.abs` (`OnlineLearner`): `import kotlin.math.abs` and `import kotlin.math.max`. Call sites `max` / `abs`.
- `@JvmStatic` (`AutoDriveModels.createSafe`): dropped. Function kept. No `kotlin.jvm.JvmStatic`.
- `@Volatile` (`BasalHistoryUtils._provider`): `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
- JVM-only collections / atomics (`OnlineLearner`): `AtomicLong` / `AtomicReference` → one `AapsLock` around every read/write of counters, `predictionHistory`, and the status snapshot (`withLock`, same as Lot Q thermal store). `incrementAndGet` → `count += 1` under the lock. `statusRef.get()` / `set` → `var snapshot`. No `java.util.concurrent`. `removeIf` → collect stale keys then `remove`. `learnAndUpdate` / `statusSnapshot` signatures kept. Kotlin `synchronized` not used.
- Explicit imports: `SourceSensor` on `AutoDriveModels` (never FQN at the property). Context files import `context.ContextIntent` (nested `Activity` / `Illness` / `Stress` / `HypoRecovery` stay context nested types). No star-import of `model.ContextIntent`. `TpoDeltaBuilder` unused `kotlin.math.roundToInt` dropped. `AIMIDecisionOrchestratorShadowMTR` not in this list.
- KDoc: `HyperSeverityTier.ordinal` and dump `RT.reason` are backticks. `` `PkPdIntegration` `` stays backticks. Dest TAP-G / `InsulinActionModel` / `ContextIntent` KDoc were not retargeted.
- School English: no mass-translate of French dump comments (`ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `BasalPlanner`, `ContextParser`).
- Strings / prefs: no `R.string`, `ResourceHelper`, or `org.json`. TPO keeps `Preferences` + typed keys. `ContextIntentDeserializer` keeps `OrgJsonCompat` + kotlinx.serialization.
- Extra commonMain fix (attempt 1): dump `doubleOrNull ?: error(…).toFloat()` is K2-ambiguous (`Double? ?: Float` → `Number`). Wrapped as `(doubleOrNull ?: error(…)).toFloat()` so `confidence` / `units` stay `Float`. Same parse behaviour.
- Therapy math unchanged except log string formatting, KMP clock, collection/lock types, Regex parse, and the `toFloat` parens.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-R.log` | **BUILD FAILED in 20s**. `ContextIntentDeserializer`: dump `doubleOrNull ?: error(…).toFloat()` is not a `Float` on K2 commonMain. |
| 2 | `/tmp/aimi-lot-R.log` | **BUILD SUCCESSFUL in 50s** (EXIT 0). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Review follow-up (before commit)

Review was **APPROVE_WITH_CONCERNS**. Important I-1 applied: unused `import app.aaps.core.interfaces.aps.OapsProfile` removed from `VirtualGlucoseEngine.kt`. No recompile (import-only deletion).

---

## Return

**DONE** — copied **15** / dest-exists skip **0**. Compile: attempt 2 **BUILD SUCCESSFUL** (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`). Recursive engine still blocked.
