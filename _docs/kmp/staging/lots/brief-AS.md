# Lot AS — auditor data collector

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `eb940c7b9b` (Lot AR)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has `AuditorDataStructures`, `TrajectoryGuard`, `PkPdRuntime`.

**The cut:** dump `advisor/auditor/AuditorDataCollector.kt` is dest-type (PersistenceLayer / TIR / TDD are common interfaces). Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Do **not** copy `AuditorOrchestrator` (LiveData).

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `advisor/auditor/AuditorDataCollector.kt` | dest auditor DTOs + trajectory + PkPdRuntime |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `AuditorOrchestrator`, `AuditorAIService`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- FQ types → explicit imports (`TrajectoryHistoryProvider`, `TrajectoryGuard`, `PhaseSpaceState`, `StableOrbit`, `ActivityStage`, `LTag`).
- `LTag.APS` → `LTag.AIMI`.
- `"%.2f".format` → `aimiFmt2`.
- Keep dummy activity snapshot and 7d TODO numbers (dump).
- No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AS.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AS.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
