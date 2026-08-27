# Lot L — T1 math peel into `:plugins:aps` commonMain

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: `report-merge-kmp.md` Lot L list (23 names). None of those 23 have `android.*`, `java.io.File`, `org.json`, `System.currentTimeMillis`, Compose, or Android `Activity`. None already exist at dest (checked 2026-08-28).

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list (missing types still dump-only; later T1 waves). Do not add extra dump files to make Skip files compile.

---

## Copy (14 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

| rel |
|---|
| `GlucoseStatusCalculatorAimi.kt` |
| `ISF/DynIsfTrajectoryTuning.kt` |
| `IsfSourceTelemetry.kt` |
| `UndeclaredCobEstimator.kt` |
| `activity/EffortActivityBelief.kt` |
| `basal/BasalTerminalInvariants.kt` |
| `basal/DynamicBasalController.kt` |
| `comparison/KpiCalculator.kt` |
| `comparison/PerformanceScorer.kt` |
| `pkpd/DiaGovernor.kt` |
| `pkpd/PkpdSmbTailDamping.kt` |
| `pkpd/TapPeakGovernor.kt` |
| `safety/InsulinLoadGovernor.kt` |
| `safety/InsulinStackingStance.kt` |

---

## Skip (9 files) — do not copy this lot

All nine pass the Android/JSON/clock filter. They still import types that live **only in the dump**, not in `commonMain`. Pulling those types would expand the dump. Leave them parked.

| rel | Missing type(s) still dump-only |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode`, `PatientModeOrchestrator`, `PatientStateSnapshot`, `MealAbsorptionPhaseEngine`, `PhysioLatentState`, `UamHypothesisId` / `UamHypothesisState`, `HarmoniaAction`, `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `basal/T3cTrajectoryContext.kt` | `trajectory/TrajectoryAnalysis` (`PhaseSpaceModels.kt`) |
| `inflammatory/InflammationAdjuster.kt` | `wcycle/WCyclePreferences` (`ThyroidStatus` / `WCycleDefaults` already in commonMain) |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` (`PkPdIntegration.kt`) |
| `smb/SmbDampingUsecase.kt` | `PkPdRuntime` (`SmbDampingAudit` already in commonMain) |
| `trajectory/TrajectoryGuard.kt` | `PhaseSpaceState`, `StableOrbit`, `TrajectoryAnalysis` (`PhaseSpaceModels.kt`) |
| `trajectory/TrajectoryMetricsCalculator.kt` | same phase-space types |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, anything with `System.currentTimeMillis`.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` where already present. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` (`DynIsfTrajectoryTuning`). Keep `LTag.GLUCOSE` on `GlucoseStatusCalculatorAimi` (same as SMB / `DeltaCalculator`). Drop unused `LTag` import on `DynamicBasalController`.
3. **Time** — no `System.currentTimeMillis()`. Keep `dateUtil.now()` on `GlucoseStatusCalculatorAimi`. `IsfSourceTelemetry.STALE_AFTER_MS` stays a constant.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit imports (`import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2`). For `%.4f` only (`DynIsfTrajectoryTuning` mult): `NumberFormat(minFractionDigits = 4).format(value, NumberFormatPlatform.SEPARATOR_DOT)` with explicit `NumberFormat` / `NumberFormatPlatform` imports. Do not add a new `aimiFmt4` helper.
5. **`@Volatile`** (`IsfSourceTelemetry`) — `import kotlin.concurrent.Volatile` (see `ProfileRepositoryImpl`). Not `kotlin.jvm.Volatile`.
6. **`@JvmStatic`** — delete on `KpiCalculator` and `PerformanceScorer`.
7. **Explicit imports** — no fully qualified names at use site. Add the import even if used once.
8. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links.
9. **School English** — new or changed comments only. Do not mass-translate French dump comments.
10. **Strings / JSON / prefs** — these 14 files do not use `R.string`, `ResourceHelper`, or `org.json`. Do not introduce them. Keep `Preferences` + `BooleanKey` / `DoubleKey` / `IntKey` as in dump. `TextResolver` is not needed here.

`ActivityStage` for `InsulinLoadGovernor` is already `pkpd/InsulinActionState.kt` in commonMain. Comparison types for KPI/scorer are already `comparison/ComparisonData.kt`.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PhaseSpaceModels`, `PkPdIntegration`, `patient/*`, `WCyclePreferences`, `HyperTrajectoryHypoCredibility`) to unblock Skip.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-L.md`: copied, skipped (dest exists vs missing types), rewrite notes, compile result.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
