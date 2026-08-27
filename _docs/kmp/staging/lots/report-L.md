# Lot L — CODE report

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added).

A `commonMain` compile is **not** “AIMI runs on iOS”.

---

## Copied (14) — dest did not exist

| rel | notes |
|---|---|
| `GlucoseStatusCalculatorAimi.kt` | copy as-is: Metro kept, `LTag.GLUCOSE` kept, `dateUtil.now()` kept |
| `ISF/DynIsfTrajectoryTuning.kt` | `LTag.APS` → `LTag.AIMI`; format rewrite |
| `IsfSourceTelemetry.kt` | `import kotlin.concurrent.Volatile` |
| `UndeclaredCobEstimator.kt` | `aimiFmt1`; KDoc `ContinuousStateEstimator` in backticks |
| `activity/EffortActivityBelief.kt` | `aimiFmt2` |
| `basal/BasalTerminalInvariants.kt` | `aimiFmt2` |
| `basal/DynamicBasalController.kt` | drop unused `LTag`; `aimiFmt*`; stripped `T3cTrajectoryContext` (see concerns) |
| `comparison/KpiCalculator.kt` | drop `@JvmStatic`; explicit `kotlin.math.abs` |
| `comparison/PerformanceScorer.kt` | drop `@JvmStatic`; `aimiFmt0` / `aimiFmt1`; explicit `abs` |
| `pkpd/DiaGovernor.kt` | drop `java.util.Locale`; `aimiFmt1` / `aimiFmt2` / `aimiFmtSigned1` |
| `pkpd/PkpdSmbTailDamping.kt` | copy as-is (Metro not used; prefs keys kept) |
| `pkpd/TapPeakGovernor.kt` | drop `Locale`; `aimiFmt*`; KDoc `TrajectoryPeakBias` in backticks |
| `safety/InsulinLoadGovernor.kt` | `aimiFmt*`; KDoc `DetermineBasalaimiSMB2` in backticks |
| `safety/InsulinStackingStance.kt` | `aimiFmt0` / `aimiFmt1` / `aimiFmt2` |

No dest file was overwritten.

---

## Skipped (9) — missing types still dump-only (not copied)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode`, orchestrator / snapshot / meal-phase / physio / UAM / Harmonia / post-hypo types |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `basal/T3cTrajectoryContext.kt` | `trajectory/TrajectoryAnalysis` (`PhaseSpaceModels.kt`) |
| `inflammatory/InflammationAdjuster.kt` | `wcycle/WCyclePreferences` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` (`PkPdIntegration.kt`) |
| `smb/SmbDampingUsecase.kt` | `PkPdRuntime` |
| `trajectory/TrajectoryGuard.kt` | phase-space types |
| `trajectory/TrajectoryMetricsCalculator.kt` | same phase-space types |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, anything with `System.currentTimeMillis`.

---

## Rewrite notes

- Metro `@Inject` / `@SingleIn(AppScope)` kept where already present. No Hilt. No `@IntKey(225)`.
- AIMI logs: `LTag.APS` → `LTag.AIMI` in `DynIsfTrajectoryTuning`. `GlucoseStatusCalculatorAimi` stays `LTag.GLUCOSE`. Unused `LTag` import dropped on `DynamicBasalController`.
- Format: `aimiFmt0` / `aimiFmt1` / `aimiFmt2` / `aimiFmtSigned1` with explicit imports. `%.4f` in DynISF uses `NumberFormat(minFractionDigits = 4).format(..., NumberFormatPlatform.SEPARATOR_DOT)`. No new `aimiFmt4`.
- `@Volatile` → `kotlin.concurrent.Volatile`. `@JvmStatic` removed on KPI/scorer.
- No `android.*`, `File`, `org.json`, `System.currentTimeMillis`, `R.string`, or `ResourceHelper` in these 14 files.
- Therapy math unchanged except log string formatting.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `_docs/kmp/staging/lots/compile-L.log` | **FAILED** (`compileKotlinIosSimulatorArm64`): unresolved `T3cTrajectoryContext` in `DynamicBasalController.kt` |
| 2 | `_docs/kmp/staging/lots/compile-L-retry.log` | **BUILD SUCCESSFUL in 1m 3s** (EXIT 0). Both requested tasks compiled. |

Fix on retry (Lot L file only): package-level `T3cTrajectoryContext` data class + `guardBg` appended in `DynamicBasalController.kt`. Dump `build()` was **not** copied (`TrajectoryAnalysis` is still dump-only). Skip file `basal/T3cTrajectoryContext.kt` was **not** copied.

---

## Concerns (why not DONE)

1. **`T3cTrajectoryContext` lives in `DynamicBasalController.kt`.** It is a subset of the skip file (fields + `guardBg` only). A later T1 wave that copies dump `basal/T3cTrajectoryContext.kt` must **remove this inlined type first**, or the module will have two declarations. Do not copy `PhaseSpaceModels` / `TrajectoryAnalysis` in that same lot unless the skip list is updated.
2. First compile failed; retry passed after that type peel.
3. Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE_WITH_CONCERNS** — 14 copied, 9 skipped, retry compile **BUILD SUCCESSFUL**.
