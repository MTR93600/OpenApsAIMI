# Lot AR — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `ce70dfa509` (Lot AQ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Tick file not copied. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Replay quality export is dest.** `IobSurveillanceExport` extracted from tick nested type. Dump `AimiDecisionContext` / tick stay dump. Auditor collector stays dump. Compose wall. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Builder is sync. Dest export is not live tick.

---

## Copied (1 extract + 1) — dest did not exist

| rel | notes |
|---|---|
| dest `quality/IobSurveillanceExport.kt` | dump nested DTO from `DetermineBasalAIMI2.kt`. KDoc `[capSmbDose]` / `[RT.units]` → backticks |
| `quality/ReplayQualityExport.kt` | dest physio / patient / RBT. Nested type → dest DTO. Nullable JSON → `JsonNull` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `DetermineBasalAIMI2` / `AimiDecisionContext` | tick |
| `AuditorDataCollector` | PersistenceLayer host; not this list |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Type name only for the extract. Tag / guard strings unchanged.
- Lot AK `JsonNull` for nullable `put`.
- No Metro. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AR.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: quality export + IOB DTO only. Tick not split beyond the documented extract.
- Next graph: Compose wall. Auditor collector still dump. Tick last.

Return DONE.
