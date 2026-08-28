# Lot BM — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0b19c1fc0b` (Lot BL)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Neural/ML trainers not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Multi-scale basal file learner is dest (androidMain).** Tick / plugin stay parked. Dest learner is not a live tick host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file I/O via dest `AimiStorageHelper`. `init` loads from disk on the constructing thread, same as dump. `AtomicReference` / `AtomicLong` kept.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `learning/BasalLearner.kt` | dest `AimiStorageHelper`. Dump unused `Context` kept. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `BasalNeuralLearner` / `UnifiedReactivityLearner` | File + ML trainers |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Disk JSON: kotlinx parse/build + `OrgJsonCompat`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.nf".format` → `aimiFmt0` / `aimiFmt2` / `NumberFormat.DECIMAL_3`.
- `LTag.APS` → `LTag.AIMI`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BM.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File learner only. Neural trainer not copied.
- Next graph: `ComparisonCsvParser` File, or Compose. Tick last.

Return DONE.
