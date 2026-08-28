# Lot P — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `91b9ce0451` (Lot O)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio types already present were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, and `HyperSeverityClassifier`. Lot O landed `PhysioContextMTR` / `PhysioMultipliersMTR`; that does **not** unblock TickContext. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind`. Classifier / `MealAbsorptionPhaseEngine` / `ExerciseHyperOverridePolicy` hang on `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` → `DecisionPredictionAuthority` (UAM Compose). This lot did not copy TickContext / Models / engine / adapters.

`physio.thyroid.ThyroidStatus` (`EUTHYROID`, `HYPER_MILD`, `HYPER_MODERATE`, `HYPER_SEVERE`, `NORMALIZING`, `UNKNOWN`) is **not** dest `wcycle.ThyroidStatus` (`EUTHYROID`, `HYPOTHYROID_TREATED`, `HASHIMOTO`, `THYROIDECTOMY`). The two enums were not merged. Thyroid files do not import `wcycle.ThyroidStatus`.

---

## Copied (11) — dest did not exist

| rel | notes |
|---|---|
| `physio/gate/CosineTrajectoryGate.kt` | Metro kept; `EnumMap` → `mutableMapOf`; `aimiFmt1` / `aimiFmt2`; `abs(...)`; `[TrajectoryGuard]` FQCN KDoc link |
| `physio/AIMIPhysioFeatureExtractorMTR.kt` | Metro kept; `LTag.AIMI`; `aimiWallClockMs`; `aimiFmt1`; deleted `Double.format` |
| `physio/AIMIPhysioBaselineModelMTR.kt` | Metro kept; `LTag.AIMI`; `kotlin.concurrent.Volatile`; `mutableMapOf` + `AapsLock`; `aimiFmt1` |
| `physio/AIMIPhysioContextEngineMTR.kt` | Metro kept; `LTag.AIMI`; `aimiWallClockMs` |
| `physio/PhysioAggregator.kt` | Metro kept; `LinkedList` → `ArrayDeque`; `aimiWallClockMs`; `AapsLock` instead of JVM `synchronized` |
| `physio/thyroid/ThyroidModels.kt` | Basedow DTOs; `timestampMs` default `aimiWallClockMs()` |
| `physio/thyroid/ThyroidEffectModel.kt` | copy as-is; same-package `ThyroidStatus` |
| `physio/thyroid/ThyroidSafetyGates.kt` | copy as-is |
| `physio/thyroid/ThyroidStateEstimator.kt` | copy as-is; `StateFlow` |
| `physio/thyroid/ThyroidPreferences.kt` | `enumValues` like Lot M; `aimiWallClockMs`; BooleanKey / StringKey already in `:core:keys` |
| `physio/thyroid/ThyroidDiagnosticsLogger.kt` | `String.format(Locale.US, …%.2f…)` → `aimiFmt2`; no `java.util.Locale` |

No dest file was overwritten. Dest had no `physio/gate/` and no `physio/thyroid/`.

