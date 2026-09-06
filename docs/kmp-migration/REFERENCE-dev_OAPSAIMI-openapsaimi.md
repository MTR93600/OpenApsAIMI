# REFERENCE cartography — OpenApsAIMI / AIMI plugin

**Purpose:** a verified map of AIMI **as it exists today**, for the Kotlin Multiplatform (KMP) migration.  
**Not a design proposal.** This document does not change clinical behaviour and does not invent modules that are not in the tree.

| Field | Value |
|---|---|
| Repo | https://github.com/MTR93600/OpenApsAIMI |
| Starting branch | `dev_OAPSAIMI` |
| Verified commit | `c5db5a033379390bceb7851ff92004b72ef055bf` (2026-09-06) — `feat(loop): improve glucose value handling for re-gridded sources to prevent delta flattening` |
| App version suffix | `4.0.0.0-dev.AIMI.060926` (`buildSrc/src/main/kotlin/Versions.kt`) |
| Plugin display name | `"AIMI 3.3"` (`plugins/aps/src/main/res/values/strings.xml` → `openapsaimi`) |
| Method | live tree only (Gradle, Kotlin sources, manifests, existing markdown). Older docs were compared to code, not trusted first. |

---

## 1. Executive summary

AIMI is **not** a separate Gradle module. It lives inside **`:plugins:aps`**, package `app.aaps.plugins.aps.openAPSAIMI`.

- **449** main Kotlin files + **259** test Kotlin files under that package.
- Plugin class: `OpenAPSAIMIPlugin` — implements `APS` + `PluginConstraints`.
- Hilt key: `@IntKey(225)` in `ApsPluginsListModule`.
- Visible only when `config.APS` is true (`BuildConfig.FLAVOR == "full"`).
- Closed-loop entry: `LoopPlugin.invoke` → `activePlugin.activeAPS.invoke` → `OpenAPSAIMIPlugin.invoke` → `DetermineBasalaimiSMB2.determine_basal` → `RT` → `APSResult`.
- The live determinator is **native Kotlin**, not oref JavaScript. File `DetermineBasalAIMI2.kt` (19 312 lines) holds class `DetermineBasalaimiSMB2`.
- On-device ML is mostly a custom `AimiNeuralNetwork` (JSON weights). TFLite UAM and optional ONNX advisor models are separate, Android-bound paths.
- **No AIMI-specific KMP migration plan exists today.** ComboCtl already uses KMP; that is unrelated to AIMI.

---

## 2. Module map

### 2.1 Gradle modules (AIMI-related)

There is **no** `include ':...aimi...'` in `settings.gradle`. AIMI is a package tree inside `:plugins:aps`.

| Gradle module | Path | AIMI role |
|---|---|---|
| **`:plugins:aps`** | `plugins/aps` | **Host.** All AIMI algorithm, physio, ML, advisor, UI activities. Namespace `app.aaps.plugins.aps`. |
| `:app` | `app` | Wires `StepService`, `AimiUamHandler`, `AimiStorageHelper`; flavor gate `ConfigImpl.APS`; Health Connect permission activity; `ComparatorActivity`. |
| `:plugins:main` | `plugins/main` | Explicit `implementation(project(":plugins:aps"))` — dashboard, auditor indicator, overview physio/trajectory. |
| `:core:interfaces` | `core/interfaces` | Shared types: `OapsProfileAimi`, `GlucoseStatusAIMI`, `AimiAdaptationStatus`, `APSResult.Algorithm.AIMI`. |
| `:core:keys` | `core/keys` | Many `OApsAIMI*` / `Aimi*` preference keys (94 `OApsAIMI` hits in `BooleanKey.kt` alone). |
| `:core:ui` | `core/ui` | AIMI strings / dashboard colours. |
| `:database:impl`, `:database:persistence` | `database/*` | Persist `APSResult.Algorithm.AIMI`. |
| `:implementation` | `implementation` | AIMI cloud backup trigger via `ImportExportPrefsImpl`. |
| `:wear` | `wear` | Step / HR listeners write AAPS DB; AIMI reads them. Not an AIMI host. |

**Not in the main Gradle graph**

| Path | Note |
|---|---|
| `tools/aimi_viewer/` | Separate Flutter viewer. Its `settings.gradle.kts` only includes `:app`. Not part of the AndroidAPS build. |

**Sibling APS packages in the same `:plugins:aps` module (not AIMI)**

| Package | Role vs AIMI |
|---|---|
| `openAPSAMA` | Independent `APS` plugin (`@IntKey(210)`). |
| `openAPSSMB` | Independent `APS` plugin (`@IntKey(220)`). AIMI comparison tools call `DetermineBasalSMB`. |
| `openAPSAutoISF` | Independent `APS` plugin (`@IntKey(230)`). `DynIsfTrajectoryTuning` uses its glucose calculator. |
| `openAPS` | Shared helpers (`DeltaCalculator`, `TddStatus`) used by AIMI. |
| `loop` | `LoopPlugin` (`@IntKey(200)`, `@APS`) — invokes whichever APS is active. |
| `autotune` | `AutotunePlugin` (`@IntKey(240)`). |

### 2.2 Plugin IntKey map (`ApsPluginsListModule`)

| IntKey | Class | Qualifier |
|---|---|---|
| 200 | `LoopPlugin` | `@APS` |
| 210 | `OpenAPSAMAPlugin` | `@AllConfigs` |
| 220 | `OpenAPSSMBPlugin` | `@AllConfigs` |
| **225** | **`OpenAPSAIMIPlugin`** | **`@AllConfigs`** |
| 230 | `OpenAPSAutoISFPlugin` | `@AllConfigs` |
| 240 | `AutotunePlugin` | `@AllConfigs` |

Both `OpenAPSAIMIPlugin` and `OpenAPSSMBPlugin` call `.setDefault()` on `PluginDescription`. Which one wins on a fresh install is an **open question** (see §10).

