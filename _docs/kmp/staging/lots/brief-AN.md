# Lot AN — D2b Medtrum lock + PkPdLogRow extract

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `9843b68af8` (Lot AM)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen except this D2 pump lock.

Pipeline docs step: iOS pump is now **Medtrum**. Write [`adr-g0-d2-ios-pump-medtrum.md`](../../adr-g0-d2-ios-pump-medtrum.md) and point G0 / README at it. Do **not** copy `:pump:medtrum` to `iosMain`. Do **not** vendor Trio `MedtrumKit`. Do **not** register `@IntKey(225)`. Tick last.

**The cut:** dump `pkpd/PkPdCsvLogger.kt` has dest-type `PkPdLogRow` in the same file as Android `File` / `Environment` / `Log`. **Extract the DTO** into dest `pkpd/PkPdLogRow.kt`. **Omit** `object PkPdCsvLogger`. Same class of documented park as Lot W / Lot AI. Cap ~15. Copy count **1 extract**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM builder / Compose screens stay dump. CSV logger stays dump. Auditor host still LiveData. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| dest `pkpd/PkPdLogRow.kt` (**extract**) | dump `data class PkPdLogRow` from `pkpd/PkPdCsvLogger.kt` only. Logger stays dump |

If dest `PkPdLogRow.kt` already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `PkPdCsvLogger`, `PkPdIntegration`, tick, `OpenAPSAIMIPlugin`, `:pump:medtrum`.

---

## Rewrite on copy

None on the DTO. No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AN.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Port Medtrum BLE to KMP this lot.
- Copy the Android plugin to iOS.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AN.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
