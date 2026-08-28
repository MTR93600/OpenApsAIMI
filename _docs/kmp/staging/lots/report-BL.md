# Lot BL — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f7d57bee3b` (Lot BK)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Hormonitor Compose screen not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Hormonitor JSONL reader is dest (androidMain).** Tick / plugin stay parked. Dest reader is not a live viewer host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `withContext(aapsIoDispatcher)` on `hasData` / `readDays` / `readDayDetail`, same as dump `Dispatchers.IO`. `@Synchronized` index rebuild kept.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `hormonitor/viewer/HormonitorReader.kt` | dest viewer models. `File` / `RandomAccessFile` / `SimpleDateFormat`. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `HormonitorViewerScreen` | Compose T2 |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- JSONL parse: kotlinx `Json.parseToJsonElement` + `OrgJsonCompat`. Dump `opt*OrNull` helpers on `JsonObject`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- Array `optString(i)` → `optStringAt(i)` (attempt 1 used `optStringCompat(i)` by mistake).
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BL.log`.

Attempt 1 **exit 1**: `JsonArray.optStringCompat(i)` receiver mismatch.  
Attempt 2 **exit 0** after `optStringAt`. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File reader only. Compose screen not copied.
- Next graph: File learners (`BasalLearner`, comparison CSV), or Compose. Tick last.

Return DONE.
