# Lot BE — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `72f184a521` (Lot BD)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Hormonitor viewer screen / LLM HTTP clients not copied. Health Connect library not added. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Viewer label text and LLM retry backoff are dest (androidMain).** Dest labels are not a live Compose viewer. Dest retry is not wired to Gemini/Claude. Tick / plugin stay parked. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `LlmHttpRetry.withTransientRetry` still blocks with `Thread.sleep` on the caller thread (dump). Callers must stay off the UI thread.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `hormonitor/viewer/HormonitorLabels.kt` | dump `Locale.getDefault()` |
| androidMain `llm/LlmHttpRetry.kt` | dump `Log` + `Thread.sleep` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `HormonitorViewerScreen` / `HormonitorReader` | Compose / `File` viewer |
| Gemini / Claude / OpenAI clients | T2 HTTP + Context |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Copy as dump. No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BE.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: two host objects only. Viewer and LLM clients stay dump.
- Next graph: File loggers / SAF, or Compose. Tick last.

Return DONE.