### 2.3 Flavor gating

```kotlin
// app/src/main/kotlin/app/aaps/implementations/ConfigImpl.kt
override val APS = BuildConfig.FLAVOR == "full"
```

- `full`: AIMI is in the plugin list (`showInList({ config.APS })`); Loop is merged via `@APS`.
- `aapsclient*` / `pumpcontrol`: `config.APS == false` → AIMI hidden; Loop not in the active list.

`:app` depends on every `:plugins:*` module automatically (`app/build.gradle.kts` filter). Adding AIMI as its own Gradle module would be a **new** split; it does not exist today.

---

## 3. Entry points

### 3.1 Plugin and algorithm

| Role | Class | Path |
|---|---|---|
| Plugin | `OpenAPSAIMIPlugin` | `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/OpenAPSAIMIPlugin.kt` (2 333 lines) |
| Determinator | `DetermineBasalaimiSMB2` | `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt` (19 312 lines) |
| Tick wrapper | `AimiDetermineBasalTickOrchestrator` | `.../orchestration/AimiDetermineBasalTickOrchestrator.kt` |
| Tick inputs | `AimiTickContext` | `.../orchestration/AimiTickContext.kt` |
| Glucose | `GlucoseStatusCalculatorAimi` | `.../GlucoseStatusCalculatorAimi.kt` |
| Profile DTO | `OapsProfileAimi` | `core/interfaces/.../OapsProfileAimi.kt` |
| Glucose DTO | `GlucoseStatusAIMI` | `core/interfaces/.../GlucoseStatusAIMI.kt` |
| Result | `RT` → `APSResult.with(RT)` | `core/interfaces/.../RT.kt` |

`OpenAPSAIMIPlugin` constructor injects the engine, physio manager, steps manager, physio adapter, auditor, context manager, ML scheduler, storage, trajectory, TPO, and more. It sets:

```kotlin
override val algorithm = APSResult.Algorithm.AIMI
```

`getGlucoseStatusData` delegates to `GlucoseStatusCalculatorAimi`, not the generic `GlucoseStatusProvider` (that field is still injected — role on the AIMI path is unclear).

### 3.2 Live tick path (verified)

```
LoopPlugin.invoke(...)
  → activePlugin.activeAPS.invoke(...)
  → OpenAPSAIMIPlugin.invoke(...)
      → determineBasalaimiSMB2.determine_basal(...) : RT
          → AimiTickContext(...)
          → AimiDetermineBasalTickOrchestrator.run(this, ctx)
          → DetermineBasalaimiSMB2.runDetermineBasalTick(ctx)
      → apsResultProvider.get().with(rt)
      → lastAPSResult = ...
      → rxBus.send(EventAPSCalculationFinished)
  → Loop persists APSResult, applies constraints, enacts TBR / SMB
```

`determine_basal` signature (current):

```kotlin
fun determine_basal(
    glucose_status: GlucoseStatusAIMI,
    currenttemp: CurrentTemp,
    iob_data_array: Array<IobTotal>,
    profile: OapsProfileAimi,
    autosens_data: AutosensResult,
    mealData: MealData,
    microBolusAllowed: Boolean,
    currentTime: Long,
    flatBGsDetected: Boolean,
    dynIsfMode: Boolean,
    uiInteraction: UiInteraction,
    pkpd_iob_data_array: Array<IobTotal>? = null,
    effective_dia_hours: Double? = null,
    effective_peak_minutes: Double? = null,
    extraDebug: String = ""
): RT
```

AIMI does **not** implement `Loop` or `DetermineBasalAdapter` (the JS bridge used by SMB/AMA/AutoISF tests).

### 3.3 Hilt / DI

| Module | Path | What it does |
|---|---|---|
| `ApsPluginsListModule` | `plugins/aps/.../di/ApsPluginsListModule.kt` | Binds AIMI at `@IntKey(225)`. |
| `ApsModule` | `plugins/aps/.../di/ApsModule.kt` | Includes `WCycleModule`, `AIMIStepsProviderModuleMTR`, `AIMIPhysioModuleMTR`; Android injectors for AIMI activities. |
| `WCycleModule` | `openAPSAIMI/di/WCycleModule.kt` | Provides W-cycle stack. |
| `AIMIStepsProviderModuleMTR` | `openAPSAIMI/di/AIMIStepsProviderModuleMTR.kt` | Empty marker; steps classes use `@Inject`. |
| `AIMIPhysioModuleMTR` | `plugins/aps/.../di/AIMIPhysioModuleMTR.kt` | Empty marker; physio classes use `@Inject`. |

### 3.4 AndroidManifest activities

**`:plugins:aps`** (`plugins/aps/src/main/AndroidManifest.xml`)

- `AuditorReportActivity`
- `AimiProfileAdvisorActivity`
- `MealAdvisorActivity`
- `MealAdvisorCameraActivity`
- `AimiModeSettingsActivity`
- `ContextActivity`
- `AIMIEmergencySosPermissionActivityMTR`

**`:app`** (`app/src/main/AndroidManifest.xml`)

- `ComparatorActivity` (AIMI vs SMB comparison)
- `AIMIHealthConnectPermissionActivityMTR` (Health Connect intent-filter)

`ApsModule` `@ContributesAndroidInjector` covers advisor / auditor / context / mode activities. SOS and Health Connect permission activities are started from the plugin / app manifests.

### 3.5 App process wiring (`MainApp`)

- Registers `StepService` on `Sensor.TYPE_STEP_COUNTER`.
- Calls `AimiUamHandler.configureUamModel` / cache / close on plugin lifecycle.
- Injects `AimiStorageHelper`.

---

## 4. Package / class map

Root: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Counts = main `.kt` files (verified 2026-09-06).

### 4.1 Root files (22)

