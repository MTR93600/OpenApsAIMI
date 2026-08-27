# Lot M — T1 peel: phase-space types + WCycle prefs (unblocks 4 of 9 Lot L skips)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `3198b71e45` (Lot L)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Report source: Lot L skip list (9 files). This lot copies the dump-only types that make four of those skips copy-safe, then those four files. Cap ~15; this list is 6.

**Do not copy the whole dump.** Copy only the **Copy** list. Skip the **Skip** list. Do not add extra dump files to make Skip files compile.

---

## Copy (6 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these six exist at dest (checked 2026-08-28). `ActivityStage` is already `pkpd/InsulinActionState.kt`. `ThyroidStatus` / `VerneuilStatus` / `WCycleDefaults` / `CycleTrackingMode` are already `wcycle/`. WCycle `BooleanKey` / `DoubleKey` / `IntKey` / `StringKey` entries already exist in `:core:keys`. `ClampPkpdScenarioReconcile` is **not** needed here.

| rel | why |
|---|---|
| `trajectory/PhaseSpaceModels.kt` | dump-only types (`PhaseSpaceState`, `StableOrbit`, `TrajectoryAnalysis`, …) that blocked three Lot L skips |
| `basal/T3cTrajectoryContext.kt` | Lot L skip; dump type is T1-clean once `TrajectoryAnalysis` lands |
| `trajectory/TrajectoryGuard.kt` | Lot L skip; copy-safe after `PhaseSpaceModels` |
| `trajectory/TrajectoryMetricsCalculator.kt` | Lot L skip; copy-safe after `PhaseSpaceModels` |
| `wcycle/WCyclePreferences.kt` | dump-only type that blocked `InflammationAdjuster` |
| `inflammatory/InflammationAdjuster.kt` | Lot L skip; copy-safe after `WCyclePreferences` |

### Dest edit in the same lot (not a dump copy)

`basal/DynamicBasalController.kt` already has an inlined package-level `T3cTrajectoryContext` (fields + `guardBg` only; no `build()`). **Delete that inlined class and its “later T1 wave” comment** in this lot, after the real file is in `basal/T3cTrajectoryContext.kt`. Same package — keep `applyT3cBasalBrake` / `T3cTrajectoryContext.guardBg` call sites. Do not leave two declarations.

---

## Skip (5 remaining Lot L skips) — do not copy this lot

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / `PatientModeOrchestrator`, `PatientStateSnapshot`, `MealAbsorptionPhaseEngine`, `PhysioLatentState`, `UamHypothesisId` / `UamHypothesisState` (**Compose** import), `HarmoniaAction`, `PostHypoDeliveryAuthority` (pulls `PatientMode`) |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` (builder needs `DecisionPredictionAuthority` graph) |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` (`patient/PhysiologicalTree.kt` + thermal dump types) |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose** `readAimiBehaviorRuntimeProfile`) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file (`SmbDampingAudit` already in commonMain) |

Also still parked (report, not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `patient/PhysiologicalTree.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

`PhaseSpaceModels` dump has two `System.currentTimeMillis()` **defaults**. Copy it **only** with the clock rewrite below (same lot). Do not copy other clock/tick files.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — keep `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn` on `TrajectoryGuard`. No Hilt. No `javax.inject`. Do **not** add `@IntKey(225)` or `ApsPluginRegistrations`.
2. **Log** — `LTag.APS` → `LTag.AIMI` (`TrajectoryGuard`). Prefer `LTag.AIMI`.
3. **Time** — `PhaseSpaceModels`: `TrajectoryWarning.timestamp` and `TrajectoryAnalysis.timestamp` defaults must be `aimiWallClockMs()` with `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`. No `System.currentTimeMillis()`. Same pattern as `model/DecisionModels.kt`.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format(...)`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit imports. For `%.3f` only (`PhaseSpaceModels.toConsoleLog`, `TrajectoryGuard` curvature): `NumberFormat(minFractionDigits = 3).format(value, NumberFormatPlatform.SEPARATOR_DOT)` with explicit `NumberFormat` / `NumberFormatPlatform` imports. Signed two-decimal: sign + `aimiFmt2`. Do **not** add `aimiFmt3` or `aimiFmtSigned2`.
5. **`java.lang.Enum.valueOf`** (`WCyclePreferences`) — JVM-only; will fail iOS. Replace with `enumValues<T>().firstOrNull { it.name == raw } ?: default` (keep the blank → default.name behaviour). No `java.lang.Enum`.
6. **Explicit imports** — no fully qualified names at use site. `StableOrbit.toPhaseSpaceState()` dump uses `app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage.TAIL` — use `ActivityStage.TAIL` (file already imports `ActivityStage`). `Vector2D.angleTo`: `import kotlin.math.acos`, not `kotlin.math.acos(...)`.
7. **KDoc** — if `[Symbol]` cannot resolve from this module, use backticks. Do not add module deps for links.
8. **School English** — new or changed comments only. Do not mass-translate French dump comments.
9. **Strings / JSON / prefs** — these 6 files do not use `R.string`, `ResourceHelper`, or `org.json`. Do not introduce them. Keep `Preferences` + typed keys on `WCyclePreferences` as in dump. `TextResolver` is not needed here.
10. **`T3cTrajectoryContext`** — copy dump `build()` (needs `TrajectoryAnalysis`). After copy, delete the inlined stub in `DynamicBasalController.kt` so there is one class only.

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

- Restore the 324-file dump, or copy Skip files, or copy extra dump files (`PkPdIntegration`, `PhysiologicalTree`, `DoseTerminalSnapshot`, `DecisionPredictionAuthority`, `patient/*` orchestrator/snapshot, `UamHypothesisState`, `HyperTrajectoryHypoCredibility`, `TrajectoryHistoryProvider`) to unblock Skip.
- Leave the inlined `T3cTrajectoryContext` in `DynamicBasalController.kt` after copying the real file.
- Register `@IntKey(225)`. Do not move tick or plugin. Do not edit `:plugins:source`.
- Add inter-module `project()` deps. Do not invent AIMI `iosMain`.
- Commit. No push. (Commit agent later.)

---

## Report

`_docs/kmp/staging/lots/report-M.md`: copied, skipped (dest exists vs missing types), rewrite notes (clock, enum, format, stub delete), compile result.

Return DONE | DONE_WITH_CONCERNS | BLOCKED.
