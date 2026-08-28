# Lot BJ — Android host T3c and Harmonia JSONL history readers

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2fb0e804fc` (Lot BI)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `JsonlTailReader`.

**The cut:** dump `T3cRuntimeHistoryReader` and `HarmoniaRuntimeHistoryReader` are `File` / `Environment` / `org.json` plus dest `JsonlTailReader`. Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** Advisor screens stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest readers are not a live advisor host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `advisor/data/T3cRuntimeHistoryReader.kt` | androidMain same rel |
| `advisor/data/HarmoniaRuntimeHistoryReader.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy Advisor Compose, `AdvisorHistoryRepository`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `OrgJsonCompat`. Keep dump `opt*OrNull` / default helpers on `JsonObject`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `Enum.values()` → `Enum.entries`.
- Keep `File` / `Environment` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BJ.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BJ.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
