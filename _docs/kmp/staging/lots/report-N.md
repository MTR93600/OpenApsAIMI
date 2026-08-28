# Lot N — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a445a3e279` (Lot M)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest scenario types already present were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

---

## Copied (5) — dest did not exist

| rel | notes |
|---|---|
| `scenario/ScenarioProjectionContext.kt` | copy as-is; uses dest `TrajectoryAnalysis` / `MealSafetyContext` / phases |
| `scenario/ScenarioProjectionPair.kt` | copy as-is; uses dest `ScenarioProjectionCurve` |
| `scenario/InsulinSlopePreserveHysteresis.kt` | `@Volatile` → `kotlin.concurrent.Volatile` |
| `scenario/ScenarioProjectionEngine.kt` | `aimiFmt0` / `aimiFmt1` / `aimiFmt2`; therapy math unchanged |
| `scenario/ScenarioProjectionApplicator.kt` | copy as-is except KDoc; maps pair onto `RT.predBGs` |

No dest file was overwritten.

Already in dest and **not** copied: `ScenarioContributor.kt`, `ScenarioContributorId.kt`, `ScenarioProjectionCurve.kt`, `ScenarioProjectionKind.kt`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / meal-phase / physio / UAM (Compose) / Harmonia / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (Compose) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

Also parked (not this list): `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, `recursive/*`, `patient/*` without runtime repos, `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `patient/PhysiologicalTree.kt`, `physio/AIMIPhysioDataModelsMTR.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: none of these 5 use `@Inject`. No Hilt. No `@IntKey(225)`.
- Log: these 5 do not log. No log calls added.
- Time: no `System.currentTimeMillis()`. No clock added.
- Format: `ScenarioProjectionEngine` contributor summaries — `"%.0f".format` → `aimiFmt0`, `"%.1f".format` → `aimiFmt1`, `"%.2f".format` → `aimiFmt2` (Float `contextSmbFactor` via `.toDouble()`). Explicit imports. No `aimiFmt3`.
- `@Volatile`: `import kotlin.concurrent.Volatile` on `InsulinSlopePreserveHysteresis`. Not `kotlin.jvm.Volatile`.
- Explicit imports: `kotlin.math.abs` / `max` / `min` kept on the engine. No fully qualified names at use site.
- KDoc: unresolved `[docs/AIMI_SCENARIO_PROJECTION.md]`, `[preserveInsulinSlope]`, and `[RT.predBGs]` → backticks. Dest-resolvable `[ScenarioProjectionPair]` / `[ScenarioProjectionKind.*]` / `[HOLD_TICKS_DEFAULT]` kept.
- No `android.*`, `File`, `org.json`, `R.string`, or `ResourceHelper` in these 5 files.
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
| 1 | `/tmp/aimi-lot-N.log` | **BUILD SUCCESSFUL in 13m 30s** (EXIT 0). Both requested tasks compiled. First run downloaded Gradle 9.7.1 and Kotlin/Native. |

No retry. Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — 5 copied, 5 Lot L skips left parked as planned, compile **BUILD SUCCESSFUL**.
