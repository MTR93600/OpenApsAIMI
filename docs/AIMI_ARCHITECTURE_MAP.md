# AIMI Architecture Map — Harmonia Decision → Production

> **Scope.** Living reference for the Harmonia subsystem: how it *decides*, how (and when) it
> *acts* on the pump, and every gate in between. Built from a code audit + 24 h runtime data
> review. Line numbers are a snapshot (current as of this writing); rely on **symbol/function
> names** for durability. Extend this document as we map other AIMI subsystems.

All paths below are under
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`.

---

## 1. TL;DR — the three Harmonia components

Harmonia is **not** one block. Separate what *observes*, what *decides*, and what *acts on the pump*:

| Component | Acts on pump? | File / entry point | Role |
|---|---|---|---|
| `PhysiologicalTree` | ❌ no | `patient/PhysiologicalTree.kt` | Read-only physiological belief tree; feeds the decision engine |
| **`HarmoniaDecisionEngine`** (ex-`HarmoniaSimulationEngine`) | ❌ not directly | `patient/HarmoniaDecision.kt` | **The brain.** Decides the action + target basal/SMB. Its output IS what production applies. |
| **Production basal-first** | ✅ yes | `DetermineBasalAIMI2.kt` → `planHarmoniaProductionBranch` | Owns the final basal when no SMB authority is active |
| **SMB modulation (RBT)** | ✅ yes | `recursive/RecursiveBeliefResolver.kt` → `resolveHarmoniaSmb` | Adjusts an *existing* RBT SMB (never originates one) |
| **`HarmoniaHarmonizer`** | ✅ yes | `patient/HarmoniaHarmonizer.kt` | 2nd-pass BLOCK/SOFTEN on the basal, descending only |

> ⚠️ **Naming caveat.** The engine was historically called *Simulation* and its decision record
> still carries legacy JSON keys `simulation_only=true` / `applies_to_pump=false` and
> `source="harmonia_simulation_branch_v1"`, plus the console string `"Harmonia sim:"`. Those flags
> mean only that *the record itself* does not command the pump — **its content does reach the pump**
> via the production/SMB channels. The Kotlin types were renamed to `HarmoniaDecision*` to remove
> this ambiguity; the JSON keys are kept as a **data contract** for the runtime-history readers /
> external analysis pipeline.

---

## 2. The decision engine (`HarmoniaDecisionEngine.evaluate`)

`patient/HarmoniaDecision.kt`. One pass per loop tick:

1. **`buildBlockers(tree, env)`** — safety gates. If non-empty, `chooseAction` is **short-circuited**
   and the action is forced to `BLOCKED` (this is why a single blocker kills the whole chain).
   Blockers, in order:
   - `sensor_warmup` — `env.sensorAgeMin in 1..120` (real minutes since insertion; `0` = unknown → not warmup)
   - `sensor_noise` — `sensorNoise >= 0.75`
   - `sensor_uncertain` — `sensorTrust.confidence < 0.40`
   - `hypo_or_recovery` — `hypoRisk.confidence >= 0.45`
   - `low_or_falling_bg` — `bg < 80 && delta <= 0`
   - `max_iob_pressure` — `iob >= maxIob * 0.92`
   - `critical_risk` — trunk risk `CRITICAL`
2. **`chooseAction`** → one of `HarmoniaAction` (priority order matters):
   - `STABILIZE` — fragility ≥ 0.55 or exhaustion ≥ 0.65 or chaos ≥ 0.50
   - `MEAL_SUPPORT` — **H4 bridge** first: trunk `DIGESTION_ACTIVE` + `meal_rise_confirmed` +
     BG > target+30 + Δ≥0.8 (beats activity protective; no flip on falling BG); else declared/undeclared meal-rise
   - `PROTECTIVE_REDUCTION` — activity ≥ 0.55 or postActivity ≥ 0.45
   - `BASAL_FIRST` — hormonalResistance / stress / insulinEffectiveness confidence ≥ 0.55
   - `OBSERVE` — nothing salient (→ no production action)
   - Env carries `targetBgMgdl` for the H4 band check.
3. **Factor → bounded target** against a virtual pump model (caps, steps, IOB headroom):

   | Action | basal factor | smb factor |
   |---|---|---|
   | `BASAL_FIRST` | ×1.18 | 0 |
   | `MEAL_SUPPORT` | ×1.10 | ×0.30 |
   | `PROTECTIVE_REDUCTION` | ×0.70 | 0 |
   | `STABILIZE` | ×0.85 | 0 |
   | `OBSERVE` / `BLOCKED` | ×1.0 | 0 |

4. Returns `HarmoniaDecision`: `action`, `eligible`, **`targetBasalUph`**, **`targetSmbU`**,
   `basalFactor`, `smbFactor`, `blockers`, `rationale`, `compactSummary`.

### How the decision feeds production
Stored as `lastHarmoniaDecision`, then wired into `RbtExtendedSignals` (`DetermineBasalAIMI2.kt` ~§3426):

| Decision output | RBT signal | Effect |
|---|---|---|
| `action` | `harmoniaAction` | selects the channel |
| `eligible` | `harmoniaDecisionEligible` | **gates both channels** |
| `targetBasalUph` | `harmoniaBasalDemandRateUph` | **target** of applied basal |
| `targetSmbU` | `harmoniaSmbDemandU` | **target** of SMB modulation |

Production does **not** re-decide — it inherits action + target and only adds safety bounding.

---

## 3. Channel arbitration (`selectBasalFirstChannel`)

`recursive/RecursiveBeliefResolver.kt` (~§657). Mutually exclusive partition by whether RBT granted
an SMB release authority:

```
T3C eligible                              → T3C_BASAL_FIRST            (T3C wins, absolute priority)
else Harmonia eligible & authority==NONE  → HARMONIA_PRODUCTION_BASAL_FIRST
else Harmonia eligible & SOFT + MEAL_SUPPORT + DIGESTION_ACTIVE
                                          → HARMONIA_PRODUCTION_BASAL_FIRST  (soft-meal exception)
