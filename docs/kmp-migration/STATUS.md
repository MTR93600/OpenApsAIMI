# AIMI / OpenApsAIMI KMP status

**Verified:** 2026-09-06  
**This branch tip (when measured):** `kmp-aimi-migration-study` @ `f237f2d3d0` (2026-09-03)  
**Reference tip:** `origin/dev_OAPSAIMI` @ `c5db5a0333` (2026-09-06)  
**Merge-base:** `283a184f60` (2026-08-25, nightscout dependabot)  
**Ahead / behind reference:** study is **875** unique commits ahead, **2746** behind.

This file is the live snapshot. Older notes under `_docs/kmp/` are history. They were useful, but several claims are now false. See [DELTA-remaining.md](DELTA-remaining.md) for the next lots.

**Not re-run in this session:** `:app:assembleFullDebug`, `:plugins:aps:compileKotlinIosArm64`, `:plugins:aps:testAndroidHostTest`. Last written claim of those gates being green is 2026-09-03 in `_docs/kmp/AIMI_PORT_STATE.md`. Treat that as last-known, not re-proven today.

---

## 1. Two-line summary

The Android AIMI **plugin path is live in the KMP tree**: `OpenAPSAIMIPlugin` sits in `androidMain` and registers itself with `@MetroIntKey(250)`. A large share of AIMI math is already in `commonMain`.

That is **not** “AIMI runs on iOS”. The 18 886-line tick is still Android-only. The dedicated `:plugins:aimi-engine` module is still a **Hold stub**. iOS is still a **follower** (`APS = false`). And `dev_OAPSAIMI` moved the dose path again on 2026-09-04…06.

---

## 2. What is already shared (verified)

### 2.1 Repo-wide KMP spine

| Item | Count today | Notes |
|---|---:|---|
| Modules with `kotlin("multiplatform")` | **34** | Includes `:plugins:aps`, `:plugins:aimi-*`, `:ios:shell`, core, database, UI, workflow |
| `commonMain` Kotlin files (whole repo) | **2203** | Up from the Aug 25 study (~1002) |
| `iosMain` Kotlin files (whole repo) | **75** | None of them are `openAPSAIMI` |
| `expect` declarations (repo) | **11 files** | UI/platform (`TextRef`, `AapsLock`, map picker, …). **Zero** in AIMI |
| iOS app | **yes** | `ios/app/AAPSClient.xcodeproj` + `ios/shell` |
| iOS product kind | **follower** | `IosClientConfig.APS = false`, `PUMPCONTROL = false`, `PUMPDRIVERS = false`, `AAPSCLIENT = true` |

`:ios:shell` `migratedModules` already lists `:plugins:aps` and all five `:plugins:aimi-*` modules.

### 2.2 AIMI inside `:plugins:aps`

Package: `app.aaps.plugins.aps.openAPSAIMI`.

| Source set | `.kt` files | Code lines | Role |
|---|---:|---:|---|
| `commonMain` | **339** | **47 554** | Math, models, safety, recursive belief, most physio/advisor types |
| `androidMain` | **109** | **50 515** | Tick, plugin, Health Connect, TFLite/ONNX, trainers, Compose screens, SOS |
| `iosMain` | **0** | 0 | No AIMI actuals. Only `loop/IosLoopNotifier.kt` exists in this module |
| `androidHostTest` (AIMI package) | **11** | — | Small math tests only |
| `commonTest` (AIMI) | **0** | — | The two `commonTest` files in `:plugins:aps` are loop/TDD, not AIMI |

`commonMain` AIMI has **no** `android.*` / `java.io` / `org.json` imports. JSON construction uses the local `AimiJson` / `JsonObj` shim over kotlinx serialization.

Largest first-level `commonMain` packages (file count):

`physio` 51 · `advisor` 34 · `pkpd` 29 · `safety` 24 · `recursive` 22 · root 20 · `patient` 19 · `wcycle` 10 · `scenario` / `basal` / `autodrive` 9 each.

WCycle **is still in live `commonMain`** (10 files). Commit `a4a303eac6` only deleted **staging copies**. The commit subject is misleading.

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

### 2.4 Plugin registration and ports

- `OpenAPSAIMIPlugin` is in `androidMain`, Metro `@MetroIntKey(250)`, `@Inject` constructor.
- `ApsPluginRegistrations` still lists only AMA / SMB / AutoISF (210–230). AIMI is **not** in that object. That is fine: AIMI self-registers.
- Eight collaborator ports in `ports/AimiCollaboratorPorts.kt` **do have Android implementations** (`AuditorOrchestrator`, `TpoOrchestrator`, `AimiSmbComparator`, `AndroidAimiEmergencySos`, `ContextLLMClient`, `HealthContextRepository`, `AIMIPhysioDataRepositoryMTR`, `AndroidAimiBehaviorProfileSource`). The file header that still says “no implementation yet” is **stale**.
- `ports/Ports.kt` (`Clock`, `PkpdPort`, `MlUamPort`, actuators) is a **planned** extract surface. The live tick does not call it.
- Storage seam exists: `AimiStorage` (common) / `AndroidAimiStorage` + `AimiStorageHelper` (android).
- UAM model is still `app/src/main/assets/modelUAM.tflite`. `AimiModelHandler` is androidMain + TensorFlow Lite.

