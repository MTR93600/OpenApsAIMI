# Lot BN — Android host comparison CSV parser

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `cb509b973a` (Lot BM)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `ComparisonEntry` and report DTOs in commonMain.

**The cut:** dump `ComparisonCsvParser` is `File` plus dest comparison types. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** `AimiSmbComparator` / simulator stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest parser is not a live comparator host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `comparison/ComparisonCsvParser.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AimiSmbComparator`, `AimiSmbSimulator`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `String.format` / `"%.nf".format` → `aimiFmt1` / `aimiFmt2`.
- Keep `File` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BN.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BN.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
