# Lot P — T1 peel: Lot O physio consumers + independent thyroid math

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `91b9ce0451` (Lot O)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot O skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; `wcycle/*` adjusters need File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. `PhysioContextMTR` / `PhysioMultipliersMTR` landed in Lot O; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: physio files whose types exist in dest **after Lot O** (cosine gate + MTR feature/baseline/context engine), plus independent Basedow thyroid math, plus in-memory `PhysioAggregator`. Cap ~15; this list is 11.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (11 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these eleven exist at dest (checked 2026-08-28). Dest has no `physio/gate/` and no `physio/thyroid/`.

Already in dest and **must not be copied**: Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `recursive/BeliefLeafId.kt`, `BeliefParadoxId.kt`, `RecursiveBeliefMemory.kt`, `WaveletBelief.kt`, `RbtExtendedSignals.kt`, `ChannelInterferenceOptimizer.kt`, `patient/PatientEventMemory.kt`, `BodyKineticsDigest.kt`, `AimiCascadeArbitrationArtifacts.kt`, `HarmoniaSensorTelemetry.kt`, `physio/MealAbsorptionPhase.kt`, `PhysiologicalPhase.kt`, `BehavioralRiskPolicy.kt`, `HormonalScenarioTerminalCap.kt`, `EndogenousBasalBridgePolicy.kt`, `thermal/ThermalBeliefDigest.kt`. Cosine-gate and thyroid **keys** already exist in `:core:keys` (`BooleanKey.AimiCosineGateEnabled`, `DoubleKey.AimiCosineGate*`, `IntKey.AimiCosineGateMaxPeakShift`, `BooleanKey.OApsAIMIThyroid*`, `StringKey.OApsAIMIThyroid*`). Do not add keys.

Dump scan on these 11: no `android.*`, `java.io.File`, `org.json`, Compose, `Activity`, plugin, or `PkPdIntegration`. `CosineTrajectoryGate` kernel name `ACTIVITY` and comment “Activity” are product names, not Android `Activity`. `PhysioAggregator` step/HR buffers are in-memory, not Health Connect.

**Name clash:** dest `wcycle.ThyroidStatus` (`EUTHYROID`, `HYPOTHYROID_TREATED`, `HASHIMOTO`, `THYROIDECTOMY`) is **not** the Basedow enum. Dump `physio.thyroid.ThyroidStatus` (`EUTHYROID`, `HYPER_MILD`, `HYPER_MODERATE`, `HYPER_SEVERE`, `NORMALIZING`, `UNKNOWN`) must stay in package `physio.thyroid`. Do **not** import `wcycle.ThyroidStatus` into these thyroid files.

| rel | why |
|---|---|
| `physio/gate/CosineTrajectoryGate.kt` | cosine-gate math; dest Lot O `GateInput` / `KernelType` / `TrajectoryKernelRef` / `PhysioModulation`; keys already in `:core:keys` |
| `physio/AIMIPhysioFeatureExtractorMTR.kt` | feature math; dest Lot O `RawPhysioDataMTR` / `PhysioFeaturesMTR` / `SleepDataMTR` / `HRVDataMTR` / `RHRDataMTR` |
| `physio/AIMIPhysioBaselineModelMTR.kt` | 7-day baseline; dest Lot O `PhysioFeaturesMTR` / `PhysioBaselineMTR` / `MetricBaselineMTR` |
| `physio/AIMIPhysioContextEngineMTR.kt` | state engine; dest Lot O `PhysioFeaturesMTR` / `PhysioBaselineMTR` / `PhysioContextMTR` / `PhysioStateMTR` |
| `physio/PhysioAggregator.kt` | in-memory 15m/60m step/HR windows; no dump-only types |
| `physio/thyroid/ThyroidModels.kt` | Basedow DTOs (`ThyroidInputs` / `ThyroidEffects` / enums); no dest types needed |
| `physio/thyroid/ThyroidEffectModel.kt` | multiplier math on `ThyroidStatus` |
| `physio/thyroid/ThyroidSafetyGates.kt` | normalizing-phase SMB/basal gates |
| `physio/thyroid/ThyroidStateEstimator.kt` | manual/auto hysteresis; `StateFlow` is KMP |
| `physio/thyroid/ThyroidPreferences.kt` | prefs DTO; BooleanKey / StringKey already in `:core:keys` |
| `physio/thyroid/ThyroidDiagnosticsLogger.kt` | log line builder; same thyroid DTOs |

Copy `ThyroidModels` **before** the other five thyroid files. Feature extractor / baseline / context engine / cosine gate / aggregator do not depend on each other (they share dest Lot O types only).

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

Lot O said this stays blocked. **Do not copy:**

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier` (Lot O landed the physio **DTOs**, not the classifier / pattern snapshot) |
| `recursive/RecursiveBeliefModels.kt` | `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump pattern / Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dump `HyperTrajectoryReleaseEvaluator` |
| `recursive/RecursiveBeliefAuthorityGate.kt` | classifier / UAM Compose / `PatientMode` / pattern snapshot |

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` still needs `PhysiologicalPhaseClassifier` even after `PhysioContextMTR`. `PhysiologicalPatternInput.phaseOutput` is `PhysiologicalPhaseClassifier.Output?`. Catalog / Id / Detector / Policy / Hysteresis / CapHold / Export hang off that. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). Adjusters need those. `WCycleEstimator`: `java.time.LocalDate`. `EndocrineAmplitudeGovernor`: `java.time.LocalTime.now()`. `EndometriosisAdjuster`: dump `AimiPhysioInputs` is dest after Lot O, but the adjuster still needs learner/File. `WCyclePreferences` already dest (Lot M). |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| `physio/thermal/ThermalBeliefEngine.kt` + `ThermalBaselineStore.kt` | dest has `ThermalDataWindowMTR` / `ThermalBeliefDigest`, but the store uses `java.util.Calendar` + `CopyOnWriteArrayList`. Next thermal lot, not this list. |
| copy-safe leftovers (not this wave) | `control/StraightLineTubeAdvisor.kt` (keys exist; `"%.nf".format` rewrite), `prediction/NaiveEventualBgSignGuard.kt` (dest `InsulinActivityStage`; `Math.round` → Kotlin), `AIMIAdaptiveBasal.kt` (`java.util.Locale`). Do not mix into this physio lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `KalmanFilter.kt` (`java.util.concurrent.atomic.AtomicBoolean` + async TDD), anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

Do **not** copy dest-already-present recursive / patient / physio types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `CosineTrajectoryGate`, `AIMIPhysioFeatureExtractorMTR`, `AIMIPhysioBaselineModelMTR`, `AIMIPhysioContextEngineMTR`, `PhysioAggregator`. Thyroid files have no `@Inject`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` on feature extractor, baseline model, and context engine. Prefer `LTag.AIMI`. Cosine gate and thyroid files do not call `aapsLogger` (keep the injected logger on the gate; do not add log calls).
3. **Time** — every `System.currentTimeMillis()` in this list must become `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`: `ThyroidInputs.timestampMs` default, `ThyroidPreferences.getCurrentInputs`, `PhysioFeaturesMTR.timestamp` in the extractor, baseline `now` / age log, context engine `PhysioContextMTR.timestamp`, all `PhysioAggregator` reads. No `System.currentTimeMillis()` left.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1` (etc.). Do **not** add `aimiFmt3`.
   - `CosineTrajectoryGate`: `"%.2f".format` → `aimiFmt2`, `"%.1f".format` → `aimiFmt1`.
   - Feature extractor / baseline: delete `Double.format(decimals)` (`"%.${decimals}f".format`). All call sites use `format(1)` → `aimiFmt1`.
   - `ThyroidDiagnosticsLogger`: `String.format(Locale.US, "…%.2f…")` → `aimiFmt2`. Drop the `java.util.Locale` import.
5. **`@Volatile`** (`AIMIPhysioBaselineModelMTR`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
6. **JVM-only collections / reflection** (will fail iOS):
   - `CosineTrajectoryGate.calculateWeights`: `EnumMap<KernelType, Double>(KernelType::class.java)` → `mutableMapOf<KernelType, Double>()` (then the same `forEach` fills). No `java.util.EnumMap`. No `KernelType::class.java`.
   - `CosineTrajectoryGate.compute` log guard: `kotlin.math.abs(...)` → `import kotlin.math.abs` and `abs(...)`. No fully qualified `kotlin.math.abs` at use site.
   - `AIMIPhysioBaselineModelMTR`: `java.util.concurrent.ConcurrentHashMap` → `mutableMapOf` plus `synchronized` on the instance (same lock for every history map). No `java.util.concurrent`.
   - `PhysioAggregator`: `java.util.LinkedList` → Kotlin `ArrayDeque`. Keep `removeFirst()` cleanup. No `java.util.LinkedList`.
   - `ThyroidPreferences.enumValue`: `java.lang.Enum.valueOf(T::class.java, name)` → `enumValues<T>().firstOrNull { it.name == raw } ?: default` (blank / unknown → default, same as Lot M `WCyclePreferences`). No `java.lang.Enum`.
7. **Explicit imports** — no fully qualified names at use site. Feature extractor / baseline / context engine share package `physio` with dest `PhysioFeaturesMTR` / `PhysioContextMTR` — do not write fully qualified dest type names. Thyroid files must use `physio.thyroid.ThyroidStatus`, never `wcycle.ThyroidStatus`.
8. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. Cosine-gate KDoc that mentions `TrajectoryGuard` may stay a link (dest class). Dump `[docs/…]` paths that are not Kotlin symbols → backticks.
9. **School English** — new or changed comments only. Do not mass-translate French dump comments.
10. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. Keep `Preferences` + typed keys on `CosineTrajectoryGate` and `ThyroidPreferences`. `TextResolver` is not needed here.

`SleepLiveDetector.Source.HEALTH_CONNECT` is already dest (Lot O). Do not touch it. Cosine-gate `KernelType.ACTIVITY` is T1 naming, not Android `Activity`. Keep it.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/TickContext/Models/adapters) to unblock Skip.
- Overwrite dest recursive / patient / physio types listed as already present. Do not overwrite Lot O files.
- Import `wcycle.ThyroidStatus` into `physio/thyroid/*`. Do not merge the two enums.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-P.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, `aimiFmt1` / `aimiFmt2`, `@Volatile`, `EnumMap` / `ConcurrentHashMap` / `LinkedList` / `java.lang.Enum`), compile result. State that the recursive engine is still blocked. State that `physio.thyroid.ThyroidStatus` is not `wcycle.ThyroidStatus`.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
