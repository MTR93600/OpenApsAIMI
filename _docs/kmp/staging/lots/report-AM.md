# Lot AM — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8c943c13fe` (Lot AL)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `OrefReasonParser` / `OrefModelFeatures` were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**OREF feature builder is dest.** ONNX scorer / personal trainer / local pipeline / user insight stay dump (ONNX / File / `Context` / `R.string`). `class PkPdIntegration` stays dump. UAM builder stays dump. Auditor orchestrator still dump (LiveData + integration builder). Tick / plugin stay parked. Dest builder is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. `buildRow` is sync. `APSResult.rawData()` is the same dump cast to `RT`.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| `advisor/oref/OrefFeatureBuilder.kt` | dest snapshot / parser / `RT`. UTC hour: `Calendar` → `Instant` + `TimeZone.UTC`. FQ `abs` → import. Feature index order unchanged |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `OrefLocalPipeline.kt` | `Context` + `Dispatchers` + DB |
| `OrefOnnxScorer.kt` / `OrefPersonalMlTrainer.kt` | ONNX / `File` |
| `OrefUserInsightFormatter.kt` | `Context` + `R.string` |
| `PkpdAdvisor.kt` | `ResourceHelper` / `R.string` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- UTC hour only. Feature math unchanged.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AM.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: OREF feature vector only. Pipeline / ONNX / tick not copied.
- Next graph: auditor host still blocked on LiveData + `PkPdIntegration`. Compose wall. Tick last.

Return DONE.
