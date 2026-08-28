# Lot BF — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `66f6b8fd97` (Lot BE)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `AimiBackupManager` / `WCycleLearner` not copied. Health Connect library not added. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**AIMI Documents/AAPS storage helper and WCycle CSV append are dest (androidMain).** Helper is not a live backup. Logger is not wired to `WCycleLearner`. Tick / plugin stay parked. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. `@Synchronized` on `determineStorageDirectory` only, same as dump.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `utils/AimiStorageHelper.kt` | `LTag.AIMI`. 16 MiB cap inlined |
| androidMain `wcycle/WCycleCsvLogger.kt` | dest helper. Explicit `Date` / `Locale` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AimiBackupManager` | SAF / Rx |
| `WCycleLearner` | `File` trainer |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Backup size cap copied as helper companion const (same 16 MiB as dump manager).
- `LTag.AIMI`. Star import expanded on the CSV logger.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BF.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: storage helper + WCycle CSV only. SAF backup and learner stay dump.
- Next graph: SAF backup, File learners, or Compose. Health Connect still needs a library. Tick last.

Return DONE.
