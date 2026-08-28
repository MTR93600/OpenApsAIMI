# Lot Q — T1 peel: Lot P leftovers + thermal engine rewrite + dest-type TAP-G / PKPD math

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8563bdc01c` (Lot P)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot P skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; `wcycle/*` adjusters need File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot P named copy-safe leftovers for a later wave: `StraightLineTubeAdvisor`, `NaiveEventualBgSignGuard`, `AIMIAdaptiveBasal`. Thermal engine was parked on `Calendar` / `CopyOnWriteArrayList` unless rewritten in the same lot.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot P landed cosine gate / MTR engines / thyroid; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: the three Lot P leftovers (types already dest), thermal belief + in-memory baseline/cache with a small teacher-style Calendar / concurrent rewrite, plus independent TAP-G / PKPD math whose types exist in dest **after Lot P**. Cap ~15; this list is 11.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (11 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these eleven exist at dest (checked 2026-08-28). Dest has no `control/` folder and no `prediction/NaiveEventualBgSignGuard.kt`. Dest has `physio/thermal/ThermalDataMTR.kt` / `ThermalBeliefDigest.kt` / `ThermalSourceTier.kt` / `ThermalDataOrigins.kt` only — no engine, store, or cache.

Already in dest and **must not be copied**: Lot P files (`physio/gate/CosineTrajectoryGate.kt`, `AIMIPhysioFeatureExtractorMTR.kt`, `AIMIPhysioBaselineModelMTR.kt`, `AIMIPhysioContextEngineMTR.kt`, `PhysioAggregator.kt`, `physio/thyroid/*`); Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `pkpd/InsulinWeibullCurve.kt`, `CleanPostBolusWindow.kt`, `TapPeakGovernor.kt`, `InsulinActionState.kt` (`ActivityStage`), `pkpd/PkPdCore.kt` (`InsulinActivityStage`), `wcycle/WCycleTypes.kt` (`CyclePhase`), `tpo/TpoModels.kt`, `advisor/tuning/TuningContextModels.kt`. Tube / adaptive-basal **keys** already exist in `:core:keys` (`BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled`, `DoubleKey.AimiTube*`, `DoubleKey.OApsAIMIAdaptiveBasalMaxScaling`). Do not add keys.

Dump scan on these 11: no `android.*`, `java.io.File`, `org.json`, Compose, `Activity`, plugin, or `PkPdIntegration`. `NaiveEventualBgSignGuard` KDoc mentions dump `DetermineBasalAIMI2.round` — comment only, not the tick. Thermal narrative strings say “Health Connect” — product text, not Health Connect APIs. `KernelType.ACTIVITY` is not in this list.

**Name clash:** dest `wcycle.CyclePhase` is the enum this thermal engine uses. dest `context.ContextIntent.CyclePhase` is a **different** nested type. Thermal files must import `app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase`. Do **not** import the ContextIntent nested enum.

| rel | why |
|---|---|
| `control/StraightLineTubeAdvisor.kt` | MPC-lite tube math; `DoubleKey.AimiTube*` already in `:core:keys` |
| `prediction/NaiveEventualBgSignGuard.kt` | negative-IOB eventual-BG guard; dest `InsulinActivityStage` |
| `AIMIAdaptiveBasal.kt` | plateau kicker / micro-resume; `DecimalFormatter` is `core:interfaces` commonMain |
| `physio/thermal/ThermalBaselineStore.kt` | nocturnal median baseline; dest `ThermalSampleMTR`. Rewrite `Calendar` + `CopyOnWriteArrayList` in this lot |
| `physio/thermal/ThermalDataCache.kt` | in-memory window; dest `ThermalDataWindowMTR`. Rewrite `AtomicReference` in this lot |
| `physio/thermal/ThermalBeliefEngine.kt` | thermal hypothesis math; dest `ThermalBeliefDigest` / `ThermalDataWindowMTR` / `CyclePhase` / this-lot store + cache |
| `physio/AIMIDecisionOrchestratorShadowMTR.kt` | shadow fusion only (no enact); dest Lot O `PhysioMultipliersMTR` / `InflammationLatentStateMTR` |
| `pkpd/TrajectoryPeakBias.kt` | TAP-G geometry nudge; dest Lot M `TrajectoryAnalysis` + dest `CleanPostBolusWindow` |
| `pkpd/TrajectoryPeakMismatchScorer.kt` | TAP-G history RSS nudge; dest `PhaseSpaceState` / `InsulinWeibullCurve` / this-lot `TrajectoryPeakBias` |
| `pkpd/InsulinActionProfiler.kt` | Weibull activity profile; dest `InsulinWeibullCurve` + `OapsProfileAimi` / `IobTotal` commonMain |
| `pkpd/RealTimeInsulinObserver.kt` | onset / stage observer; dest `InsulinActionState` / `ActivityStage` |

Copy thermal **store + cache before** `ThermalBeliefEngine` (engine calls both). Copy `TrajectoryPeakBias` **before** `TrajectoryPeakMismatchScorer`. Tube advisor / sign guard / adaptive basal / shadow / profiler / observer do not depend on each other (they share dest types only).

Dest `TapPeakGovernor` / `CleanPostBolusWindow` / `InsulinWeibullCurve` KDoc already mention `TrajectoryPeakBias` / `InsulinActionProfiler`. Do **not** edit those dest files to retarget links.

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

Lot P said this stays blocked. **Do not copy:**

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
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). Adjusters need those. `WCycleEstimator`: `java.time.LocalDate`. `EndocrineAmplitudeGovernor`: `java.time.LocalTime.now()`. `EndometriosisAdjuster`: dest `AimiPhysioInputs` after Lot O, but the adjuster still needs learner/File. `WCyclePreferences` already dest (Lot M). |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal clients | `HcRecoveryProxyThermalSource.kt`: Health Connect / `java.time` / clock. `OuraApiThermalClient.kt`: `org.json` + `java.time`. Not this list. |
| copy-safe leftovers (not this wave) | `tpo/TpoLadderSupport.kt` + `tpo/TpoDeltaBuilder.kt` (dest `TpoModels` / `TuningChange` / `PkpdSmbTailDamping`; keys exist). `TpoTriggerEngine` still needs dump `PatientMode`. `autodrive/models/AutoDriveModels.kt` is a dest-type DTO (`InsulinActionModel` / `SourceSensor`) but `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`. `advisor/oref/OrefReasonParser.kt` needs `java.util.regex.Pattern` → Kotlin `Regex` rewrite. Do not mix into this leftovers/thermal/TAP-G lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `KalmanFilter.kt` (`java.util.concurrent.atomic.AtomicBoolean` + async TDD), `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

Do **not** copy dest-already-present recursive / patient / physio / pkpd types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `StraightLineTubeAdvisor` and `AIMIAdaptiveBasal`. The other nine have no `@Inject`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` on the tube advisor and adaptive basal. Prefer `LTag.AIMI`. Do not add log calls to files that do not log.
3. **Time** — every `System.currentTimeMillis()` in this list must become `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`: `ThermalBeliefEngine.build` fallback `nowMs`, `InsulinActionProfiler.calculate` `now`. No `System.currentTimeMillis()` left. Do not add a `now` parameter to `calculate` — keep the dump signature.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0` (etc.). Do **not** add `aimiFmt3`.
   - `StraightLineTubeAdvisor`: `"%.0f".format` → `aimiFmt0`, `"%.1f".format` → `aimiFmt1`, `"%.2f".format` → `aimiFmt2`.
   - `AIMIAdaptiveBasal.pureSuggest`: delete `d0` / `d2` (`String.format(Locale.US, …)`). Call sites `d0` → `aimiFmt0`, `d2` → `aimiFmt2`. Injected `fmt.to0Decimal` / `fmt.to2Decimal` on `suggest` stay. Drop the unused `APSResult` import and the `java.util.Locale` import.
   - `ThermalBeliefEngine.buildNarrative`: `String.format(Locale.US, "%.1f", …)` → `aimiFmt1`. Drop `java.util.Locale`.
5. **`Math.round`** (`NaiveEventualBgSignGuard`) — `java.lang.Math` is JVM-only. `import kotlin.math.roundToLong` and `roundToLong(...)`. Same nearest-long behaviour as dump `Math.round(Double)`. KDoc `[DetermineBasalAIMI2.round]` cannot resolve — backticks.
6. **`@JvmStatic`** (`AIMIAdaptiveBasal.pureSuggest`) — drop the annotation. Keep the function. No `kotlin.jvm.JvmStatic`.
7. **`@Volatile`** (`ThermalDataCache`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
8. **JVM-only collections / calendar / atomics** (will fail iOS):
   - `ThermalBaselineStore`: `java.util.Calendar` + `Locale.US` → teacher SMB local hour: `Instant.fromEpochMilliseconds(sample.timestampMs).toLocalDateTime(TimeZone.currentSystemDefault()).hour` with explicit `import kotlinx.datetime.Instant`, `import kotlinx.datetime.TimeZone`, `import kotlinx.datetime.toLocalDateTime`. Keep nocturnal `hour in 2..5`. This is device local hour (dump `Calendar.getInstance(Locale.US)` uses the default zone; do **not** switch to UTC).
   - `ThermalBaselineStore`: `CopyOnWriteArrayList` → `mutableListOf<Double>()` plus one `AapsLock` (`import app.aaps.core.interfaces.concurrent.AapsLock`, `import app.aaps.core.interfaces.concurrent.withLock`) for every read/write of `nightlyMediansC` (same as Lot P baseline / aggregator). No `java.util.concurrent`. No `java.util.Calendar`.
   - `ThermalDataCache`: `AtomicReference` → `@Volatile private var window = ThermalDataWindowMTR()` with get/set. No `java.util.concurrent.atomic`.
9. **Explicit imports** — no fully qualified names at use site. Thermal files must use `wcycle.CyclePhase`, never `ContextIntent.CyclePhase`. `AIMIDecisionOrchestratorShadowMTR` shares package `physio` with dest `PhysioMultipliersMTR` / `InflammationLatentStateMTR` — do not write fully qualified dest type names. `AutoDriveModels` is **not** in this list; do not add `SourceSensor` FQCNs.
10. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. Dump `[docs/…]` paths that are not Kotlin symbols → backticks. Do not edit dest `TapPeakGovernor` / `CleanPostBolusWindow` / `InsulinWeibullCurve` KDoc.
11. **School English** — new or changed comments only. Do not mass-translate French dump comments (`AIMIAdaptiveBasal`, `RealTimeInsulinObserver`, `InsulinActionProfiler`).
12. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. Keep `Preferences` + typed keys on `StraightLineTubeAdvisor`. `TextResolver` is not needed here. Adaptive basal does not read `DoubleKey.OApsAIMIAdaptiveBasalMaxScaling` in the dump — do not invent a prefs read.

Thermal narrative “Health Connect” / “Oura API token” is T1 product text, not a client. Keep it.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/TickContext/Models/adapters, `HcRecoveryProxyThermalSource`, `OuraApiThermalClient`) to unblock Skip.
- Overwrite dest recursive / patient / physio / pkpd types listed as already present. Do not overwrite Lot O / Lot P files. Do not overwrite dest `ThermalDataMTR` / `ThermalBeliefDigest` / `InsulinWeibullCurve` / `CleanPostBolusWindow` / `TapPeakGovernor` / `InsulinActionState`.
- Import `ContextIntent.CyclePhase` into `physio/thermal/*`. Do not merge the two `CyclePhase` types.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-Q.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, `aimiFmt0` / `aimiFmt1` / `aimiFmt2`, `roundToLong`, drop `@JvmStatic`, `Calendar` → kotlinx datetime local hour, `CopyOnWriteArrayList` → `mutableListOf` + `AapsLock`, `AtomicReference` → `@Volatile`), compile result. State that the recursive engine is still blocked. State that thermal `CyclePhase` is `wcycle.CyclePhase`, not `ContextIntent.CyclePhase`.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
