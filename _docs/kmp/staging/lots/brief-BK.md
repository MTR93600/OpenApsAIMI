# Lot BK — Android host physio context file store

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `4166406029` (Lot BJ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `PhysioContextMTR` / `PhysioBaselineMTR` / `ProbeResult` JSON in commonMain.

**The cut:** dump `AIMIPhysioContextStoreMTR` is `Context` / `File` / `ReentrantReadWriteLock` / `org.json`. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** Health Connect physio manager stays dump. Tick / plugin stay parked. Dest store is not a live physio host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `physio/AIMIPhysioContextStoreMTR.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy PhysioManager, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `Json` / `buildJsonObject` / `OrgJsonCompat`. Pretty print with kotlinx `Json { prettyPrint = true }`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- `android.os.Environment` → imported `Environment`.
- Keep `Context` / `File` / `ReentrantReadWriteLock` / `@Volatile` / Metro `@Inject` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BK.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BK.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
