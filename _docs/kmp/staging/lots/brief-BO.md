# Lot BO — Android host unified reactivity file learner

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `89a58bf4d9` (Lot BN)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `ReactivityDaypart` and androidMain `AimiStorageHelper`.

**The cut:** dump `UnifiedReactivityLearner` is `Context` / `File` / `org.json` / `Dispatchers.IO` plus dest storage helper and dest daypart math. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** neural/ML trainers stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest learner is not a live tick host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `learning/UnifiedReactivityLearner.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `BasalNeuralLearner`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `OrgJsonCompat`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.nf".format` → `aimiFmt1` / `aimiFmt2` / `NumberFormat.DECIMAL_3`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `Context` / `File` / `Calendar` / `SimpleDateFormat` / Metro `@Inject` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BO.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BO.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
