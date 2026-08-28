# Lot BT — Android host Autodrive dataset File lock and CSV lake

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `e6f793e0c1` (Lot BS)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `AimiStorageHelper` and commonMain `AutoDriveState` / `AutodriveDatasetSchema`.

**The cut:** dump `AutodriveDatasetLock` is `ReentrantLock.tryLock`. Dump `AutodriveDataLake` is `File` / `FileWriter` / `String.format`. Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** `AutodriveNeuralTrainer` / backfiller / `MechanismAttentionGate` stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest lake is not a live Autodrive host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `autodrive/learning/AutodriveDatasetLock.kt` | androidMain same rel |
| `autodrive/learning/AutodriveDataLake.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AutodriveNeuralTrainer`, `AutodriveDataBackfiller`, workers, `MechanismAttentionGate`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.nf".format(Locale.US, …)` → `aimiFmt1` / `NumberFormat.DECIMAL_3` / `NumberFormat.withDecimals(4)`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `File` / `FileWriter` / `ReentrantLock` / `SimpleDateFormat` / `@Synchronized` monitor (androidMain). `AapsLock` has no `tryLock`.
- Unresolvable KDoc `[AutodriveDataBackfiller]` / `[AutodriveNeuralTrainer]` → backticks.
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BT.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BT.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