| File | Class / object | Live? |
|---|---|---|
| `OpenAPSAIMIPlugin.kt` | `OpenAPSAIMIPlugin` | **Yes** — APS plugin |
| `DetermineBasalAIMI2.kt` | `DetermineBasalaimiSMB2` | **Yes** — main engine |
| `DetermineBasalCoordinator.kt` | `DetermineBasalCoordinator` | **No callers** outside its file. Port-based sketch (`Clock`, `SmbEngine`, `SafetyGuards`). |
| `DetermineBasalInvocationCaches.kt` | caches | Used by tick |
| `GlucoseStatusCalculatorAimi.kt` | calculator | **Yes** |
| `StepService.kt` | step singleton | **Yes** — Android sensor |
| `AimiModelHandler.kt` | `AimiUamHandler` | **Yes** — TFLite UAM + confidence |
| `aimiNeuralNetwork.kt` | `AimiNeuralNetwork` | **Yes** — shared net |
| `therapy.kt` | `Therapy` | **Yes** — NOTE events (sport / sleep / meals) |
| `KalmanFilter.kt` | `KalmanFilter`, `KalmanISFCalculator` | **Yes** — ISF path |
| `TrainingConfig.kt` | training config | Shared by nets |
| others (`CircadianMath`, `NightGrowthResistance*`, `UndeclaredCobEstimator`, `AutodriveBasalPolicy`, `AIMIAdaptiveBasal`, …) | helpers | Used from the engine |

### 4.2 Subpackages

| Package | Files | What it holds (key types) |
|---|---|---|
| `advisor/` | 63 | `AimiAdvisorService`, meal / vision, auditor, OREF ONNX, tuning, gestation |
| `physio/` | 61 | `AIMIPhysioManagerMTR`, Health Connect repo, sleep, thermal, thyroid, patterns, latent state |
| `pkpd/` | 28 | `PkPdIntegration`, `AdaptivePkPdEstimator`, prediction kinetics |
| `safety/` | 24 | Hypo / IOB / stacking / LGS guards |
| `recursive/` | 22 | `RecursiveBeliefEngine`, `RecursiveBeliefResolver`, RBT leaves |
| `patient/` | 19 | `PhysiologicalTree`, `HarmoniaDecisionEngine`, `HarmoniaHarmonizer` |
| `autodrive/` | 18 | `AutodriveEngine`, MPC, `ContinuousStateEstimator`, neural trainer |
| `orchestration/` | 13 | Tick orchestrator, loop guard, adaptation status, in-tree roadmap MD |
| `tpo/` | 12 | Thyroid-patient orchestration + notifications |
| `steps/` | 11 | Health Connect / phone / DB / composite / unified activity |
| `context/` | 11 | Declared user context + `ContextActivity` |
| `compose/` | 10 | Control Center, PKPD settings |
| `learning/` | 10 | `BasalNeuralLearner`, `UnifiedReactivityLearner`, `BasalMlTrainerWorker` |
| `wcycle/` | 10 | Cycle / endocrine belief |
| `basal/` | 9 | `BasalDecisionEngine`, `DynamicBasalController`, `T3cAnticipation` |
| `smb/` | 9 | `SmbEngine`, `SmbInstructionExecutor` |
| `ISF/` | 8 | DynISF / blender / trajectory tuning |
| `comparison/` | 8 | `AimiSmbComparator` (shadow vs `DetermineBasalSMB`) |
| `ml/` | 7 | `AimiSmbTrainer`, `NeuralModelTrainer`, feature schema |
| `release/` | 7 | Hyper / release policy |
| `utils/` | 7 | `AimiStorageHelper`, `AimiBackupManager` |
| `prediction/` | 5 | Advanced prediction helpers |
| `risk/` | 5 | Risk envelope |
| `trajectory/` | 5 | `TrajectoryGuard`, phase-space types |
| `model/` | 5 | LoopContext / Decision / plans (used by coordinator + ports) |
| `activity/` | 4 | `EffortActivityBelief`, `ActivityManager`, exercise override |
| `hormonitor/` | 4 | Study export + in-app viewer |
| `llm/` | 3 | Gemini HTTP |
| `plugins/` | 3 | In-process `AimiPluginManager` (not Android `PluginBase`) |
| `quality/` | 3 | Quality helpers |
| `decision/` | 2 | Decision context types |
| `di/` | 2 | WCycle + steps marker modules |
| `keys/` | 2 | Extra AIMI keys |
| `sos/` | 2 | Emergency SOS |
| `scenario/` | 9 | Scenario projection |
| `control/`, `carbs/`, `inflammatory/`, `extensions/`, `ports/`, `validation/` | 1 each | Small / sketch |

**Tests:** `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/` — 259 files, including `orchestration/AimiDetermineBasalTickOrchestratorTest.kt`.

### 4.3 Shared core types (outside the package)

| Type | Path |
|---|---|
| `APS` | `core/interfaces/.../aps/APS.kt` — `invoke`, `getGlucoseStatusData`, dyn ISF/IC |
| `APSResult.Algorithm.AIMI` | `core/interfaces/.../aps/APSResult.kt` |
| `OapsProfileAimi` | SMB-like profile + `variable_sens`, `TDD`, `peakTime`, activity fields |
| `GlucoseStatusAIMI` | extends `GlucoseStatus` — parabola, `combinedDelta`, `isNightGrowthCandidate` |
| `AimiAdaptationStatus` | module / phase / reason for dashboard |
| `RT` | pump-facing result (`rate`, `duration`, `units`, `predBGs`, `aimiAdaptationStatus`) |

`OapsProfileAimi` currently imports `android.annotation.SuppressLint` (commented). That is a small Android leak in `:core:interfaces`.

---

## 5. Closed-loop / APS surfaces AIMI owns or extends

### 5.1 Owns

