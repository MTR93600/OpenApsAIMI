# Lot BQ — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `cacd627eaa` (Lot BP)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Trainers / workers not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**On-device neural weight File I/O is dest (androidMain).** Tick / plugin stay parked. Dest stores are not a live trainer host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync `File` save/load on the caller thread, same as dump.

---

## Copied (4) — dest did not exist

| rel | notes |
|---|---|
| androidMain `aimiNeuralNetwork.kt` | `File` + dest JSON rewrite. `OrgJsonCompat` |
| androidMain `ml/AimiNeuralModelStore.kt` | dest network. tmp/bak rotate |
| androidMain `ml/AimiSmbModelStore.kt` | dest shared store |
| androidMain `learning/BasalMlModelStore.kt` | dest shared store |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `NeuralModelTrainer` / `AimiSmbTrainer` / workers | training host |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Weight JSON: kotlinx parse/build arrays. `getInt` / `getJSONArray` → `getValue` + `jsonPrimitive` / `jsonArray` (still throw, dump catch → null).
- `schemaVersion` still `optIntCompat(..., 0)`.
- KDoc `NeuralModelTrainer` → backticks (dump class).
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BQ.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Weight File I/O only. Trainers not copied.
- Next graph: hormonitor exporter, ML trainers, or Compose. Tick last.

Return DONE.