### 2.5 CGM plugins that AIMI cares about

| Module | KMP? | Files | iOS |
|---|---|---:|---|
| `:plugins:source` | **yes** | Dexcom ONE+ / Libre 3 **Activities stay androidMain** (29 android files vs 2 common countdown composables) | no drivers |
| `:plugins:dexcom_oneplus` | **no** (`com.android.library`) | 72 kt | no |
| `:plugins:libre3` | **no** | 107 kt | no |
| `:plugins:libkeks` | **no** | 24 | no |

`:plugins:source` still has Metro `includeDagger()` for **7** `javax.inject` leftovers (PORT_STATE said 14; that number is now wrong).

---

## 3. What is partial

| Area | Shared today | Still Android | iOS |
|---|---|---|---|
| Dose tick `DetermineBasalaimiSMB2` | many callees in commonMain | **the 18 886-line class itself** (`Context`, `File`, `java.time`, `Atomic*`) | not compiled |
| Plugin shell | some prefs/keys | `OpenAPSAIMIPlugin` 2 337 lines | not registered; `APS=false` |
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

`_docs/kmp/staging/openAPSAIMI-android-wip/` is **not** on any source set. Remaining files are legacy View Activities and a few support types:

- `AimiModeSettingsActivity`, `AimiProfileAdvisorActivity`
- `MealAdvisorActivity`, `MealAdvisorCameraActivity`
- `ContextActivity` + ViewModel / adapter / gauge binder
- Health Connect / SOS permission Activities
- `AimiSmbSimulator`, `AimiDiagnosticsManager`, `AimiLoopRuntimeGuard`
- `AIMICompositeStepsProviderMTR`, `AIMIHealthConnectStepsProviderMTR`
- `StateTransitionManager`, `AimiMemberInjectors`

These do **not** block the plugin from compiling. Porting a View Activity as-is is often the wrong call (see Auditor trampoline → `EventShowDialog` + Overview chip).

---

## 5. Diff vs `dev_OAPSAIMI` (plugin area)

Reference still uses `plugins/aps/src/main/...` (plain Android). Study split that tree into `commonMain` / `androidMain`.

By **basename**:

| | count |
|---|---:|
| Names on both sides | **421** |
| Only on reference | **28** |
| Only on study | **37** (KMP seams, ports, Android adapters, extra host tests) |

### 5.1 Only on reference — new clinical / extract files (high priority)

Added on reference **after or around** the study tip. Study’s `DetermineBasalAIMI2.kt` does **not** mention these names (except the inline max-SMB ladder tags):

| File | First landed on reference | Why it matters |
|---|---|---|
| `ISF/CommandedIsf.kt` | 2026-09-06 `db21308e6c` | Read each instrument before the brake it measures |
| `ISF/ObservedSensitivityMeter.kt` | 2026-09-04 `eb84e078f5` | Passive observed sensitivity |
| `ISF/DynIsfCache.kt` | 2026-09-03 `dd9979ca4d` | Fresh ISF cache (no `class DynIsfCache` on study) |
| `patient/HarmoniaCounterfactual.kt` | 2026-09-05 `da9bc789ce` | Cost of refusing Harmonia |
| `quality/InsulinOriginMeter.kt` | 2026-09-05 `da9bc789ce` | Who actually decided the insulin |
| `smb/MaxSmbLadder.kt` | 2026-09-05 `f03fa321a6` | Extract of the maxSMB ladder; **logic still inline** in study DB2 (`lastMaxSmbLadderBranch`) |
| `ml/SmbTrainingRowBuffer.kt` | 2026-09-05 `7f8aa0109c` | SMB training rows / outcomes |
| `pkpd/PkPdLearnedState.kt` | 2026-09-01 `0761e9c00a` | Shared learned DIA/peak between the two `PkPdIntegration` copies |

`MaxSmbLadder` may be an extract of behaviour study already has. The others look like **new reference behaviour** that study does not have under those names.

### 5.2 Only on reference — Activities / DI still parked on study

The 17 staging files above, plus `AuditorReportActivity` / `AuditorStatusIndicator` (already replaced on study by the Overview chip), `AIMIStepsProviderModuleMTR` (Hilt module; study is Metro), and `AIMI_ORCHESTRATION_ROADMAP.md`.

