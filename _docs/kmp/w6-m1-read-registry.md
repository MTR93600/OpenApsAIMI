# W6 — M1 read-registry on freeze (docs + capture)

> **Freeze:** tag `aimi-baseline-2026-08-26` = `1ae418e106`  
> **Source:** `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`  
> **Class:** `DetermineBasalaimiSMB2`  
> **This week does not rewrite the tick.** Lot E0 of annex 8 only.

## What W6 is

M1.1–M1.5 start here: list every field and every service read on the **freeze**, give a first taxonomy, and capture the 109 config keys. This is the contract that later `engine-replay-v1` must cover.

W6 is **not**:

- Android dual-write of snapshots (M1.6–M1.11 / lot E1)
- an isolated Android replay harness (M1.12)
- `:plugins:aimi-engine` or `evaluate()` (W7)
- a copy of the 18k-line tick onto this branch
- One+ smoke on a phone (not run; no install)

## Counts vs annex 8

Annex 8 counted SHA `06e7bc5021` (25 Aug 2026). W6 recounts the freeze tag.

| Surface | Annex 8 | Freeze `1ae418e106` |
|---|---:|---:|
| Live `private var` field decls | 239 | **239** (includes 5 `@Volatile` fields) |
| `private var` token in file | 239 | 240 (one commented `//private var enablebasal`) |
| Unique `*Key.Name` refs | 109 | **109** |
| `preferences` token | 259 | 263 |
| `persistenceLayer` | 18 | 18 |
| `tddCalculator` | 9 | 11 |
| `tirCalculator` | 18 | 30 |
| `physioAdapter` | 37 | 37 |

`tirCalculator` grew on freeze. Replay must capture TIR the way the freeze tick reads it, not the older annex 8 count.

Re-generate the CSVs:

```text
python3 _docs/kmp/tools/generate_m1_read_registry.py
```

## Taxonomy first pass (M1.1)

All **239** live `private var` names are in [`generated/m1-private-var-registry.csv`](generated/m1-private-var-registry.csv). Every row has a class. That is the inventory.

The class is **not** all signed by CORE yet:

| Confidence | Count | Meaning |
|---|---:|---|
| `ANNEX8` | 12 | Named in annex 8. Keep this class until an ADR. |
| `ANNEX8_OPEN` | 1 | `adaptiveMult` — annex 8 says learner **or** computed. Do not pick. |
| `HEURISTIC` | 176 | Name / `ThisTick` / annex 8 scratch examples. |
| `NEEDS_REVIEW` | 50 | `last*` snapshots. Prove they are not read on tick N+1 before write. |

| Class | Count | Destination later |
|---|---:|---|
| `WORKING` | 157 | `AimiTickWorkingState` (new each tick) |
| `ENGINE_STATE` | 27 | `AimiEngineState` |
| `INPUT` | 26 | `AimiInputSnapshot` (copies today) |
| `TELEMETRY` | 16 | `AimiDecisionTrace` |
| `CACHE` | 9 | shell `ReadSetCache`, not the KMP engine |
| `EFFECT` | 3 | events after the decision |
| `LEARNER_STATE` | 1 | `adaptiveMult` only in this table; learners also live in injected services |

`targetBg` (member) is `INPUT`. Schedule `target_bg` is a **local** in the tick (step 24 and 29 of the harvest map). Do not merge them.

Missing values must stay `TimedValue.Missing`. Do not store `0` for HR, steps, TDD, or TIR when the read failed.

Stateful `private val` (atomics, lazy files, `PatternCapHold`, learners) are in [`generated/m1-private-val-stateful.csv`](generated/m1-private-val-stateful.csv).

## Persist / rebuild / reset (M1.3)

Only four fields have a proven **persist** path on freeze (`preferences.put` of `AimiLongKey`):

| Field | Pref key | Policy |
|---|---|---|
| `internalLastSmbMillis` | `AimiLongKey.LastPrebolusTime` | persist |
| `internalLastLegacyPrebolusMillis` | `AimiLongKey.LastLegacyPrebolusTime` | persist |
| `pendingLegacyPrebolusUnit` | `AimiLongKey.PendingLegacyPrebolusUnitMilli` | persist |
| `pendingLegacyPrebolusExpiry` | `AimiLongKey.PendingLegacyPrebolusExpiry` | persist |

Also written during the tick (effect / one-shot, not only config):

- `BooleanKey.OApsAIMIMealAdvisorTrigger` → `false`
- `DoubleKey.OApsAIMILastEstimatedCarbs` / `OApsAIMILastEstimatedCarbTime` → `0.0`

All other `ENGINE_STATE` rows are **`unknown`**: member only. A restart may zero them. That is an open product question (README). Do not invent a silent rebuild.

`PatternCapHold` (`private val patternCapHold`) is engine state in annex 8. Policy still unknown.

