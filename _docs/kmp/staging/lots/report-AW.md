# Lot AW — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `a7075ce385` (Lot AV)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dump `@ColorRes` `AuditorUIState` not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Auditor state transition manager is dest.** Uses dest sealed `AuditorUIState`. Orchestrator / LiveData stay dump. Compose wall. Tick / plugin stay parked. Dest manager is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none for coroutines. JVM `@Synchronized` / `ConcurrentLinkedQueue` → `AapsLock` + `ArrayDeque`. Same lock region (`transitionTo` only). `getLogs` still unlocked, as dump.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `advisor/auditor/AimiStateTransitionManager.kt` | dest sealed `AuditorUIState`. `aimiWallClockMs`. `LTag.AIMI`. Evict-50 log kept |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `advisor/auditor/model/AuditorUIState.kt` | `@ColorRes` |
| `AimiLoopGate` | `ReentrantLock.tryLock` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Lock / queue / clock / log tag only. Transition rules unchanged (`canTransitionTo`).
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AW.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: auditor state machine only. ColorRes UI model not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
