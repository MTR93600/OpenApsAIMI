# Lot S — T1 peel: Lot R leftovers + dest-type AutoDrive auditor / NGR / PKPD learner / activity / utils

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `17d159b5b1` (Lot R)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot R skip notes: `physio/pattern/*` still needs `PhysiologicalPhaseClassifier`; remaining `release/*` needs `DoseTerminalSnapshot`; `wcycle/*` adjusters need File/android; rest of `patient/*` needs tree/orchestrator; meal/endogenous hysteresis need engines/classifier. Recursive **engine** still blocked. Lot R named copy-safe leftovers for a later wave: `VirtualSmbState` (`List.removeIf`), `KalmanFilter` (`AtomicBoolean` + async TDD). `TuningContextEngine` still needs dump `AdvisorMetrics`. `AdvancedPredictionEngine` still needs dump `PredictionPhysioModulation`. `AutodriveDatasetLock` stays `ReentrantLock` / T2 file lake. `HormonitorLabels` still needs device locale. `TpoTriggerEngine` still needs dump `PatientMode`. `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

**Compose-graph wall (say this in the report):** the recursive **engine** is not copy-safe yet. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot R landed TPO ladder / AutoDrive CBF-PSE-learner / basal planner / context parser / OREF report / virtual-glucose; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind` in `PhysiologicalPatternModels`, which still needs `PhysiologicalPhaseClassifier`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). Do **not** pull that graph. Do **not** copy `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / engine / adapters.

This lot is the next copy-safe set: Lot R leftovers whose types already exist in dest, plus dest-type files that Lot R unblocked (`AutodriveAuditor` on dest `AutoDriveState` / `AutoDriveCommand`), dest NGR monitor (dest `NightGrowthResistanceLearner` already exists), dest-type PKPD learner / activity manager / TAP-G history / therapy clocks / auditor sentinel / utils. Cap ~15; this list is 13.

The 5 remaining Lot L skips still need Compose or dump graphs. **Do not copy them.** `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/adapters, `PhysiologicalTree`, runtime patient repos — not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (13 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these thirteen exist at dest (checked 2026-08-28, HEAD `17d159b5b1`). Dest `autodrive/learning/` has `AutodriveDatasetSchema.kt` / `OnlineLearner.kt` only — no auditor. Dest `comparison/` has KPI / scorer / `ComparisonData` / Lot R `VirtualGlucoseEngine` — no `VirtualSmbState`. Dest `pkpd/` has `PkPdCore.kt` (`Kernel` / `PkPdParams` / `InsulinActivityState`) — no `AdaptivePkPdEstimator`. Dest `activity/` has `ActivityContext.kt` / `EffortActivityBelief.kt` — no `ActivityManager`. Dest `advisor/` has no `auditor/` and no `diag/`. Dest `utils/` has `ValidationUtils.kt` only. Dest `trajectory/` has models / guard / metrics — no history provider. Dest has `NightGrowthResistanceLearner.kt` — no monitor. Dest has no `therapy.kt`. Dest has no `KalmanFilter.kt`.

Already in dest and **must not be copied**: Lot R files (`tpo/TpoLadderSupport.kt` / `TpoDeltaBuilder.kt` / `TpoPreferenceKeys.kt`, `advisor/oref/OrefReasonParser.kt` / `OrefAnalysisReport.kt`, `autodrive/models/AutoDriveModels.kt`, `autodrive/safety/ControlBarrierShield.kt`, `autodrive/estimator/ContinuousStateEstimator.kt`, `autodrive/learning/OnlineLearner.kt`, `basal/BasalHistoryUtils.kt` / `BasalPlanner.kt`, `context/ContextInfluenceEngine.kt` / `ContextIntentDeserializer.kt` / `ContextParser.kt`, `comparison/VirtualGlucoseEngine.kt`); Lot Q files (`control/StraightLineTubeAdvisor.kt`, `prediction/NaiveEventualBgSignGuard.kt`, `AIMIAdaptiveBasal.kt`, `physio/thermal/ThermalBaselineStore.kt` / `ThermalDataCache.kt` / `ThermalBeliefEngine.kt`, `physio/AIMIDecisionOrchestratorShadowMTR.kt`, `pkpd/TrajectoryPeakBias.kt` / `TrajectoryPeakMismatchScorer.kt` / `InsulinActionProfiler.kt` / `RealTimeInsulinObserver.kt`); also dest `pkpd/PkPdCore.kt`, `activity/ActivityContext.kt`, `NightGrowthResistanceLearner.kt`, `trajectory/PhaseSpaceModels.kt` / `TrajectoryBgDerivatives.kt`, `pkpd/InsulinActionState.kt` (`ActivityStage`), `model/Models.kt` (`BgSnapshot` / `LoopContext` / `BasalPlan` / `SmbPlan`), `utils/ValidationUtils.kt`. Do not add keys.

Dump scan on these 13: no `android.*`, `java.io.File`, `org.json`, Compose, plugin, or `PkPdIntegration` **as a type**. `AdaptivePkPdEstimator` KDoc mentions `PkPdIntegration` — comment only; backticks. `ContextExtensions.ContextPlugin` is a dest `LoopContext` helper interface, **not** `OpenAPSAIMIPlugin`. `therapy.kt` meal-clock flags are **not** `DetermineBasalAIMI2`. `LocalSentinel` takes `pkpdStage` as `String?`. `ActivityManager` returns dest `ActivityContext`. `TrajectoryHistoryProvider` `pkpdStage` is dest `ActivityStage`. NGR `Instant` / `LocalTime` in this lot are **kotlinx.datetime**, not `java.time`.

| rel | why |
|---|---|
| `autodrive/learning/AutodriveAuditor.kt` | Lot R dest `AutoDriveState` / `AutoDriveCommand`. Rewrite FQCN imports |
| `comparison/VirtualSmbState.kt` | virtual IOB reservoir; dest `BS` / `TB` / `RT` / `OapsProfile`. Rewrite `removeIf`. Drop unused Metro imports |
| `pkpd/AdaptivePkPdEstimator.kt` | online DIA/peak learner; dest `Kernel` / `PkPdParams` / `InsulinActivityState`. Rewrite atomics in this lot |
| `activity/ActivityManager.kt` | steps/HR scoring; dest `ActivityContext` / `ActivityState`. Keep `@Inject` (no `SingleIn` in dump) |
| `advisor/auditor/LocalSentinel.kt` | dual-brain sentinel math; primitives only (no dump `AuditorVerdict`) |
| `utils/JsonSafeLogger.kt` | JSON-safe number/string helpers. Rewrite format in this lot |
| `utils/AimiLogger.kt` | structured AIMI log DSL; this-lot `JsonSafeLogger.sanitizeForJson`. Drop unused `AtomicLong` / `formatUS` imports |
| `utils/ContextExtensions.kt` | dest `BgSnapshot` / `LoopContext` helpers. Rewrite format + drop `@JvmStatic` |
| `KalmanFilter.kt` | ISF Kalman + dest `TddCalculator`. Rewrite `AtomicBoolean` in this lot |
| `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt` | secret-key redaction. Rewrite `Locale.US` lowercase |
| `trajectory/TrajectoryHistoryProvider.kt` | TAP-G history; dest `PhaseSpaceState` / `ActivityStage` / `InsulinWeibullCurve` / `TrajectoryBgDerivatives` + commonMain `IobCobCalculator.ads` |
| `therapy.kt` | note-event meal clocks; dest `PersistenceLayer` / `TE`. Rewrite clock + atomics + `Pattern` + `TimeUnit` in this lot. Drop unused `Calendar` import |
| `NightGrowthResistanceMonitor.kt` | NGR evaluate; dest `NightGrowthResistanceLearner` is the param helper. Rewrite `java.time` + format + atomics in this lot |

Copy `JsonSafeLogger` **before** `AimiLogger`. The other eleven do not depend on each other (they share dest types only).

Do **not** edit dest `AutoDriveModels` / `PkPdCore` / `ActivityContext` / `NightGrowthResistanceLearner` / `PhaseSpaceModels` / `InsulinActionState` / `Models` KDoc to retarget links.

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

Lot R said this stays blocked. **Do not copy:**

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
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. Do **not** copy `HyperSeverityClassifier` (calls dump `HyperTrajectoryHypoCredibility.highBgBandMgdl`). Do **not** copy `HyperTrajectoryMpcFeedForward` (needs classifier `Output`). |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). Adjusters need those. `WCycleEstimator`: `java.time.LocalDate`. `EndocrineAmplitudeGovernor`: `java.time.LocalTime.now()`. `EndometriosisAdjuster` still needs learner/File. `WCyclePreferences` already dest (Lot M). |
| rest of `patient/*` | `PhysiologicalTree` builder needs `PatientModeOrchestrator` / `PatientStateSnapshot`. `MealCertainty.fromTreeAndEnvironment` needs the tree. `HarmoniaDecision` / `HarmoniaSmbAuthorityDecision` need tree / `PatternCapKind`. `PatientEventMemoryCalculator` needs dump `PhysioLatentState`. `CausalStatePosterior` needs meal-phase engine + UAM + pattern snapshot. Runtime repos stay parked. `PatientStatePresentation` needs dump snapshot / mode / tree / Harmonia. |
| `MealAbsorptionMemory.kt` / `MealAbsorptionPhaseHysteresis.kt` | `MealAbsorptionPhaseEngine.Output` |
| `EndogenousPhaseHysteresis.kt` / `EndogenousCounterRegulatoryDetector.kt` / `PhysioPhaseFusion.kt` | `PhysiologicalPhaseClassifier` |
| remaining thermal clients | `HcRecoveryProxyThermalSource.kt`: Health Connect / `java.time` / clock. `OuraApiThermalClient.kt`: `org.json` + `java.time`. Not this list. |
| AutoDrive engine graph | `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`. `AutodriveEngine` needs MPC + `AutodriveDataLake` / File + `System.currentTimeMillis`. `AutoDriveGater` needs dump `HealthContextRepository`. `MechanismAttentionGate`: `org.json` + `AimiStorageHelper`. `PhysiologicalStressMaskBuilder` needs classifier / pattern snapshot. `AutodriveDatasetLock` is a file lock with `ReentrantLock.tryLock` (T2 data lake). Do **not** pull that graph. |
| remaining TPO | `TpoTriggerEngine` still needs dump `PatientMode`. `TpoUiSupport`: `R.string`. `TpoLlmValidator` / session / orchestrator / persistence / notification: clock / Locale / advisor history / Compose. Not this list. |
| Lot R leftovers still blocked | `advisor/tuning/TuningContextEngine.kt` (dump `AdvisorMetrics` in `AdvisorModels`, which needs `HarmoniaDecision`). `pkpd/AdvancedPredictionEngine.kt` (`PredictionPhysioModulation` — UAM / classifier / `PkPdRuntime`). `hormonitor/viewer/HormonitorLabels.kt` (`Locale.getDefault()` language; no commonMain locale without `iosMain`). `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` (dump `AuditorVerdict` in `AuditorDataStructures`, which needs Harmonia / tree). Do not mix into this leftovers lot. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt` (Health Connect fetch enum), `physio/AIMIPhysioManagerMTR.kt` (`android.content.Context`), `pkpd/CausalKineticsModulator.kt` / `PkpdLearningDiagnostics.kt` / `InsulinKineticsAuthority.kt` (`CausalStatePosterior`), `pkpd/PredictionPhysioModulation.kt` (classifier / UAM Compose / `PkPdRuntime`), `pkpd/PkpdSoftFloorPathMin.kt` (`DoseTerminalSnapshot`), `basal/BasalDecisionEngine.kt` (`android.content.Context` + `R.string`), `comparison/AimiSmbComparator.kt` (`android.Context` + `File`), `comparison/AimiSmbSimulator.kt` (tick), `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` (tick lock), anything else with `android.*`, `File`, `org.json`, Compose, `Activity` (Android class), tick, or plugin.

Do **not** copy dest-already-present recursive / patient / physio / pkpd / tpo / autodrive types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `AutodriveAuditor` and `TrajectoryHistoryProvider`. Keep `@Inject` on `ActivityManager` (dump has no `SingleIn`). `VirtualSmbState` imports Metro but has no `@Inject` — **drop** the unused `Inject` / `SingleIn` / `AppScope` imports. The other nine have no Metro. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` on `AimiLogger`, `KalmanFilter`, `TrajectoryHistoryProvider`. Prefer `LTag.AIMI`. Do not add log calls to files that do not log.
3. **Time** — every `System.currentTimeMillis()` in this list must become `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`:
   - `therapy.kt`: `refreshIfNeededAsync`, `buildSnapshot`, `getTimeElapsedSinceLastEvent`.
   No `System.currentTimeMillis()` left. `VirtualIobCalculator` already uses `dateUtil.now()` — keep it. `AdaptivePkPdEstimator.update` already takes `epochMin` — do not add a wall-clock call. `TrajectoryHistoryProvider.buildHistory` already takes `nowMillis` — keep the parameter.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0` (etc.). Do **not** add `aimiFmt3`. For local `formatUS(decimals)` / dynamic decimals, rewrite to `NumberFormat.withDecimals(digits).format(this.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)` with explicit `import app.aaps.core.data.format.NumberFormat` and `import app.aaps.core.data.format.NumberFormatPlatform`.
   - `JsonSafeLogger.formatUS`: rewrite both `Double` / `Float` helpers as above. Keep call-site name `formatUS(decimals)`.
   - `ContextExtensions.BgSnapshot.toShortString`: `"%.1f".format(delta5)` → `aimiFmt1(delta5)`.
   - `NightGrowthResistanceMonitor`: `"NGR decay: multipliers %.2f/%.2f, %d min remaining."` → `"NGR decay: multipliers ${aimiFmt2(smb)}/${aimiFmt2(basal)}, $minutesCeil min remaining."`. Persistence `" (persistence %d×5')"` stays a Kotlin string. Active reason `"NGR %s: rise %.1f mg/dL/5' for %d min, eventual +%d mg/dL%s."` → `"NGR $label: rise ${aimiFmt1(slope)} mg/dL/5' for $minutes min, eventual +$over mg/dL$persistence."`.
   - `AimiDiagnosticsPrefExportPolicy`: `key.lowercase(Locale.US)` → `key.lowercase()`. Drop `java.util.Locale`.
5. **`java.util.regex.Pattern`** (`therapy.kt` `extractDateFromDeleteEvent`) — `Pattern.compile("delete (\\d{2}/\\d{2}/\\d{4})", Pattern.CASE_INSENSITIVE)` → `Regex(…, RegexOption.IGNORE_CASE)`. `matcher.find()` / `group(1)` → `find(note)` / `groupValues[1]`. Drop `java.util.regex.Pattern`. Keep parse behaviour (date after `delete `).
6. **`java.util.concurrent.TimeUnit`** (`therapy.kt`) — `TimeUnit.DAYS.toMillis(1)` → `T.days(1).msecs()`, `TimeUnit.MINUTES.toMillis(15)` → `T.mins(15).msecs()`, `TimeUnit.MINUTES.toMillis(60)` → `T.mins(60).msecs()`, with `import app.aaps.core.data.time.T`. No `java.util.concurrent`.
7. **`@JvmStatic`** (`AdaptivePkPdEstimator.IsfTddProvider.isfTdd` / `set`; `ContextExtensions` `ContextValidator` / `ContextSerializer` / `ContextPluginRegistry`) — drop the annotation. Keep the functions. No `kotlin.jvm.JvmStatic`.
8. **`@Volatile`** (`KalmanFilter` TDD cache; `AdaptivePkPdEstimator.IsfTddProvider.isf`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
9. **JVM-only collections / atomics / time** (will fail iOS):
   - `VirtualSmbState.pruneOldData`: `removeIf` is JVM — rewrite to collect matching items then `removeAll`. Keep prune behaviour.
   - `AdaptivePkPdEstimator`: `AtomicReference` / `AtomicLong` → one `AapsLock` (`import app.aaps.core.interfaces.concurrent.AapsLock`, `import app.aaps.core.interfaces.concurrent.withLock`) around every read/write of `state`, `acceptedUpdateCount`, and the status snapshot (same as Lot R `OnlineLearner`). Replace `incrementAndGet` with `count += 1` under the lock. Replace `get()` / `set` with `var` fields. No `java.util.concurrent`. Keep `params` / `update` / `statusSnapshot` signatures.
   - `KalmanFilter.KalmanISFCalculator`: `AtomicBoolean` `tddRefreshInFlight` → `@Volatile var` plus the same in-flight guard under one `AapsLock` (or a simple `if (inFlight) return; inFlight = true` under the lock). Keep `Dispatchers.IO` + `CoroutineScope`. No `java.util.concurrent.atomic`.
   - `therapy.kt`: companion `AtomicReference` / `AtomicBoolean` → one `AapsLock` around snapshot + in-flight flag (same pattern). Keep `runBlocking(Dispatchers.IO)` / async refresh behaviour. Drop unused `java.util.Calendar` import.
   - `NightGrowthResistanceMonitor`: `java.time.Instant` / `LocalTime` / `Duration` / `ZoneId` → kotlinx.datetime (same device-local zone as Lot Q thermal store): `import kotlinx.datetime.Instant`, `import kotlinx.datetime.LocalTime`, `import kotlinx.datetime.TimeZone`, `import kotlinx.datetime.toLocalDateTime`. Constructor default `TimeZone.currentSystemDefault()`. `evaluate(now: Instant, …)` keeps the `Instant` name — it is **kotlinx.datetime.Instant**, not `java.time.Instant`. Night check: `now.toLocalDateTime(zone).time` then `time >= start && time <= end` (wrap window: `time >= start || time <= end`). Duration minutes: `(now.toEpochMilliseconds() - start.toEpochMilliseconds()) / 60_000L` (same integer minutes as dump `Duration.toMinutes()`). `NightGrowthResistanceMode.latestResultRef` `AtomicReference` → `@Volatile var` or one `AapsLock`. No `java.time`. No `java.util.concurrent`.
10. **Explicit imports** — no fully qualified names at use site. `AutodriveAuditor.generateHumanReadableReason`: `import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState` and `import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand`, then short names (dump uses FQCN on the method). `ActivityManager` shares package `activity` with dest `ActivityContext` — do not write fully qualified dest type names. `AdaptivePkPdEstimator` shares package `pkpd` with dest `PkPdParams` / `Kernel` / `InsulinActivityState`. `ContextExtensions.ContextPlugin` stays that name; do **not** import `OpenAPSAIMIPlugin`. `AimiLogger` unused `formatUS` and `AtomicLong` imports — drop them. Keep `sanitizeForJson`.
11. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. `AdaptivePkPdEstimator` `[PkPdIntegration]` cannot resolve — backticks. Dump `[docs/…]` paths that are not Kotlin symbols → backticks. Do not edit dest `PkPdCore` / `AutoDriveModels` / TAP-G KDoc.
12. **School English** — new or changed comments only. Do not mass-translate French dump comments (`AutodriveAuditor`, `KalmanFilter`, `LocalSentinel`).
13. **Strings / JSON / prefs** — no `R.string`, `ResourceHelper`, or `org.json`. `AimiDiagnosticsPrefExportPolicy` stays string-key redaction. `TextResolver` is not needed here.

`LocalSentinel` / `ActivityManager` / `AutodriveAuditor` therapy math unchanged except log / import / lock / format rewrites above.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `HyperTrajectoryMpcFeedForward`, `HyperSeverityClassifier`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `PatientMode`, `AdvisorModels`, `AuditorDataStructures`, `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, recursive engine/TickContext/Models/adapters, `AutodriveEngine`, `MpcController`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `HcRecoveryProxyThermalSource`, `OuraApiThermalClient`, `HormonitorLabels`) to unblock Skip.
- Overwrite dest recursive / patient / physio / pkpd / tpo / autodrive types listed as already present. Do not overwrite Lot O / Lot P / Lot Q / Lot R files. Do not overwrite dest `PkPdCore` / `ActivityContext` / `NightGrowthResistanceLearner` / `PhaseSpaceModels` / `InsulinActionState` / `Models` / `AutoDriveModels` / `VirtualGlucoseEngine`.
- Treat `ContextExtensions.ContextPlugin` as `OpenAPSAIMIPlugin`. Do not register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`. Do not add `aimiFmt3`. Do not keep `java.time` on NGR (use kotlinx.datetime). Do not keep `System.currentTimeMillis` on `therapy.kt`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-S.md`: copied, skipped (dest exists vs missing types / banned APIs / Compose graph), rewrite notes (`aimiWallClockMs`, `aimiFmt1` / `aimiFmt2`, `NumberFormat.withDecimals` `formatUS`, `Pattern` → `Regex`, `TimeUnit` → `T.days` / `T.mins`, drop `@JvmStatic`, `Atomic*` → `AapsLock`, `removeIf` → collect/`removeAll`, `kotlin.concurrent.Volatile`, NGR `java.time` → kotlinx.datetime), compile result. State that the recursive engine is still blocked. State that NGR `Instant` is `kotlinx.datetime.Instant`, not `java.time`. State that `ContextPlugin` is not the APS plugin. State that `TpoTriggerEngine` still needs dump `PatientMode` and `MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