### 5.3 Tick / plugin size drift

| File | Study | Reference | Δ (ref − study) |
|---|---:|---:|---:|
| `DetermineBasalAIMI2.kt` | 18 886 | 19 312 | **+426** |
| `OpenAPSAIMIPlugin.kt` | 2 337 | 2 333 | −4 |

Sample of overlapping math files (`SmbQuantizer`, `SafetyNet`, `RecursiveBeliefEngine`) is 0-line drift. `AdaptivePkPdEstimator` is +2 on reference. **This is not a full numeric-literal audit.** Overlapping files were not re-diffed one by one today.

Recent reference AIMI commits **after** study tip `f237f2d3d0` (2026-09-03 23:39):

1. `eb84e078f5` observed sensitivity  
2. `81e370bfbf` late-fat damping floor  
3. `f03fa321a6` confirmed-rise SMB ceiling  
4. `da9bc789ce` decision owner + Harmonia counterfactual  
5. `7f8aa0109c` SMB training row buffer  
6. `db21308e6c` instrument-before-brake  
7. `02c90656b1` training corpus / running profile in support package  
8. later same day: `c5db5a0333` loop glucose re-grid (not only AIMI)

### 5.4 Tests

| | Reference `src/test/.../openAPSAIMI` | Study AIMI `androidHostTest` |
|---|---:|---:|
| Files | **259** | **11** |

Almost the whole AIMI test corpus is **not** on the KMP study branch. `:plugins:aps` has 44 host-test files in total (SMB/AMA/AutoISF + 11 AIMI). PORT_STATE’s “330 tests” was the **whole module**, last claimed 2026-09-03, **not re-counted as methods today**.

---

## 6. Corrections to older docs

| Old claim | Reality 2026-09-06 |
|---|---|
| “DetermineBasalAIMI2 is 2 files from `commonMain`” (`AIMI_PORT_STATE` §1) | The tick **compiles in `androidMain`**. It is not in `commonMain`. Remaining work is seams + JSON already done, not “two files”. |
| “Plugin is not registered” (`AIMI_PORT_VERIFICATION`) | **False.** `@MetroIntKey(250)` in `androidMain`. |
| “AIMI commonMain 356 / androidMain 100” (PORT_STATE 6h) | **339 / 109** for the `openAPSAIMI` package. Counting method in the old note may have included extras. |
| “`aimi-engine` is the place the tick lives” (blueprint / backlog M2) | Modules exist. **Evaluate is Hold.** Live tick is still `:plugins:aps`. |
| “Room / Dagger untouched” (Aug 25 study) | Already retired. Database modules are KMP. Metro is the DI. |
| “No iOS app” (Aug 25 study) | Retired. Follower app exists. Master on iPhone does not. |
| Freeze tag `aimi-baseline-2026-08-26` = `1ae418e106` | Tag **not present** in this clone. Do not treat that SHA as today’s reference. Today’s reference tip is `c5db5a0333`. |
| WCycle removed | Staging copies removed. **Live WCycle sources remain.** |

---

## 7. Uncertainties (do not guess)

1. **Build gates were not re-run today.** Last written green assemble / iOS klib / 330 host tests: 2026-09-03.
2. **Numeric fidelity** of the 421 overlapping files vs current `dev_OAPSAIMI` was not re-diffed. Older lots used a literal-multiset check; that check is stale against this week’s reference.
3. **`MaxSmbLadder`:** extract vs behaviour change. Study still has ladder tags inside DB2. Compare before porting, do not paste blindly.
4. **`PkPdLearnedState`:** reference added it so two `PkPdIntegration` copies share learned DIA/peak. Study has one `PkPdIntegration.kt` in androidMain. Whether the two-copy bug exists here was **not** proven.
5. **`HarmoniaDecisionEngine`:** no file of that name on either tip. Tests on reference use that name; implementation is `HarmoniaHarmonizer` / types in `HarmoniaDecision.kt` / logic inside DB2.
6. **Clinical behaviour:** this study branch and `dev_OAPSAIMI` are **not** the same AIMI. Do not ship the KMP plugin as “current AIMI” until the §5.1 files and the 426-line tick delta are reconciled — or an explicit decision says the freeze stays 2026-09-03.

---

## 8. What “done” would mean (unchanged goal)

Android + iOS from **one** `commonMain` engine, no clinical rewrite.

Today:

- Android plugin path: **reachable in a KMP app graph** (last-known assemble green).
- Shared math: **large, real, not extracted**.
- iOS: **shared spine + follower UI**. No AIMI tick. No pump. No HealthKit. No TFLite.
- Extracted engine API: **stub**.
