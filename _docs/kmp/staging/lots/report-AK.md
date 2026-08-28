# Lot AK — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `54389cd694` (Lot AJ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `AdvancedPredictionCurves.kt` was **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Snapshot JSON + PKPD prediction modulation + AdvancedPredictionEngine are dest.** `class PkPdIntegration` stays dump. UAM builder stays dump. Auditor orchestrator still dump (LiveData + integration builder). Tick / plugin stay parked. Dest engine is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. JSON writer / modulation / curve predict are sync. No new coroutines. `aimiWallClockMs()` is the same wall-clock read as dump `System.currentTimeMillis()`.

---

## Copied (3) — dest did not exist

| rel | notes |
|---|---|
| `orchestration/IntelligenceSnapshotJson.kt` | dest snapshot. Nullable `skip_reason` / `dia_learn_blocked_by` → `JsonNull` |
| `pkpd/PredictionPhysioModulation.kt` | dest Runtime / meal engine / UAM DTO / latent. `Locale` format → `aimiFmt2`. FQ `abs` → import |
| `pkpd/AdvancedPredictionEngine.kt` | dest curves + this-lot modulation. `aimiWallClockMs()`. Dropped `@JvmStatic` / `@JvmOverloads`. Inlined freeze `PREDICTION_GRAPH_MIN_MINUTES` = 120. Did **not** add it to kmp `Constants` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dest `pkpd/AdvancedPredictionCurves.kt` | already dest; do not overwrite |
| `class PkPdIntegration` | Compose `readAimiBehaviorRuntimeProfile` |
| `AimiLoopTelemetry` / `AimiLoopGate` | JVM lock / hormonitor exporter |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- JSON keys unchanged. Therapy math unchanged. French dump comments kept.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`. No new `project()` deps.

---

## Compile

Redirect: `/tmp/aimi-lot-AK.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: snapshot JSON + prediction modulation + curve engine only. Tick / integration / auditor host not copied.
- Next graph: auditor host still blocked on LiveData + `PkPdIntegration`. Compose wall. Tick last.

Return DONE.
