# Lot N — T1 peel: scenario projection math

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a445a3e279` (Lot M)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` later T1 waves. Lot M unblocked `TrajectoryAnalysis` / `TrajectoryType`. This lot copies the **scenario/** math wave (5 files). Cap ~15; this list is 5.

The 5 remaining Lot L skips still need Compose or dump graphs (`UamHypothesisState` Compose, `PkPdRuntime` in `PkPdIntegration` Compose, `DoseTerminalSnapshot` / `PhysiologicalTree`). **Do not copy them.**

`physio/pattern/*` is **not** copy-safe yet: `PhysiologicalPatternModels` needs dump `PhysioContextMTR` / `PhysiologicalPhaseClassifier`, and the classifier itself needs `HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot`. `release/*` and `wcycle/*` adjusters are also not this lot (see Skip).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (5 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these five exist at dest (checked 2026-08-28). Already in dest and **must not be copied**: `scenario/ScenarioContributor.kt`, `ScenarioContributorId.kt`, `ScenarioProjectionCurve.kt`, `ScenarioProjectionKind.kt`. `AdvancedPredictionCurves`, `MealSafetyContext`, `MealAbsorptionPhase`, `PhysiologicalPhase`, `TrajectoryAnalysis` / `TrajectoryType`, `AimiRiskConstants`, `PredictiveHypoConstants`, `Predictions`, `RT` are already commonMain.

Dump scan: no `android.*`, `java.io.File`, `org.json`, Compose, `Activity`, tick, plugin, or `PkPdIntegration`. `contextActivityActive` / `applyActivityLayer` are product names, not Android `Activity`.

| rel | why |
|---|---|
| `scenario/ScenarioProjectionContext.kt` | tick-local fusion input; needs Lot M `TrajectoryAnalysis` |
| `scenario/ScenarioProjectionPair.kt` | input/output pair + log line; uses dest `ScenarioProjectionCurve` |
| `scenario/InsulinSlopePreserveHysteresis.kt` | hold for insulin-slope seed; engine calls `stabilize` |
| `scenario/ScenarioProjectionEngine.kt` | pure fusion math; all types already in dest |
| `scenario/ScenarioProjectionApplicator.kt` | maps pair onto `RT.predBGs` (`Predictions` in `core:interfaces` commonMain) |

---

## Skip — do not copy this lot

### Remaining Lot L skips (still not T1-clean)

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `MealAbsorptionPhaseEngine` / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (`DecisionPredictionAuthority`) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` (`patient/PhysiologicalTree.kt`) |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Other later T1 waves (not this list)

| bucket | why not this lot |
|---|---|
| `physio/pattern/*` (8 dump files) | `PhysiologicalPatternModels` / `Detector` need dump `PhysioContextMTR` (`AIMIPhysioDataModelsMTR.kt`) and `PhysiologicalPhaseClassifier` (classifier imports `HyperTrajectoryHypoCredibility`). `PhysiologicalPatternExport` also needs `MealAbsorptionPhaseEngine` + `HyperTrajectoryHypoCredibility`. Dest has no `physio/pattern/`. |
| `release/*` remaining 5 | `HyperTrajectoryHypoCredibility` needs `DoseTerminalSnapshot`. Classifier / prefs / evaluator / MPC feed-forward hang off that. Dest already has `HyperTrajectoryReleaseResult` / `HyperSeverityTier` only. |
| `wcycle/*` adjusters | `WCycleLearner` / `WCycleCsvLogger`: `android.*` + `File` (+ `org.json` on learner). `WCycleAdjuster` / `WCycleFacade` need those. `WCycleEstimator`: `java.time.LocalDate`. `EndocrineAmplitudeGovernor`: `java.time.LocalTime.now()`. `EndometriosisAdjuster`: dump `AimiPhysioInputs`. `WCyclePreferences` already dest (Lot M). |
| `recursive/*` without File | next wave after this one; do not mix. |
| `patient/*` without runtime repos | next wave; `PatientStateRuntimeRepository` stays parked. |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `patient/PhysiologicalTree.kt`, `physio/AIMIPhysioDataModelsMTR.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

Do **not** copy dest-already-present scenario types listed above.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 5 use `@Inject`. If one appears, keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — prefer `LTag.AIMI`. These 5 do not log. Do not add log calls.
3. **Time** — no `System.currentTimeMillis()` in these 5. Do not add a clock.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. `ScenarioProjectionEngine` contributor summaries: `"%.1f".format(...)` → `aimiFmt1`, `"%.2f".format(...)` → `aimiFmt2`, with explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1` / `aimiFmt2`. Do **not** add `aimiFmt3`.
5. **`@Volatile`** (`InsulinSlopePreserveHysteresis`) — `import kotlin.concurrent.Volatile` (same as `IsfSourceTelemetry`). Not `kotlin.jvm.Volatile`.
6. **Explicit imports** — no fully qualified names at use site. `kotlin.math.abs` / `max` / `min` already imported on the engine; keep them. Do not write `kotlin.math.abs(...)` without the import.
7. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links. `InsulinSlopePreserveHysteresis` already backticks the missing hysteresis types. If `[RT.predBGs]` on the applicator does not resolve, use backticks.
8. **School English** — new or changed comments only. Do not mass-translate French dump comments.
9. **Strings / JSON / prefs** — these 5 files do not use `R.string`, `ResourceHelper`, `org.json`, or `Preferences`. Do not introduce them. `TextResolver` is not needed here.

`ScenarioProjectionApplicator` assigning `RT.predBGs` / `eventualBG` is T1 output mapping, not Compose. Keep it.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `AIMIPhysioDataModelsMTR`, `PhysiologicalPhaseClassifier`, `MealAbsorptionPhaseEngine`, `physio/pattern/*`, `release/*`, `wcycle/*` adjusters, `recursive/*`, `patient/*`) to unblock Skip.
- Overwrite dest `ScenarioContributor.kt` / `ScenarioContributorId.kt` / `ScenarioProjectionCurve.kt` / `ScenarioProjectionKind.kt`.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-N.md`: copied, skipped (dest exists vs missing types / banned APIs), rewrite notes (`aimiFmt1` / `aimiFmt2`, `@Volatile`), compile result.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
