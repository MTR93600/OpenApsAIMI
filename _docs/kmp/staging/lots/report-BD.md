# Lot BD — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `74564c3fcf` (Lot BC)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest common: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `PkPdLogRow` not overwritten. Health Connect library not added. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**PKPD CSV append is dest (androidMain).** Uses dest `PkPdLogRow`. Logger is not wired into the tick. Tick / plugin stay parked. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file append on the caller thread, same as dump.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `pkpd/PkPdCsvLogger.kt` | object only. Dest DTO. Dump `Log` / `File` / `Environment` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| dump `data class PkPdLogRow` | dest exists (Lot AN) |
| Health Connect steps | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- DTO omitted. Same package as dest `PkPdLogRow`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BD.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: CSV logger host only. Tick does not call it yet.
- Next graph: other File hosts, or viewer `Locale` labels. Tick last.

Return DONE.
