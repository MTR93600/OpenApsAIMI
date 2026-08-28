# Lot AU — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8a3aa81748` (Lot AT)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Bitmap / camera / HTTP providers not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**FoodAnalysisPrompt + meal JSON parsers are dest.** `AIVisionProvider` stays dump (Bitmap). Compose wall. Tick / plugin stay parked. Dest parsers are not live tick. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Parsers are sync.

---

## Copied (1 extract + 2) — dest did not exist

| rel | notes |
|---|---|
| dest `advisor/meal/FoodAnalysisPrompt.kt` | dump object from `AIVisionProvider.kt`. `org.json` → `OrgJsonCompat`. Clamp / FPU / recommended-carb math unchanged |
| `advisor/meal/MealVisionJsonParser.kt` | copy as-is |
| `advisor/meal/MealVisionChatCompletionsParser.kt` | copy as-is |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AIVisionProvider` | Bitmap |
| camera Activities / HTTP providers | T2 |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- JSON reads only (`OrgJsonCompat`). Prompt text and carb math unchanged.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AU.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: meal JSON parse path only. Camera not copied.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
