# Lot AY — context JSON deserializer + database steps provider

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `529e1b96a6` (Lot AX)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `ContextIntent`, `AIMIStepsProviderMTR`, `aapsIoDispatcher`, kotlin atomics.

**The cut:** dump `AIMIDatabaseStepsProviderMTR` is dest-type after Lot AX (PersistenceLayer is a common interface). Dest `ContextIntentDeserializer` already exists — **skip**. Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** Composite / phone / HC steps stay dump (`StepService`, Health Connect). Steps manager stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `steps/AIMIDatabaseStepsProviderMTR.kt` | dest `AIMIStepsProviderMTR` + PersistenceLayer |

Skip dest-exists: `context/ContextIntentDeserializer.kt`.

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AIMICompositeStepsProviderMTR` (injects dump HC + phone), `AIMIPhoneStepsProviderMTR` (`StepService`), `AIMIStepsManagerMTR`, HC sync, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `LTag.APS` → `LTag.AIMI`.
- `java.time.Instant` → `kotlin.time.Instant` (`toEpochMilliseconds()`).
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `java.util.concurrent.atomic` → `kotlin.concurrent.atomics` (`load` / `store` / `compareAndSet`, `@OptIn(ExperimentalAtomicApi::class)`).
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- FQ `app.aaps.core.data.model.SC` → explicit import.
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AY.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AY.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
