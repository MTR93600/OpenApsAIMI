# Lot BU — Android host basal neural learner and training coordinator

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `d00d010349` (Lot BT)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `AimiNeuralNetwork`, `BasalMlModelStore`, `NeuralModelTrainer`, `AimiStorageHelper`.

**The cut:** dump `BasalNeuralLearner` is `Context` / `File` / `String.format`. Dump `BasalMlTrainingCoordinator` (same file as parser) is `Dispatchers.IO` / `org.json` / dest trainer. Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** `AimiSmbTrainer` / `BasalMlTrainerWorker` stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest learner is not a live tick host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `learning/BasalNeuralLearner.kt` | androidMain same rel |
| `learning/BasalMlTrainingCoordinator.kt` | androidMain same rel (includes `BasalMlDatasetParser`) |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `BasalMlTrainerWorker`, `AimiSmbTrainer`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `buildJsonObject` / `OrgJsonCompat`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- `String.format` / `"%.nf".format` → `aimiFmt1/2` / `NumberFormat.withDecimals`.
- Keep `Context` / `File` / `@Synchronized` / `AtomicLong` (androidMain).
- Drop unused dump imports (`Environment`, unused coroutines on the learner).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BU.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BU.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
