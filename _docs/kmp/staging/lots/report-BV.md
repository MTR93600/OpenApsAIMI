# Lot BV — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `2702822231` (Lot BU)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. Autodrive workers / `AimiSmbTrainer` not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Autodrive CSV backfill, attention-weight File I/O, and attention gate are dest (androidMain).** Tick / plugin stay parked. Dest Autodrive File graph is not a live engine host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: none in these three classes. Sync File I/O under `AutodriveDatasetLock` (backfiller / trainer read). Gate `init` loads weights on the constructing thread. Workers stay dump.

---

## Copied (3) — dest did not exist

| rel | notes |
|---|---|
| androidMain `autodrive/learning/AutodriveNeuralTrainer.kt` | dest lake lock + dest schema. Pretty JSON indent 4 spaces (dump `toString(4)`) |
| androidMain `autodrive/learning/AutodriveDataBackfiller.kt` | dest `PersistenceLayer` + dest lock |
| androidMain `autodrive/learning/MechanismAttentionGate.kt` | dest trainer `WEIGHTS_FILE_NAME` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| Autodrive workers | WorkManager host |
| `AimiSmbTrainer` | needs dump `AimiBehaviorRuntimeProfile` (`AimiAutonomyMode` + `R.string`) |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- Weight JSON: `Json.parseToJsonElement` / `buildJsonObject` / `optDoubleCompat`. `"bias_balanced" in json` for dump `has`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- Keep `Context` / `File` / dest `PersistenceLayer`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BV.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: Autodrive File trainer + backfiller + gate only. Workers not copied.
- Next graph: `AimiSmbComparator`, Compose math (`AimiBehaviorRuntimeProfile` if `ApsStrings` can replace `R.string`), or workers. Tick last.

Return DONE.
