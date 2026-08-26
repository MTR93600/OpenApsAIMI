# AIMI KMP — execution plan (Android + iOS)

> **Use this file to code.** The blueprint stays the architecture bible.  
> **ADR:** [`adr-g0-defaults.md`](adr-g0-defaults.md)  
> **AIMI freeze:** `aimi-baseline-2026-08-26` = `origin/dev_OAPSAIMI` @ `1ae418e106`  
> **This branch:** `kmp-aimi-migration-study`  
> **Date:** 2026-08-26  
> **Language:** school English on purpose.

If this file and an older annex disagree, this file and the ADR win for *what to do next*. The annexes still win for *why* on ML, Tree/Harmonia, iOS runtime, and replay contracts.

---

## 0. Verdict

The AIMI **dose engine** can run on Android and iOS from one `commonMain` tree.

The AIMI **Android plugin as one blob** cannot. UI, Health Connect, SMS, SAF, Wear, Camera, Hilt, WorkManager, and Android BLE/NFC stay in shells or get a rewrite.

Dexcom ONE+ and Libre 3 **are in scope**. They land as:

- Android: AIMI native plugins, Metro instead of Hilt.
- iOS: `G7SensorKit` / `LibreTransmitter` (+ CoreNFC), same `GlucosePort`.
- Engine: typed glucose (`SourceSensor`, age, warmup). Never GATT.

Strategy is **S2 extract**. Do not rebase `dev_OAPSAIMI` onto `kmp`.

---

## 1. Current truth (do not use the 25 Aug SHAs as live tips)

| Item | Audit 25 Aug | Truth 26 Aug |
|---|---|---|
| Study branch | `kmp` @ `4957c26eb8` | `kmp-aimi-migration-study` @ merge of later `kmp` (Metro) |
| AIMI | `06e7bc5021` | **`1ae418e106`** (+6; One+ BLE hardening) |
| AIMI code on this branch | planned | **absent** (docs only, until extract) |
| One+ / Libre 3 git sources | “free to port” | **missing** (leftover `build/` dirs are not source) |
| DI | Hilt assumed | **Metro is the law** |
| Tick class | docs say `DetermineBasalAIMI2` | class is **`DetermineBasalaimiSMB2`** in `DetermineBasalAIMI2.kt` |
| Tick map | blueprint S0–S6 | AIMI has a **45-step** map (see §5) |

UAM model (frozen):

- path on AIMI: `app/src/main/assets/modelUAM.tflite`
- size 4504 bytes
- SHA-256 `741c5248fb81a2551ee4c612c9cbf2be97dbf6b434db7b7407a3ba2214235092`

---

## 2. Dual-platform map (AIMI package → KMP)

Root on AIMI: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
(~442 files / ~102 kLOC main, 243 test files).

| Package / module | commonMain | Android shell | iOS shell |
|---|---|---|---|
| `patient` `recursive` `safety` `pkpd` (math) `basal` `smb` `ISF` `risk` `scenario` `trajectory` `activity` `inflammatory` `model` `prediction` `decision` `quality` `release` `carbs` `extensions` `validation` | **yes** after ports | — | — |
| Tick `DetermineBasalaimiSMB2` + `orchestration/` | **yes** after extract to `evaluate` | AAPS `OpenAPSAIMIPlugin` | Trio `AimiKmpEngine` |
| `ports/Ports.kt` | rewrite: clock, PKPD, UAM, safety. **Drop pump actuators** | effect runner | effect runner |
| `ml/` `aimiNeuralNetwork.kt` | math + schema | file store | file store |
| `AimiModelHandler.kt` / `AimiUamHandler` | `UamInferenceEngine` | LiteRT CPU | TFLite CPU |
| `learning/` `autodrive/learning/` trainers | trainer math | WorkManager | BGProcessingTask |
| `physio/pattern` `thermal` `thyroid` `gate` | **yes** | — | — |
| `physio` HC repo/workers, `steps/`, `StepService` | snapshot DTO only | Health Connect | HealthKit |
| `advisor` parsers / oref math | **yes** | Activities, Camera2, ONNX load | SwiftUI / AVFoundation / ORT later |
| `tpo` rules | **yes** | NotificationCompat | UserNotifications |
| `compose/` `context/` UI | no v1 | stay Android | SwiftUI later (M10) |
| `hormonitor` schema/reader | **yes** | Compose viewer, Documents path | Files / Document Picker |
| `sos/` | no | SMS | drop or Messages rewrite |
| `utils/` SAF / Documents | no | Android FS | FileManager |
| `di/` Hilt | no | Metro composition | Swift composition |
| `OpenAPSAIMIPlugin` | no | Metro plugin | not used on iOS |
| `:plugins:dexcom_oneplus` + `:plugins:libkeks` | parse/policy later | GATT + Java KEKS | `G7SensorKit` |
| `:plugins:libre3` | crypto/parse later | GATT + NFC | `LibreTransmitter` + CoreNFC |
| `:plugins:eversense` `:pump:apex` | later | Android | out of first 8 weeks |

