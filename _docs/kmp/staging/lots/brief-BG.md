# Lot BG — Android host JSONL tail reader + TPO file persistence

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `fb3c0ffd79` (Lot BF)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has TPO session/ledger JSON and androidMain `AimiStorageHelper`. Host files go in `androidMain`.

**The cut:** dump `JsonlTailReader` is `File` / `RandomAccessFile`. Dump `TpoPersistence` is File + dest TPO types + dest storage helper. Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** `AimiBackupManager` stays dump (`EventAimiCloudBackup*` not on this branch). `TpoSessionManager` stays dump. Health Connect stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `advisor/data/JsonlTailReader.kt` | androidMain same rel |
| `tpo/TpoPersistence.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AimiBackupManager`, `TpoSessionManager`, `HormonitorReader`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- TPO: `org.json.JSONObject` → `Json.parseToJsonElement` / `OrgJsonCompat` / `buildJsonObject`. Pretty print with kotlinx `Json { prettyPrint = true }`.
- Keep `File` / `RandomAccessFile` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BG.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Add `EventAimiCloudBackup*` to `:core:interfaces`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BG.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
