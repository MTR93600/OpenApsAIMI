# Lot BJ — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2fb0e804fc` (Lot BI)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Advisor Compose not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**T3c and Harmonia JSONL history readers are dest (androidMain).** Tick / plugin stay parked. Dest readers are not a live advisor host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file tail on the caller thread, same as dump.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `advisor/data/T3cRuntimeHistoryReader.kt` | dest `JsonlTailReader`. `Environment` / `File`. `OrgJsonCompat` |
| androidMain `advisor/data/HarmoniaRuntimeHistoryReader.kt` | dest T3c file helper + dest tail reader. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| Advisor Compose / `AdvisorHistoryRepository` | Gson / UI |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- JSONL parse: kotlinx `Json.parseToJsonElement` + `OrgJsonCompat`. Dump `opt*OrNull` helpers kept on `JsonObject`.
- `shadow_only` default true kept via `optBooleanOrDefault` (compat has no fallback).
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `Enum.values()` → `Enum.entries`.
- Harmonia `production.opt*` platform-type calls became `production?.`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BJ.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: JSONL history readers only. Advisor screens not copied.
- Next graph: File stores (`AIMIPhysioContextStoreMTR`, `HormonitorReader`). Tick last.

Return DONE.
