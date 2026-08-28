# Lot AZ — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `f52fc5f443` (Lot AY)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `applyTuningPlan` / `tryExportSettings` not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Tuning preference labels and plan preview text are dest.** Apply/export stay dump (`AdvisorHistoryRepository`, Android `Context`). Tick / plugin stay parked. Dest labels are not live prefs. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Objects + string format only.

---

## Copied (1 extract) — dest did not exist

| rel | notes |
|---|---|
| dest `advisor/tuning/TuningPreferenceLabels.kt` | `TuningPreferenceLabels` + `TuningContextFormatSupport`. `NumberFormat.DECIMAL_3` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dump `applyTuningPlan` | dump `AdvisorHistoryRepository` |
| dump `tryExportSettings` | Android `Context` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `%.3f` Locale.US → `NumberFormat.DECIMAL_3` + `SEPARATOR_DOT`.
- Enum `lowercase()` without `Locale.US`.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AZ.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: labels/format extract only. Apply/export stay dump.
- Next graph: Compose wall. Loop telemetry still `ReentrantLock`. Tick last.

Return DONE.
