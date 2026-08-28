# Lot AH — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `d12a77f181` (Lot AG)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `AuditorDataStructures` / `LocalSentinel` / `AuditorStatusTracker` were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**DualBrain helpers + verdict cache are dest.** Orchestrator / AI HTTP / Jsonl / data collector stay dump (`PkPdRuntime`, LiveData, `org.json`). UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase`. Tick / plugin stay parked. Dest engine is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: dest `AuditorVerdictCache` still a sync TTL cache of async auditor results. `ConcurrentHashMap` → `AapsLock` + `MutableMap`. No new coroutines.

---

## Copied (6) — dest did not exist

| rel | notes |
|---|---|
| `advisor/auditor/DualBrainHelpers.kt` | dest Sentinel / `AuditorVerdict`. `String.format` → `aimiFmt2`. `javaClass.simpleName` → enum `.name` / `::class.simpleName` |
| `advisor/auditor/DecisionModulator.kt` | dest `VerdictType`. `String.format` → `aimiFmt2` / `aimiFmt0`. FQ `abs` → import |
| `advisor/auditor/AuditorStableContextGuard.kt` | dest `AuditorVerdict`. `"%.2f".format` → `aimiFmt2` |
| `advisor/auditor/AuditorPromptBuilder.kt` | dest `AuditorInput`. `toString(2)` → compact `toString()` (no `org.json` indent) |
| `advisor/auditor/AuditorVerdictCache.kt` | `AapsLock` + `aimiWallClockMs()` + `kotlin.concurrent.Volatile`. Dropped `@JvmStatic` / `@JvmOverloads`. TTL / keys unchanged |
| `utils/RtInstrumentationHelpers.kt` | dest tracker + this-lot cache. `Locale` format → `aimiFmt2`. `javaClass.simpleName` → `::class.simpleName` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AuditorOrchestrator.kt` | dump `PkPdRuntime` + `AuditorStatusLiveData` |
| `AuditorAIService.kt` | `android` + `org.json` |
| `AuditorJsonlExport.kt` / `AuditorDataCollector.kt` | `org.json` / `PkPdRuntime` |
| auditor `ui/` / `AuditorUIState` | Android UI |
| `PkpdAbsorptionGuard.kt` / `SmbDampingUsecase.kt` | remaining Lot L (`PkPdRuntime`) |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Format / clock / lock / no `javaClass` on Native. Combine / modulate / TTL math unchanged.
- Prompt JSON in the LLM string is compact (no 2-space indent).
- No Metro. No `aimiFmt3`. No `@IntKey(225)`.

---

## Compile

Redirect: `/tmp/aimi-lot-AH.log`.

Attempt 1 **FAILED**: `javaClass` unresolved on Native.

Attempt 2 **BUILD SUCCESSFUL** after `::class.simpleName` / enum `.name`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: DualBrain math + cache + RT debug lines only. No orchestrator / HTTP / UI / tick.
- Next graph: `PkpdAbsorptionGuard` DTO park, or host/tick, or leftover dest-type hunt (likely small).

Return DONE.
