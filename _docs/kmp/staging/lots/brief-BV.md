# Lot BV — Android host Autodrive trainer, backfiller, and attention gate

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2702822231` (Lot BU)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `AutodriveDataLake` / `AutodriveDatasetLock` / `AimiStorageHelper`.

**The cut:** dump Autodrive trainer / backfiller / gate are `File` / `org.json` / dest lake lock. Cap ~15. Copy count **3** into **androidMain**.

**Compose-graph wall after this lot:** Autodrive workers stay dump. `AimiSmbTrainer` stays dump (`AimiBehaviorRuntimeProfile` needs dump `AimiAutonomyMode` + `R.string`). Health Connect stays dump. Tick / plugin stay parked. Dest Autodrive File graph is not a live engine host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `autodrive/learning/AutodriveNeuralTrainer.kt` | androidMain same rel |
| `autodrive/learning/AutodriveDataBackfiller.kt` | androidMain same rel |
| `autodrive/learning/MechanismAttentionGate.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy Autodrive workers, `AimiSmbTrainer`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `OrgJsonCompat`. Pretty indent 4 spaces (dump `toString(4)`).
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `Context` / `File` / dest `PersistenceLayer` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BV.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BV.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
