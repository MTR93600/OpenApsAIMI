# Lot M — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added).

A `commonMain` compile is **not** “AIMI runs on iOS”.

---

## Copied (6) — dest did not exist

| rel | notes |
|---|---|
| `trajectory/PhaseSpaceModels.kt` | clock → `aimiWallClockMs()`; `acos` explicit import; `ActivityStage.TAIL`; format rewrite |
| `basal/T3cTrajectoryContext.kt` | copy as-is including `build()` (now that `TrajectoryAnalysis` landed) |
| `trajectory/TrajectoryGuard.kt` | Metro kept; `LTag.APS` → `LTag.AIMI`; format rewrite |
| `trajectory/TrajectoryMetricsCalculator.kt` | copy as-is |
| `wcycle/WCyclePreferences.kt` | `java.lang.Enum.valueOf` → `enumValues<T>().firstOrNull`; blank → `default.name` kept |
| `inflammatory/InflammationAdjuster.kt` | copy as-is |

No dest file was overwritten.

Dest edit (not a dump copy): deleted the inlined package-level `T3cTrajectoryContext` and the “later T1 wave” comment from `basal/DynamicBasalController.kt`. One class remains, in `basal/T3cTrajectoryContext.kt`. Call sites `applyT3cTrajectoryHypoBrake` / `T3cTrajectoryContext.guardBg` kept.

---

## Skipped (5 remaining Lot L skips) — missing types still dump-only (not copied)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / meal-phase / physio / UAM (Compose) / Harmonia / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (Compose) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

Also parked (not this list): `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `patient/PhysiologicalTree.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro `@Inject` / `@SingleIn(AppScope)` kept on `TrajectoryGuard`. No Hilt. No `@IntKey(225)`.
- AIMI logs: `LTag.APS` → `LTag.AIMI` in `TrajectoryGuard`.
- Time: `TrajectoryWarning.timestamp` and `TrajectoryAnalysis.timestamp` defaults use `aimiWallClockMs()`. No `System.currentTimeMillis()`.
- Format: `aimiFmt0` / `aimiFmt1` / `aimiFmt2` with explicit imports. `%.3f` uses `NumberFormat(minFractionDigits = 3).format(..., NumberFormatPlatform.SEPARATOR_DOT)`. Signed two-decimal: sign + `aimiFmt2`. No `aimiFmt3` / `aimiFmtSigned2`.
- Enum: `WCyclePreferences` uses `enumValues<T>().firstOrNull { it.name == raw } ?: default`. No `java.lang.Enum`.
- Explicit imports: `acos`, `ActivityStage.TAIL`. No fully qualified names at use site.
- No `android.*`, `File`, `org.json`, `R.string`, or `ResourceHelper` in these 6 files.
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
| 1 | `/tmp/aimi-lot-M.log` | **BUILD SUCCESSFUL in 53s** (EXIT 0). Both requested tasks compiled. |

No retry. Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — 6 copied, inlined stub deleted, 5 skipped as planned, compile **BUILD SUCCESSFUL**.