**Not migratable as-is:** copy `DetermineBasalaimiSMB2` into `commonMain`.  
**Migratable with conditions:** shared engine + two shells + glucose port.

---

## 3. KMP engine contract (replaces `AimiTickContext`)

Today `AimiTickContext` still holds AAPS types and `UiInteraction`. Properties **alias** the `determine_basal` parameters (not a deep copy). That is unsafe for KMP.

Target:

```kotlin
fun evaluate(
    input: AimiInputSnapshot,
    state: AimiEngineState,
    models: AimiModelBundle
): AimiTickResult
```

| Bucket | Must contain |
|---|---|
| `AimiInputSnapshot` | Precomputed glucose, pump/temp/caps, profile, IOB/COB, physio `TimedValue`, frozen config, capabilities, clocks |
| `AimiEngineState` | Hysteresis, RBT rings, PKPD observer, ISF fusion, learner checkpoints — versioned and serializable |
| `AimiModelBundle` | UAM SHA + schema, Kotlin NN generations |
| `AimiTickResult` | Command, next state, training/persist **events**, trace, safety. **No pump call** |

Shell **before** `evaluate`: read DB, prefs, CGM, HealthKit/HC, pump caps.  
Shell **after** `evaluate`: persist, notify, enact, export.

Locked: config is immutable for one tick. No callback may mutate tick N (Auditor → N+1). Missing ≠ 0. RMSSD ≠ SDNN.

⚠️ **ASYNC:** `OpenAPSAIMIPlugin.invoke` is `suspend`. TDD/TIR caches refresh in the background. Stage 43 Auditor can mutate today. Training WorkManager must never block dose.

---

## 4. CGM — One+ and Libre 3

### 4.1 Why both platforms

iOS closed loop wakes on **BLE notify**, not on a 5 minute timer. One+ and Libre 3 native BLE are the heartbeat. Nightscout / xDrip / Notification Reader / Dexcom Share **cannot** own that heartbeat.

### 4.2 Android (this branch)

Land from freeze tag, Metro not Hilt:

1. `SourceSensor.DEXCOM_ONEPLUS_NATIVE` (`AAPS-DexcomOnePlus`)
2. `SourceSensor.LIBRE_3_NATIVE` (`AAPS-Libre3`)
3. `CgmWarmupProvider` / `CgmSensorStatusProvider` in `core:interfaces` `commonMain`
4. `:plugins:libkeks` (Java, Android only)
5. `:plugins:dexcom_oneplus`
6. `:plugins:libre3` (Stub default, Real behind the AIMI gate)
7. `:plugins:source` glue: plugins `@IntKey(446)` / `@IntKey(447)` like existing Metro source plugins

Do not copy `SourcePluginsListModule` Hilt.

### 4.3 iOS

| CGM | Kit | Heartbeat |
|---|---|---|
| ONE+ / G7 (first) | `G7SensorKit` | CGM BLE indicate/notify |
| Libre 3 (second) | `LibreTransmitter` + CoreNFC | CGM BLE after NFC start |

Host (Trio) owns connect, restore, and `CBCentralManagerOptionRestoreIdentifierKey`. AIMI only consumes `GlucosePort`.

`libkeks` never goes to Native. iOS uses kit crypto.

D12: re-pair on iOS. No Android bond import.

### 4.4 GlucosePort (freeze early)

`onGlucose(sample)` → shared ingest (age, warmup, staging) → persistence / snapshot → `evaluate`.  
Staging glucose is **never** loop glucose until `promoteStagingToProduction`.