| Surface | How |
|---|---|
| Active APS plugin slot | `OpenAPSAIMIPlugin` implements `APS` + `PluginConstraints` |
| Native determine-basal | `DetermineBasalaimiSMB2` — **replaces** oref JS for this plugin |
| AIMI glucose status | `GlucoseStatusCalculatorAimi` + `GlucoseStatusAIMI` |
| AIMI profile DTO | `OapsProfileAimi` |
| Result algorithm tag | `APSResult.Algorithm.AIMI` |
| Max IOB / basal constraints | `applyMaxIOBConstraints`, `applyBasalConstraints` (meal / autodrive / T3C prefs) |
| Dyn ISF | `calculateVariableIsf`, Kalman, blender, PKPD scale, physio ISF factor |
| Tick orchestration | helpers extracted from the 19k-line class; order is load-bearing |

### 5.2 Reuses (does not replace)

| Surface | Owner | AIMI use |
|---|---|---|
| `LoopPlugin` | `:plugins:aps` / `loop` | Calls `activeAPS.invoke` |
| AAPS constraints / hard limits | `:plugins:constraints`, `HardLimits` | After APS result |
| `IobCobCalculator` | `:plugins:main` | Glucose buckets, IOB, COB / meal data |
| `TddCalculator`, `ProfileFunction` | core / plugins | TDD windows, active profile, temp targets |
| `RT` / `APSResult` | `:core:interfaces` | Same result object as SMB |
| `DeltaCalculator`, `TddStatus` | `openAPS` package | Shared math |
| `DetermineBasalSMB` | `openAPSSMB` | **Comparison / shadow only**, not the live dose path |

### 5.3 Does not class-extend

AIMI does **not** subclass `OpenAPSSMBPlugin`, `OpenAPSAMAPlugin`, or `OpenAPSAutoISFPlugin`. All four implement `APS` side by side.

There is **no** AIMI `.js` determine-basal bundle under `openAPSAIMI/`.

### 5.4 Decision stack inside one tick (code names, not clinical advice)

Order is documented in `plugins/aps/.../orchestration/AIMI_ORCHESTRATION_ROADMAP.md`. That file is an in-tree refactor map. It says medical order must stay identical unless reviewed.

High-level stages that exist as named helpers today:

1. Bootstrap / caches / TDD (`runEarlyDetermineBasalStages`)
2. Physio + IOB profiler
3. Glucose pack — **abort if missing**
4. Early PKPD + G6 / BYODA delta compensation
5. Therapy clocks / meal modes / **T3C brittle early return**
6. Signal prep + PKPD runtime
7. Trajectory + context + advanced predictions
8. Safety halt → Meal advisor → Hard brake → **Autodrive V3** → V2 fallback
9. Post-hypo / compression / drift
10. Global basal schedule + UAM / SMB path
11. WCycle / carbs advisor / MAX_IOB gate
12. `BasalDecisionEngine.decide`
13. Learners + auditor + JSONL / hormonitor export

**Parallel decision names that can return early with TBR/SMB** (all inside the same class): T3C brittle, meal advisor, hard brake, Autodrive V3/V2, drift terminator, max IOB gate.

**Harmonia** (`patient/HarmoniaDecision.kt`): reads `PhysiologicalTree`, outputs action + target basal/SMB. Production applies that through `planHarmoniaProductionBranch` and RBT SMB modulation. JSON still uses legacy keys `simulation_only` / `harmonia_simulation_branch_v1` — those keys are a **data contract**, not proof that the branch is shadow-only. See `docs/AIMI_ARCHITECTURE_MAP.md`.

**Auditor** (`advisor/auditor/AuditorOrchestrator`): KDoc says bounded modulation, not a direct pump command. Callback can mutate `RT` **asynchronously** after export starts — race is an open question.

### 5.5 Unused / sketch APS surfaces

| Item | Evidence |
|---|---|
| `DetermineBasalCoordinator` | Only referenced in its own file + `docs/STANDALONE_APP_RETENTION.md`. Not called from `OpenAPSAIMIPlugin` or `determine_basal`. |
| `ports/Ports.kt` | `Clock`, `BasalActuator`, `SmbActuator`, `PkpdPort`, `MlUamPort`, `SafetyGuards`, `ModeEngine`. Used by the coordinator, **not** the live tick. |
| `SafetyAggressionPlugin`, `StableControlPlugin` | Implement `AimiDecisionPlugin`. No `register()` calls found. `AimiPluginManager` is injected into `AimiAdvisorService` but starts empty unless something registers at runtime. |

---

## 6. Machine learning

### 6.1 Shared net

`AimiNeuralNetwork` (`aimiNeuralNetwork.kt`): one hidden layer, z-score → linear → LeakyReLU → optional layer-norm / dropout → linear. Weights saved as **JSON**, trained on device. Used by SMB, basal/T3C, and OREF personal heads.

`ml/NeuralModelTrainer`: 80/20 split, normalization, publish probes, atomic save.

### 6.2 Distinct ML / learning subsystems (today)

| # | Name | Type | On the dose path? | Storage |
|---|---|---|---|---|
| 1 | `AimiSmbTrainer` | `AimiNeuralNetwork`, input **21** (`SmbRefinementFeatureSchema`) | **Yes** — `refine()` after predicted SMB; clamp ±min(0.05 U, 25%) | App files via `AimiSmbModelStore` |
| 2 | `BasalNeuralLearner` | Two nets (T3C + universal basal), input **16** | **Yes** — basal / T3C factors | `t3c_brain_weights.json`, `basal_adaptive_weights.json` |
| 3 | `AutodriveNeuralTrainer` | Logistic / attention-gate weights | **Gating** for Autodrive stress mask | `autodrive_attention_weights.json` |
| 4 | Autodrive `OnlineLearner` + `AutodriveDataLake` | Online rows / CSV | Shadow + engaged Autodrive | Data lake files |
| 5 | `OrefOnnxScorer` | ONNX Runtime Android | **No** — Advisor risk scores | Optional `assets/oref/*.onnx` — **not committed** |
| 6 | `OrefPersonalMlTrainer` | `AimiNeuralNetwork`, 35 OREF features | **No** — Advisor; KDoc says weights are saved but **not loaded back yet** | `filesDir/oref_personal/` |
| 7 | `AimiUamHandler` | TensorFlow Lite `Interpreter` | **Yes for UAM** — `predictSmbUam` + `confidenceOrZero` used in many tick sites | External `/Documents/AAPS/ml/modelUAM.tflite` |

