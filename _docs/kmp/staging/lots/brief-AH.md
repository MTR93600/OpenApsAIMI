# Lot AH — deliberate graph: DualBrain auditor helpers after AuditorVerdict

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `d12a77f181` (Lot AG)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AG landed `AuditorVerdict` / `AuditorInput` / Harmonizer. This lot is **6 dump copies**. Cap ~15.

**The cut:** DualBrain combine / modulate / stable-context / prompt / verdict cache / RT debug lines only need dest `AuditorVerdict` + dest `LocalSentinel` / `DecisionResult`. Do **not** copy `AuditorOrchestrator` (`PkPdRuntime` + `AuditorStatusLiveData`), `AuditorAIService` (android + `org.json`), Jsonl export, data collector, or Compose/UI auditor screens.

**`AuditorVerdictCache`:** dump uses `ConcurrentHashMap`, `@JvmStatic` / `@JvmOverloads`, `System.currentTimeMillis()`. Rewrite to dest style: `AapsLock` + `MutableMap`, `aimiWallClockMs()`, `kotlin.concurrent.Volatile`. Drop JVM-only annotations. Cache keys / TTL / display-align logic stay the same.

**Compose-graph wall after this lot:** orchestrator / AI service / Jsonl stay dump. UAM builder stays dump. Remaining Lot L: `PkpdAbsorptionGuard` / `SmbDampingUsecase` (`PkPdRuntime` in Compose `PkPdIntegration`). Tick / plugin stay parked. Dest engine is not live tick.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (6 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip and report**. Do not overwrite.

None of these six exist at dest (checked 2026-08-28, HEAD `d12a77f181`). Dest `advisor/auditor/` has StatusTracker / LocalSentinel / DataStructures. Dest has no `utils/RtInstrumentationHelpers`.

| rel | why |
|---|---|
| `advisor/auditor/DualBrainHelpers.kt` | dest Sentinel / `AuditorVerdict` / `DecisionResult`. `String.format` → `aimiFmt2` |
| `advisor/auditor/DecisionModulator.kt` | dest `VerdictType` / `AuditorVerdict`. `String.format` → `aimiFmt2` / `aimiFmt0`. FQ `kotlin.math.abs` → import |
| `advisor/auditor/AuditorStableContextGuard.kt` | dest `AuditorVerdict`. `"%.2f".format` → `aimiFmt2` |
| `advisor/auditor/AuditorPromptBuilder.kt` | dest `AuditorInput` / `LlmWorldConservativePreamble`. `JsonObject.toString(2)` is JVM `org.json` — use `toString()` (same JSON, no indent) |
| `advisor/auditor/AuditorVerdictCache.kt` | dest `AuditorVerdict` / `DecisionResult`. KMP cache rewrite as above |
| `utils/RtInstrumentationHelpers.kt` | dest StatusTracker + this-lot cache. `Locale.US` format → `aimiFmt2`. FQ `kotlin.math.abs` → import |

Copy order: DualBrain + modulator + stable guard + prompt; cache; RT helpers (uses cache).

---

## Skip — do not copy this lot

Do **not** copy: `AuditorOrchestrator`, `AuditorAIService`, `AuditorJsonlExport`, `AuditorDataCollector`, `AimiStateTransitionManager`, auditor `ui/` / `model/AuditorUIState`, `PkPdIntegration`, remaining Lot L, tick, `OpenAPSAIMIPlugin`.

---

## Rewrite on copy (Milos / merge rules)

1. **Metro** — none of these 6 use `@Inject`. No `@IntKey(225)`.
2. **Time** — cache: `aimiWallClockMs()`, not `System.currentTimeMillis()`.
3. **Lock** — cache: `AapsLock.withLock`, not `ConcurrentHashMap`.
4. **Format** — `aimiFmt0` / `aimiFmt2`. No `aimiFmt3`. No `java.util.Locale`.
5. **JSON** — prompt embeds compact `JsonObject.toString()`, not indented `org.json`.
6. **Explicit imports.** No FQ `kotlin.math.abs` at use site.
7. **KDoc** — `[docs/…]` → backticks.
8. Keep therapy / combine / TTL math. School English on **new** comments only.

⚠️ ASYNC IMPACT: dump cache is a sync read of async auditor results. Dest keeps the same object + TTL; lock is `AapsLock` instead of CHM. No new coroutines.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect `/tmp/aimi-lot-AH.log`. macOS: `./gradlew`. No `cd &&`.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy orchestrator / AI HTTP / Jsonl / UI / tick / plugin.
- Register `@IntKey(225)`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AH.md`. State cache JVM APIs rewritten. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
