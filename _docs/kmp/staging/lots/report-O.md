# Lot O — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f5eb48553a` (Lot N)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest recursive / patient / physio types already present were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Compose-graph wall:** the recursive **engine** is still blocked. `RecursiveBeliefTickContext` / `RecursiveBeliefModels` / adapters still need dump classifier / `DoseTerminalSnapshot` / UAM Compose. This lot did not copy that graph.

---

## Copied (7) — dest did not exist

| rel | notes |
|---|---|
| `physio/SleepLiveDetector.kt` | `aimiWallClockMs` default; `"%.2f".format` → `aimiFmt2`; `[isNight]` → backticks |
| `physio/HealthContextSnapshot.kt` | already `aimiWallClockMs`; dest `ThermalBeliefDigest` |
| `physio/AIMIPhysioDataModelsMTR.kt` | copy as-is; dest `PhysiologicalPhase` + `OrgJsonCompat`; uses this-lot `HealthContextSnapshot` |
| `physio/AIMIVectorModels.kt` | `javaClass` → `other !is TrajectoryKernelRef`; timestamp `aimiWallClockMs` |
| `patient/PhysioLiveDigest.kt` | copy as-is; `from()` takes `nowMs` |
| `recursive/RecursiveBeliefPreferences.kt` | copy as-is; BooleanKeys already in `:core:keys` |
| `recursive/RbtEpisodeMemory.kt` | `kotlin.concurrent.Volatile`; DetermineBasal KDoc → backticks |

No dest file was overwritten.

Already in dest and **not** copied: `recursive/BeliefLeafId.kt`, `BeliefParadoxId.kt`, `RecursiveBeliefMemory.kt`, `WaveletBelief.kt`, `RbtExtendedSignals.kt`, `ChannelInterferenceOptimizer.kt`, `patient/PatientEventMemory.kt`, `BodyKineticsDigest.kt`, `AimiCascadeArbitrationArtifacts.kt`, `HarmoniaSensorTelemetry.kt`, `physio/MealAbsorptionPhase.kt`, `PhysiologicalPhase.kt`, `BehavioralRiskPolicy.kt`, `HormonalScenarioTerminalCap.kt`, `thermal/ThermalBeliefDigest.kt`.

---

## Skipped — remaining Lot L skips (missing types still dump-only)

| rel | reason |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / meal-phase / physio / UAM (Compose) / Harmonia / `PostHypoDeliveryAuthority` |
| `activity/ExerciseHyperOverridePolicy.kt` | `release/HyperTrajectoryHypoCredibility` → `DoseTerminalSnapshot` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (Compose) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Recursive engine (File-free but not copy-safe)

Not copied: `RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / paradox / resolver / cascade / chaos / release / authority gate. They still need dump classifier / pattern / `DoseTerminalSnapshot` / UAM Compose.

Also parked (not this list): `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, rest of `patient/*` (tree / repos), `keys/AimiStringKey.kt`, tick/plugin, `trajectory/TrajectoryHistoryProvider.kt`, `pkpd/PkPdIntegration.kt`, `orchestration/DoseTerminalSnapshot.kt`, `patient/PhysiologicalTree.kt`, `physio/PhysiologicalPhaseClassifier.kt`, `physio/MealAbsorptionPhaseEngine.kt`, `physio/UamHypothesisState.kt`, anything else with `android.*`, `File`, `org.json`, Compose, `Activity`, tick, or plugin.

---

## Rewrite notes

- Metro: none of these 7 use `@Inject`. No Hilt. No `@IntKey(225)`.
- Log: these 7 do not log. No log calls added.
- Time: `SleepLiveDetector.Input.nowMs` and `GateInput.timestamp` → `aimiWallClockMs()`. `AIMIPhysioDataModelsMTR` / `HealthContextSnapshot` already used it. `PhysioLiveDigest.from` still takes `nowMs`.
- Format: `SleepLiveDetector` wearable summary `"%.2f".format(confidence)` → `aimiFmt2`. No `aimiFmt3`.
- `@Volatile`: `import kotlin.concurrent.Volatile` on `RbtEpisodeMemory`. Not `kotlin.jvm.Volatile`.
- `javaClass`: `TrajectoryKernelRef.equals` now uses `other !is TrajectoryKernelRef`.
- Explicit imports: `aimiWallClockMs` / `aimiFmt2` / `ThermalBeliefDigest`. No fully qualified names at use site.
- KDoc: SleepLiveDetector `[isNight]` → backticks. `RbtEpisodeMemory` `[DetermineBasalAIMI2]` → backticks. Dest `[RecursiveBeliefMemory]` and `[PostHypoAggressiveRiseExit]` kept.
- No `android.*`, `File`, `org.json`, `R.string`, or `ResourceHelper` in these 7 files.
- `SleepLiveDetector.Source.HEALTH_CONNECT` is an enum name, not a Health Connect client.
- Therapy math unchanged except log string formatting and KMP clock / equals.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-O.log` | **BUILD SUCCESSFUL in 44s** (EXIT 0). Both requested tasks compiled (`:plugins:aps:compileKotlinIosSimulatorArm64`, `:plugins:aps:compileAndroidMain`). |

No retry. Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — 7 copied, recursive engine still blocked as planned, compile **BUILD SUCCESSFUL**.