Gradle deps in `:plugins:aps`:

- TensorFlow Lite **2.4.0** (+ GPU, support, metadata)
- ONNX Runtime Android **1.20.0**
- Health Connect **1.1.0** (not ML, but listed with the same module)

**No PyTorch** in AIMI loop code. **No `.onnx` or `.tflite` model files committed** under `plugins/aps/src/main/assets/oref/` — only `PLACE_ONNX_MODELS_HERE.txt`. Stub generator: `scripts/generate_oref_stub_onnx.py`.

### 6.3 Training pipelines in-repo

| Pipeline | Trigger | Notes |
|---|---|---|
| SMB refinement | `AimiSmbTrainer.maybeTrainAsync` | Min 200 new rows / 6 h; circuit breaker 6 h after 3 failures |
| Basal / T3C | `BasalMlTrainingCoordinator` + `BasalMlTrainerWorker` (`@HiltWorker`) | Governance can hold conservative on high hypo rate |
| Autodrive | `AutodriveNeuralTrainerWorker`, `AutodriveBackfillWorker` | Chronological holdout; hypo-in-1h labels |
| OREF personal | Advisor `trainAndSummarize` | Persistence incomplete (see open questions) |
| Scheduler | `AimiMlTrainingScheduler` | Started/cancelled from plugin lifecycle |

Heuristic (not neural) learners that still change factors: `BasalLearner`, `UnifiedReactivityLearner`, `AdaptivePkPdEstimator` (DIA/peak).

### 6.4 SMB feature vector (21)

From `AimiSmbTrainer` / `SmbRefinementFeatureSchema` comments:

- 10 base: BG, IOB, COB, deltas, TDD rates
- 4 latent physio: meal probability, endogenous drive, circadian SI, transient resistance
- 3 patient-mode
- 3 causal context
- 1 trend indicator

Blending in the engine (site ~L15487): `alpha * mlRefined + (1 - alpha) * predictedSMB`.

---

## 7. Physiological data and state estimation

### 7.1 Ingestion into the tick

| Signal | Source in current code |
|---|---|
| Glucose | `IobCobCalculator.ads` → `GlucoseStatusCalculatorAimi.compute()` → `GlucoseStatusAIMI` |
| IOB | `iobCobCalculator.calculateFromTreatmentsAndTemps` + optional PKPD IOB array |
| COB / meal | `getMealDataWithWaitingForCalculationFinish()` → `MealData` |
| ISF / CR | Active profile + dyn ISF + WCycle IC multiplier |
| TDD | `TddCalculator` (several windows) in plugin and tick |
| Steps / HR | `UnifiedActivityProviderMTR` (Garmin > Wear > Health Connect / phone) |
| Sleep / HRV / RHR / skin temp | `AIMIPhysioDataRepositoryMTR` (Health Connect); optional `OuraApiThermalClient` |
| Declared context | `ContextManager` / `ContextParser` / therapy NOTE events |
| Insulin kinetics | `PkPdLearnedState` + `PkPdIntegration` |

Physio **safety gate** (plugin injects this):

```kotlin
// AIMIInsulinDecisionAdapterMTR
fun getMultipliers(
    currentBG: Double,
    currentDelta: Double? = null,
    recentHypoTimestamp: Long? = null,
    iob: Double = 0.0,
    cob: Double = 0.0
): PhysioMultipliersMTR
```

Comments in that adapter: hard caps about ±15% ISF/basal and ±10% SMB; neutral on hypo / low BG. Treat as **code comments**, not a clinical spec.

### 7.2 Estimators / filters (named in code)

| Component | Path | Role in comments |
|---|---|---|
| `KalmanISFCalculator` | `KalmanFilter.kt` | Fast ISF from TDD + BG |
| `ContinuousStateEstimator` | `autodrive/estimator/` | Bergman-style SI & Ra; EKF/UKF; G6 lead in T9 bootstrap |
| `AdaptivePkPdEstimator` | `pkpd/` | Learn DIA / peak; `anticipationWeight` is **insulin onset**, not movement |
| `PhysioLatentStateBuilder` | `physio/PhysioLatentState.kt` | Latent features for ML |
| `RecursiveBeliefEngine` | `recursive/` | Multi-horizon belief (15/60/180/480 min) |
| `ThyroidStateEstimator` | `physio/thyroid/` | Endocrine submodule |
| `WCycleEstimator` / learner | `wcycle/` | Cycle-phase belief |
| `SleepLiveDetector` | `physio/SleepLiveDetector.kt` | Asleep confidence |
| `AIMIPhysioFeatureExtractorMTR` | `physio/` | `sleepQualityScore` and other HC-derived features |
| `ThermalBeliefEngine` | `physio/thermal/` | Recovery / thermal belief |

There is **no** class named “digital twin”. Closest named models: Autodrive MPC + `ContinuousStateEstimator`, PKPD predictions, `AIMIPhysioManagerMTR` health context.

### 7.3 Background physio jobs

`AIMIPhysioManagerMTR` schedules WorkManager jobs (`PhysioRealtimeWorker`, `PhysioMetabolicWorker`). Those workers are plain `CoroutineWorker` + static `instance`, not `@HiltWorker`. Preference `AimiPhysioAssistantEnable` **defaults to false**. Sleep/HRV data prefs default **true**.

---

## 8. Activity anticipation and sleep quality

Three different “anticipation” words exist. Do not mix them.

### 8.1 Exercise / effort (movement)

