# Lot BF — Android host AIMI storage helper + WCycle CSV logger

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `66f6b8fd97` (Lot BE)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Host files go in `androidMain`.

**The cut:** dump `AimiStorageHelper` is Android `File` / `Environment` / `Context`. Dump `WCycleCsvLogger` needs that helper. Do **not** copy `AimiBackupManager` (SAF / Rx). Inline the 16 MiB backup cap. Cap ~15. Copy count **2**.

**Compose-graph wall after this lot:** `WCycleLearner` / `AimiBackupManager` stay dump. Health Connect stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `utils/AimiStorageHelper.kt` | androidMain same rel |
| `wcycle/WCycleCsvLogger.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AimiBackupManager`, `WCycleLearner`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `LTag.APS` → `LTag.AIMI`.
- `AimiBackupManager.MAX_BACKUP_FILE_BYTES` → same `16L * 1024L * 1024L` on the helper (BackupManager stays dump).
- WCycle logger: `java.util.*` → explicit `Date` / `Locale`.
- Keep `Context` / `File` / `Environment` / `@Synchronized` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BF.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BF.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
