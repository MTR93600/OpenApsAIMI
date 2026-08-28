# Lot BC — Android host phone steps (StepService + provider)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `fb9be233d3` (Lot BB)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest common: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AIMIStepsProviderMTR`. Host files go in `androidMain`.

**The cut:** dump `StepService` is Android sensor host. Dump `AIMIPhoneStepsProviderMTR` is dest-type except `java.time.Instant` and dump `StepService`. Cap ~15. Copy count **2** into **androidMain**.

**Compose-graph wall after this lot:** Composite / HC / steps manager stay dump. Tick / plugin stay parked. Do **not** add `@IntKey(225)`.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `StepService.kt` | `androidMain` … `/openAPSAIMI/StepService.kt` |
| `steps/AIMIPhoneStepsProviderMTR.kt` | `androidMain` … `/openAPSAIMI/steps/AIMIPhoneStepsProviderMTR.kt` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AIMICompositeStepsProviderMTR`, HC steps, `AIMIStepsManagerMTR`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `StepService`: `System.currentTimeMillis()` → `aimiWallClockMs()`. Keep `android.util.Log` (androidMain object, no logger).
- Phone provider: `java.time.Instant` → `kotlin.time.Instant`. `LTag.APS` → `LTag.AIMI`. `System.currentTimeMillis()` → `aimiWallClockMs()`.
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BC.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BC.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