Notification clocks (`lastCycleNotificationDay`, basal-learner notify times) are **EFFECT / platform**. They must not change physiology after restart.

## Service reads during the tick (M1.2)

Constructor + `@Inject lateinit` list: [`generated/m1-injected-services.csv`](generated/m1-injected-services.csv).  
Token hits and `persistenceLayer.*` methods: [`generated/m1-service-reads.csv`](generated/m1-service-reads.csv).

These reads happen **inside** the tick class today. `evaluate()` must not call them. Capture **before** the tick.

### Room / history (`persistenceLayer`)

| Method | What it feeds |
|---|---|
| `getTherapyEventDataFromTime` (`CANNULA_CHANGE`) | pump site / age cache |
| `getNewestBolusOfType(SMB)` | last SMB cache |
| `getMostRecentCarbByDate` / `getFutureCob` / `getMostRecentCarbAmount` | meal snapshot |
| `getUserEntryDataFromTime` | notes / tags |
| `getTherapyEventDataFromTime` (`SENSOR_CHANGE`) | sensor insertion |
| `getStepsCountFromTimeToTime` | steps windows |
| `getHeartRatesFromTimeToTime` | HR windows |
| `getTemporaryBasalsStartingFromTime` | TBR history |
| `getBolusesFromTime` | bolus history |

`Therapy(persistenceLayer)` is also built in several places. That is still a DB read.

### Other platform reads

| Service | Capture as |
|---|---|
| `tddCalculator` / `tirCalculator` | insulin / quality inputs with freshness |
| `iobCobCalculator.ads.getBucketedDataTableCopy()` | glucose history |
| `profileFunction.getProfile` | profile snapshot |
| `physioAdapter` | physiology snapshot (not zeros) |
| `activePlugin.activePump` / `activeBgSource` | capabilities + source id |
| `preferences.get` (229 call sites, **109** keys) | `AimiConfigSnapshot` later |
| `wCycleFacade` / `wCyclePreferences` / `wCycleLearner` | cycle config + learner |
| `activityManager` / `glucoseStatusCalculatorAimi` | glucose + activity |
| `autodriveEngine` / `autodriveGater` | autodrive state + command (shell) |
| `determineIoScope.launch` | **async refresh**. Forbidden inside `evaluate()`. Snapshot the last cache value. |
| `storageHelper` / `File(` / CSV / JSONL | TELEMETRY. Engine must not open files. |
| `notificationManager` / `uiInteraction` | EFFECT events |
| `auditorOrchestrator` / `tpoOrchestrator` | advice for tick **N+1**, do not mutate tick N |

## Config keys (M1.7 inventory only)

[`generated/m1-config-keys.csv`](generated/m1-config-keys.csv) lists the **109** unique keys. W5 contracts still hold only `AimiConfigSnapshot.schemaVersion`. Filling the 109 fields is M1.7, not W6.

## Learners / models (M1.4, list only)

Injected on the freeze class:

- `basalLearner`, `unifiedReactivityLearner`, `basalNeuralLearner`, `basalMlTrainingCoordinator`
- `wCycleLearner`, `NightGrowthResistanceLearner` (constructed in the class)
- `insulinObserver` / `PkPdIntegration`
- UAM file `modelUAM.tflite` (keep; do not rewrite as a Kotlin net)

W6 does not capture UAM tensors. That is M1.9.

## Async / product callbacks (M1.5)

⚠️ ASYNC IMPACT — already on freeze, not introduced by W6:

- `determineIoScope` refreshes caches **off the tick thread**.
- Harvest step **43** may call the auditor async. Auditor must not mutate tick N.
- Meal advisor one-shot lives in a preference write.
- Step **44** writes JSON/Hormonitor **after** the decision. That is TELEMETRY.

## Tick order (do not reorder)

Harvest copy: [`harvest/AIMI_ORCHESTRATION_ROADMAP.md`](harvest/AIMI_ORCHESTRATION_ROADMAP.md).  
Steps **0–45**. Early returns are marked `return` in that table. `targetBg` (member) ≠ `target_bg` (schedule).

## Capture that W6 does **not** implement

Annex 8 `engine-replay-v1` still needs, on Android, later:

- `AimiInputSnapshot` filled from the reads above
- `AimiEngineState` before/after
- `AimiModelBundle` (UAM + learners)
- expected command + state hash on **every** early return

W5 only defined the snapshot **types**. No Android writer yet. That is correct for W6.

## One+ smoke

Not run. This Mac session did not drive a device, and the project rule is: never install the app unless the user asks.

## Gate

W6 **inventory** is done. M1 **go/no-go** (Android can replay its own decisions) is **not** done. Stop iOS dosing work if M1.12 fails later. Do not start W7 until this registry is accepted.
