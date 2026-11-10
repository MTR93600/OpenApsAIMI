# AIMI — Roadmap (2026-07-10)

Purpose: a precise, prioritized plan built on **verified code**, not memory or vibes. Every claim below was
checked against the current tree this session; where a prior belief was wrong it is corrected in §0. Companion
memory: `basal-ml-training-bugs`, `pkpd-floor-39-contamination`, `hormonitor-viewer`, `harmonia-decision-not-simulation`.

**Cascade décisionnelle (Tree → Harmonia → Auditor)** — contrat + roadmap de réalisation (défaut natif, arbre
intégral, Harmonia branch-aware) :
[AIMI_DECISION_CASCADE_CONTRACT.md](AIMI_DECISION_CASCADE_CONTRACT.md) ·
[AIMI_DECISION_CASCADE_ROADMAP.md](AIMI_DECISION_CASCADE_ROADMAP.md).

---

## 0. Ground truth — corrections to avoid repeating hallucinations

- **The physiological tree DOES drive dosing, via Harmonia.** Earlier framing ("the tree observes but doesn't
  decide") was **wrong / outdated**. It came from stale labels in
  `PhysiologicalTree.kt:260-276` — `basalState`/`isfState` = *"read-only, no write path in Harmonia **Lot 1**"*
  and `mlAsyncState.safetyImpact = "no direct dosing authority"*. These describe an early phase.
  **Reality:** `HarmoniaDecisionEngine.evaluate(tree = physiologicalTree)` (`DetermineBasalAIMI2.kt:3090`)
  derives a decision **from the tree**, and `planHarmoniaProductionBranch` (`:7065`) can set
  `finalProposedRate = harmoniaProductionPlan.rateUph` (`:7475`) → `setTempBasal` → **the pump**, gated by
  `selectedForProduction` + safety blockers. So physiology has **gated production authority**, not "advisory only".
- **What is genuinely open** is not "can physiology decide" but: (a) the stale labels/docs mislead; (b) *how often*
  Harmonia actually wins vs the rule cascade is **unmeasured**; (c) whether its production is calibrated/validated.

---

## 1. Priorities (ordered by leverage)

### P1 — Prediction root (pkpd). Highest leverage: everything reads from it.
- **Problem (verified):** `AdvancedPredictionEngine` uses `NUMERIC_FLOOR = 39` and `eventual = hybrid terminal`;
  the hybrid curve lacks an endogenous/EGP reversion term, so it collapses to 39 on ~24% of ticks (field data),
  while real BG never reached hypo on those ticks. This one defect poisons **two** consumers: the SMB zone-2 clamp
  and the basal-ML training labels. See `pkpd-floor-39-contamination`.
- **Done this session:** anti-absorbing endogenous reversion on the **hybrid curve only** (pref
  `OApsAIMIPkpdEndogenousReversion`, default on) — leaves `minPredictedAcrossCurves` (IOB/COB/UAM/ZT) intact, so
  hypo-protection is untouched. Evidence-gated SMB reconciliation (`ClampPkpdScenarioReconcile` /
  `reconcileSmbEventualWithScenario`) — always applied before stacking+SafetyNet (not skipped when
  Prediction Authority is ON); digestion/high-BG arm added so zone-3 stacking cannot crush on a
  false floor alone.
- **Done this session — learned insulin kinetics now shape the curves (the missing link).** The prediction curves
  computed insulin action from the **static** insulin profile (`iCfg.dia/peak`), so the adaptive PK/PD *learned*
  DIA/peak drove SMB/ISF/TAP-G but **not** the shape of `eventual`/`minPred`/the pkpd graph. Fixed by rebuilding the
  forward insulin-activity array on the **learned** DIA/peak — reusing the production
  `IobCobCalculator.calculateIobArrayInDia` with a profile whose `ICfg` carries the learned kinetics (no parallel
  IOB math; same treatment iteration). Built in the plugin's suspend context from `pkpdRuntime.params.diaHrs`/
  `.peakMin` (raw learner output), plumbed as `AimiTickContext.pkpdIobDataArray` → the two `computePkpdPredictions`
  call sites (`ctx.pkpdIobDataArray ?: ctx.iobDataArray`) → `predictCurves`. Pref `OApsAIMIPkpdPredictionKinetics`
  (default **on**); **fail-safe** → static profile array when pref off, learner params out of range, or the
  exponential model would be invalid (guard: peak must stay `< DIA/2`, else tau ≤ 0 → NaN). Only the prediction
  curves consume this; SMB IOB accounting is untouched.
- **TODO:** (1) **validate on device** — could not be validated offline (predictCurves needs the full IOB array,
  absent from CSVs). (2) Consider feeding the Kalman `getLastRa()` (already computed, **not wired** into
  predictCurves) as the physiological source of the reversion instead of a fixed baseline. (3) Multi-day
  quantification of floor→realized-BG using more support packages.
- **Validation:** compare eventual-vs-realized-BG error before/after; watch for *reduced* hypo-prediction leading
  to under-caution (device test, hypo-dominant profile).

### P2 — Basal ML on clean labels + stability. The chaotic side; where "normal life" smoothness is won.
- **Problem (verified):** two bugs made the basal NN useless — (a) DI: `BasalMlTrainingCoordinator` was never
  instantiated (`instance` null) → the trainer worker retried forever → weights never produced → dormant heuristics
  for months; (b) labels: `BasalMlDatasetParser` used the floored `eventualBg` prediction as ground truth. Both
  **fixed** (inject coordinator into workers; label = realized future BG ±30 min). See `basal-ml-training-bugs`.
  Separately, the **rule cascade is unstable** (~30 whiplash/day, 43 jumps ≥3 U/h) — fixed with an upward
  slew-rate limiter (`slewLimitBasalUp`, pref `OApsAIMIBasalSlewLimitEnabled`).
- **TODO:** (1) confirm the trainer **actually runs** on device (worker constraint = charging+idle, ~nightly;
  check log `BasalMlTraining: published`). (2) After a few days of clean-label training, evaluate whether the NN
  output (idealScale) is sane and whether enabling it improves stability/TIR. (3) Reconsider the target function
  (`neededDelta/actualDelta` ratio) — it is ad-hoc; the label SOURCE is fixed but the target MATH could be
  principled. (4) Tune slew params (`BASAL_SLEW_UP_*`) from data.
- **Note:** clean labels show this patient's BG ran high → the NN would push basal **up** on average, not down;
  "corrige trop" is instability (variance), a separate axis from the mean level. Don't conflate.

### P3 — Harmonia authority: measure it, calibrate it, fix the stale labels.
- **Problem (verified):** Harmonia has real gated authority (§0) but we **don't know how often it wins**, and the
  `PhysiologicalTree` "Lot 1 read-only" labels/comments are stale and misleading.
- **Done 2026-07-17 (support-package KFC hyper + post-hypo sticky):**
  1. **Aggressive post-hypo rise exit** (`PostHypoAggressiveRiseExit`: BG ≥ target+30 **and** Δ>15) clears
     RBT `EPISODE_POST_HYPO`, bypasses `MODE_POST_HYPO_RECOVERY`, and keeps RBT at **SOFT** under
     `PREDICTIVE_HYPO` (`PREDICTIVE_HYPO_AGGRESSIVE_RISE`) instead of shadow `NONE` — closes the 15:05–15:14
     authority gap before meal-bypass confirmation.
  2. **H4 meal-rise bridge (partial):** `HarmoniaDecisionEngine.chooseAction` prefers `MEAL_SUPPORT` over
     `PROTECTIVE_REDUCTION` when trunk=`DIGESTION_ACTIVE` + `meal_rise_confirmed` + BG > target+30
     + **Δ≥0.8** (rationale `h4_meal_rise_bridge`). Falling/flat post-peak stays protective (field:
     ~48% of ungarded flips were Δ≤0). Env now carries `target_bg_mgdl`.
- **TODO:** (1) **Measure** from Hormonitor: share of ticks with `harmonia_production.selected_for_production=true`
  vs cascade; distribution of `runtime_blocker` reasons (why Harmonia is blocked). The viewer can surface this.
  (2) ~~Rename the stale "Lot 1 / no write path" labels~~ **DONE 2026-07-10** (`PhysiologicalTree.kt` roots corrected +
  anti-hallucination note added). (3) Decide, per branch, whether Harmonia should
  win MORE (bounded, evidence-gated like the SMB reconciliation) rather than deferring to the cascade so often.
  (4) Finish H4 remainder (veto vs `mealDeliveryPriority`, leaf→`MealCorrectionContextResolver`) — see
  `aimi-harmonia-implementation.md` §14. (5) Device-validate exit + H4 on a meal-after-mild-hypo day.
  (6) ~~`rbt_no_harmonia_channel` on SOFT meal rise~~ **DONE 2026-07-17**: SOFT + `MEAL_SUPPORT` +
  `DIGESTION_ACTIVE` keeps `HARMONIA_PRODUCTION_BASAL_FIRST`; production bypasses `smb_authority_active`;
  Harmonia `POST_HYPO` block cleared on aggressive-rise exit.
- **Validation:** the Hormonitor viewer per-day aggregation (already built) — add a "Harmonia authority" line;
  log markers `POST_HYPO_AGGRESSIVE_RISE_EXIT`, `PREDICTIVE_HYPO_AGGRESSIVE_RISE`, `h4_meal_rise_bridge`.

### P4 — Sport: the single effort-belief system (`EffortActivityBelief`), activated + extended.
- **Correction (2026-07-10):** an earlier version of this roadmap said "build a per-person exercise model" — that
  was wrong. A well-designed sensor **`EffortActivityBelief`** (multi-window steps + HR + HRV + stress
  disambiguation + effort **memory** ~120 min → graded, reduction-only SMB **and** basal factors) already existed
  (docs `AIMI_ARCHITECTURE_MAP.md` §11) but was dormant, and this session mistakenly added a **parallel** cruder
  hard-lockout + glucose brake. That parallel path has been **removed**; we harmonize into the belief instead.
- **Done this session:** `EffortActivityBelief` is now the single path — `basalFactor` **wired** to basal (was
  computed-not-applied), **enabled by default** (`OApsAIMIEffortActivityProtection`); parallel biometric-lockout +
  glucose brake removed; `HealthContextRepository` threshold reverted. Plus **post-effort adrenaline ≠ meal**: the
  belief is computed before meal detection and vetoes the undeclared-meal reading of an effort/adrenaline rise
  (`effortSuppressesUndeclaredMeal` → `detectMealOnset`/`inferredMealSafetyIntent` return false in EXERTION
  ACTIVE/RECENT_EFFORT, no declared meal, COB<12) — kills the `FAST_MEAL` over-correction. See map §11.6 v2/v2.1.
  Plus **effort is now a first-class tree branch** (v2.2): the belief feeds `PhysiologicalTree` activity/postActivity
  → Harmonia chooses PROTECTIVE_REDUCTION natively; single reduction/tick (orchestration skipped when Harmonia owns);
  detection thresholds lowered (≥200 steps/5m). Same performance guaranteed, no gap, no double-count.
- **TODO (map §11.6 deferred):** (1) **HRV plumbing** into the belief inputs (engine ready, wiring passes null).
  (2) Real **RBT authority** for the sensor leaves + **Harmonia** honoring sensor effort outside basal-first.
  (3) **Intent-propagation bug** (declared activity not reaching `user_intent.has_activity`) — needs a trace.
  (4) **Biometric-silent fallback** as a glucose **input to the belief** (not a parallel brake). (5) Consider a
  stronger basal floor at high exertion if `BASAL_FLOOR=0.70` proves too weak on real sessions.
- **Validation:** device testing on real sessions; the effort belief is reduction-only (fail-safe), watch that it
  fires appropriately (RECENT_EFFORT memory) and doesn't over-reduce.

### P5 — The validation loop (anti dead-code). The biggest *systemic* risk.
- **Problem (verified):** the largest failures this cycle were **silent**: a NN dormant for months, a floor poisoning
  three consumers, rich telemetry (Hormonitor) that nobody read. Not algorithm bugs — missing feedback.
- **Done this session:** Hormonitor **in-app viewer** (indexed, EN/FR) so the exported data can actually be read
  and shown to be structured + meaningful.
- **TODO:** (1) a lightweight **"is it wired and acting as intended"** audit for the computed-but-maybe-unused
  signals (the user's earlier "après coup" request) — e.g., Kalman `getLastRa()` computed but not fed to
  predictCurves. (2) surface, in the viewer or logs, whether each ML head (SMB, basal, T3C) is **live** (weights
  present, last-trained date). (3) a periodic self-check that flags dormant learners.

### P6 — Personalization for a normal life (child / woman / adult).
- **Principle (opinion, grounded):** AIMI already **models the person's context** (cycle, circadian/growth,
  activity, thermal) — that is the right philosophy and a genuine differentiator. The gap is that the ML wasn't
  *learning* (P2) and the prediction wasn't *honest* (P1). Fix those and personalization follows. Prefer
  **stability + calibration over aggression** — trust is what makes a life "normal".
- **Child:** small-dose robustness (the Medtrum U200 zero-bolus/concentration issue — already guarded), dawn/growth,
  unpredictable activity (P4), tolerance to imprecise meal announcement.
- **Woman:** the cycle-phase modulation (`wcycle` mults) is a **real strength AIMI already has** and most AID lack —
  needs **validation on real data** (the Hormonitor viewer is exactly for this).
- **Adult:** stress / variable schedule / exercise → reliable activity+stress detection (improved in P4).

---

## 2. Status snapshot (this cycle, uncommitted working tree)

| Item | Status |
|---|---|
| pkpd endogenous reversion (hybrid-only) | done, **needs device validation** |
| pkpd **learned kinetics drive the prediction curves** (`OApsAIMIPkpdPredictionKinetics`, default on, fail-safe) | done, **needs device validation** |
| SMB evidence-gated scenario reconciliation | done (committed earlier) |
| Basal ML: DI fix (coordinator injected) | done |
| Basal ML: label fix (realized future BG) | done |
| Basal instability: upward slew limiter | done |
| Sport: harmonized into `EffortActivityBelief` (basal wired, enabled; parallel path removed) | done, **needs device validation** |
| Hormonitor in-app viewer (indexed, EN/FR) | done |
| Eversense app-context AlertDialog crash | fixed |
| Fix stale "Lot 1" tree labels | **DONE** (2026-07-10) |
| Post-hypo aggressive rise exit + PREDICTIVE_HYPO SOFT | done, **needs device validation** |
| H4 meal-rise bridge (DIGESTION → MEAL_SUPPORT vs PROTECTIVE) | **partial**, needs device validation |
| Stability family ↔ SMB tail damping polarity (legacy ≤0.55 remapped) | **fixed 2026-07-18** (ladder 0.70–0.92; CC shows effective + migrates on open) |
| Measure Harmonia authority share | **TODO** |
| Per-person exercise model | **TODO** (superseded framing: extend `EffortActivityBelief`, not a parallel model) |
| Wire computed-but-unused signals (e.g. Ra→predict) | **TODO** |
| Confirm each ML head is live on device | **TODO** |
| Undeclared-meal **rise** under-dosing (A1 meal-rise bypass, `PREDICTIVE_HYPO_MEAL_BYPASS[_HYPER]`) | done (committed `db436e0b1a`), field-validated 26/07 (rise dosed ~20 U vs 2.82 U on 25/07) — see [AIMI_HARMONIA_SMB_ARBITRATION.md](AIMI_HARMONIA_SMB_ARBITRATION.md) §8 |
| Undeclared-meal **descent** hard-zeros — two-layer interlock (A1b clear-hyper hold + `HyperInstalledDroppingExemption` extended to `isPrediction`/`isAcceleratingDown`, shared projection-gated predicate) | **done 2026-07-26 (uncommitted)**, needs device validation on next fat/protein meal |
| pkpd **hyper-reversion** (hold insulin-only path at counter-reg baseline ≤80 in clear hyper, kills false `eventual=39`; `OApsAIMIPkpdHyperReversion`) | **done 2026-07-26 (uncommitted)**, general robustness; needs device validation |
| Post-hypo bypass latency (~14 min; meal right after hypo held NONE, `PostHypoAggressiveRiseExit` needs Δ>15) | **TODO** (softer meal-rise exit; validate in shadow) |
| pkpd Guard B coherence (hyper-reversion self-suspended at Δ≤−3; align on shared −15 predicate) | **TODO** |

---

## 3. Recommended order of attack

1. **P1 device validation** of the pkpd reversion (it gates trust in everything downstream).
2. **P2**: confirm the basal trainer runs; evaluate the freshly-trainable NN on clean labels.
3. **P3 measurement** (cheap, read-only via the viewer) + **fix the stale tree labels** (docs hygiene).
4. **P4** per-person exercise model (highest impact on "normal life").
5. **P5** wiring audit + liveness checks (prevents the next silent failure).

Nothing here is committed except where noted; on-device validation per `docs/NON_REGRESSION_CHECKLIST.md`.
