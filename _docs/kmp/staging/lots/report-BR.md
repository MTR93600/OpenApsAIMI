# Lot BR — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `c174fa6f69` (merge `kmp` into this branch; last peel Lot BQ `e02efdab07`)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Viewer Compose not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Hormonitor study JSONL File I/O is dest (androidMain).** Tick / plugin stay parked. Dest exporter is not a live loop host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `writeScope` = `SupervisorJob() + aapsIoDispatcher`; `Channel` DROP_OLDEST queue; `startWriter` + `startLoopWatchdog` `launch` in `init`. Same as dump `Dispatchers.IO`. Sync `init` also calls `restoreDailyState()`.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `physio/AimiHormonitorStudyExporterMTR.kt` | `Context` / `File` / `Settings` / `SystemClock` / Channel writer. Dest JSON rewrite. Dump event JSON shape kept (not dest `PhysioDecisionTraceMTR.toJSON()`) |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `HormonitorViewerScreen` | Compose stays dump |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `org.json` → kotlinx `buildJsonObject` / `buildJsonArray` / `JsonNull` / `OrgJsonCompat`. File-level `putOrNull` overloads for nullable JSON writes.
- Restore: `Json.parseToJsonElement` + `opt*Compat` + `keys.forEach` (not `keys()`).
- `Dispatchers.IO` → `aapsIoDispatcher`. Keep `SystemClock.uptimeMillis()`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`. `isoUtcNow` uses `Date(aimiWallClockMs())`.
- `LTag.APS` → `LTag.AIMI`.
- `"%.2f%%".format` → `aimiFmt2`. SHA hex uses `toString(16).padStart`, not `String.format`.
- `JSONObject(trace.shadowContributions)` → `mapToJsonObject`. List fields → `stringJsonArray`.
- Keep `Context` / `File` / `Settings.Secure` / `Environment` / `@Synchronized`.
- First rewrite used DOTALL on `put(..., ?: JSONObject.NULL)` and swallowed extra puts. Recopied dump and rewrote without DOTALL.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BR.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: study JSONL exporter only. Viewer Compose not copied. Event JSON not replaced with dest `PhysioDecisionTraceMTR.toJSON()`.
- Next graph: ML trainers, autodrive File, or Compose. Tick last.

Return DONE.
