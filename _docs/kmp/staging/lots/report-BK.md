# Lot BK — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `4166406029` (Lot BJ)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. PhysioManager not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Physio context JSON file store is dest (androidMain).** Tick / plugin stay parked. Dest store is not a live Health Connect physio host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync file I/O under `ReentrantReadWriteLock`, same as dump. `init` restores from disk on the constructing thread.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `physio/AIMIPhysioContextStoreMTR.kt` | dest `PhysioContextMTR` / baseline / `ProbeResult`. `Context` / `File` / `ReentrantReadWriteLock`. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips. Dump unused injected `Context` kept.

---

## Skipped — not this list

| rel | reason |
|---|---|
| PhysioManager / HC workers | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Disk JSON: kotlinx parse/build + pretty `Json`. Dest `toJSON` / `fromJSON`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BK.log`.

Attempt 1 **exit 0** (`--quiet`; no `e:`). Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File store only. Health Connect not added.
- Next graph: `HormonitorReader` File, or Compose. Tick last.

Return DONE.
