# Lot Q — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8563bdc01c` (Lot P)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio / pkpd types already present were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot P landed cosine gate / MTR engines / thyroid; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

Thermal `CyclePhase` is `app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase`. It is **not** `ContextIntent.CyclePhase`. The two enums were not merged. Thermal files do not import the ContextIntent nested enum.

---

## Copied (11) — dest did not exist

| rel | notes |
|---|---|
| `control/StraightLineTubeAdvisor.kt` | Metro kept; `LTag.AIMI`; `aimiFmt0` / `aimiFmt1` / `aimiFmt2`; `DoubleKey.AimiTube*` already in `:core:keys` |
| `prediction/NaiveEventualBgSignGuard.kt` | dest `InsulinActivityStage`; `Math.round` → `Double.roundToLong()`; KDoc `DetermineBasalAIMI2.round` in backticks |
| `AIMIAdaptiveBasal.kt` | Metro kept; `LTag.AIMI`; dropped `@JvmStatic`, unused `APSResult`, `java.util.Locale`; `pureSuggest` uses `aimiFmt0` / `aimiFmt2`; injected `fmt.to0Decimal` / `fmt.to2Decimal` on `suggest` stay |
| `physio/thermal/ThermalBaselineStore.kt` | dest `ThermalSampleMTR`; `Calendar` → kotlinx-datetime local hour; `CopyOnWriteArrayList` → `mutableListOf` + `AapsLock` |
| `physio/thermal/ThermalDataCache.kt` | dest `ThermalDataWindowMTR`; `AtomicReference` → `@Volatile` (`kotlin.concurrent.Volatile`) |
| `physio/thermal/ThermalBeliefEngine.kt` | dest `ThermalBeliefDigest` / `ThermalDataWindowMTR` / `wcycle.CyclePhase`; `aimiWallClockMs`; `aimiFmt1`; Health Connect / Oura narrative kept as product text |
| `physio/AIMIDecisionOrchestratorShadowMTR.kt` | dest Lot O `PhysioMultipliersMTR` / `InflammationLatentStateMTR`; same-package short names |
| `pkpd/TrajectoryPeakBias.kt` | dest Lot M `TrajectoryAnalysis` + dest `CleanPostBolusWindow`; dump `@see docs/…` → backticks |
| `pkpd/TrajectoryPeakMismatchScorer.kt` | dest `PhaseSpaceState` / `InsulinWeibullCurve` / this-lot `TrajectoryPeakBias` |
| `pkpd/InsulinActionProfiler.kt` | dest `InsulinWeibullCurve` + `OapsProfileAimi` / `IobTotal`; `aimiWallClockMs`; `calculate` signature unchanged |
| `pkpd/RealTimeInsulinObserver.kt` | dest `InsulinActionState` / `ActivityStage`; `"%.2f".format` → `aimiFmt2` |

No dest file was overwritten. Dest had no `control/` folder and no `prediction/NaiveEventualBgSignGuard.kt`. Dest thermal folder already had `ThermalDataMTR` / `ThermalBeliefDigest` / `ThermalSourceTier` / `ThermalDataOrigins` only — no engine, store, or cache.