| Piece | Path | Status |
|---|---|---|
| `StepService` | root | **Implemented.** `Sensor.TYPE_STEP_COUNTER` in `MainApp`. |
| Phone / HC / DB providers | `steps/` | **Implemented.** Composite: Wear → Phone → Health Connect. |
| `UnifiedActivityProviderMTR` | `steps/` | **Implemented.** Garmin > Wear > HC/Phone. Mode in SharedPreferences `aimi_activity_source_mode`. |
| `EffortActivityBelief` | `activity/` | **Implemented, pure Kotlin.** Reduction-only SMB/basal factors. Wired in `DetermineBasalAIMI2.refreshEffortActivityBelief()`. Pref `OApsAIMIEffortActivityProtection` **default true**. |
| `ActivityManager` | `activity/` | **Still injected and used** in `applyEndoAndActivityAdjustments()` (~L17733). Parallel path, despite `AIMI_ARCHITECTURE_MAP.md` §11.6 saying “No parallel exercise logic remains.” |
| `ExerciseHyperOverridePolicy` | `activity/` | **Implemented.** Basal correction when hyper + rising during lockout. |
| `Therapy.sportTime` | `therapy.kt` | NOTE events containing `"sport"` (excludes walking). |
| Declared activity | `context/` | User / LLM labels → context storage. |

**HRV into effort belief:** `EffortActivityBelief` accepts `hrvDeviationZ`, but the production call at `DetermineBasalAIMI2.kt` ~L16832 passes **`hrvDeviationZ = null`** (`"HRV plumbing is a follow-up; steps + HR drive v1"`). HRV **is** used in physio patterns / RBT from `physioCtx`.

**Google Fit:** not implemented. Mentioned only as a future idea in older trajectory docs.  
**Raw accelerometer:** not used. Step counter only.

### 8.2 T3C prediction anticipation (not exercise)

`basal/T3cAnticipation.kt` — fuses OpenAPS prediction curves for hypo/hyper lead times in T3C brittle mode. Consumed by `DynamicBasalController` and RBT leaf `T3C_ANTICIP`.

### 8.3 PK “anticipation weight” (not movement)

`AdaptivePkPdEstimator.anticipationWeight` — minutes until insulin onset. Used to damp SMB. Different meaning.

### 8.4 Sleep and sleep quality

| Piece | Status |
|---|---|
| `SleepLiveDetector` | Pure. Priority: therapy sleep → HC ongoing session → wearable heuristic (quiet steps + HR near RHR). Thresholds `ASLEEP_THRESHOLD` / `RELEASE_GUARD_THRESHOLD` = 0.55. |
| `AIMIPhysioFeatureExtractorMTR.sleepQualityScore` | From HC sleep: duration, efficiency, fragmentation, deep %. |
| `HealthContextRepository` | `sleepDebtMinutes`, `sleepEfficiency`, `hcSleepSessionActive`, `asleepLiveConfidence`. |
| `Therapy.sleepTime` | Therapy NOTE sleep events. Used on the RBT path in `DetermineBasalAIMI2`. |
| RBT leaves `SLEEP_QUALITY`, `SLEEP_DEBT`, `SLEEP_LIVE` | Implemented. |
| TPO / thermal | Consume sleep debt scores. |

**Gap:** `HealthContextRepository` does **not** pass `therapySleepTime` into `SleepLiveDetector` the same way the RBT path in `DetermineBasalAIMI2` does. Whether that is intentional is an open question.

There is **no** separate sleep-quality sensor API beyond Health Connect features + heuristics.

---

## 9. Platform-coupled list (expect / actual candidates)

**86** files under `openAPSAIMI/` import `android.*` or `androidx.*`.

### 9.1 Hard Android (poor KMP `commonMain` fit)

| Concern | Representative files | Why it is coupled |
|---|---|---|
| Sensors | `StepService.kt`, `MainApp.kt` | `SensorManager` / `SensorEventListener` |
| Health Connect | `AIMIPhysioDataRepositoryMTR.kt`, `AIMIHealthConnect*.kt`, permission activity | `androidx.health.connect` |
| WorkManager | physio workers, `BasalMlTrainerWorker`, Autodrive trainers, `AimiMlTrainingScheduler` | Periodic Android jobs |
| Activities | advisor, meal camera, context, SOS, HC permission | Android UI + camera + SMS / location permissions |
| Compose UI | `compose/AimiControlCenter*.kt`, hormonitor viewer, preference screens | Android Compose |
| Notifications / services | `AimiAdvisorService`, `AiCoachingService`, `FoodRecognitionService`, `AuditorAIService`, `TpoNotificationManager`, `EmergencySosManager` | Android lifecycle |
| TFLite | `AimiModelHandler.kt` | `org.tensorflow.lite.Interpreter` + external storage path |
| ONNX | `advisor/oref/OrefOnnxScorer.kt` | `ai.onnxruntime` + `Context` assets |
| Storage | `AimiStorageHelper`, backup, CSV loggers, hormonitor exporter | `Environment`, app files, `/Documents/AAPS` |
| SharedPreferences | `UnifiedActivityProviderMTR.getMode()` and many `SP` / `Preferences` calls | Android prefs |
| Dashboard glue | `:plugins:main` Overview / CircleTop | Android views + AIMI types |

SOS permissions in `:plugins:aps` manifest: `SEND_SMS`, `CALL_PHONE`, fine / coarse / background location. Camera + internet for meal advisor.

### 9.2 Already mostly portable (good `commonMain` candidates)

Large set of files with **no** Android imports, including:

- `activity/EffortActivityBelief.kt`, `ExerciseHyperOverridePolicy.kt`, most of `ActivityManager`
- `physio/SleepLiveDetector.kt`, feature extractor **logic**
- `basal/T3cAnticipation.kt`, `BasalDecisionEngine`, `DynamicBasalController`
- most of `patient/`, `recursive/`, `pkpd/`, `safety/`, `trajectory/`, `wcycle/`
- `AimiNeuralNetwork` core (uses `java.io.File` + `org.json` — needs a file/JSON port, not Android)

