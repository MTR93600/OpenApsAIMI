# AIMI / OpenApsAIMI KMP status

**Verified:** 2026-09-06 (refresh post-P0.1)  
**This branch tip (when measured):** `kmp-aimi-migration-study` @ `6f66e63565` (2026-09-06, merge of PR #71)  
**Previous living-doc measurement:** PR #69 @ `f237f2d3d0` (2026-09-03 tip). **Do not treat that SHA as HEAD.**  
**Reference tip:** `origin/dev_OAPSAIMI` @ `c5db5a0333` (2026-09-06)  
**Merge-base:** `283a184f60` (2026-08-25, nightscout dependabot)  
**Ahead / behind reference:** study is **984** unique commits ahead, **2746** behind (re-counted today).

This file is the live snapshot. Older notes under `_docs/kmp/` are history. See [DELTA-remaining.md](DELTA-remaining.md) for the next lots.

**Gates — last-known, not re-proven on this tip.** `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64`, and the whole-module “330 tests / 0 failures” claim were last written green on **2026-09-03** in `_docs/kmp/AIMI_PORT_STATE.md`, measured at an older SHA. The tip has since moved (`kmp` merge `196179309b` + fallout `9e2b5bd40e` + P0.1 `6f66e63565`). **Do not claim those gates green without a fresh run.** P0.1 itself claimed `:plugins:aps:compileAndroidMain` SUCCESS and two targeted host-test classes green; it did **not** re-run `:app:assembleFullDebug`.

---

## 1. Two-line summary

The Android AIMI **plugin path is live in the KMP tree**: `OpenAPSAIMIPlugin` sits in `androidMain` and registers itself with `@MetroIntKey(250)`. A large share of AIMI math is already in `commonMain`. **P0.1 `PkPdLearnedState` is DONE** (PR #71, this tip).

That is **not** “AIMI runs on iOS”. The ~18 888-line tick is still Android-only. The dedicated `:plugins:aimi-engine` module is still a **Hold stub**. iOS is still a **follower** (`APS = false`). And `dev_OAPSAIMI` still has later clinical files that study does not (`DynIsfCache`, `CommandedIsf`, …). **P0.2+ stay pending.** `DynIsfCache` is in flight on another agent — do not start a second copy.

---

## 2. What is already shared (verified)

### 2.1 Repo-wide KMP spine

| Item | Count today | Notes |
|---|---:|---|
| Modules with `kotlin("multiplatform")` | **34** | Same count as PR #69; includes `:plugins:aps`, `:plugins:aimi-*`, `:ios:shell` |
| `commonMain` Kotlin files (whole repo) | **2232** | Was 2203 at `f237f2d3d0`. Rise is the later `kmp` merge, not AIMI |
| `iosMain` Kotlin files (whole repo) | **84** | Was 75. Still **none** are `openAPSAIMI` |
| `expect` declarations (repo) | **14 files** | Was 11. UI/platform. **Zero** in AIMI |
| iOS app | **yes** | `ios/app/AAPSClient.xcodeproj` + `ios/shell` |
| iOS product kind | **follower** | `IosClientConfig.APS = false`, `PUMPCONTROL = false`, `PUMPDRIVERS = false`, `AAPSCLIENT = true` |

`:ios:shell` `migratedModules` already lists `:plugins:aps` and all five `:plugins:aimi-*` modules.

### 2.2 AIMI inside `:plugins:aps`

Package: `app.aaps.plugins.aps.openAPSAIMI`.

| Source set | `.kt` files | Code lines | Role |
|---|---:|---:|---|
| `commonMain` | **340** | **47 630** | +1 file / +~76 LOC vs PR #69: `pkpd/PkPdLearnedState.kt` |
| `androidMain` | **109** | **50 576** | Tick, plugin, Health Connect, TFLite/ONNX, trainers, Compose, SOS |
| `iosMain` | **0** | 0 | No AIMI actuals. Only `loop/IosLoopNotifier.kt` exists in this module |
| `androidHostTest` (AIMI package) | **13** | — | Was 11. + `PkPdLearnedStateIntegrationTest` + `PkpdPresetProfilesLearnedGenerationTest` |
| `commonTest` (AIMI) | **0** | — | The two `commonTest` files in `:plugins:aps` are loop/TDD, not AIMI |

`commonMain` AIMI has **no** `android.*` / `java.io` / `org.json` imports. JSON construction uses the local `AimiJson` / `JsonObj` shim over kotlinx serialization.

Largest first-level `commonMain` packages (file count):

`physio` 51 · `advisor` 34 · `pkpd` **30** · `safety` 24 · `recursive` 22 · root 20 · `patient` 19 · `wcycle` 10 · `scenario` / `basal` / `autodrive` 9 each.

WCycle **is still in live `commonMain`** (10 files). Commit `a4a303eac6` only deleted **staging copies**.

### 2.3 Dedicated AIMI KMP modules (scaffolding only)

These compile for JVM + `iosArm64` + `iosSimulatorArm64` and are linked by `ios/shell`. **`:plugins:aps` does not depend on them.** Nothing in the running plugin calls them.

| Module | What is actually there |
|---|---|
| `:plugins:aimi-contracts` | Envelope DTOs (`AimiInputSnapshot`, `AimiTickResult`, …) + `hello()` |
| `:plugins:aimi-engine` | `AimiEngine.evaluate(...)` implemented only by `HoldAimiEngine` → always `Hold("ENGINE_NOT_EXTRACTED")` |
| `:plugins:aimi-learning` | `hello()` |
| `:plugins:aimi-io` | `hello()` |
| `:plugins:aimi-testkit` | empty snapshot helpers + a hello test |

So: **module graph for an extracted engine exists. The engine does not.**

### 2.4 Plugin registration, ports, P0.1 holder

- `OpenAPSAIMIPlugin` is in `androidMain`, Metro `@MetroIntKey(250)`, `@Inject` constructor.
- `ApsPluginRegistrations` still lists only AMA / SMB / AutoISF (210–230). AIMI is **not** in that object. That is fine: AIMI self-registers.
- Eight collaborator ports in `ports/AimiCollaboratorPorts.kt` **do have Android implementations**. The file header that still says “no implementation yet” is **stale** (P1.1 still pending; docs-only lot does not touch it).
- `ports/Ports.kt` (`Clock`, `PkpdPort`, `MlUamPort`, actuators) is a **planned** extract surface. The live tick does not call it.
- Storage seam exists: `AimiStorage` (common) / `AndroidAimiStorage` + `AimiStorageHelper` (android).
- UAM model is still `app/src/main/assets/modelUAM.tflite`. `AimiModelHandler` is androidMain + TensorFlow Lite.

**P0.1 DONE (PR #71, this tip).** `PkPdLearnedState` lives in `commonMain` (`plugins/aps/src/commonMain/.../pkpd/PkPdLearnedState.kt`). Metro `@SingleIn(AppScope::class)` + `@Inject` (same pattern as `PhysioAggregator`). `@Synchronized` (JVM) → `AapsLock`. Study has **one** `PkPdIntegration` class (`androidMain`) and **two instances** of that type: `OpenAPSAIMIPlugin` ≈ L405 (reader) and `DetermineBasalaimiSMB2` in `DetermineBasalAIMI2.kt` ≈ L10339 (learner). Same process-wide holder is constructor-injected into both. Learned fields are shared; ISF fusion, SMB damping, `recentBolusSamples`, structural config stay per consumer.

P0.1 test claim (not re-run in this docs lot): `PkPdLearnedStateIntegrationTest` 14/14 and `PkpdPresetProfilesLearnedGenerationTest` 3/3 on `:plugins:aps:testAndroidHostTest`. `:app:assembleFullDebug` **was not run** on that lot.

### 2.5 CGM plugins that AIMI cares about

| Module | KMP? | Files | iOS |
|---|---|---:|---|
| `:plugins:source` | **yes** | Dexcom ONE+ / Libre 3 **Activities stay androidMain** | no drivers |
| `:plugins:dexcom_oneplus` | **no** (`com.android.library`) | 72 kt | no |
| `:plugins:libre3` | **no** | 107 kt | no |
| `:plugins:libkeks` | **no** | 24 | no |

`:plugins:source` still has Metro `includeDagger()` for **7** `javax.inject` leftovers (PORT_STATE said 14; that number is wrong).

### 2.6 ML pipeline (user-confirmed — REFERENCE #70 §6)

Do **not** contradict this. Cartography lives in PR #70 (`docs/kmp-migration/REFERENCE-dev_OAPSAIMI-openapsaimi.md` on `dev_OAPSAIMI`); the order was re-checked in code and confirmed by the user.

| Path | First value | Then | Live TFLite? |
|---|---|---|---|
| **SMB** | TFLite (`AimiUamHandler.predictSmbUam` / `modelUAM.tflite`) | `AimiNeuralNetwork` (`AimiSmbTrainer`) refine + train on `oapsaimiML2_records.csv` | **Yes** (Android-only) |
| **Basal** | Heuristic `BasalLearner` / internal factor | Same house JSON net trains on `basal_adaptive_records.csv` | **No.** `model.tflite` is a commented leftover |

Refine + train on the SMB hot path sit behind `OApsAIMIMLtraining` (default **false**). TFLite / ONNX are Android-only. The portable piece is the house JSON + CSV net. This is **not** “TFLite first on both paths”.

### 2.7 How to port on this study (one-liner)

Ports on `kmp-aimi-migration-study` must follow the **Milos KMP world already on this tree** (Metro, `commonMain` / `androidMain` / `iosMain` sourcesets, existing seams). Paste-from-`dev_OAPSAIMI` (Hilt, `src/main`, javax `@Singleton`, `org.json`, JVM-only locks) is the wrong method. A full patterns map may land in a sibling doc under this folder if another agent owns it.

---

## 3. What is partial

| Area | Shared today | Still Android | iOS |
|---|---|---|---|
| Dose tick `DetermineBasalaimiSMB2` | many callees in commonMain | **the 18 888-line class itself** (`Context`, `File`, `java.time`, `Atomic*`) | not compiled |
| Plugin shell | some prefs/keys | `OpenAPSAIMIPlugin` 2 339 lines | not registered; `APS=false` |
| Learned PK/PD | **`PkPdLearnedState` in commonMain** | two `PkPdIntegration` instances in androidMain | holder is common; consumers are not |
| UAM / TFLite | schema + `aimiNeuralNetwork` math in commonMain | `AimiModelHandler`, model asset, LiteRT | no adapter |
| On-device trainers | some math | WorkManager workers, file stores | no |
| Health / steps | snapshot types + ports | Health Connect repo/workers | no HealthKit |
| Auditor / TPO | models, some rules | orchestrators, notifications, LLM | chip port is common; impl android |
| Meal vision | models / prompt parse in commonMain | HTTP providers, Activities parked | no |
| Compose Control Center / PKPD / Hormonitor | some types | screens in androidMain | no |
| Extracted `evaluate()` | interface + Hold stub | unused | unused |

**A `commonMain` compile of `:plugins:aps` is not “AIMI runs on Native”.** iOS compiles the shared callees and `IosLoopNotifier`. It does not compile the tick.

---

## 4. What remains Android-only or parked

### 4.1 Live androidMain (must stay or be seamed)

The tick and the plugin. Health Connect. TFLite / ONNX. Autodrive / basal trainers and workers. SOS SMS. Compose screens. File-backed history readers. Phone step services.

### 4.2 Staging leftovers (17 Kotlin files)

`_docs/kmp/staging/openAPSAIMI-android-wip/` is **not** on any source set. Remaining files are legacy View Activities and a few support types (same list as PR #69). They do **not** block the plugin from compiling.

---

## 5. Diff vs `dev_OAPSAIMI` (plugin area)

Reference still uses `plugins/aps/src/main/...` (plain Android). Study split that tree into `commonMain` / `androidMain`.

PR #69 basename counts (421 both / 28 only-ref / 37 only-study) were measured at `f237f2d3d0`. They were **not** re-diffed file-by-file today. After P0.1, `PkPdLearnedState.kt` is **no longer** “only on reference”.

### 5.1 Only on reference — remaining clinical / extract files

Study still has **no** `class DynIsfCache` (plugin only has a “cache empty” warning). `DetermineBasalAIMI2.kt` does **not** mention the other names below (except the inline max-SMB ladder tags):

| File | First landed on reference | Status on study |
|---|---|---|
| `pkpd/PkPdLearnedState.kt` | 2026-09-01 `0761e9c00a` | **DONE** — commonMain, PR #71 |
| `ISF/DynIsfCache.kt` | 2026-09-03 `dd9979ca4d` | **Pending (P0.2).** In flight elsewhere. Do not start a second implementation. |
| `ISF/ObservedSensitivityMeter.kt` | 2026-09-04 `eb84e078f5` | Pending (P0.3) |
| `ISF/CommandedIsf.kt` | 2026-09-06 `db21308e6c` | Pending (P0.4) |
| `smb/MaxSmbLadder.kt` | 2026-09-05 `f03fa321a6` | Pending (P0.5). Logic still **inline** in study DB2 |
| `patient/HarmoniaCounterfactual.kt` | 2026-09-05 `da9bc789ce` | Pending (P0.6) |
| `quality/InsulinOriginMeter.kt` | 2026-09-05 `da9bc789ce` | Pending (P0.6) |
| `ml/SmbTrainingRowBuffer.kt` | 2026-09-05 `7f8aa0109c` | Pending (P0.7) |

### 5.2 Only on reference — Activities / DI still parked on study

The 17 staging files, plus `AuditorReportActivity` / `AuditorStatusIndicator` (already replaced on study by the Overview chip), `AIMIStepsProviderModuleMTR` (Hilt module; study is Metro), and `AIMI_ORCHESTRATION_ROADMAP.md`.

### 5.3 Tick / plugin size drift

| File | Study today | Reference (PR #69) | Δ (ref − study) |
|---|---:|---:|---:|
| `DetermineBasalAIMI2.kt` | 18 888 | 19 312 | **+424** |
| `OpenAPSAIMIPlugin.kt` | 2 339 | 2 333 | −6 |

P0.1 added constructor pass-through only on the tick (no P0.8 clinical hunks). Reference line counts were **not** re-measured today.

Recent reference AIMI commits after the old study tip `f237f2d3d0` are unchanged: observed sensitivity, late-fat floor, confirmed-rise SMB ceiling, Harmonia counterfactual, SMB training buffer, instrument-before-brake, training corpus in the support package, then loop glucose re-grid `c5db5a0333` (not only AIMI).

### 5.4 Tests

| | Reference `src/test/.../openAPSAIMI` | Study AIMI `androidHostTest` |
|---|---:|---:|
| Files | **259** (PR #69 count) | **13** |

Almost the whole AIMI test corpus is **not** on the KMP study branch. PORT_STATE’s “330 tests” was the **whole `:plugins:aps` module**, last claimed 2026-09-03, **not re-counted as methods today**, and **not re-run on tip `6f66e63565`**.

---

## 6. Corrections to older docs

| Old claim | Reality 2026-09-06 (this tip) |
|---|---|
| Living snapshot is `f237f2d3d0` / PR #69 | **False.** HEAD is `6f66e63565`. PR #69 was never merged; its numbers are the previous measurement. |
| “P0.1 `PkPdLearnedState` unfinished / two-copy bug unproven” (PR #69 §7.4) | **False.** Merged via PR #71. One class, two instances, shared holder in commonMain. |
| “DetermineBasalAIMI2 is 2 files from `commonMain`” (`AIMI_PORT_STATE` §1) | The tick **compiles in `androidMain`**. It is not in `commonMain`. |
| “Plugin is not registered” (`AIMI_PORT_VERIFICATION`) | **False.** `@MetroIntKey(250)` in `androidMain`. |
| “AIMI commonMain 356 / androidMain 100” (PORT_STATE 6h) | **340 / 109** for the `openAPSAIMI` package today. |
| “`aimi-engine` is the place the tick lives” | Modules exist. **Evaluate is Hold.** Live tick is still `:plugins:aps`. |
| “No iOS app” (Aug 25 study) | Retired. Follower app exists. Master on iPhone does not. |
| Freeze tag `aimi-baseline-2026-08-26` = `1ae418e106` | Do not treat that SHA as today’s reference. Today’s reference tip is `c5db5a0333`. |
| TFLite-first on **both** SMB and basal | **False.** See §2.6. Basal has **no** live TFLite. |
| WCycle removed | Staging copies removed. **Live WCycle sources remain.** |
| assemble / iOS / “330 tests” green **on this tip** | **Unproven.** Last written green: 2026-09-03, older SHA. |

---

## 7. Uncertainties (do not guess)

1. **Build gates were not re-run on `6f66e63565`.** Last written green assemble / iOS klib / 330 host tests: 2026-09-03. P0.1 claimed only `compileAndroidMain` + two test classes.
2. **Numeric fidelity** of overlapping files vs current `dev_OAPSAIMI` was not re-diffed today.
3. **`MaxSmbLadder`:** extract vs behaviour change. Study still has ladder tags inside DB2. Compare before porting.
4. **`HarmoniaDecisionEngine`:** no file of that name on either tip. Tests on reference use that name; implementation is `HarmoniaHarmonizer` / types in `HarmoniaDecision.kt` / logic inside DB2.
5. **Clinical behaviour:** this study branch and `dev_OAPSAIMI` are **not** the same AIMI. P0.1 closed the shared PK/PD holder. P0.2–P0.8 and the ~424-line tick delta remain.
6. **`DynIsfCache`:** absent on this tip. Another cloud is implementing P0.2. This docs lot does not start it.

---

## 8. What “done” would mean (unchanged goal)

Android + iOS from **one** `commonMain` engine, no clinical rewrite.

Today:

- Android plugin path: **reachable in a KMP app graph** (last-known assemble green — **not** re-proven on this tip).
- Shared math: **large, real, not extracted**. Shared learned DIA/peak: **landed**.
- iOS: **shared spine + follower UI**. No AIMI tick. No pump. No HealthKit. No TFLite.
- Extracted engine API: **stub**.
