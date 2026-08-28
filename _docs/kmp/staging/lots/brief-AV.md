# Lot AV — patient state presentation

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `01f299a065` (Lot AU)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `PatientRuntimeSnapshot`, `aimiFmtSigned1`.

**The cut:** dump `patient/PatientStatePresentation.kt` is dest-type except `java.util.Locale`. Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Do **not** copy `AimiStateTransitionManager` (`@Synchronized` / `ConcurrentLinkedQueue`).

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `patient/PatientStatePresentation.kt` | dest runtime snapshot / thermal / mode enums |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `PatientStateRuntimeRefresher`, Compose screens, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- Drop `java.util.Locale`. `lowercase()` / `titlecase()` (ASCII enum names, same as `Locale.US`).
- `String.format(Locale.US, "%+.1f", …)` → `aimiFmtSigned1`.
- Keep dump English presentation strings (not `R.string`).
- No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AV.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AV.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
