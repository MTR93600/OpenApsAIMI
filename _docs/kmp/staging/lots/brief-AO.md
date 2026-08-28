# Lot AO — SMB refinement feature schema

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `375fe54bf6` (Lot AN)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`.

**The cut:** dump `ml/SmbRefinementFeatureSchema.kt` is dest-type (patient/physio types already dest). Trainers / model stores stay dump (`File`). Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM builder / Compose screens stay dump. CSV logger stays dump. Tick / plugin stay parked. Do **not** copy `AimiAdaptationStatusBuilder` yet (needs dump `BasalLearner` snapshots).

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `ml/SmbRefinementFeatureSchema.kt` | dest `PhysioLatentState` / `PatientModeOrchestrator` / `CausalStatePosterior` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AimiSmbTrainer`, `AimiNeuralModelStore`, tick, `OpenAPSAIMIPlugin`, `:pump:medtrum`.

---

## Rewrite on copy

None expected. `lowercase()` is Kotlin stdlib. No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AO.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AO.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
