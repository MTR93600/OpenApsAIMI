# Lot BG — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `fb3c0ffd79` (Lot BF)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). `AimiBackupManager` not copied (`EventAimiCloudBackup*` missing on this branch). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**JSONL tail read and TPO session/ledger files are dest (androidMain).** `TpoSessionManager` stays dump. Tick / plugin stay parked. Dest persistence is not a live TPO host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file I/O on the caller thread, same as dump.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `advisor/data/JsonlTailReader.kt` | dump `File` / `RandomAccessFile` |
| androidMain `tpo/TpoPersistence.kt` | dest TPO JSON + dest `AimiStorageHelper`. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AimiBackupManager` | `EventAimiCloudBackupTrigger` / `Result` not in `:core:interfaces` |
| `TpoSessionManager` | dump `TpoPersistence` was the File cut; manager stays dump |
| `HormonitorReader` | `org.json` + viewer |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- TPO disk JSON: kotlinx parse/build + pretty `Json`. `toString(2)` not used.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BG.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File tail + TPO persistence only. Cloud backup events not invented.
- Next graph: `WCycleLearner` File, or Compose. Tick last.

Return DONE.
