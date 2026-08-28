# Lot AT — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `b43326bb5d` (Lot AS)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Camera / Bitmap providers not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Meal estimate DTOs + sanitizer + user prompt are dest.** `FoodAnalysisPrompt` / vision HTTP / camera Activities stay dump (`org.json` / Bitmap). Compose wall. Tick / plugin stay parked. Dest sanitizer is not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sanitizer and prompt builders are sync.

---

## Copied (1 extract + 2) — dest did not exist

| rel | notes |
|---|---|
| dest `advisor/meal/MealEstimateModels.kt` | dump `VisibleFoodItem` / `MacroRange` / `EstimationResult` from `AIVisionProvider.kt` |
| `advisor/meal/MealAdvisorResponseSanitizer.kt` | copy as-is |
| `advisor/meal/MealVisionUserPrompt.kt` | dest Harmonia + preamble |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AIVisionProvider` / `FoodAnalysisPrompt` | Bitmap / `org.json` |
| `MealVisionJsonParser` / chat completions parser | dump `FoodAnalysisPrompt` |
| camera Activities / HTTP providers | T2 |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- DTO extract only. Sanitizer / prompt copy as-is.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AT.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: meal DTO + post-parse hardening + prompt text. Camera not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
