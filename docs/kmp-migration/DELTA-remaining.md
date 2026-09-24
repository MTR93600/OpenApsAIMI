# AIMI KMP — remaining delta (post-P3, tip 2026-09-24)

**Verified:** 2026-09-24 (`git fetch origin kmp-aimi-migration-study` + `git fetch origin dev_OAPSAIMI` + `gh api` compare)  
**Study code tip (parent of this docs commit):** `kmp-aimi-migration-study` @ `ce1384814e53a73d1566006b56dd8006f7121559`  
**Last clinical lot:** P3.8 [#115](https://github.com/MTR93600/OpenApsAIMI/pull/115) @ `f4ed4e401cb88e8a08907c0c3cec4661cc1094a4`  
**Docs consolidated:** [#117](https://github.com/MTR93600/OpenApsAIMI/pull/117) @ `ce1384814e53a73d1566006b56dd8006f7121559`  
**P0 freeze:** `c5db5a033379390bceb7851ff92004b72ef055bf` (ancestor of today’s ref tip)  
**AIMI ref tip now:** `origin/dev_OAPSAIMI` @ `166ddb6db0cec3b5195006db1d5fa77f544f88c3`  
**Ahead / behind:** study **1066** ahead, **2803** behind. Status **diverged**. Merge-base `283a184f60eb8b18dac42e228faebbe260c3aa22`.  
**Previous inventory tip:** `c9ff5e2f0ef785422114aa3bd9cf45159506dd75` vs ref `c653fc4485dd985088d9a30a42c99af4e3b285e4` (2026-09-19). That ref SHA is **no longer** the tip.  
**Companion:** [STATUS.md](STATUS.md) · [`AGENT_OPS.md`](../../_docs/kmp/AGENT_OPS.md)

Rule: one lot is small enough to compile and (where it touches numbers) to check against a baseline. **No clinical “improvements”.** Medical closed-loop: move or copy behaviour, do not invent it. Do not invent formulas in this file. Copy numbers from the cited ref SHA.

Do **not** start by copying `DetermineBasalAIMI2` into `:plugins:aimi-engine`. The live path is still `:plugins:aps` (`androidMain` only). The Hold stub stays Hold until a later extract lot has a real `evaluate()` with replay.

**P0.1 → P3.8 are MERGED.** See the [STATUS ledger](STATUS.md#1-lot-ledger-p0--p38-all-merged). Do not reopen those IDs. Do not reuse **P3.x**.

Method for the cluster compare: Kotlin under `plugins/aps/**/openAPSAIMI` and `plugins/calibration` on both tips, keyed by path after the source set. Declaration names and `git log c653fc4485..166ddb6db0` name the **new** ref work. This is not a byte-identical overwrite audit of all 638 shared keys (KMP seams are expected).

---

## Closed on this tip (do not restart)

| Series | What landed | PRs |
|---|---|---|
| P0.1–P0.8 | Types + clinical tick/plugin hunks vs freeze `c5db5a0333` | #71–#80 |
| P0.9–P0.12 | Autodrive leftovers, GateKind, IAM floor, barrier replay | #82 #84 #86 #88 |
| P1.1–P1.2 | smbGiven training; DescentRedoseGuard | #90 #92 |
| P2.1 / P2.2 / P2.5.1 | Flutter viewer; support ZIP; first pure-test package | #94 #96 #98 |
| P3.1–P3.8 | Named clinical port gap through ref `c653fc4485` | #101 #103 #105 #107 #109 #111 #113 #115 |

Docs ancre PRs [#73](https://github.com/MTR93600/OpenApsAIMI/pull/73) / [#74](https://github.com/MTR93600/OpenApsAIMI/pull/74) / [#83](https://github.com/MTR93600/OpenApsAIMI/pull/83) / [#85](https://github.com/MTR93600/OpenApsAIMI/pull/85) / [#87](https://github.com/MTR93600/OpenApsAIMI/pull/87) / [#89](https://github.com/MTR93600/OpenApsAIMI/pull/89) were **closed unmerged** (superseded).

---

## Study-only commits after `c9ff5e2f` (before #117)

First-parent `c9ff5e2f..91dc6106` (Claude), then docs #117. Classification vs **today’s** ref tip. No formula restatement.

| SHA | Subject | vs ref `166ddb6db0` |
|---|---|---|
| `b423b73af3` | PKPD unit tests | Same filenames exist on ref (`AdaptivePkPdEstimatorTest`, `DiaGovernorTest`, `PkPdCoreTest`, …). **Catch-up already on study.** Not a new ref requirement. |
| `c6b0e10d83` | Safety / SMB policy tests | Same filenames exist on ref. Also edited private `rateLimit` on `IsfAdjustmentEngine.kt` / `IsfBlender.kt`. **Signature now matches ref** (`rateLimit(target, nowMs)`). |
| `6d753ebff0` | APS component tests | Same filenames exist on ref. Extra copies also sit under `_docs/kmp/deferred-tests/` for three of them. |
| `0e2ed12bc3` | Readability refactor + replay JSONL fixtures | Not a ref clinical gap. |
| `6a6afc40bb` | `UndeclaredCobEstimator` HR gating | **Aligned.** Declaration sets match (`commonMain` vs ref `src/main`). Normalized body diff is the KMP formatter (`aimiFmt1` vs `String.format`) only. |
| `ec7783a509` | `AimiKeyValueCache` prefs | **Study-only.** Ref has no `AimiKeyValueCache`. KMP prefs seam (`utils/AimiKeyValueCache.kt`, `AndroidAimiKeyValueCache.kt`). Do not delete it to “match ref”. |
| `91dc6106a4` | Aimi retention (worker, policy, archive/trim) | **Needed vs ref, and already ported.** Ref introduced the same tree in `505b848fb6`. See RET below. |

`91dc6106a484c066dacf69884417968a0ca59a14` is the code tip immediately before #117.

---

## Remaining clinical delta vs ref `166ddb6db0`

Ref commits after the P3.8 anchor tip `c653fc4485` (oldest first):

| SHA | Date | Subject | Study |
|---|---|---|---|
| `505b848fb6` | 2026-09-20 | Retention system + comparison CSV schema + tests | Retention **yes** (KMP). Comparison schema **no** |
| `468cf7a034` | 2026-09-20 | Docs only (`plans/2026-09-19-aimi-telemetry-retention*.md`) | Ignore for code |
| `6a6561caab` | 2026-09-23 | Awake resting HR + `WorkingIsf` + SMB trainer state + Glass/Garmin hunks | **Absent** (AIMI subset) |
| `b7e05f3037` | 2026-09-23 | Auditor profile-factor gate + tests | **Absent** |
| `4b0675549d` | 2026-09-23 | Meal boost cap + shadow export test | **Absent** |
| `166ddb6db0` | 2026-09-24 | Garmin `sport` mode + FCL temporary target | **Absent** |

`git grep` on study: `evaluateMealBoostCap`, `class WorkingIsf`, `AwakeRestingHeartRate`, `applyAuditorIsfFactorToWorkingIsf`, `class AuditorProfileFactorGate`, `REASON_NO_BASELINE`, `entriesForFit`, `lowEndSafe`, `MIN_ENTRIES_FOR_SLOPE`, `CalibrationLongKey` → **0 files**. Each symbol is present on ref.

### C1 — Awake resting HR + WorkingIsf (severity: high)

Insulin-sensitivity path on the tick. Ref `6a6561caab` (AIMI files only; split the SMB trainer and the Garmin/Glass hunks into later lots).

| Evidence on ref | Study |
|---|---|
| `plugins/aps/src/main/.../ISF/WorkingIsf.kt` (new, ~170 lines) | **No file** |
| `plugins/aps/src/main/.../physio/AwakeRestingHeartRate.kt` (new) | **No file** |
| `ISF/StressIsfFloor.kt` — decl `REASON_NO_BASELINE` only on ref | shared file, missing that decl |
| `physio/HealthContextRepository.kt` — awake-resting refresh decls only on ref | `androidMain` copy, those decls absent |
| `IsfSourceTelemetry.kt` — `recordCalcPath` / `recordCommandFloorMultiplier` only on ref | shared, missing |
| `core/interfaces/.../OapsProfileAimi.kt` — vals `pre_floor_isf_mgdl`, `stress_floor_isf_mgdl` only on ref | `commonMain` interface missing both |
| `DetermineBasalAIMI2.kt`, `OpenAPSAIMIPlugin.kt`, `basal/BasalDecisionEngine.kt` | hunks from this SHA not ported |
| `KalmanFilter.kt` | this SHA is **+9 lines**. Older decls (`physiologicalFloor`, `isUsableTdd`, …) also differ. **Copy the +9 with this lot. Do not widen the lot to the older Kalman file.** |
| Tests in the same SHA: `StressIsfFloorAwakeBaselineTest.kt`, `WorkingIsfStressFloorTest.kt`, `AwakeRestingHeartRateTest.kt` | **Absent** |

### C2 — Auditor profile-factor gate (severity: high)

Ref `b7e05f3037`. Wired into `DetermineBasalAIMI2` (`applyAuditorIsfFactorToWorkingIsf`). Depends on `WorkingIsf` (C1).

New on ref, absent on study (all under `advisor/auditor/`):

`AuditorProfileContext.kt`, `AuditorProfileFactorCache.kt`, `AuditorProfileFactorGate.kt`, `AuditorProfileFactorModels.kt`, `AuditorProfileFactorParser.kt`, `AuditorProfileFactorPromptBuilder.kt`, `AuditorProfileFactorValidator.kt`, `AuditorTickRing.kt`.

Also touched: `AuditorAIService.kt`, `AuditorDataCollector.kt`, `AuditorDataStructures.kt`, `AuditorOrchestrator.kt`, `AuditorPromptBuilder.kt`, `core/keys/.../BooleanKey.kt`. Tests in that commit (parser, rate limit, validator, tick state, snapshot levels, tick ring, gate ISF, gate target) are absent on study.

### C3 — Meal boost basal cap (severity: high)

Ref `4b0675549d`. `safety/CorrectionAggressionBasalCap.kt` gains `evaluateMealBoostCap` (study file has no such decl). `DetermineBasalAIMI2.kt` call site. Test `safety/BasalShadowExportTest.kt` **absent** on study. Copy the SHA. Do not restate the cap in docs.

### C4 — SMB trainer persisted state (severity: medium)

Same ref commit as C1 (`6a6561caab`) but a different concern: `ml/AimiSmbTrainer.kt` is **305** lines on study (`androidMain`) vs **901** on ref. Funs only on ref include `buildTrainingCorpus`, `shouldAttempt`, `persistState`, `loadPersistedState`, `isCircuitOpenNow`. Test `ml/AimiSmbTrainingStateTest.kt` **absent** on study. Training state, not the live dose equation. Own PR.

### C5 — Calibration applicability still deferred from P3.8 (severity: high)

Re-checked 2026-09-24. Still the hole named in [`P3.8-ANCHOR`](../../_docs/kmp/P3.8-ANCHOR.md). `1b81e356c8` (**ancestor** of `166ddb6db0`) is `feat: Implement calibration handling for Dexcom ONE+ and Libre3 plugins`.

| Symbol / file on ref | Study |
|---|---|
| `entriesForFit` in `plugins/calibration/src/main/.../LinearCalibrationPlugin.kt` | **0** |
| `lowEndSafe`, `MIN_ENTRIES_FOR_SLOPE` in `CalibrationMath.kt` | **0** |
| `plugins/calibration/src/main/.../keys/CalibrationLongKey.kt` | **No file** |

P3.8 ported stale/blend health only. Do not invent thresholds. Copy `1b81e356` / current ref blobs when the lot opens. This is calibration **policy** on `:plugins:calibration`, not the GATT drivers (those stay D1).

### C6 — Comparison CSV schema from the retention commit (severity: low)

Ref `505b848fb6` also changed comparison export, not only retention. Study did not take that half.

| File | Lines study → ref | Decls only on ref |
|---|---|---|
| `comparison/ComparisonCsvParser.kt` | 480 → 581 | `isHeaderLine`, `recommendationReasonForLlm`, `schemaOffset` |
| `comparison/ComparisonData.kt` | 297 → 436 | `causes` |
| `comparison/ComparisonCsvParserFormatTest.kt` | absent | ref only |

`AimiSmbComparator.kt` / `PerformanceScorer.kt` function **names** match; still diff the `505b848fb6` hunks rather than assuming equality.

### C7 — Glass trajectory publish (severity: low for insulin)

`pkpd/TrajectoryRuntimeRepository.kt` exists on ref at the **old** anchor `c653fc4485` (introduced `2c0103e619`, glass dashboard) and is still **absent** on study (`git grep` = none). One tick call: `DetermineBasalAIMI2` → `publish`. Consumer: `GlassLoopDashboardViewModel.getLatest`. Not a dose equation. Ride with G1, not with C1–C3.

---

## What is not a remaining clinical gap

### RET — retention (landed both sides)

| File | Normalized body |
|---|---|
| `AimiAppendGuard`, `AimiArchive`, `AimiCutPlanner`, `AimiFileLock`, `AimiLineScanner`, `AimiRetentionManager` | **identical** after dropping package / imports / comments |
| `AimiRetentionPolicy` | **identical** once KDoc markers are stripped |
| `AimiRetentionWorker` | KMP seam only: study `MetroWorkerCreator` + `aimiWallClockMs`; ref `@HiltWorker` + `System.currentTimeMillis` |
| `AimiTimestampKey` | KMP charset API (`decodeToString` vs `String(..., US_ASCII)`) |
| `AimiAppendCap` / `AndroidAimiAppendCap` | **Study-only** seam. Ref has no such type |

Study tests for this tree are on `androidHostTest/.../retention/` (same names as ref `src/test`). **No second retention lot.**

### CACHE — prefs

`AimiKeyValueCache` is study infrastructure (`ec7783a509`). Keep it.

### COB HR gate

`UndeclaredCobEstimator` HR gating from `6a6afc40bb` matches ref declarations. Formatter-only diff. **Do not reopen.**

### Tests already shared

PKPD / safety / SMB / APS test filenames added on study after `c9ff5e2f` are also on ref. Historical android unit tests that exist **only** on ref by basename: **131** keys (autodrive, basal, patient, pkpd, …). That is a port-when-the-subject-is-`commonMain` backlog, not a new formula series. New tests for C1–C6 travel **inside** those lots.

UI Activities that exist only on ref (`AimiModeSettingsActivity`, meal/auditor/context activities) have study Compose screens. Not clinical lots.

---

## Standing tracks (not P4 formula lots)

### D1 — Dexcom ONE+ / Libre 3 (Android drivers, later KMP host)

Re-verified on `ce138481`.

| Item | State | Do not |
|---|---|---|
| Host plugins | `DexcomOnePlusPlugin.kt` `@IntKey(446)`, `Libre3NativePlugin.kt` `@IntKey(447)` — present on **both** tips | Re-port inside an AIMI math lot |
| Driver modules | `:plugins:dexcom_oneplus`, `:plugins:libre3`, `:plugins:libkeks` still `alias(libs.plugins.android.library)` | Flip with `android-module-dependencies` (kmp-module-flip forbids it) |
| GATT / NFC | Stay platform | Pretend they are `commonMain` |
| iOS drivers | None in these modules | Claim One+/Libre3 on iPhone from this study branch |
| `:plugins:source` | KMP (`kotlin("multiplatform")`) + `includeDagger()` in `plugins/source/build.gradle.kts`. **7** activity files still `import javax.inject.Inject` (recount 2026-09-24). The gradle comment still says 14 | Copy AIMI-parent `com.android.library` + Hilt gradle |
| Calibration policy | **Not this track.** See C5 | Mix driver BLE with `entriesForFit` |

ADR G0: first CGM is **Dexcom ONE+ / G7**. Libre 3 is **wave 2**.

### T1 — iOS pumps via Trio

| Item | Decision / leftover | Source |
|---|---|---|
| iOS host | **Trio**. AIMI is `AimiKit`. No `OpenAPSAIMIPlugin` on iOS. | [`adr-g0-defaults.md`](../../_docs/kmp/adr-g0-defaults.md) |
| First iOS pump | **Medtrum** via Trio `MedtrumKit` after W8. Until then **VirtualPump**. No Dana-i first. No Bluetooth Classic. | [`adr-g0-d2-ios-pump-medtrum.md`](../../_docs/kmp/adr-g0-d2-ios-pump-medtrum.md) |
| Android pump | `:pump:medtrum` stays Android | Do **not** put it in `iosMain` |
| Trio kit | Two BLE stacks, two repos | Do **not** vendor `MedtrumKit` into this tree |
| Still open | W8 go/no-go to drop VirtualPump; exact Trio / MedtrumKit pin | ADR G0-D2 “Still open” (unchanged) |
| Follower flag | `IosClientConfig.APS = false` (`ios/shell/src/iosMain/.../IosClientConfig.kt`) | Do **not** flip to iOS master in a driver lot |

### P-ask — open product choices (do not guess)

From [`_docs/kmp/README.md`](../../_docs/kmp/README.md) + ADR G0. **Ask the user.**

- UAM: embedded-only vs versioned user import
- Persistence / rebuild of memories after restart
- Private corpus and shadow criteria
- v1 extras: learners, HealthKit, Hormonitor viewer, Advisor/TPO
- Apple distribution + Critical Alerts entitlement
- Parity threshold after pump quantification
- **`AimiLoopRuntimeGuard` — re-checked, still not on the study plugin/app tree.** Ref has `orchestration/AimiLoopRuntimeGuard.kt` (defer overview refresh while a determine-basal tick is in progress). Call sites on ref: `app/.../ComposeMainActivity.kt`, `plugins/main/.../DashboardOverviewRefreshGate.kt`, `plugins/main/.../IobCobCalculatorPlugin.kt`. Study has `AimiLoopTelemetry` but **zero** `AimiLoopRuntimeGuard` references under `plugins/` or `app/`. A byte copy lives only in `_docs/kmp/staging/openAPSAIMI-android-wip/orchestration/AimiLoopRuntimeGuard.kt`. UI defer, not a formula. **Ask before a lot.**
- Loop glucose re-grid / Garmin `LoopHubImpl` — **loop / sync**, see G1, not an AIMI math lot
- `:plugins:aimi-engine` `HoldAimiEngine.evaluate()` still returns `Hold("ENGINE_NOT_EXTRACTED")` until a dedicated extract + replay lot

### R4 — KMP tick / Native engine (still true, unnumbered)

Not a clinical formula catch-up:

1. Inventory / seam platform types still inside `DetermineBasalAIMI2` (`Context`, `File`, `java.time`, `Atomic*`) before any “tick in commonMain” claim. The tick file is **only** `plugins/aps/src/androidMain/.../DetermineBasalAIMI2.kt`.
2. Port **pure** AIMI tests whose subject is already `commonMain` (the 131 ref-only test keys, plus P2.5.1 was package 1 only).
3. iOS `actual`s (storage, UAM adapter, HealthKit) only behind existing ports. Missing/Denied/Stale, never silent zero.
4. Move `evaluate()` body only after replay exists. Android plugin stays the only dose path until then.

`:app:assembleFullDebug` remains the Metro-binding gate. **Not re-run** for this inventory.

---

## Ordered next lots (one PR each)

Do not recycle P0–P3.8. Base `kmp-aimi-migration-study`. Copy the cited SHA. No invented numbers.

| Order | ID | Why this order | Ref SHA | Severity |
|---|---|---|---|---|
| 1 | **P4.1** | Newest tick ISF path; later auditor lot calls `WorkingIsf` | `6a6561caab` AIMI subset = C1 | high |
| 2 | **P4.2** | Auditor ISF/target factors; needs P4.1 | `b7e05f3037` = C2 | high |
| 3 | **P4.3** | Meal boost cap on the same tick file | `4b0675549d` = C3 | high |
| 4 | **P4.4** | SMB trainer state from the same commit as P4.1, split so P4.1 stays reviewable | `6a6561caab` `AimiSmbTrainer` + test = C4 | medium |
| 5 | **P4.5** | Calibration applicability still open since P3.8. Separate module; may run **in parallel** with P4.4 if the orchestrator wants CGM before more tick edits | `1b81e356` / tip `CalibrationMath` + `LinearCalibrationPlugin` + `CalibrationLongKey` = C5 | high |
| 6 | **P4.6** | Comparison CSV schema left behind by the retention port | `505b848fb6` comparison hunks = C6 | low |
| 7 | **G1** | Garmin `sport` + FCL temporary target (`postTempTarget` present on ref, absent on study `GarminPlugin.kt`) plus Glass `TrajectoryRuntimeRepository` publish. Constants stay in the SHA | `166ddb6db0` + Garmin/Glass hunks of `6a6561caab` + C7 | low (insulin) / product |
| — | **Guard** | `AimiLoopRuntimeGuard` call sites | ask first | low |
| — | **D1 / T1** | Drivers, Trio pin, W8 VirtualPump | ADR G0 | not a formula lot |

**Next lot to open: P4.1.**

---

## How to open the next lot

1. Orchestrator writes the lot id from the table (do not recycle P0–P3.8).  
2. One cloud agent, one GitHub PR, base `kmp-aimi-migration-study`.  
3. Triple feu (Ancre + Qualité + Delta + Portage) = GO/MERGEABLE — see [`AGENT_OPS.md`](../../_docs/kmp/AGENT_OPS.md).  
4. User GO, then merge.

---

## Recurring failure shapes (still true)

1. A class that implements a port but lacks `@ContributesBinding(AppScope::class)` compiles in `:plugins:aps` and fails only in `:app`.  
2. `*ResId: Int` almost always became a `TextRef`.  
3. `Atomic*` / `synchronized` → `AapsLock`; `System.currentTimeMillis()` → `aimiWallClockMs()`; `String.format` → `aimiFmtN`; `java.time` → `kotlinx.datetime`.  
4. Duplicate top-level types (by **declaration**, not filename).  
5. Capabilities dropped in the KMP rewrite (restore from `dev_OAPSAIMI` only after confirming they still exist there).  
6. Do not rebase `dev_OAPSAIMI` onto this branch. Cherry-pick or copy files. Strategy S2 still stands.  
7. No Hilt in `commonMain`.

---

## What this backlog is not

- Not a new 12-milestone plan. M0–M12 in `_docs/kmp/AIMI_KMP_IMPLEMENTATION_BACKLOG.md` is still the long architecture.
- Not permission to change SMB / basal / ISF / calibration formulae while “cleaning”.
- Not a claim that iOS can close the loop.
- Not a claim that retention, `AimiKeyValueCache`, or the Claude test commits are still missing versus ref.
- Not a claim that P3.8 brought `entriesForFit` / tip `1b81e356` policy / ONE+ / Libre3 drivers onto study.
