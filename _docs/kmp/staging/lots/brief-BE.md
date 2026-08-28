# Lot BE — Android host Hormonitor labels + LLM HTTP retry

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `72f184a521` (Lot BD)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. These two dump objects were parked for `Locale.getDefault()` / `Thread.sleep` + `Log`. Host files go in `androidMain`.

**The cut:** dump `HormonitorLabels` needs device locale (viewer T2). Dump `LlmHttpRetry` needs `Thread.sleep` + `android.util.Log` (blocking HTTP host). Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** Hormonitor Compose viewer / LLM clients stay dump. Health Connect stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `hormonitor/viewer/HormonitorLabels.kt` | androidMain same rel |
| `llm/LlmHttpRetry.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `HormonitorViewerScreen`, `HormonitorReader`, Gemini/Claude clients, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- Copy as dump. Keep `Locale.getDefault()`, `Thread.sleep`, `android.util.Log` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BE.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BE.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
