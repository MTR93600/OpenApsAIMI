# Lot BR — Android host Hormonitor study JSONL exporter

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c174fa6f69` (merge `kmp` into `kmp-aimi-migration-study`; last peel Lot BQ `e02efdab07`)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `PhysioDecisionTraceMTR`, `PhysioLiveDigest`, `ThermalBeliefDigest`, and androidMain `HormonitorReader`.

**The cut:** dump `AimiHormonitorStudyExporterMTR` is `Context` / `File` / `Settings` / `SystemClock` / `Dispatchers.IO` / `org.json`. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** Hormonitor Compose screens stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest exporter is not a live loop host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `physio/AimiHormonitorStudyExporterMTR.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `HormonitorViewerScreen`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `buildJsonObject` / `buildJsonArray` / `JsonNull` / `OrgJsonCompat`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`. Keep `SystemClock.uptimeMillis()`.
- `LTag.APS` → `LTag.AIMI`.
- `"%.2f%%".format` → `aimiFmt2`.
- Keep `Context` / `File` / `Settings.Secure` / `Environment` / `@Synchronized` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BR.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BR.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
