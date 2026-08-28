# Lot Y — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `5b2c729c92` (Lot X)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Lot X dest `physio/pattern/*` and Lot W dest classifier / HTR / meal engine / DTS DTO were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**TickContext is still blocked** on dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority) and dump `SafetyPredictionTerminals` (resolver file) — not only `PatternCapKind`. **Models still needs dump `HarmoniaAction`** (`HarmoniaSmbAuthorityDecision` / `HarmoniaDecision` tree). Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

---

## Copied (5) — dest did not exist

| rel | notes |
|---|---|
| `activity/ExerciseHyperOverridePolicy.kt` | dest `HyperTrajectoryHypoCredibility.highBgBandMgdl`. `[ThyroidEffectModel]` → backticks |
| `autodrive/controller/MpcController.kt` | dest `HyperTrajectoryMpcFeedForward` + `AutoDriveState` / `AutoDriveCommand` / `InsulinActionModel` / `HyperSeverityTier`. Metro `@Inject` / `@SingleIn` / `AppScope` kept. `LTag.APS` → `LTag.AIMI`. `"%.nf".format` → `aimiFmt2` |
| `autodrive/learning/PhysiologicalStressMaskBuilder.kt` | dest pattern snapshot / classifier / physio / gate / inflammation. `String.format(Locale.US, "%.2f")` → `aimiFmt2`. Dropped `java.util.Locale`. `[MechanismAttentionGate]` → backticks |
| `quality/SmbBindingTrace.kt` | dest `PatternCapKind`. `internal` kept. kotlinx.serialization builders kept. Dropped unused `JsonArray` import. `org.json` remains comment-only |
| `pkpd/PkpdSoftFloorPathMin.kt` | dest `DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL` + dest `AdvancedPredictionCurves`. `[AdvancedPredictionEngine]` → backticks. Dest `[DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL]` stays a link |

No dest file was overwritten. Zero dest-exists skips.

The five files do not depend on each other.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `AimiRiskEnvelope` + dump `SafetyPredictionTerminals` |
| `risk/AimiRiskEnvelope.kt` | dump Authority + `MealCertainty` + resolver. Not split |
| `risk/SafetyPredictionTerminalsResolver.kt` | dump Harmonia / `MealCertainty` / Authority. Not split |
| `risk/DecisionPredictionAuthority.kt` | UAM / tree / latent / posterior. Not split |
| `recursive/RecursiveBeliefModels.kt` | dump `HarmoniaSmbAuthorityDecision` / dump `HarmoniaAction` |
| `patient/HarmoniaSmbAuthorityDecision.kt` / `HarmoniaDecision.kt` | dump `HarmoniaAction` / tree |
| recursive engine / adapters / paradox / cascade | TickContext / Models |
| `MealCorrectionContextResolver.kt` / `T3cAutodriveBasalBridge.kt` / `PkpdAbsorptionGuard.kt` / `SmbDampingUsecase.kt` | remaining Lot L skips (4) |
| `UamHypothesisState` / `PhysioLatentState` / PatientMode / Authority / tick / plugin | Compose / dump graph / parked |

Five Lot L skips become **four** after Copy (exercise override landed). **Not copied.**

---

## Rewrite notes

- Metro: `MpcController` keeps `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn`. No Hilt. No `javax.inject`. No `@IntKey(225)`. No `ApsPluginRegistrations`. The other four have no `@Inject`.
- Log: `MpcController` `LTag.APS` → `LTag.AIMI` (two `aapsLogger.debug` calls). The other four do not call `aapsLogger`. No log calls added.
- Time: no `System.currentTimeMillis()`. `SmbBindingTrace.timestampMs` is a field. Kept.
- Format: no `String.format`, no `java.util.Locale`, no `"%.nf".format`. No `aimiFmt3`.
  - Stress mask `formatMask`: `String.format(Locale.US, "%.2f", …)` → `aimiFmt2`. Dropped `java.util.Locale`. Explicit import.
  - MPC log / horizon labels: `Double.format(2)` / `"%.${digits}f".format` → `aimiFmt2`. Helper removed.
- `@Volatile`: none of these 5 use it. Not added.
- `AapsLock` / `kotlin.synchronized`: none of these 5 use them. Not added.
- Explicit imports: no fully qualified names at use site. `aimiFmt2` imported in MPC and stress mask.
- KDoc: dump types → backticks (`ThyroidEffectModel`, `MechanismAttentionGate`, `AdvancedPredictionEngine`). Dest-resolvable `[PatternCapKind.HARD]` / `[DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL]` / `[HyperTrajectoryHypoCredibility]` stay links. Dest `InsulinActionModel` / `ContextInfluenceEngine` KDoc not edited.
- School English: new or changed comments only. Dump French comments on `MpcController` left as-is.
- JSON: kotlinx.serialization builders kept. No `org.json`. No `R.string`.
- Therapy math unchanged except format / import / KDoc / unused `JsonArray` drop.

---

## Compile

Tasks:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-Y.log` | **BUILD SUCCESSFUL in 1m 1s** (EXIT 0). Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`. |

Compile success is **not** “AIMI runs on iOS”. No plugin registration, no tick, no enact.

---

## Return

**DONE** — copied **5**. TickContext still blocked on dump `AimiRiskEnvelope` / `SafetyPredictionTerminals` (not only `PatternCapKind`). Models still needs dump `HarmoniaAction`. Compile **BUILD SUCCESSFUL**.
