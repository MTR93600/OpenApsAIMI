# Lot AR — IobSurveillanceExport extract + replay quality

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ce70dfa509` (Lot AQ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Lot AK: nullable JSON → `JsonNull`.

**The cut:** dump `quality/ReplayQualityExport.kt` is dest-type except nested `AimiDecisionContext.IobSurveillanceExport` in the tick file. **Extract the DTO**. Omit tick / `AimiDecisionContext`. Cap ~15. Copy count **1 extract + 1 file**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Auditor collector stays dump this lot (PersistenceLayer host). Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `quality/IobSurveillanceExport.kt` (**extract**) | dump `AimiDecisionContext.IobSurveillanceExport` from `DetermineBasalAIMI2.kt` only |
| `quality/ReplayQualityExport.kt` | dest physio / patient / RBT types |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`. Do not copy the tick file.

---

## Skip — do not copy this lot

Do **not** copy `DetermineBasalAIMI2`, `AimiDecisionContext`, `AuditorDataCollector`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `AimiDecisionContext.IobSurveillanceExport` → dest `IobSurveillanceExport`.
- Nullable `put(...)` → `JsonNull` (Lot AK). Therapy strings / tags unchanged.
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AR.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AR.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
