# Lot BU — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `d00d010349` (Lot BT)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest host: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Health Connect library not added. `:pump:medtrum` not moved to `iosMain`. `AimiSmbTrainer` / `BasalMlTrainerWorker` not copied.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**Basal/T3C neural File I/O and fire-and-forget training coordinator are dest (androidMain).** Tick / plugin stay parked. Dest learner is not a live tick host. `HoldAimiEngine` stays Hold.

⚠️ ASYNC IMPACT: `BasalMlTrainingCoordinator.maybeTrainAsync` launches on `aapsIoDispatcher` with a `Mutex`, same as dump `Dispatchers.IO`. `init` also calls `loadPersistedState()` on the constructing thread. Worker stays dump, so dest coordinator is unused until the worker peels.

---

## Copied (2) — dest did not exist

| rel | notes |
|---|---|
| androidMain `learning/BasalNeuralLearner.kt` | dest store + dest `BasalMlTrainingCoordinator.INPUT_SIZE`. Unused dump `Environment` / coroutine imports dropped |
| androidMain `learning/BasalMlTrainingCoordinator.kt` | same file as `BasalMlDatasetParser` / `BasalLabelWindow` / `BasalCsvSchema`. Dest `NeuralModelTrainer` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `BasalMlTrainerWorker` / `AimiMlTrainingScheduler` | WorkManager host |
| `AimiSmbTrainer` | next graph |
| Health Connect | no `connect-client` on `:plugins:aps` |
| tick / `OpenAPSAIMIPlugin` | parked |

---

## Rewrite notes

- `org.json` state file → `Json.parseToJsonElement` / `buildJsonObject` / `optLongCompat`.
- `Dispatchers.IO` → `aapsIoDispatcher`.
- `System.currentTimeMillis()` → `aimiWallClockMs()`.
- `LTag.APS` → `LTag.AIMI`.
- `"%.5f"` / `"%.6f"` → `NumberFormat.withDecimals(5)` / `DECIMAL_6`. Other logs → `aimiFmt1/2`.
- Keep `Context` / `File` / `@Synchronized` / `AtomicLong`.
- No Metro plugin map. No `@IntKey(225)`. Tick not copied.

---

## Compile

Redirect: `/tmp/aimi-lot-BU.log`.

Attempt 1 **exit 0**. Tasks: `:plugins:aps:compileKotlinIosSimulatorArm64` `:plugins:aps:compileAndroidMain`.

A `commonMain` compile is **not** “AIMI runs on iOS”. Tick last.

---

## Review

APPROVE.

- Spec: basal/T3C learner + coordinator only. Worker not copied.
- Next graph: `AimiSmbTrainer`, Autodrive trainer, or Compose. Tick last.

Return DONE.