Already in dest and **not** copied: Lot P files (`physio/gate/CosineTrajectoryGate.kt`, `AIMIPhysioFeatureExtractorMTR.kt`, `AIMIPhysioBaselineModelMTR.kt`, `AIMIPhysioContextEngineMTR.kt`, `PhysioAggregator.kt`, `physio/thyroid/*`); Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `pkpd/InsulinWeibullCurve.kt`, `CleanPostBolusWindow.kt`, `TapPeakGovernor.kt`, `InsulinActionState.kt`, `pkpd/PkPdCore.kt`, `wcycle/WCycleTypes.kt`, `tpo/TpoModels.kt`, `advisor/tuning/TuningContextModels.kt`. Dest `TapPeakGovernor` / `CleanPostBolusWindow` / `InsulinWeibullCurve` KDoc were **not** edited. Tube / adaptive-basal keys already in `:core:keys`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority` + UAM Compose) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

None of the 11 Copy files already existed at dest. Zero dest-exists skips.

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
| copy-safe leftovers (not this wave) | `tpo/TpoLadderSupport.kt` + `tpo/TpoDeltaBuilder.kt`; `autodrive/models/AutoDriveModels.kt`; `advisor/oref/OrefReasonParser.kt` (`java.util.regex.Pattern` → Kotlin `Regex`) |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/AIMIPhysioManagerMTR.kt`, `KalmanFilter.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: kept `@Inject` / `AppScope` / `SingleIn` on `StraightLineTubeAdvisor` and `AIMIAdaptiveBasal`. The other nine have no `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`.
- Log: `LTag.APS` → `LTag.AIMI` on the tube advisor and adaptive basal. No log calls added to files that do not log.
- Time: `ThermalBeliefEngine.build` fallback `nowMs` and `InsulinActionProfiler.calculate` `now` use `aimiWallClockMs()`. No `System.currentTimeMillis()` left. `calculate` signature unchanged (no new `now` parameter).
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. Tube advisor `"%.0f"` → `aimiFmt0`, `"%.1f"` → `aimiFmt1`, `"%.2f"` → `aimiFmt2`. Adaptive basal `pureSuggest` deleted `d0` / `d2`; call sites → `aimiFmt0` / `aimiFmt2`. Thermal narrative `String.format(Locale.US, "%.1f", …)` → `aimiFmt1`. Observer debug reason `"%.2f".format` → `aimiFmt2`. No `aimiFmt3`.
- `Math.round` (`NaiveEventualBgSignGuard`): `import kotlin.math.roundToLong`; call sites use the Double extension `(…).roundToLong()` (same nearest-long behaviour as dump `Math.round(Double)`). Attempt 1 used `roundToLong(x)` which is not valid for a Kotlin extension.
- `@JvmStatic` (`AIMIAdaptiveBasal.pureSuggest`): dropped. Function kept. No `kotlin.jvm.JvmStatic`.
- `@Volatile` (`ThermalDataCache`): `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`. `AtomicReference` → `@Volatile private var window`.
- Calendar / collections: `ThermalBaselineStore` local hour is `Instant.fromEpochMilliseconds(sample.timestampMs).toLocalDateTime(TimeZone.currentSystemDefault()).hour` (device local, not UTC). Nocturnal `hour in 2..5` kept. `CopyOnWriteArrayList` → `mutableListOf<Double>()` plus one `AapsLock` for every read/write of `nightlyMediansC` (`withLock`, same as Lot P baseline / aggregator). No `java.util.concurrent`. No `java.util.Calendar`. Kotlin `synchronized` not used.
- Explicit imports: `aimiWallClockMs` / `aimiFmt0` / `aimiFmt1` / `aimiFmt2` / `roundToLong`. Thermal files use `wcycle.CyclePhase`, never `ContextIntent.CyclePhase`. Shadow uses same-package `PhysioMultipliersMTR` / `InflammationLatentStateMTR`. `AutoDriveModels` was not copied.
- KDoc: `DetermineBasalAIMI2.round` and dump `docs/research/TAP_G_PEAK_GOVERNOR_RFC.md` are backticks. Dest TAP-G KDoc links were not retargeted.
- School English: no mass-translate of French dump comments (`AIMIAdaptiveBasal`, `RealTimeInsulinObserver`, `InsulinActionProfiler`).
- Strings / prefs: no `R.string`, `ResourceHelper`, or `org.json`. Tube advisor keeps `Preferences` + typed keys. Adaptive basal does not invent a read of `DoubleKey.OApsAIMIAdaptiveBasalMaxScaling`.
- Thermal narrative “Health Connect” / “Oura API token” is T1 product text, not a client. Kept.
- Therapy math unchanged except log string formatting, KMP clock, collection/lock types, and `roundToLong` call syntax.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-Q.log` | **BUILD FAILED in 20s**. `NaiveEventualBgSignGuard`: `roundToLong(x)` is not valid; Kotlin needs `(x).roundToLong()`. |
| 2 | `/tmp/aimi-lot-Q.log` | **BUILD SUCCESSFUL in 50s** (EXIT 0). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **11** / dest-exists skip **0**. Compile: attempt 2 **BUILD SUCCESSFUL** (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`). Recursive engine still blocked.