---

## 5. AIMI tick order (45 steps) — do not reorder

Source of truth on AIMI: `openAPSAIMI/orchestration/AIMI_ORCHESTRATION_ROADMAP.md` (not in this folder until harvested).

Group into blueprint stages S0–S6 **without** changing order.

| # | Step | Early return? | KMP note |
|---|---|---|---|
| 0 | `traceDetermineBasalTick` | | telemetry port |
| 1 | build `AimiTickContext` | | becomes snapshot (deep copy) |
| 2 | `runEarlyDetermineBasalStages` | | |
| 3 | `bootstrapPhysiologyAfterEarlyTick` | | |
| 4 | `buildDecisionContextInitRtSosAndFlatShadow` | | use **local** `flatBGsDetected` after override |
| 5 | physio IOB profiler + insulin observer | | |
| 6 | WCycle + load glucose | **Abort** | |
| 7 | T9 physio / early PKPD / tube | | not BYODA combined delta |
| 8 | combined delta BYODA + dynamic peak | | |
| 9 | pre-therapy Autodrive / BYODA flags | | |
| 10 | clocks / TIR / copy deltas | | before Therapy |
| 11 | Therapy + exercise lockout | **ReturnEarly** | |
| 12 | legacy meal modes | **early** | **before** T3c |
| 13 | T3c brittle | **return** | |
| 14 | signal prep PKPD | **StaleAbort** | |
| 15 | trajectory → context → TDD/ISF | | |
| 16 | advanced predictions + PRED_PIPE | | `minBg` composite ≠ `min_bg` |
| 17 | safety halt | **Halt** | **before** Meal Advisor |
| 18 | Meal Advisor | **return** if applied | |
| 19 | Hard Brake Lyra | **return** | **before** Autodrive V3 |
| 20 | Autodrive V3 | | |
| 21 | Autodrive V2 fallback | | locked if V3 acted |
| 22 | post-hypo classify **once** | | |
| 23 | post-hypo compression / Drift | **return** | |
| 24 | global basal schedule | | local `target_bg` ≠ member `targetBg` |
| 25 | steps / HR / tick deltas | | |
| 26 | TDD / basalaimi / PAI | | |
| 27 | endo + activity | | |
| 28 | ISF bounds + physio multipliers | | |
| 29 | PKPD predictions / BGI | | |
| 30 | UAM model + hypo guard | | `UamInferenceEngine` |
| 31–33 | SMB instruction + PKPD cap | | |
| 34 | snapshot RT reset / priority commands | | |
| 35 | meal/hyper basal boost | **CompleteLoop** | |
| 36 | reason compact | | |
| 37 | WCycle IC/CSF | | |
| 38 | CarbsAdvisor + hard hypo basal | **return** | CarbsAdvisor uses member `targetBg` |
| 39 | meal first 30 / NGR | **EarlyTempBasal** | |
| 40 | max IOB gate | **ReturnTempBasal** | |
| 41 | insulinReq / microbolus | | |
| 42 | `BasalDecisionEngine.decide` | | |
| 43 | learners + **Auditor** | | Auditor must become N+1 event |
| 44 | medical JSONL + Hormonitor export | | shell I/O only |
| 45 | `return finalResult` | | `AimiTickResult` |

Invariants 4–9 in that AIMI roadmap are clinical. A reorder is a product change, not a KMP cleanup.

---

## 6. Three tracks, weeks 1–8

```text
W1 freeze + SourceSensor + lifecycle APIs
        /              |               \
       v               v                v
 (A) empty aimi-*    (B) CGM Android    (C) empty iOS harness
 contracts/engine     libkeks → One+     XCFramework hello()
                      → Libre 3
        \              |               /
         +--------- W8 go/no-go --------+
```

