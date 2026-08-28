# Lot BP — Android host auditor JSONL export

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8ffc153984` (Lot BO)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AuditorVerdict` and `DecisionResult`.

**The cut:** dump `AuditorJsonlExport` is `File` / `org.json`. Cap ~15. Copy count **1** into **androidMain**.

**Compose-graph wall after this lot:** Auditor UI / LiveData orchestrator stay dump. Health Connect stays dump. Tick / plugin stay parked. Dest export is not a live auditor host.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | dest |
|---|---|
| `advisor/auditor/AuditorJsonlExport.kt` | androidMain same rel |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AuditorOrchestrator`, `AuditorAIService`, Health Connect, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `org.json` → kotlinx `buildJsonObject` / `JsonNull` / `JsonObject`.
- Keep `File` (androidMain).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-BP.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Add Health Connect library.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-BP.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
