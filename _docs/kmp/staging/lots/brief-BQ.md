# Lot BQ — Android host neural weight File stores

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `cacd627eaa` (Lot BP)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Host files go in `androidMain`.

**The cut:** dump `AimiNeuralNetwork` is `File` / `org.json`. The three model stores are thin `File` facades over it. Cap ~15. Copy count **4** into **androidMain**.

**Compose-graph wall after this lot:** `NeuralModelTrainer` / workers stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest stores are not a live trainer host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `aimiNeuralNetwork.kt` | androidMain same rel |
| `ml/AimiNeuralModelStore.kt` | androidMain same rel |
| `ml/AimiSmbModelStore.kt` | androidMain same rel |
| `learning/BasalMlModelStore.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `NeuralModelTrainer`, `AimiSmbTrainer`, workers, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `buildJsonArray` / `OrgJsonCompat`.
- Keep `File` (androidMain).
- Unresolvable KDoc `[NeuralModelTrainer]` → backticks.
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BQ.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BQ.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
