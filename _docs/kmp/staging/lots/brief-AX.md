# Lot AX — PKPD integration DTOs + steps provider interface

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `db488cdcbb` (Lot AW)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest wall clock is `kotlin.time.Clock` (`AimiWallClock.kt`), not `kotlinx.datetime.Clock`.

**The cut:** dump `PkPdIntegration.kt` has dest-type DTOs beside Compose `class PkPdIntegration`. **Extract the DTOs.** Dump `AIMIStepsProviderMTR.kt` is dest-type except `java.time.Instant`. Cap ~15. Copy count **1 extract + 1 file**.

**Compose-graph wall after this lot:** `class PkPdIntegration` stays dump (`readAimiBehaviorRuntimeProfile`). UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Do **not** copy `AIMIStepsManagerMTR` (Health Connect sync).

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `pkpd/PkPdIntegrationModels.kt` (**extract**) | dump `MealAggressionContext` + `PkpdBolusSample` from `pkpd/PkPdIntegration.kt` only |
| `steps/AIMIStepsProviderMTR.kt` | interface + `AIMIStepsDataMTR` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `class PkPdIntegration`, `AIMIStepsManagerMTR`, HC sync services, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `java.time.Instant` → `kotlin.time.Instant` / `kotlin.time.Clock.System.now()`.
- DTO copy as-is. No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AX.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AX.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
