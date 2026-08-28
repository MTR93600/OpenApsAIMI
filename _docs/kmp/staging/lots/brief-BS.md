# Lot BS — Android host neural train-and-publish core

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0a176e7a5f` (Lot BR)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `AimiNeuralNetwork` and `AimiNeuralModelStore` (Lot BQ).

**The cut:** dump `NeuralModelTrainer` is `File` + dest store. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** `AimiSmbTrainer` / `BasalNeuralLearner` / workers stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest trainer core is not a live training host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `ml/NeuralModelTrainer.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AimiSmbTrainer`, `BasalNeuralLearner`, workers, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- Keep `File` (androidMain).
- Unresolvable KDoc links to dump-only types → backticks.
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BS.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BS.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
