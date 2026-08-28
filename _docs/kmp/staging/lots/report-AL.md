# Lot AL — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f951f691ba` (Lot AK)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `TuningContextModels.kt` was **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Advisor models + TuningContextEngine are dest.** `PkpdAdvisor` / apply support stay dump (`R.string` / `Context`). `class PkPdIntegration` stays dump. UAM builder stays dump. Auditor orchestrator still dump (LiveData + integration builder). Tick / plugin stay parked. Dest engine is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Metrics DTOs and `computePlan` are sync. No new coroutines.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| `advisor/AdvisorModels.kt` | dest Harmonia / OREF report / `AimiPriority`. Explicit imports. KDoc PersistenceLayer as plain text |
| `advisor/tuning/TuningContextEngine.kt` | dest `AdvisorMetrics` + dest tuning models + dest `PkpdSmbTailDamping`. Math unchanged |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dest `advisor/tuning/TuningContextModels.kt` | already dest |
| `advisor/PkpdAdvisor.kt` | `ResourceHelper` / `R.string` |
| `advisor/tuning/TuningContextApplySupport.kt` | `android.content.Context` |
| `advisor/oref/OrefFeatureBuilder.kt` | `Calendar` / `TimeZone` |
| `class PkPdIntegration` | Compose |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Explicit imports only on models. Tuning math unchanged.
- No Metro. No `aimiFmt3`. No `@IntKey(225)`. Did **not** split `AdvisorModels`.

---

## Compile

Redirect: `/tmp/aimi-lot-AL.log`.

Attempt 1 **BUILD SUCCESSFUL**.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: advisor DTOs + preference-plan engine only. Android apply / PKPD advisor UI / tick not copied.
- `titleResId: Int` is dump-inherited; callers still pass Android `R.string` from dump UI. Dest does not resolve resources.
- Next graph: auditor host still blocked on LiveData + `PkPdIntegration`. Compose wall. Tick last. `OrefFeatureBuilder` still needs a clock rewrite (`Calendar`).

Return DONE.