Already in dest and **not** copied: Lot O files (`physio/AIMIPhysioDataModelsMTR.kt`, `AIMIVectorModels.kt`, `SleepLiveDetector.kt`, `HealthContextSnapshot.kt`, `patient/PhysioLiveDigest.kt`, `recursive/RecursiveBeliefPreferences.kt`, `RbtEpisodeMemory.kt`); also `recursive/BeliefLeafId.kt`, `BeliefParadoxId.kt`, `RecursiveBeliefMemory.kt`, `WaveletBelief.kt`, `RbtExtendedSignals.kt`, `ChannelInterferenceOptimizer.kt`, `patient/PatientEventMemory.kt`, `BodyKineticsDigest.kt`, `AimiCascadeArbitrationArtifacts.kt`, `HarmoniaSensorTelemetry.kt`, `physio/MealAbsorptionPhase.kt`, `PhysiologicalPhase.kt`, `BehavioralRiskPolicy.kt`, `HormonalScenarioTerminalCap.kt`, `EndogenousBasalBridgePolicy.kt`, `thermal/ThermalBeliefDigest.kt`. Cosine-gate and thyroid keys already in `:core:keys`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose**) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (Compose) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` | still needs `PhysiologicalPhaseClassifier` |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot` |
| `wcycle/*` adjusters | `android.*` + `File` |
| rest of `patient/*` | tree / orchestrator / `PatternCapKind` |
| meal / endogenous hysteresis | engines / classifier |
| `physio/thermal/ThermalBeliefEngine.kt` + `ThermalBaselineStore.kt` | `java.util.Calendar` + `CopyOnWriteArrayList` |
| copy-safe leftovers | `StraightLineTubeAdvisor`, `NaiveEventualBgSignGuard`, `AIMIAdaptiveBasal` — not this physio wave |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `risk/DecisionPredictionAuthority.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, `physio/PhysioLatentState.kt`, `physio/AIMIPhysioOutcomes.kt`, `physio/AIMIPhysioManagerMTR.kt`, `KalmanFilter.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: kept `@Inject` / `AppScope` / `SingleIn` on cosine gate, feature extractor, baseline, context engine, aggregator. Thyroid files have no `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`.
- Log: `LTag.APS` → `LTag.AIMI` on feature extractor, baseline, and context engine. Cosine gate keeps the injected logger; no log calls added.
- Time: every `System.currentTimeMillis()` → `aimiWallClockMs()` (`ThyroidInputs.timestampMs`, `ThyroidPreferences.getCurrentInputs`, feature extractor timestamp + HRV window, baseline `now` / age log, context engine timestamp, all aggregator reads). No `System.currentTimeMillis()` left.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. Cosine gate `"%.2f"` → `aimiFmt2`, `"%.1f"` → `aimiFmt1`. Feature extractor / baseline deleted `Double.format`; call sites `format(1)` → `aimiFmt1`. Thyroid diagnostics `String.format(Locale.US, "…%.2f…")` → `aimiFmt2`. No `aimiFmt3`.
- `@Volatile`: `import kotlin.concurrent.Volatile` on baseline. Not `kotlin.jvm.Volatile`.
- Collections: cosine gate `EnumMap` / `KernelType::class.java` → `mutableMapOf`. Baseline `ConcurrentHashMap` → `mutableMapOf` plus one lock for every history map (`keys.removeIf` → `removeAll`). Aggregator `LinkedList` → `ArrayDeque` with `removeFirst()`. Teacher SMB: `kotlin.synchronized` is JVM only, so locks are `AapsLock.withLock` (same as OpenAPS SMB cache), not the `synchronized` keyword.
- `ThyroidPreferences.enumValue`: `java.lang.Enum.valueOf` → `enumValues<T>().firstOrNull { it.name == raw } ?: default` (blank / unknown → default, same as Lot M `WCyclePreferences`).
- Explicit imports: `aimiWallClockMs` / `aimiFmt1` / `aimiFmt2` / `abs`. No fully qualified names at use site. Thyroid files use same-package `ThyroidStatus`, never `wcycle.ThyroidStatus`.
- KDoc: cosine-gate `TrajectoryGuard` kept as a resolvable FQCN link. Dump `[docs/…]` paths were not present.
- No `android.*`, `File`, `org.json`, `R.string`, or `ResourceHelper` in these 11 files.
- Cosine-gate `KernelType.ACTIVITY` is T1 naming, not Android `Activity`. Aggregator step/HR buffers are in-memory, not Health Connect.
- Therapy math unchanged except log string formatting, KMP clock, and collection/lock types.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-P.log` | **BUILD SUCCESSFUL in 49s** (EXIT 0). Both requested tasks compiled. |
| 2 | `/tmp/aimi-lot-P.log` | **BUILD SUCCESSFUL in 43s** (EXIT 0). After Important review fixes (logs outside lock; `cleanup(now)`). Both requested tasks compiled. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Review follow-up (before commit)

Review was **APPROVE_WITH_CONCERNS**. The two Important items were applied:

1. `AIMIPhysioBaselineModelMTR.restoreBaseline` / `clearHistory` — log calls moved outside `historyLock`. History sizes captured under the lock.
2. `PhysioAggregator.cleanup` — takes the caller `now`; no second `aimiWallClockMs()` sample.

Minor suggestions (WINDOW constants, dead cosine-gate `if`, thyroid `else`) left as-is.

---

## Return

**DONE** — 11 copied, recursive engine still blocked as planned, `physio.thyroid.ThyroidStatus` is not `wcycle.ThyroidStatus`, compile **BUILD SUCCESSFUL**.
