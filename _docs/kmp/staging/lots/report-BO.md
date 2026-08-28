# Lot BO — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `89a58bf4d9` (Lot BN)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Neural trainers not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Unified reactivity file learner is dest (androidMain).** Tick / plugin stay parked. Dest learner is not a live tick host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: fire-and-forget `ioScope.launch` on `aapsIoDispatcher` for PersistenceLayer BG/exercise refresh, same as dump `Dispatchers.IO`. Sync JSON load in `init` on the constructing thread.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `learning/UnifiedReactivityLearner.kt` | dest `ReactivityDaypart` + dest `AimiStorageHelper`. `Calendar` / `File` / `FileWriter`. `OrgJsonCompat` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `BasalNeuralLearner` / ML trainers | File + ONNX/TFLite |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Disk JSON: kotlinx parse/build + `OrgJsonCompat`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `"%.nf".format` → `aimiFmt1` / `aimiFmt2` / `NumberFormat.DECIMAL_3`.
- `LTag.APS` → `LTag.AIMI`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BO.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: File learner only. Neural trainer not copied.
- Next graph: ML File stores, hormonitor exporter, or Compose. Tick last.

Return DONE.
