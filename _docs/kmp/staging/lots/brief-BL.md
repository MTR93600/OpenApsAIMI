# Lot BL — Android host Hormonitor JSONL reader

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f7d57bee3b` (Lot BK)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `HormonitorViewerModels` and androidMain `HormonitorLabels`.

**The cut:** dump `HormonitorReader` is `File` / `RandomAccessFile` / `org.json` / `Dispatchers.IO`. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** Hormonitor Compose screens stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest reader is not a live viewer host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `hormonitor/viewer/HormonitorReader.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `HormonitorViewerScreen`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `OrgJsonCompat`. Keep dump `opt*OrNull` helpers on `JsonObject`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- Keep `File` / `RandomAccessFile` / `SimpleDateFormat` / `Locale.US` / `@Synchronized` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BL.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BL.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
