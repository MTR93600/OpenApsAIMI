# Lot BP — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `8ffc153984` (Lot BO)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Auditor UI / orchestrator not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Auditor JSONL export is dest (androidMain).** Tick / plugin stay parked. Dest export is not a live auditor host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync `File.appendText` on the caller thread, same as dump.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `advisor/auditor/AuditorJsonlExport.kt` | dest `AuditorVerdict` / `DecisionResult`. `File`. `JsonNull` for dump `JSONObject.NULL` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AuditorOrchestrator` / `AuditorAIService` | LiveData / UI |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Writes: kotlinx `buildJsonObject` / `JsonNull`. `org.json` not used.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BP.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: JSONL export only. Auditor UI not copied.
- Next graph: neural weight File stores, or hormonitor exporter. Tick last.

Return DONE.