| Week | PRs | Owner |
|---|---|---|
| **W1** | This plan + ADR. Tag freeze. `SourceSensor` + warmup/lifecycle in `commonMain`. Converters. | ARCH |
| **W2** | `:plugins:libkeks`. Skeleton `:plugins:dexcom_oneplus` + `settings.gradle` | DEVICE |
| **W3** | Copy One+ from freeze tag. Metro `@IntKey(446)`. `fullDebug` APK | DEVICE |
| **W4** | `:plugins:libre3` compiles (Stub default). Empty `:plugins:aimi-contracts` (JVM + iosSimulatorArm64) | DEVICE, ARCH |
| **W5** | Snapshot **interfaces** only. Swift harness links `hello()` | ARCH, IOS |
| **W6** | M1 read-registry on freeze tag (docs + capture, not rewrite). One+ smoke if hardware. Inventory: [`w6-m1-read-registry.md`](w6-m1-read-registry.md) | ARCH, CORE, DEVICE |
| **W7** | Empty `:plugins:aimi-engine` + forbidden-import check. Libre 3 NFC Android or explicit flag | ARCH, DEVICE |
| **W8** | APK: One+ selectable + VirtualPump. CI: iOS compile of empty modules. Go/no-go note | ARCH, SAFETY |

ML parallel after freeze (not on the dose path): capture ≥1000 Android UAM ticks → common NN+PRNG → Android `UamInferenceEngine` → iPhone TFLite golden. Training stays off during golden capture.

### W8 GO only if all hold

1. Freeze tag + ADR used in PR text.
2. `SourceSensor` native values in `commonMain` with converter round-trip.
3. One+ Metro-registered in the APK (or this GO is delayed to the week One+ lands — then W1 GO is “contracts only”).
4. Empty `aimi-contracts` compiles JVM **and** iOS simulator (from W4 onward).
5. Written Metro recipe: AIMI Android plugin → Metro leaf.
6. Host still Trio. First CGM still One+/G7.

### W8 NO-GO

- One+ needs Hilt back in `:app`.
- Metro churn on `kmp` breaks landing every week → freeze a `kmp` SHA.
- Product rejects Trio → stop; that is a new programme.

---

## 7. After W8 (backlog M1–M12, compressed)

1. **M1** Android capture that can **re-run** the tick (not JSONL projections). Stop iOS dosing work if this fails.
2. **M2** fill contracts, clock, PRNG, replay codec, CI macOS.
3. **M3** vertical slice Tree → Harmonia → RBT → safety → quantize on Native.
4. **M4–M7** stateful engine, ML ports, physio/Hormonitor.
5. **M8** Android production uses the KMP engine (shadow then switch, kill switch).
6. **M9–M11** Trio framework, GlucosePort, shadow, no enact.
7. **M12** allowlisted enact (One+/G7 + chosen BLE pump).

---

## 8. Do not do in the first 8 weeks

- Rebase / merge-tree `dev_OAPSAIMI` onto this branch.
- Move the 18k-line tick into `commonMain`.
- Port dashboard, Advisor UI, TPO LLM, SOS, Wear, Apex, Eversense.
- Copy Hilt modules as-is.
- iOS enact, Critical Alerts promise, 5 minute timer guarantee.
- Enable `OnlineLearner` / personal OREF.
- Silent therapy fixes without a new ADR + freeze tag.
- Say “AIMI runs on iOS” because a harness compiled.

---

## 9. Harvest from AIMI (copy/summarize, do not lose)

These files live on `dev_OAPSAIMI` and are **not** in `_docs/kmp` yet:

- `plugins/aps/.../orchestration/AIMI_ORCHESTRATION_ROADMAP.md` (45-step table)
- `docs/AIMI_ARCHITECTURE_MAP.md`, `docs/AIMI_DECISION_CASCADE_CONTRACT.md`, `docs/AIMI_SMB_OWNERSHIP_MATRIX.md`
- `docs/adr/*` (AIMI product ADRs)
- `docs/MERGE_CONSTRAINT_DEXCOM_ONEPLUS.md`, `docs/MERGE_CONSTRAINT_LIBRE3.md`
- `docs/DEXCOM_ONEPLUS_*.md`, `docs/LIBRE3_*.md`, `docs/spikes/ONEPLUS_*`
- `docs/AIMI_README.md`, `docs/ARCHITECTURE.md`

When harvesting, keep medical order. Do not “simplify” `targetBg` vs `target_bg`.

---

## 10. Owners

Same codes as the backlog: ARCH, CORE, ML, IOS, PRODUCT, DEVICE, SAFETY.

A dose-facing gate needs a second reviewer.

---

## 11. Done rule

A task is done only with an automated check or an archived proof.  
A successful Gradle build is not “AIMI works on iOS”. A human must confirm device behaviour before any such claim.