`therapy.kt` is logic + `PersistenceLayer` (Android at the DI boundary).

### 9.3 Existing port sketch

`openAPSAIMI/ports/Ports.kt` is the only named expect-style seam. It is **not** the live tick boundary. A KMP split that assumed this port is already the production API would be wrong.

### 9.4 JNI / native

- No custom `.so` under `plugins/aps`.
- ONNX Runtime and TFLite bring their own JNI.
- Bluetooth / pump / CGM stacks live in other modules (`:pump:*`, `:plugins:source`, …), not in `openAPSAIMI`.

### 9.5 Time / timezone

Mixed `Calendar`, `ZoneId`, `System.currentTimeMillis()` (`SleepLiveDetector.Input` default). `ports.Clock` exists but is unused on the live path.

---

## 10. Dependency edges

```
:app
  └── all :plugins:* (auto)
        ├── :plugins:aps          ← AIMI lives here
        └── :plugins:main
              └── implementation :plugins:aps   // comment: AIMI Auditor UI

:plugins:aps
  ├── :core:data, :core:interfaces, :core:keys, :core:objects
  ├── :core:utils, :core:graph, :core:ui, :core:nssdk
  ├── tensorflow-lite 2.4.0 (+ gpu/support/metadata)
  ├── androidx.health.connect:connect-client:1.1.0
  ├── com.microsoft.onnxruntime:onnxruntime-android:1.20.0
  ├── androidx.hilt.work
  └── test: :pump:virtual, :shared:tests

:core:interfaces  ← OapsProfileAimi, GlucoseStatusAIMI, AimiAdaptationStatus, Algorithm.AIMI
:core:keys        ← OApsAIMI* / Aimi* prefs
:database:*       ← persist Algorithm.AIMI
:wear             ← writes SC/HR rows; AIMI reads via PersistenceLayer
```

No module depends on a standalone `:plugins:aimi` — that module does not exist.

`:plugins:main` importing AIMI types (`AimiLoopRuntimeGuard`, auditor UI, `AIMIPhysioDataRepositoryMTR`, `AutodriveEngine`, `TrajectoryGuard`) is a **real compile edge**. A KMP extract of AIMI must decide whether dashboard stays Android-only and talks through interfaces.

---

## 11. Existing markdown (AIMI / migration) and staleness

**There is no `docs/kmp-migration/` folder in `dev_OAPSAIMI` before this file.**  
Repo-wide search for “KMP / Kotlin Multiplatform / expect/actual” hits **ComboCtl** (`pump/combov2/README.md`) only — not AIMI.

### 11.1 Still useful as architecture (check symbols, not line numbers)

| Path | What it is | vs code today |
|---|---|---|
| `docs/AIMI_README.md` | Product overview | High-level matches (Harmonia, PKPD, on-device NNs, Autodrive V3, effort, Control Center). Marketing tone; not a module map. |
| `docs/AIMI_ARCHITECTURE_MAP.md` | Harmonia decision → pump | **Mostly current.** §11.6 **contradicts itself**: v1 says effort pref “default off”, v2 says default on. **Code: default true.** v2 also says “No parallel exercise logic remains” — **false**: `ActivityManager` is still used. |
| `docs/AIMI_ROADMAP.md` (2026-07-10) | Priorities P1–P6 | Intent still useful. Line numbers (`:3090`, `:7065`) are snapshots. HRV-into-effort still open (matches `hrvDeviationZ = null`). |
| `docs/AIMI_DECISION_CASCADE_CONTRACT.md` | Tree → Harmonia → Auditor | Design contract (2026-07-18). Not a file map. |
| `docs/AIMI_DECISION_CASCADE_ROADMAP.md` | Cascade implementation | Active roadmap; notes `AimiPhysioAssistantEnable` default false. |
| `plugins/aps/.../orchestration/AIMI_ORCHESTRATION_ROADMAP.md` | Tick helper extraction | **Closest to live tick structure.** Mentions branch `feature/aimi-phase2-tick-context` (historical). |
| `docs/AIMI_PHYSIOLOGICAL_PHASE.md` | Phase classifier | Matches `PhysiologicalPhaseClassifier` presence. |
| `docs/AIMI_PHYSIO_RUNTIME_ACTIVATION_2026-06-06.md` | Physio prefs | Pref names still exist. |
| `docs/aimi-harmonia-implementation.md` | Lots H4–H7 | Companion to architecture map. |
| `docs/AIMI_WCYCLE_ENDOCRINE_ARCHITECTURE.md` | Cycle / endocrine | More accurate than user manuals for WCycle. |
| `CLAUDE.md` | Agent / merge rules | Current workspace rules. Mentions Eversense merge constraint. |
| `docs/NON_REGRESSION_CHECKLIST.md` | Upstream merge | Process, not AIMI internals. |
| `docs/MERGE_DEV_2026-09-06.md` | Latest `dev` → `dev_OAPSAIMI` merge | Current process log. |
| `docs/adr/*.md` | Short ADRs (ISF, autodrive, replay) | Small and relatively stable. |

### 11.2 Related but not a KMP plan

| Path | Note |
|---|---|
| `docs/STANDALONE_APP_RETENTION.md` (2026-07-21) | Retention for a standalone app. Says “~429 Kotlin files” — **now 449**. Still correct that AIMI is not its own app. Lists `DetermineBasalCoordinator` as if it were a live basal/SMB owner — **stale**. |
| `.claude/procedures/migration.md` | XML→Compose / API migration **procedure**. Not AIMI, not KMP. |
| `docs/MERGE_CONSTRAINT_EVERSENSE.md` | Keep Eversense registrations on merge. Not AIMI algorithm. |

### 11.3 User manuals — treat as stale for engineering

