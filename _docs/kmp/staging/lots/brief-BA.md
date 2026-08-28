# Lot BA — clinical report metabolic math extract

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ce30ee43e8` (Lot AZ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AdvisorMetrics` and `JsonNull`.

**The cut:** dump `advisor/AimiClinicalReportEngine.kt` has dest-type `ClinicalContext` + GMI/CV/LBGI math beside dump `AIMIPhysioManagerMTR`. **Extract the DTO and metabolic/algo helpers.** Omit the engine class and physio section. Cap ~15. Copy count **1 extract**.

**Compose-graph wall after this lot:** `class AimiClinicalReportEngine` stays dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `advisor/AimiClinicalReportModels.kt` (**extract**) | dump `ClinicalContext` + metabolic/algo/std helpers from `AimiClinicalReportEngine.kt` only |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `class AimiClinicalReportEngine`, `generatePhysioSection`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- DTO + math copy as-is. `JsonNull` already dest-style.
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BA.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BA.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
