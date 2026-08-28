# Lot AZ — tuning preference labels extract

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f52fc5f443` (Lot AY)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `TuningContextModels` and `NumberFormat.DECIMAL_3`.

**The cut:** dump `advisor/tuning/TuningContextApplySupport.kt` has dest-type label/format helpers beside Android export + dump `AdvisorHistoryRepository`. **Extract the labels and format helpers.** Omit apply/export. Cap ~15. Copy count **1 extract**.

**Compose-graph wall after this lot:** apply/export stay dump (`Context`, `AdvisorHistoryRepository`). Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `advisor/tuning/TuningPreferenceLabels.kt` (**extract**) | dump `TuningPreferenceLabels` + format helpers from `TuningContextApplySupport.kt` only |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `applyTuningPlan`, `tryExportSettings`, `AdvisorHistoryRepository`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `String.format(Locale.US, "%.3f", value)` → `NumberFormat.DECIMAL_3` (same as dest `aimiFmt*`).
- `lowercase(Locale.US)` → `lowercase()` (ASCII enum names).
- FQ `PreferenceKey` → explicit import.
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AZ.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AZ.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