| Path | Why stale |
|---|---|
| `_docs/OpenAPS_AIMI_User_Manual.md` | Dec 2025; branch name `aimi-dev`; predates 2026 effort-belief work. |
| `docs/AIMI_USER_MANUAL.md`, `docs/AIMI_USER_MANUAL_FR.md` | Behind Control Center / current prefs. Do not use as a class map. |

### 11.4 Session / COMPLETE / FINAL docs — historical snapshots

`docs/` has **239** markdown files. Many AIMI-tagged names (`*_COMPLETE*.md`, `*_FINAL*.md`, `SESSION_*`, `MERGE_DEV_2026-*.md`, meal-advisor mission reports). Examples:

| Path | Staleness |
|---|---|
| `docs/AUDIT_CHEMINS_FICHIERS_AIMI.md` (2025-12-23) | Paths under `/Documents/AAPS`, TFLite-centric. Predates JSON neural refactor. TFLite is now one of several heads. |
| `docs/AIMI_NEXT_SESSION.md` | Long handover. Useful history; not canonical. Mentions HC step-window bugs — not re-verified here. |
| `docs/TRAJECTORY_GUARD_INTEGRATION_COMPLETE.md` | Wishlist includes Google Fit — **not in code**. |
| `docs/AIMI_ADVISOR_MULTI_MODEL_COMPLETE.md` and many `MEAL_ADVISOR_*` | Status reports. Verify against `advisor/meal/` before trusting “complete”. |

`tools/aimi_viewer/README.md` describes the Flutter hormonitor viewer. Confirm schema against `AimiHormonitorStudyExporterMTR` before treating it as current.

---

## 12. Open questions

Do not guess these. They matter for a KMP split.

1. **`DetermineBasalCoordinator` vs live tick** — unfinished port, or future migration target? Nothing calls `runOnce` today.
2. **`ports/Ports.kt` adoption** — should KMP start from this seam, or extract from `DetermineBasalaimiSMB2` / `AimiTickContext` instead?
3. **Dual `.setDefault()`** on AIMI and SMB — who is the default APS on a clean `full` install?
4. **`GlucoseStatusProvider` injection** in `OpenAPSAIMIPlugin` — unused leftover, or used on a path not traced here?
5. **`ActivityManager` vs `EffortActivityBelief`** — architecture map says one path; code still has both. Which is the intended long-term activity owner?
6. **`hrvDeviationZ = null`** in effort refresh — when (if ever) should HC HRV enter `EffortActivityBelief`?
7. **`therapySleepTime` gap** in `HealthContextRepository` vs RBT path — bug or split of concerns?
8. **`AimiPhysioAssistantEnable` default false** vs product docs that describe a live physiological tree — which is the intended product default?
9. **Autodrive V3 default** is `OApsAIMIautoDriveActive = true`. How often the V3 gater actually enacts vs falls through is not measured in this cartography.
10. **`OrefPersonalMlTrainer`** writes weights that (per KDoc) nothing loads. Dead persistence?
11. **Auditor async vs export** — can `RT` change after hormonitor / JSONL snapshot?
12. **`AimiDecisionPlugin` impls** — dead code, or registered only from a path grep missed (reflection / late init)?
13. **`tools/aimi_viewer`** — keep as a separate tool, or part of the KMP product surface?
14. **TFLite UAM** — required for current UAM SMB, or can confidence-only / neural SMB replace it?
15. **Wear / Garmin ownership** — keep DB contract only, or wrap vitals behind one AIMI port (`UnifiedActivityProviderMTR`)?
16. **Filename vs class name** — `DetermineBasalAIMI2.kt` / `DetermineBasalaimiSMB2` will confuse a port. Rename is out of scope here.
17. **`:plugins:main` compile dependency on AIMI** — extract interfaces to `:core:interfaces` before any KMP module, or keep dashboard Android-only?
18. **Intent `user_intent.has_activity`** — `AIMI_ROADMAP.md` P4 still flags a propagation bug. Not re-proven in this pass.
19. **HC step overlapping-window over-count** mentioned in `AIMI_NEXT_SESSION.md` — is a fix on this commit? Not verified here.
20. **Which of ~80 COMPLETE/FINAL docs to archive** — process question, not answered by code.

---

## 13. What a KMP migration can trust from this baseline

**Safe to treat as current facts**

- AIMI = package inside `:plugins:aps`, plugin key 225, algorithm enum `AIMI`.
- Live dose path = `OpenAPSAIMIPlugin` → `DetermineBasalaimiSMB2.determine_basal` → `RT`.
- No dedicated AIMI Gradle module and no AIMI KMP doc existed before this file.
- Hard platform shell = sensors, Health Connect, WorkManager, Activities/Compose, TFLite, ONNX, file paths.
- Decision math (effort belief, sleep detector, T3C anticipation, Harmonia, RBT, most PKPD/safety) is already largely Kotlin-without-Android.

**Not safe to copy from older markdown without re-checking**

- File counts (“~429”), default-off effort protection, “no parallel activity logic”, “Harmonia is simulation only”, “AIMI is a separate module”, Google Fit, committed ONNX/TFLite assets, `DetermineBasalCoordinator` as the live engine.

**Out of scope for this document**

- Any change to dosing, safety caps, or clinical defaults.
- A proposed KMP module graph (that is a later design step).
- Runtime measurement of how often each early-return branch fires.

---

## 14. How to re-verify this map

```text
# file counts
find plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI -name '*.kt' | wc -l

# plugin registration
# plugins/aps/src/main/kotlin/app/aaps/plugins/aps/di/ApsPluginsListModule.kt

# live entry
# OpenAPSAIMIPlugin.invoke → DetermineBasalaimiSMB2.determine_basal
# → AimiDetermineBasalTickOrchestrator.run

# no separate module
# grep settings.gradle for aimi  →  should be empty
```

If this file and the tree disagree, **trust the tree** and update this file.
