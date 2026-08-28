# Lot AW — auditor state transition manager

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a7075ce385` (Lot AV)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Dest already has sealed `AuditorUIState` and `AapsLock`.

**The cut:** dump `advisor/auditor/AimiStateTransitionManager.kt` is dest-type except JVM monitor / `ConcurrentLinkedQueue` / wall clock. Cap ~15. Copy count **1**.

**Compose-graph wall after this lot:** `PkPdIntegration` / UAM refresher / Compose screens stay dump. Tick / plugin stay parked. Do **not** copy dump `AuditorUIState` (`@ColorRes`). Do **not** copy `AimiLoopGate` (`ReentrantLock.tryLock`).

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy

| rel | why |
|---|---|
| `advisor/auditor/AimiStateTransitionManager.kt` | dest sealed `AuditorUIState` |

If dest already exists: **skip and report**. Do not overwrite dest `SmbDamping.kt`.

---

## Skip — do not copy this lot

Do **not** copy `advisor/auditor/model/AuditorUIState.kt`, `AuditorOrchestrator`, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy

- `@Synchronized` → `AapsLock.withLock` (same monitor region: `transitionTo` only).
- `ConcurrentLinkedQueue` → `ArrayDeque` (mutations stay inside that lock).
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- Keep dump log strings. No Metro. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AW.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Register the plugin.
- Copy BLE to `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AW.md`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
