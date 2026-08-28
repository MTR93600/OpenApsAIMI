# Lot BS — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `0a176e7a5f` (Lot BR)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. `AimiSmbTrainer` / `BasalNeuralLearner` / workers not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**On-device train-and-publish core is dest (androidMain).** Tick / plugin stay parked. Dest trainer is not a live training host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none. Sync train/validate/publish on the caller thread, same as dump.

---

## Copied (1) — dest did not exist

| rel | notes |
|---|---|
| androidMain `ml/NeuralModelTrainer.kt` | `File` + dest `AimiNeuralModelStore` / `AimiNeuralNetwork`. No JSON rewrite |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `AimiSmbTrainer` / `BasalNeuralLearner` / workers | training host still dump |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Keep `File`. Dest store `save` / `load` / `delete` already match dump.
- No `org.json` / `String.format` / `Dispatchers` in this file.
- KDoc `[AimiNeuralModelStore]` resolves in androidMain. `BasalNeuralLearner` mentions already in backticks.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BS.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: shared train-and-publish core only. SMB/basal trainers not copied.
- Next graph: autodrive File lake, `BasalNeuralLearner`, or Compose. Tick last.

Return DONE.