else                                      → NONE
```

- **`authority == NONE`** (no SMB this tick) → Harmonia can own the **basal** (basal-first).
- **`authority == SOFT` + Digestion `MEAL_SUPPORT`** → Harmonia may still own **basal** (closes
  `rbt_no_harmonia_channel` when SMB caps crush delivery during meal rise). Production also bypasses
  `smb_authority_active` in that case.
- **Other `authority != NONE`** → basal-first blocked; Harmonia can **modulate the SMB** (`resolveHarmoniaSmb`).

So Harmonia is *either* a basal owner *or* an SMB modulator on a given tick, never both
(soft-meal basal exception prefers basal over SMB modulation).

---

## 4. Production basal-first (`planHarmoniaProductionBranch`)

`DetermineBasalAIMI2.kt` (~§7075). Runs only when `t3cNativePlan == null`. Gate order (first hit wins),
classified **defect / legit safety**:

| Gate | Source | Verdict |
|---|---|---|
| RBT channel must be `HARMONIA_PRODUCTION_BASAL_FIRST` + eligible | §7081–7095 | structural |
| simulation/decision `eligible` (blockers empty) | §7098–7100 (`blockers.firstOrNull()`) | depends on §2 blockers |
| action ∈ {BASAL_FIRST, MEAL_SUPPORT, PROTECTIVE_REDUCTION, STABILIZE} | §7102–7108 | `no_production_action` if OBSERVE |
| finite basal demand | §7110 | legit |
| no SMB authority active | §7113 | legit mutex |
| no SMB already requested (`rT.units/insulinReq <= 0`) | §7116 | legit (basal-first defers) |
| no exercise lockout | §7119 | legit safety |
| no post-hypo authority | §7122 (`post_hypo_guard`) | legit safety |
| no meal conflict | §7125 | legit safety |
| trunk not `CRITICAL` | §7128 | legit safety |
| `iob <= maxIob` | §7131 | legit safety |
| no stacking surveillance | §7134 | legit safety |
| no LGS hypo reason | §7138–7168 | legit safety |
| `finalRate > 0` after ramp + correction-aggression cap | §7170–7194 | legit |

Bounding: hard cap (min of env/profile max basal), **ramp** (`maxStepUp`, fragility-dependent),
correction-aggression cap. Output written to `finalProposedRate` / `finalDuration` → `setTempBasal`
(~§7484).

---

## 5. SMB modulation (`resolveHarmoniaSmb`)

`recursive/RecursiveBeliefResolver.kt` (~§669). **Modulator, never originator.** Eligible only when
`authority != NONE` **and** `basalFirstChannel == NONE` and action is `MEAL_SUPPORT`/`PROTECTIVE_REDUCTION`.

```
MEAL_SUPPORT          → smbU = max(currentDemand, bounded)   // nudge UP toward maxSMB×0.30
PROTECTIVE_REDUCTION  → smbU = min(currentDemand, bounded)   // cap DOWN
```

`bounded` is capped by `maxSMB effective` and **IOB headroom**. The result is **really applied** to the
delivered SMB at `computeSmbDemand` (~§397): `smbU = resolved.demandAfterU`.

**Why no originating SMB** (by design): SMB is irreversible (a TBR is correctable every 5 min);
avoids stacking with the many other SMB sources; Harmonia stays a co-pilot, not the bolus pilot.
JSON contract: `basal_first_only=true`, `adds_smb_authority=false`.

---

## 6. Harmonizer 2nd pass (`HarmoniaHarmonizer.evaluate`)

`patient/HarmoniaHarmonizer.kt`. Runs every tick, no toggle, **basal only, descending only**, just
before `setTempBasal` (`DetermineBasalAIMI2.kt` ~§7493). Postures:

- **`BLOCK`** — *only* path is `critical_physio_risk` (trunk `CRITICAL`, not hyper-correction-dominant)
  → `finalProposedRate = profile basal`.
- **`SOFTEN`** — `finalProposedRate *= tbrFactor` (0.82–0.90, always ≤ 1.0).
- **`CONFIRM`** — no change.

> **Known dead code:** `Outcome.smbFactor` is computed but **never consumed** (only `finalProposedRate`
> is touched). Either wire it or remove it.

---

## 7. Complete "does Harmonia act?" chain

```
PhysiologicalTree ──▶ HarmoniaDecisionEngine.evaluate ──▶ HarmoniaDecision{action, eligible, target*}
                                                              │
                          ┌───────────────────────────────────┤  (eligible gates BOTH)
                          ▼ authority==NONE                    ▼ authority!=NONE
              planHarmoniaProductionBranch            resolveHarmoniaSmb
              (owns basal, no SMB)                     (modulates existing SMB)
                          │                                    │
                          └──────────────┬─────────────────────┘
                                         ▼
                          HarmoniaHarmonizer (BLOCK/SOFTEN basal)
                                         ▼
                                   setTempBasal
