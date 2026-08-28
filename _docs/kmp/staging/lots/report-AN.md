# Lot AN — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `9843b68af8` (Lot AM)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `:pump:medtrum` not moved to `iosMain`. Trio `MedtrumKit` not vendored.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**D2 pump is Medtrum.** New ADR `_docs/kmp/adr-g0-d2-ios-pump-medtrum.md`. G0 + README point at it. D1 host (Trio) and D2 CGM (ONE+/G7) unchanged.

**PkPdLogRow is dest.** Dump `PkPdCsvLogger` stays dump (`File` / `Environment` / `Log`). `class PkPdIntegration` stays dump. UAM builder stays dump. Tick / plugin stay parked. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. DTO only.

---

## Copied (1 extract) — dest did not exist

| rel | notes |
|---|---|
| dest `pkpd/PkPdLogRow.kt` | dump `data class PkPdLogRow` from `pkpd/PkPdCsvLogger.kt`. Logger omitted |

No dest file was overwritten. Zero dest-exists skips.

---

## Docs

| file | notes |
|---|---|
| `_docs/kmp/adr-g0-d2-ios-pump-medtrum.md` | new ADR |
| `_docs/kmp/adr-g0-defaults.md` | D2 pump row + still-open |
| `_docs/kmp/README.md` | iOS pump after W8 |

---

## Skipped — not this list

| rel | reason |
|---|---|
| `PkPdCsvLogger` | `File` / `Environment` / `android.util.Log` |
| `PkPdIntegration` | Compose |
| `ReplayQualityExport` | needs tick-nested `AimiDecisionContext.IobSurveillanceExport` |
| tick / `OpenAPSAIMIPlugin` | parked |
| `:pump:medtrum` → `iosMain` | two BLE stacks, two repos |

---

## Rewrite notes

- DTO copy as-is (French dump comment kept).
- No Metro. No `aimiFmt3`. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-AN.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Medtrum lock is docs only. DTO extract is Lot W-style. BLE not duplicated.
- Next graph: Compose wall. Auditor host still LiveData. Tick last.

Return DONE.
