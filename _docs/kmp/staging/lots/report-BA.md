# Lot BA — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ce30ee43e8` (Lot AZ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `class AimiClinicalReportEngine` not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**ClinicalContext and GMI/CV/LBGI helpers are dest.** Engine class stays dump (`AIMIPhysioManagerMTR`). Tick / plugin stay parked. Dest math is not a live clinical report. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. DTO + pure math.

---

## Copied (1 extract) — dest did not exist

| rel | notes |
|---|---|
| dest `advisor/AimiClinicalReportModels.kt` | `ClinicalContext` + `AimiClinicalMetabolicReport` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dump `class AimiClinicalReportEngine` | dump `AIMIPhysioManagerMTR` |
| dump `generatePhysioSection` | manager `getStatus()` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- DTO + math as dump. Nested type lifted to file-level `ClinicalContext`.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BA.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: metabolic extract only. Engine class stays dump.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
