# Lot BN — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `cb509b973a` (Lot BM)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Comparator / simulator not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Comparison CSV parser is dest (androidMain).** Tick / plugin stay parked. Dest parser is not a live comparator host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync `File` read on the caller thread, same as dump.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `comparison/ComparisonCsvParser.kt` | dest `ComparisonEntry` / report DTOs. Dump `e.printStackTrace()` kept. `aimiFmt1` / `aimiFmt2` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AimiSmbComparator` / `AimiSmbSimulator` | File + tick comparator |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `String.format` / `"%.nf".format` → `aimiFmt1` / `aimiFmt2`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BN.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: CSV parser only. Comparator not copied.
- Next graph: File learners (`UnifiedReactivityLearner`), or Compose. Tick last.

Return DONE.
