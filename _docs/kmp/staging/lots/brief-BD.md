# Lot BD — Android host PKPD CSV logger

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `74564c3fcf` (Lot BC)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest common: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `PkPdLogRow` (Lot AN). Host files go in `androidMain`.

**The cut:** dump `object PkPdCsvLogger` is Android `File` / `Environment` / `Log`. Dest DTO already exists. **Copy the logger only.** Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** Health Connect stays dump (no new library). Tick / plugin stay parked. Do **not** add `@IntKey(225)`.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| dump `object PkPdCsvLogger` | androidMain `pkpd/PkPdCsvLogger.kt` — dest `PkPdLogRow` |

If dest already exists: **skip and report**. Do not overwrite dest `PkPdLogRow.kt` or `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `data class PkPdLogRow` (dest exists), Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- Omit dump `data class PkPdLogRow`. Use dest DTO in the same package.
- Keep `android.util.Log` / `File` / `Environment` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BD.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BD.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
