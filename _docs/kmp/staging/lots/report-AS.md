# Lot AS — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `eb940c7b9b` (Lot AR)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Auditor data collector is dest.** Orchestrator / AI service / LiveData stay dump. Compose wall. Tick / plugin stay parked. Dest collector is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `suspend` collector calls `PersistenceLayer`, `TirCalculator`, `TddCalculator`, and dest `TrajectoryHistoryProvider.buildHistory` (`Dispatchers.Default`). Same dump contract. Not wired to tick this lot.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `advisor/auditor/AuditorDataCollector.kt` | dest auditor DTOs / trajectory / PkPdRuntime. FQ → imports. `LTag.APS` → `LTag.AIMI`. `"%.2f".format` → `aimiFmt2`. Dummy activity snapshot and 7d TODOs kept |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AuditorOrchestrator` / `AuditorAIService` | LiveData / Android host |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Imports, log tag, `aimiFmt2` only. Snapshot math unchanged.
- Metro already `@Inject` / `@SingleIn(AppScope)`.
- No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AS.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: collector only. Orchestrator / tick not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
