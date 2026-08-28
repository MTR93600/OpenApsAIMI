# Lot BH — Android host WCycle disk learner

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `4f202a3691` (Lot BG)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `CyclePhase` and androidMain `AimiStorageHelper`.

**The cut:** dump `WCycleLearner` is dest-type except `File` / `Environment` / `org.json`. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** `WCycleAdjuster` / `WCycleFacade` stay dump. Health Connect stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `wcycle/WCycleLearner.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `WCycleAdjuster`, `WCycleFacade`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `buildJsonArray` / `OrgJsonCompat`.
- `CyclePhase.values()` → `CyclePhase.entries`.
- Keep `Context` / `File` / `EnumMap` / `@Volatile` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BH.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BH.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