```

---

## 8. Change log (decisions already taken)

1. **`sensor_age` → `sensor_warmup` fix** (`HarmoniaDecision.kt`). The old `sensorAgeMin > 10` leaked a
   simulation threshold onto real telemetry (age in days) → blocked ~259/291 ticks, neutralizing the
   whole engine. Now `in 1..SENSOR_WARMUP_MAX_MIN (120)`; `0` (unknown insertion) and established
   sensors are not blocked. Reliability still covered by `sensor_noise` + `sensor_uncertain`.
2. **STABILIZE alignment** (`RecursiveBeliefResolver.kt` `productionAction` set). Added `STABILIZE`,
   which the production branch already accepted (`DetermineBasalAIMI2` §7106) — prevents silent
   `NO_PRODUCTION_ACTION` mismatches.
3. **Simulation → Decision rename.** All Kotlin symbols (`HarmoniaSimulation*` → `HarmoniaDecision*`,
   `simulated*Uph/U` → `target*`, `harmoniaSimulation*` → `harmoniaDecision*`) + 2 files renamed.
   **Legacy JSON keys kept** (`simulated_basal_uph`, `simulated_smb_u`, `simulation_only`,
   `applies_to_pump`, `harmonia_simulation_branch_v1`) and console `"Harmonia sim:"` for the analysis
   pipeline / history readers.
4. **Effort/activity harmonization + activation** (2026-07-10). `EffortActivityBelief` made the single
   activity-protection path: `basalFactor` wired to basal (§11.6 v2), enabled by default, and a short-lived
   **parallel** biometric hard-lockout + glucose brake (added 07-09) **removed**. See §11.6.
5. **Other 07-09/10 working-tree work** (not yet reflected elsewhere in this doc): evidence-gated SMB
   scenario reconciliation (`ClampPkpdScenarioReconcile` / always-on before stacking+SafetyNet);
   decision cascade (Tree → Harmonia → Auditor): [AIMI_DECISION_CASCADE_CONTRACT.md](AIMI_DECISION_CASCADE_CONTRACT.md) ·
   [AIMI_DECISION_CASCADE_ROADMAP.md](AIMI_DECISION_CASCADE_ROADMAP.md);
   pkpd hybrid **endogenous reversion** off
   the absorbing 39 floor (`OApsAIMIPkpdEndogenousReversion`, hybrid-only → minPred hypo-protection intact);
   basal **anti-whiplash slew limiter** (`slewLimitBasalUp`); basal-ML **DI fix** (coordinator injected so the
   NN actually trains) + **label fix** (realized future BG instead of the floored `eventualBg`); Hormonitor
   **in-app viewer**. Full plan: `docs/AIMI_ROADMAP.md`. **Correction:** the physiological tree is **not**
   observational — Harmonia (built from it) reaches the pump; stale "Lot 1 read-only" labels in
   `PhysiologicalTree.kt:260-276` are misleading (fix pending).
6. **Learned PK/PD kinetics now shape the prediction curves** (2026-07-10). Until now `predictCurves` read
   insulin action from the **static** insulin profile (`iCfg.dia/peak`), so the adaptive *learned* DIA/peak
   drove SMB/ISF/TAP-G but **not** `eventual`/`minPred`/the pkpd graph. Now the forward insulin-activity array
   is rebuilt on the learned DIA/peak via the production `IobCobCalculator.calculateIobArrayInDia` fed a profile
   whose `ICfg` carries the learned kinetics (no parallel IOB math). Built in `OpenAPSAIMIPlugin` (suspend) from
   `pkpdRuntime.params.diaHrs`/`.peakMin`, plumbed as `AimiTickContext.pkpdIobDataArray` → `computePkpdPredictions`
   (`ctx.pkpdIobDataArray ?: ctx.iobDataArray`). Pref `OApsAIMIPkpdPredictionKinetics` (default on); fail-safe to
   the static array when off / params out of range / peak ≥ DIA/2 (invalid exponential kernel). See `docs/AIMI_ROADMAP.md` §P1.

## 9. Autodrive — unified V3 product (classic V1/V2 removed)

**Context.** Two engines used to coexist: *classic* (`tryAutodrive`, key `OApsAIMIautoDrive`) and *V3*
(`AutodriveEngine`, key `OApsAIMIautoDriveActive`). Structurally V3 ran first and, whenever it delivered
SMB, locked out the classic SMB (`classicSmbLockout`); a threshold proof showed that whenever classic
qualified (BG≥120, rise≥2) V3 also engaged (BG≥120, Δ>1.2), so classic was preempted in the nominal
case and its only unique capability — a **user-defined prebolus on aggressive rise** — almost never
fired. When V3 was TBR-only, classic could still overwrite V3's TBR (conflict).

**Decision (implemented).** Single product = V3.
1. **Absorption.** `aggressiveRiseSmbFloorU` + opt-in key `OApsAIMIautodriveAggressiveSmbFloor`
   (default off). On a confirmed aggressive rise within a *safe* V3 tick, the delivered SMB is
   `max(modelSmb, prefFloor)` where the floor is the user prebolus (`OApsAIMIautodrivePrebolus` Large
   tier rise≥5 & avg≥3, `OApsAIMIautodrivesmallPrebolus` Small tier rise≥2), pre-bounded by maxSMB +
   IOB headroom and re-bounded by post-hypo cap + downstream V3 safety. Never reduces the model SMB.
2. **Removal.** Deleted `tryAutodrive`, `runAutodriveV2FallbackBranch`, `isAutodriveModeCondition`,
   `adjustAutodriveCondition`, the cooldown `lastAutodriveActionTime`, the `classicSmbLockout`
   plumbing, and the `OApsAIMIautoDrive` key everywhere. `LoopPlugin` periodic loop now gates on V3.
   - **User migration** (`OpenAPSAIMIPlugin.migrateClassicAutodriveToV3`, runs once at `onStart`): if the
     legacy key `key_use_Aimi_autoDrive` was stored and ON while V3 was OFF, V3 is enabled to preserve the
     "autodrive enabled" intent (a classic-only user would otherwise silently lose all autodrive), then
     the legacy key is dropped so it never re-runs and a later manual V3-off is respected. Reads the
     legacy value via `SP` (raw string key) since the enum entry no longer exists.
3. **Autonomy ladder (no shadow, production).** Re-expressed via V3 + sub-flags:

   | Level | V3 | HTR | RBT authority | authoritative |
   |---|---|---|---|---|
   | Observation | OFF | — | — | — |
   | Recommendations | ON | OFF | OFF | OFF |
   | AssistedApplication | ON | ON | OFF | OFF |
   | ControlledAuthority | ON | ON | ON | ON |

   Read side (`AimiControlCenterSnapshot.buildAutonomyFamily`, `AimiControlCenterSupport.readAutonomyMode`)
   and write side (`buildAutonomyPlan`) are kept inverse-consistent. No tier "computes without acting."

## 10. Open items / next steps

- **Phase 3 (validation):** after the `sensor_warmup` fix, replay the 24 h runtime export and confirm
  `sensor_warmup` rate ≈ 0, identify the new dominant blocker, the real `chooseAction` distribution,
  the APPLIED rate, and how often T3C owns the channel.
- **Phase 4 (conditional):** if still too conservative (mostly OBSERVE, or T3C owns ~always), revisit
  `chooseAction` confidence thresholds or the T3C↔Harmonia arbitration.
- **Harmonizer `smbFactor`:** wire or delete.
- **JSON/console vocabulary migration** (optional): move `simulated_*` keys + `"Harmonia sim:"` to a
  `decision`-based vocabulary, versioned (bump record `version`, dual-write legacy + new key) so old
  exports stay readable.
- **Activity/Effort protection** — see §11 (open defect: physiological activity does not arm the
  therapeutic lockout; intent-propagation bug; no effort-load memory).

---

## 11. Activity / Effort protection — gap & design

> **Origin.** Real episode (30 Jun, ~08:00–10:30): small breakfast → rise while *walking* (≈4000
> cumulative steps, HR up). The system kept dosing (SMB up to ~0.75 U, TBR up to ~9 U/h, ~5 U total)
> in a context of exercise + post-exercise insulin sensitivity. Runtime data (`AIMI_Decisions.jsonl`)
> showed `patient_state.user_intent.has_activity = false` and `harmonia_smb.exercise_block = false`
> on 100% of ticks. (`AIMI_HORMONITOR_loop_blackbox_v1.jsonl` is heartbeat/timing only — not a
> decision log; the usable decision log is `AIMI_Decisions.jsonl`.)

### 11.1 Two activity notions — only the *declared* one has authority

| | **Intent channel** (declared / LLM) | **Sensor channel** (steps + HR) |
|---|---|---|
| Source | `ContextManager.getSnapshot().hasActivity && intentCount>0` → `aimiContextActivityActive` (DetermineBasalAIMI2 §13465) | `physioLive.stepsLast15m`, `hrNow` (PhysiologicalTree §286-289) |
| Arms | **Hard lockout**: `maxSMB=0`, basal stop (§2228-2232); target→150; RBT `EXERCISE_LOCK` (weight **1.0**); `harmonia_exercise_block` | `tree.activity`; RBT `STEPS_15M` (weight **0.5**), `ACTIVITY_INT`, `SCEN_ACTIVITY`→`ACTIVITY_PROTECTION`; physio multipliers |
| Strength | **STRONG** (stops insulin) | **WEAK + windowed** (modulates only) |

The whole strong protection keys off `exerciseInsulinLockoutActive = sportTime || aimiContextActivityActive`
(§2228) — i.e. **declared intent only**. Even the RBT high-authority `EXERCISE_LOCK` leaf reads
`ctx.exerciseLockout = exerciseInsulinLockoutActive` (§3638). **Walking (steps↑ + HR↑) cannot, by
itself, stop insulin.**

### 11.2 Confirmed defects

1. **Decoupling.** Physiologically-detected activity (steps/HR) does not arm the lockout / RBT
   `EXERCISE_LOCK` / `harmonia_exercise_block`. Only declared intent does.
2. **No effort-load memory.** Tree/RBT use `stepsLast15m` (15-min rolling) + instantaneous HR. There
   is no cumulative effort load or "time-since-effort". So a walk that ended >15 min ago yields tree
   `IDLE` while post-exercise sensitivity persists for hours — exactly the 09:20 dosing window.
   `postActivity` exists but depends on `causal.exerciseAfterburnProb` (Tree §350), which did not fire.
3. **Intent-propagation bug.** User confirmed the "walking" intent *was* declared, yet
   `user_intent.has_activity=false`. `buildUserIntentSummary` returns EMPTY when `contextSnapshot==null`
   and otherwise sets `hasActivity = contextSnapshot.hasActivity` (PatientStateSnapshot §163-174). So
   either the context snapshot was **null** at patient-state build time (wiring/timing) or
   `ContextManager.hasActivity` did not reflect the declared `ContextIntent.Activity`. **Needs a
   dedicated trace.**

### 11.3 Available signals (inventory — what a real branch can use)

- **Steps**: `stepsLast5m`, `stepsLast15m`, `steps60` (HealthContextRepository / hormonitor export).
  10-min / 30-min windows not yet materialised but derivable.
- **HR**: `hrNow`, `rhrResting` (resting baseline).
- **HRV**: `hrvRmssd` (DetermineBasal §2977), `hrvDeviationZ` (§3494), baseline `hrvRMSSDHistory`
  (AIMIPhysioBaselineModelMTR), pattern `HRV_DEPRESSED`. Gated by `AimiPhysioHRVDataEnable`.
- **Stress**: `transientResistanceProb`, `causal.stressResistanceProb`, `userIntent.hasStress`,
  thermal `inflammationIndex`.

### 11.4 Design vision — a single intelligent "Effort & Activity" belief branch

A first-class branch that **fuses** signals and **carries authority**, independent of declared intent:

1. **Multi-window step view** `5 / 10 / 15 / 30 (/60)` min → distinguishes *onset* (5–10 m), *sustained
   load* (15–30 m) and provides the **memory effect** (recent cumulative load + minutes-since-effort)
   so protection **persists** after the walk, matching post-exercise sensitivity.
2. **HR elevation** (`hrNow − rhrResting`) **+ HRV depression** (`hrvDeviationZ < 0`, `HRV_DEPRESSED`)
   as corroboration — raises confidence when steps + HR + HRV agree.
3. **Stress as a third, *disambiguating* signal.** HRV depression is common to exertion **and** stress,
   but they pull insulin **opposite ways** (exercise → ↑sensitivity → less insulin; acute stress →
   ↑resistance → possibly more). The branch must separate them: steps↑ ⇒ exertion; steps flat + HR↑ +
   HRV↓ + `stressResistanceProb`↑ ⇒ stress. Output a signed posture, not a single "activity" scalar.
4. **Effort-load memory state** (cumulative recent steps + decay; minutes-since-last-effort) so the
   branch reports `ACTIVE` / `RECENT_EFFORT` / `IDLE` with a decaying confidence over ~1–3 h.

### 11.5 Wiring requirements (so it actually protects)

- **Soft therapeutic lockout / SMB cap from the sensor branch** — arm a graded protection (SMB cap or
  ×factor, target bump, basal damp) when the branch confidence ≥ threshold **OR** recent-effort memory
  is high, **without** requiring `aimiContextActivityActive`. Keep the meal-priority bypass partial
  (cover the meal but **cap** SMB under effort, don't restore full `maxSMB`).
- **Real RBT authority** for `STEPS_15M` / `ACTIVITY_INT` / `ACTIVITY_PROTECTION` (not just low-weight
  observation) so the belief tree can reduce the dose on sensor evidence alone.
- **Harmonia honors sensor activity even outside the basal-first channel** — `PROTECTIVE_REDUCTION`
  (and an SMB damp) should fire on the branch/effort-memory, not only when `tree.activity≥0.55` *now*
  and Harmonia happens to own basal.
- **Fix the intent-propagation bug** (§11.2-3) in parallel so the declared channel also works.

> Direction agreed with the user (2026-06-30). Safety note: this is insulin **reduction** under effort
> — fail-safe direction — but every change must stay bounded and validated on real episodes.

### 11.6 Implementation status

- **v1 (done):** `EffortActivityBelief` (pure, unit-tested) fuses multi-window steps (5/15/60), HR
  elevation, optional HRV depression and a stress signal into a graded posture (exertion vs stress)
  + an effort-load **memory** (`RECENT_EFFORT` persists ~120 min after movement stops). Opt-in via
  `BooleanKey.OApsAIMIEffortActivityProtection` (default off). Two-phase wiring:
  `refreshEffortActivityBelief()` (after the AIMI Context module) **computes** the belief once per
  tick (gated on `HealthContextSnapshot.isValid`) and stores `lastEffortAssessment`; the **reduction
  is applied once at the universal exit `finalizeAndCapSMB`** (`finalUnits ×= smbFactor`, skipping
  explicit user actions). This single choke point is deliberate — applying at `maxSMB` was bypassable
  because the meal-advisor one-shot, drift terminator and physio-latent refresh all reset `maxSMB`
  *after* the context module within the same tick (verification agent CRITICAL/HIGH). Effort **memory**
  persists across ticks; the per-tick **assessment** is reset in `buildDecisionContextInit…`. Stress
  posture never reduces insulin; a past hard effort cannot inflate a later light one (peak resets on
  memory expiry).
- **Scope cut (decided 2026-06-30):** the effort cap protects every SMB path that flows through
  `finalizeAndCapSMB` (Autodrive V3, global AIMI SMB, drift terminator). It deliberately does **not**
  touch the two meal paths that write `rT.units` directly and bypass `finalizeAndCapSMB` — the legacy
  meal-mode prebolus (`setLegacyPrebolusUnits`) and the Meal Advisor one-shot direct-send — because
  reducing a *meal* bolus under effort is a clinical trade-off in both directions (under-treating a
  meal → later spike). Left at full coverage by user decision; revisit only with explicit intent.
  Verification agent rated this residual **Medium** (common SMB path protected; meal-gated bypass
  reachable during a walk with a concurrent meal — the original episode).
- **v2 (2026-07-10) — harmonized + activated.** `EffortActivityBelief` is now the **single** activity-protection
  path and is **enabled by default** (`OApsAIMIEffortActivityProtection = true`). Its `basalFactor` is **wired** to
  the basal side (reduction-only damping just before `setTempBasal`, alongside the anti-whiplash slew limiter), so
  detected effort now lowers **both** SMB and basal (deferred item *b* done). A short-lived **parallel** biometric
  hard-lockout (`biometricActivityActive` → `exerciseInsulinLockoutActive`, added 07-09) plus a glucose-truth basal
  brake were **removed** as design-inconsistent duplicates (hard stop + default-on vs. the agreed graded reduction);
  the `HealthContextRepository` `activityState` step threshold was reverted to its original. No parallel exercise
  logic remains.
- **v2.1 (2026-07-10) — post-effort adrenaline ≠ meal.** The effort belief is now computed *before* the basal
  decision + meal detection (`refreshEffortActivityBelief()` moved just after `lastPhysioLatentState`), and vetoes
  the **undeclared-meal** interpretation of an effort/adrenaline rise: `detectMealOnset` and `inferredMealSafetyIntent`
  return false when `effortSuppressesUndeclaredMeal()` — EXERTION posture in ACTIVE **or RECENT_EFFORT** (the ~120-min
  memory covers the post-effort adrenaline window), *and* no declared meal mode, *and* COB < 12 g. This kills the
  `FAST_MEAL` → forced-TBR over-correction seen in the pickleball episode, **without** touching declared meals (which
  keep full coverage — the §11.6 scope-cut still holds for the legacy prebolus / Meal Advisor one-shot). Fail-safe:
  the veto only *suppresses* an escalation, never adds insulin. Intermittent bursts ("petits pas répétés" / HR by
  sequence) are naturally handled: each burst refreshes the effort memory, keeping protection alive between sequences.
- **v2.2 (2026-07-10) — effort is now a first-class TREE branch, Harmonia applies natively (deferred item *b* done).**
  The effort belief confidences are injected into `PhysiologicalTreeBuilder.build(effortActiveConfidence,
  effortRecentConfidence)`: `branches.activity` fuses them with the step count (`max`), `branches.postActivity` is
  driven by the RECENT_EFFORT memory. So `HarmoniaDecisionEngine.chooseAction` (`activity.confidence≥0.55 ||
  postActivity.confidence≥0.45 → PROTECTIVE_REDUCTION`) now fires from **real multi-window effort + memory**, not the
  coarse 15-min count — the tree holds the branch, Harmonia decides behind it. **Same performance guaranteed / single
  reduction per tick:** the belief reduction is applied once — by Harmonia PROTECTIVE_REDUCTION when it owns the basal,
  else by the orchestration damping; the orchestration basal damping is **skipped when
  `harmoniaProductionPlan.sourceAction == PROTECTIVE_REDUCTION`** (fixes a latent double-count, keeps universal
  every-tick coverage → no gap). **Efficient detection:** `HealthContextRepository.activityState` + tree
  `activityConfidence` lowered from `>1000/15m` to `≥200 steps/5m` (burst = already moving) or `≥375–600/15m`
  (sustained); physio-status display updated. SMB stays reduction-only at `finalizeAndCapSMB` (extra reduction under
  effort is fail-safe). Files: `PhysiologicalTree.kt`, `DetermineBasalAIMI2.kt`, `HealthContextRepository.kt`,
  `AIMIInsulinDecisionAdapterMTR.kt`.
- **Deferred (next):** (a) HRV plumbing (engine accepts `hrvDeviationZ`; wiring passes `null`); (b) ~~RBT/Harmonia
  authority~~ **done in v2.2** (Harmonia now reads the effort-fed tree branches → PROTECTIVE_REDUCTION); remaining:
  native **RBT leaf** weights for the sensor effort (belief tree still low-weight observation); (c) the
  **intent-propagation bug** (§11.2-3, declared activity not reaching `user_intent.has_activity`) — needs a trace,
  not a blind fix; (d) **biometric-silent fallback** — `refreshEffortActivityBelief` fails open when wearable data
  is absent (no protection). The harmonized cover is a glucose-corroboration **input to the belief**, not a parallel
  brake.
