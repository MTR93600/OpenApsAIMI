# Lot BM — Android host multi-scale basal file learner

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0b19c1fc0b` (Lot BL)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has androidMain `AimiStorageHelper`.

**The cut:** dump `BasalLearner` is `Context` / `File` / `org.json` plus dest storage helper. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** neural/ML trainers stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest learner is not a live tick host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `learning/BasalLearner.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `BasalNeuralLearner`, `UnifiedReactivityLearner`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `OrgJsonCompat`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.nf".format` → `aimiFmt0` / `aimiFmt2` / `NumberFormat.DECIMAL_3`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `Context` / `File` / `AtomicLong` / `AtomicReference` / Metro `@Inject` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BM.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BM.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
