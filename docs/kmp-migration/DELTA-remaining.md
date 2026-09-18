# AIMI KMP — remaining delta (prioritized lots)

**Verified:** 2026-09-06 (refresh post-P0.1)  
**Base:** `kmp-aimi-migration-study` @ `6f66e63565` (PR #71 merged)  
**Previous measurement:** PR #69 @ `f237f2d3d0` — superseded as HEAD  
**Reference to catch:** `origin/dev_OAPSAIMI` @ `c5db5a0333`  
**Companion:** [STATUS.md](STATUS.md)

Rule: one lot is small enough to compile and (where it touches numbers) to check against a baseline. No clinical “improvements”. Medical closed-loop: move or copy behaviour, do not invent it.

Do **not** start by copying `DetermineBasalAIMI2` into `:plugins:aimi-engine`. The live path is still `:plugins:aps`. The Hold stub stays Hold until a later extract lot has a real `evaluate()` with replay.

**ML order (REFERENCE #70 §6, user-confirmed — do not invert):** SMB = TFLite first, then `AimiNeuralNetwork` + CSV. Basal = heuristic `BasalLearner` first, then NN + CSV. **No live TFLite on basal.**

**KMP method:** adapt to Metro / sourcesets / `commonMain` vs `androidMain` already on this study. Paste-from-`dev_OAPSAIMI` is wrong. See STATUS §2.7.

---

## How to read the priorities

| Priority | Meaning |
|---|---|
| **P0** | Reference AIMI moved. Study does not have it. Dose / safety risk if we keep coding as if the trees match. |
| **P1** | Unblocks a later move (tick toward commonMain, or honest tests). |
| **P2** | Product / Android-only surface. Does not block iOS engine extract. |
| **P3** | iOS / Native engine. Only after P0–P1, or in parallel on ports that do not touch the tick. |
| **P4** | Later (CGM drivers, iOS master app, full product parity). |

---

## P0 — catch `dev_OAPSAIMI` (clinical delta)

These landed on the reference between 2026-09-01 and 2026-09-06. **P0.1 is on this tip.** The rest are not (except the inline max-SMB ladder).

Do remaining lots as **separate lots**. Each lot: copy from `origin/dev_OAPSAIMI`, adapt only KMP primitives already used in `:plugins:aps` (`aimiWallClockMs`, `AapsLock`, kotlinx time, Metro not Hilt/javax if the file has `@Inject`), compile `:plugins:aps`, then `:app:assembleFullDebug` if the lot claims the plugin is live. If the file has tests on reference, bring the tests in the same lot.

| Lot | Files on reference | Status | Gate / notes |
|---|---|---|---|
| **P0.1** | `pkpd/PkPdLearnedState.kt` + call sites on the two `PkPdIntegration` **instances** | **DONE** — PR #71 @ `6f66e63565` | Shared holder in **commonMain**, Metro `@SingleIn(AppScope)`, `@Inject`. One class, two instances (plugin reader + tick learner). Claimed: `compileAndroidMain` SUCCESS; `PkPdLearnedStateIntegrationTest` 14/14; `PkpdPresetProfilesLearnedGenerationTest` 3/3. `:app:assembleFullDebug` **not re-run**. |
| **P0.2** | `ISF/DynIsfCache.kt` + `DynIsfCacheTest` | **Pending.** In flight on another cloud (PR #72). **Do not start implementing it here.** | Study still has only a “cache empty” warning. No `class DynIsfCache`. |
| **P0.3** | `ISF/ObservedSensitivityMeter.kt` + test | Pending | New 2026-09-04. Wire only the same call sites as reference. |
| **P0.4** | `ISF/CommandedIsf.kt` + `CommandedIsfOrderTest` | Pending | New 2026-09-06. “Read the instrument before the brake.” |
| **P0.5** | `smb/MaxSmbLadder.kt` + the two ladder tests | Pending | Likely an extract. If study’s inline ladder already matches, extract only. If reference changed the rise rule (`f03fa321a6`), take that rule, do not mix. |
| **P0.6** | `patient/HarmoniaCounterfactual.kt` + `quality/InsulinOriginMeter.kt` + their tests | Pending | Pair from `da9bc789ce`. |
| **P0.7** | `ml/SmbTrainingRowBuffer.kt` + test | Pending | New 2026-09-05. Android file store can stay androidMain; buffer math can be commonMain if it is pure. |
| **P0.8** | Tick / plugin call-site sync | Pending | **Do not replace the whole file.** Study already has KMP seams (ports, `AimiJson`, TextRef) plus the P0.1 constructor pass-through. Cherry-pick the clinical hunks onto the study file. Line count today: study 18 888 vs reference 19 312 (**+424**). |

**P0.8 is the hard lot.** Treat P0.2–P0.7 as the types the hunks will need.

Also bring, with P0.8 if they are in those commits: late-fat damping floor (`81e370bfbf`) and “training corpus in the support package” (`02c90656b1`).

**Out of P0:** glucose re-grid `c5db5a0333` is a **loop** change, not only AIMI. Assign it only if the orchestrator wants loop parity too.

---

## P1 — make the Android tick honest and movable

| Lot | Work | Gate | Why this size |
|---|---|---|---|
| **P1.1** | Fix the stale header in `ports/AimiCollaboratorPorts.kt` (“no implementation yet”) | docs / comment only | Still stale on this tip (PR #69’s comment fix never landed). Stops the next agent from re-doing finished ports. |
| **P1.2** | Inventory remaining `android.*` / `java.time` / `File` / `Atomic*` in `DetermineBasalAIMI2` | a checklist in this folder or a short appendix | Needed before any “move DB2 to commonMain” claim. Re-measure; do not assume the old “16 imports” list. |
| **P1.3** | Replace `java.time` in DB2 with the same `kotlinx.datetime` aliases already used by collaborators | compile | Three sites were already known (PORT_STATE 6d). Re-measure. |
| **P1.4** | Rewire remaining `File` / `Environment` uses onto `AimiStorage` | compile | `AimiStorageHelper` stays androidMain. |
| **P1.5** | Drop or wrap `Context` in DB2 | compile | Same pattern as `AimiEmergencySos`: Context stays in the Android impl. |
| **P1.6** | Port the **pure** AIMI tests from reference whose subject is already in `commonMain` | `testAndroidHostTest` for each batch of ≤15 files | 259 → 13 is still the honesty gap. Do **not** land all 259 in one lot. |
| **P1.7** | After P0 + P1.3–P1.5: try `DetermineBasalAIMI2` in `commonMain` again | `:plugins:aps:compileKotlinIosArm64` **and** `:app:assembleFullDebug` | Remaining wall is platform types in the tick itself. If it fails, paste the compiler list into STATUS; do not guess a 20-file move. |

`:app:assembleFullDebug` remains the Metro-binding gate. `:plugins:aps:compileAndroidMain` alone can hide a missing `@ContributesBinding`. That assemble is **last-known** (2026-09-03), not proven on `6f66e63565`.

---

## P2 — leftover product surface (does not block the engine)

| Lot | Work | Decision required |
|---|---|---|
| **P2.1** | 17 staging View Activities | Ask before port-as-View. Prefer Compose or drop, like `AuditorReportActivity`. |
| **P2.2** | Meal Advisor camera / Context Activity | Needs a host screen in current `:ui` navigation. |
| **P2.3** | `AimiLoopRuntimeGuard` / `AimiSmbSimulator` / diagnostics | Confirm they are still called on reference. If dead, do not port. |
| **P2.4** | `plugins/source` : remove `includeDagger()` by converting **7** `javax.inject` Dexcom/Libre files to Metro | `:app:assembleFullDebug` | Standing merge conflict with upstream. |

---

## P3 — iOS / Native engine (after P0, or ports only)

`:plugins:aimi-*` already link on iOS. Filling them is **not** the same as making the Android plugin call them.

| Lot | Work | Gate |
|---|---|---|
| **P3.1** | Keep `HoldAimiEngine`. Add one **read-only** capture: build `AimiInputSnapshot` from the Android tick inputs (no dose change) | unit test: snapshot round-trip |
| **P3.2** | iOS `actual` for `AimiStorage` (documents directory) | `compileKotlinIosArm64` |
| **P3.3** | iOS UAM adapter (TFLite C / chosen runtime) behind `MlUamPort` or today’s handler interface | same UAM vector → same double as Android, on a fixture |
| **P3.4** | HealthKit behind `AimiHealthContext` / `AimiPhysioSource` | mapping table: HR, steps, **HRV RMSSD vs SDNN**, sleep, skin temp. Missing/Denied/Stale, not silent zero |
| **P3.5** | Only after replay exists: move `evaluate()` body. Until then the Android plugin remains the only dose path | JVM + iOS simulator parity on a frozen corpus |

**Do not** turn `IosClientConfig.APS` to `true`. That is an iOS **master** app (pumps, BLE heartbeat, Critical Alerts). Out of scope for these lots.

---

## P4 — later

| Lot | Work |
|---|---|
| **P4.1** | Flip `:plugins:dexcom_oneplus`, `:plugins:libre3`, `:plugins:libkeks` only when a KMP host needs parse/policy. GATT/NFC stay platform. |
| **P4.2** | iOS master (SC-C): pump drivers, `bluetooth-central` restore, APNs / Critical Alerts. Not “the next AIMI lot”. |
| **P4.3** | Fill `:plugins:aimi-learning` / `:plugins:aimi-io` when trainers leave WorkManager. Empty `hello()` is fine until then. |
| **P4.4** | Trio / XCFramework product wrap — only after P3.5 is real. |

---

## Suggested assignment order for the orchestrator

1. **P0.1 = done.** Do not re-open it.  
2. **P0.2 = in flight elsewhere.** Wait; do not start a second `DynIsfCache`.  
3. **P0.3 → P0.7** (one agent or one agent per file; no shared edits to DB2 until P0.8).  
4. **P0.8** (one agent, after the remaining types exist).  
5. **P1.1** (minutes). **P1.6** can run in parallel on commonMain subjects.  
6. **P1.2–P1.5** then **P1.7** (tick toward commonMain).  
7. **P3.1** can start once P0.8 is merged (capture only).  
8. **P2** and **P4** when product asks.

---

## Recurring failure shapes (still true)

Copy these into every lot brief:

1. A class that implements a port but lacks `@ContributesBinding(AppScope::class)` compiles in `:plugins:aps` and fails only in `:app`.  
2. `*ResId: Int` almost always became a `TextRef`.  
3. `Atomic*` / `synchronized` → `AapsLock`; `System.currentTimeMillis()` → `aimiWallClockMs()`; `String.format` → `aimiFmtN`; `java.time` → `kotlinx.datetime`.  
4. Duplicate top-level types (by **declaration**, not filename).  
5. Capabilities dropped in the KMP rewrite (restore from `dev_OAPSAIMI` only after confirming they still exist there).  
6. Do not rebase `dev_OAPSAIMI` onto this branch. Cherry-pick or copy files. Strategy S2 still stands.  
7. Do not paste Hilt / `src/main` / javax `@Singleton` from the reference. Metro + sourcesets on this study are the host world (STATUS §2.7).

---

## What this backlog is not

- Not a new 12-milestone plan. M0–M12 in `_docs/kmp/AIMI_KMP_IMPLEMENTATION_BACKLOG.md` is still the long architecture. **These lots are the next concrete work.**
- Not permission to change SMB / basal / ISF formulae while “cleaning”.
- Not a claim that iOS can close the loop.
- Not permission to implement P0.2 while another agent already has it.
