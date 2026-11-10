# AIMI — session handover, 2026-08-09

Started at the end of the 2026-08-09 morning session, extended 2026-08-10. Three parts:

- **Part A — the record of the morning.** Decisions and measurements. Several of its conclusions are
  superseded; read Part A-bis before acting on any of them.
- **Part A-bis — the production day of 2026-08-09.** What happened on the pump, what caused it, and
  which of Part A's conclusions were wrong.
- **Part A-ter — the lunch of 2026-08-10.** The Part A-bis build ran, and one of its brakes turned a
  near-hypo into a sustained hyper. **This is the current state of knowledge, and it reverses one
  change from Part A-bis.**
- **Part A-quater — the meal intent, traced (2026-08-11).** Answers Part A-ter's closing question and
  **corrects its diagnosis of the 2026-08-10 lunch**. This is now the current state of knowledge.
- **Part B — the prompt for the next session.** Paste it as-is, but read Part A-quater first — it
  outranks everything in Part B.

Part A is committed: `eda530a545` then `6d24095023`. **Everything in Part A-bis, A-ter and A-quater is
uncommitted.** Full module suite: **1345 tests, 0 failures** (was 1332; A-quater adds 13).

---

## Current state — read this first (updated 2026-08-18)

The newest part wins; current truth is **Part A-nonies** (2026-08-18). Nothing below is rewritten —
each part keeps the record of what was believed, which is how the wrong attributions were caught.

**A-nonies in one line.** First package from a device carrying the A-octies instruments (present on
231/231 ticks), and the first with a dated three-hour hike. BG reached **50.6**. Cause, traced and
fixed: the **sport safety guard zeroed the SMB four times and `RED_CARPET` restored it every time**,
because the sport guard never set `criticalSafetyZeroedThisTick` — the flag whose own comment says a
vital-safety zero is never restored. One line applied, quantified afterwards on 22 packages at
**-7.303 U**, and a **total no-op on both 08-15 and the 08-14 plateau**. Two corrections are owed to
A-octies: its **-13.41 U is overstated by ~25 %** (it did not model the downstream re-clamp; an
independent replay gives -10.631 U while reproducing the dinner episode exactly), and its acceptance
test **compares two vintages of the same variable**, so the three surviving "violations" are an
export artefact, not a dosing leak. On this day the shipped bound removed **0.494 U, not 26 %**.

**And two structural findings.** The 15-minute step window carried a **day total** — the Health
Connect sync sums whole records that merely *overlap* the window instead of aggregating, so one
consolidated Samsung workout record of 13 218 steps was credited in full to the 5-minute window;
nine observed values reconstruct exactly, including the recovery to 182. **That bug was protective**:
it was the only thing holding the exercise state ACTIVE, and fixing it naively **adds about +1.1 U
into the 85 minutes before the 50.6**, so the export must ship first and any guard must hold the last
plausible value rather than fall back to zero. Separately, **a meal always beats exercise**:
`exercise_afterburn_prob` reached 0.9371 and was dominant on **0 of 231 ticks**, being discounted
x0.92 while the meal saturates at 1.000; every exercise representation is gated on current movement
while every meal representation is gated on a glucose rise, so a post-exercise rebound reclassifies
as a meal one tick after the patient stands still. A three-hour hike also earns exactly what a
thirty-minute walk earns. **Everything in A-nonies is uncommitted.** Suite **1460 / 0 / 12**.

**A-octies in one line.** Nine parts ran in parallel on package `1786797984089`. **Five independent
routes to "dose more on a rising meal" were quantified and all five failed for the same reason** —
the rescue and the overdose occupy the same state space and no tick-local rule separates them
(route 3 is route 1 with the cap removed; the saturated-`min_pred` flag is `IOB ≥ 2.2 U` in disguise;
opening Harmonia's basal-first term is worth 0.000 U on three packages; an exhaustive 499-feature
scan finds no bound on the rise floor's own inputs). **The one change that worked stopped searching
in that space and went one level up:** the rise floor was capped at `maxOf(maxSMB, maxSMBHB)`, so it
overruled the ceiling the maxSMB ladder had already chosen — on 31 of 50 floor-active ticks. Both of
this package's sub-70 excursions are ticks where the ladder refused to promote and the floor
overrode it by 1.7× and 2.75×. Bounding it costs **−4.9 U before the 64.1, −4.8 U before the 65.7,
−0.098 U on the 08-14 90-minute plateau and 0.000 U on the 08-12 crash ticks**.

**And six exported instruments were found to be lying** (§AQ8-6): the whole `control_barrier` block
was written one tick early, `binding_stage` is wrong on 18 of 53 ticks, `harmonia_smb_authority` was
rebuilt from post-LIFT values so a LIFT always measured zero, `learning.gate_pass` disagrees with the
real gate on 84 of 173 transitions, `rise_floor_spent_u` gates nothing because
`remainingRiseFloorBudgetU` has zero call sites, and `variable_sens_mgdl` is a post-dose read that
agrees with the tube on 0 of 160 ticks. **Several numbers in Part A-bis, A-sexies and A-septies rest
on these.** AQ7-4's ×2.65 is dead, and RED_CARPET inflates every "units removed" figure by ~2×.

**Commit status (2026-08-15).** `84035882a2` and `4056b23d06` (the DIA kernel and contextual-shift
work) landed on the night of 08-14/08-15 and are committed. Agent CONTRACT reviewed them
retroactively and returned **FAIL**: a dosing change shipped unquantified (the kernel swap moved SMB
tail damping from 61.5 % to 93.7 % of ticks), two dosing changes shared one install, and
`4056b23d06` is **inert on the dosing path** — `OpenAPSAIMIPlugin.kt` resolves kinetics with
`causalPosterior = null`, so the contextual DIA shift is always 0 there, and the other resolve's
`effectiveDiaH` local is never read. `dia_governor.dominant_branch` is `LEARNED` on 174/174 ticks.
**Everything in Part A-octies is uncommitted.** Suite **BUILD SUCCESSFUL, 1460 tests, 0 failures,
12 skipped**. (The first run of the same suite failed once on the known-flaky
`aimiNeuralNetworkTest > test training reduces loss()`, which then passed on the re-run after the
reset fix — recorded here so nobody reads a single green run as proof that test is stable.)

**A-septies in one line.** The hypothesis that the ISF swing → the 39 floor → the tube veto → the
84-minute plateau is *one* defect was tested tick by tick and **the causality is refuted**: through
the plateau the tube was `GRADED` with full authority and `model_output_u` was 0.000, and the veto only
starts after the peak on a falling BG. They are two defects. The plateau is the controller
(`SAFETY_PRECAUTIONS` 29 %, throttle 14 %) and outranks the floor. Separately, `command_isf_mgdl` —
the basis of every ISF figure in this document — is **not** the sensitivity the dose used (ratio up to
×2.65, measured by inverting the tube's kappa on 194 ticks). The missing instruments now exist and are
export-only. Suite **1 428 / 0**.

**And the plateau now has its gate (§AQ7-7).** It is the **control barrier**: `cbf_permitted_u` falls
1.995 → 0.150 → **0.000 at 13:52** and stays there for the rest of the meal, because the barrier's own
`lfh` reaches −11.2 mg/dL/min — a claimed fall of **56 mg/dL per 5 min against a measured 9**. Its
insulin term has no appearance term to oppose it, which is the *same* root defect as the 39 floor.
`model_output_u` could never show this: it is read after the barrier, so its 0.000 means either "the
solver wanted nothing" or "the barrier suspended everything". **§AQ6-4's "the controller is absent" is
corrected — it was overruled.** `MPC_TAU_MIN` is measured to be a knife edge (τ 150 would permit
5.39 U on one tick) and must not be moved on arithmetic. Also found: `AutoDriveCommand.isSafe` is
documented as a sanity gate and is **never set false in production code**.

**Route 1 was then chosen, quantified, and refused (§AQ7-8).** Giving `lfh` an observation-derived
appearance term unsuspends 50 barrier ticks, of which **4 were followed by BG < 70 within two hours**
— all four the 2026-08-12 excursion that ran 297 → 45. The only gate that removes that exposure
(IOB < 8) also removes **every** plateau tick it was meant to help, and above IOB 11 no physiologically
bounded appearance term can offset `si x iob x bg` at all. **The 08-14 plateau and the 08-12 crash
overlap on every input the barrier has.** Nothing was changed. The real defect is the **overstated
insulin term**, not a missing appearance term.
**Commit status — the line above is now false.** Part A-bis → A-quinquies are committed
(`7447c24059`, `dfc53822d6`, `bc72fe1861`, `e4e12e723d`, `f87d25e024`); tree clean at `a478cf9330`;
**nothing is uncommitted**. Suite **1393 tests / 0 failures**, measured at `f87d25e024`, not re-run since.
*(Superseded 2026-08-15: two more commits landed after `a478cf9330`, and Part A-octies is
uncommitted on top of them. See the commit-status paragraph at the top of "Current state".)*

| what is broken right now | the measurement behind the claim | see |
|---|---|---|
| The forecast that gates the dose contradicts itself: `minPredictedBg` | drives the tube's 0.05 U ceiling on **80 of 292 ticks (28 %)**; at meal onset it vetoed with `min_pred` 64.3, **73.7** and **116.8** — two of them above the 70 floor — while its own terminal was 376 and BG rose 18 mg/dL per 5 min | §AQ5-6 |
| The rise floor still overrides the safety barrier, on purpose — `maxOf(v3SmbModel, v3SmbFloor)`, `DetermineBasalAIMI2.kt:5241` | **25 ticks, +12.87 U** above what the barrier permitted. Bounding it starved the 2026-08-10 lunch, so it stays until the governed path can carry a meal | A-bis, A-ter |
| The barrier cannot become that bound yet | `MPC_TAU_MIN` is still **75** min against 150–200 in physiology; `LEGACY_CONTROL_COEFFICIENT = 0.005` floors it so the refactor can only tighten; `cbf_permitted_u` is 0.00 on **46–58 %** of ticks | §D5–D6, A-bis 5 |
| Thresholds calibrated against a sleeping baseline — `isStressCortisol` = `HR > RHR + 12`, `PhysiologicalPhaseClassifier.kt:290` | RHR is **49**, so it is true above 61 bpm — every waking heart rate. Only a steep-rise escape sits on top, and the effort HR term has the same shape | §AQ5-2, §AQ5-5 |

**Certain, because measured.** 2026-08-09 lunch: 14.09 U in 79 min, IOB 16.75 against an 8.11 U
budget — the rise floor, **not** the ISF collapse (the 0.005 floor was active on 39 of 45 ticks).
2026-08-10 lunch: BG 268.7 on 6.53 U — the **effort multiplier** (0.45–0.56 on 14 ticks), since
6.81 + 7.01 = 13.82 U against 14.09 U. Over 24 h effort removed **21.71 U of 42.26**, and **61 of 82**
reduced ticks had no movement; the fix returned 7.26 U with no tick reduced more than before.
`LIFT_WITHIN_ENVELOPE` only restores RBT's own demand and beat the baseline on **1 tick per lunch**, so
no threshold makes it carry a meal. The tube is a faithful messenger: **52 of 53 vetoes correct**, on
descents that reached 41–76 mg/dL. **Uncontested:** the estimator advances once per CGM sample
(`ra_estimator_replayed_calls` = 0 in production), Harmonia has a real working SMB path, and the Ra
shadow separates rest (0.14) from meal (5.49 p90).

**Where parts disagree, who won.** A-ter blamed the episode budget for the 2026-08-10 undershoot;
**A-quater wins** — that budget's state was never exported, so A-ter inferred it, and the effort factor
explains the gap to within 2 %. A-ter called `LIFT_WITHIN_ENVELOPE` the fix; **A-quater wins** — it only
restores. A-ter's closing question is **answered** (§AQ1): `PhysiologicalTree.resolveInsulinIntent`,
activity gate 2, 150 of 150 ticks. The old note that `sensor_confidence` pins Harmonia is **refuted**
— 0.88 against a 0.45 threshold.

**The one question that blocks progress:** what computes `minPredictedBg`, and why does it report a
path minimum its own terminal contradicts? It gates 28 % of ticks, cannot be relaxed (right 52 of 53
times) and cannot be dosed through until trustworthy. It outranks `MPC_TAU_MIN` and the Ra gates.
**In flight — do not duplicate:** one agent on package `1786722482068` (today's lunch), one on
ISF-during-rise and on making the tree and Harmonia own the dose. To continue this work, paste the
fenced prompt at the end of this file; it supersedes Part B.

### Root cause found 2026-08-14, and what shipped

**`MpcController.buildDoseCandidates` stopped the sweep at 400 candidates of 0.005 U**, so the solver
could never *evaluate* a dose above 1.995 U — whatever `maxSMBHB` was set to. Maximum observed over
3 741 ticks: **1.9085 U**. On the 08-14 lunch the model sat at 1.845–1.852 for five consecutive ticks
against a configured cap of 2.50.

This is why the governed path never carried a meal, and why `aggressiveRiseSmbFloorU` supplied **93 %**
of the 08-14 lunch (+10.69 U, now *measured* through `rise_floor_spent_u`, matching the
`AUTODRIVE_FLOOR` stage to the cent) while `model_output_u` was 0.000 on 61 % of those ticks. Every
earlier explanation — Harmonia's intent, the catalogue cap, the SMB ceiling, the ISF collapse — sat
downstream of a controller that could not formulate the demand.

**Shipped together in `e80fef409a`:** the MPC grid fix, `ISF/DynamicSensitivityPolicy`, and the
`MealCertainty` anticipated-rise override. The recommendation had been to ship the grid alone, because
the other two move the dose on the *same* meal ticks and the export cannot separate them.

**Attribution is therefore partly lost — but not entirely.** The grid change has a signature the
other two cannot produce: `model_output_u` **above 1.9085 U**. Any tick exceeding that came from the
grid and nothing else. Use it as the discriminator on the next package; the residual is the combined
effect of the sensitivity policy and the override, which stays unattributable this cycle.

### Priority for the next session — this replaces the ISF-first framing

**`min_pred_used_mgdl = 39.0` outranks the ISF work.** It is not a prediction: it is
`NUMERIC_FLOOR_MGDL` (`pkpd/PkpdSoftFloorPathMin.kt:50`, `pkpd/AdvancedPredictionEngine.kt:15`). The
tube reads that saturated floor as an imminent hypo and caps the tick at 0.05–0.16 U. Measured on the
08-14 package: `VETO_HYPO_FLOOR` on **84/281 ticks (30 %)**, `min_pred_used_mgdl == 39.0` on **62/250**.
It produced the 84-minute plateau at 200–220 mg/dL with 11–13 U on board that the patient reported —
0.30 U delivered across 16 ticks.

> **⚠️ CORRECTED 2026-08-14 (later session) — the last sentence is wrong. See Part A-septies.**
> Through the plateau (13:42–14:21) the tube branch was **`GRADED` on every tick** with `max_smb_hb_u`
> at its full 2.50, and `model_output_u` was 0.000. The veto starts at **14:26, after the peak, on a
> falling BG**. The 39 floor and the plateau are **two separate defects**, not one.

The ISF agent said so itself: the sensitivity work fixes the tail, not the meal. On 172 rising ticks
there is no monotone relation between commanded ISF and proposed dose, and `controlCoefficient` floors
at ISF 45 so the MPC and the barrier never saw the collapse at all.

Second: **`variable_sens` — the sensitivity the dose actually uses — is exported nowhere.** Every ISF
figure in this document describes what the *predictions* saw. Fix that before trusting any of them.
**Done 2026-08-14 (Part A-septies), and it mattered: `command_isf_mgdl` is wrong by up to ×2.65.**

---

# Part A — the record

## A1. Where the code stands

Two commits landed this morning, on top of `1135038d55`.

| commit | what it contains |
|---|---|
| `eda530a545` | estimator advances once per CGM sample; one training row per tick, staged then flushed in a `finally`; `engaged` supplied by the caller; first version of the profile-anchored safety sensitivity |
| `6d24095023` | `InsulinActionModel` as the single source of truth; the `0.1` sensitivity floor removed; HTR floor and Ra exported from where they exist; MPC Ra floor wired on every path; barrier acceleration memory keyed per observation; shadow exports for the two open calibration questions |

The commit message of `eda530a545` says it *"Updated `AutoDriveState` to include a new `safetySi`
field"*. That field was removed again in `6d24095023` and replaced by a parameter on
`ControlBarrierShield.enforce`. **The message describes the opposite of the code.** Amend it or leave
a note; do not trust it when reading history.

## A2. Decisions taken, with the reasoning

### D1 — The once-per-tick invariant lives in the estimator, not in the callers

`AutodriveEngine.tick()` is reached from three places: the engaged Autodrive branch, the T3C shadow
tick, and `proposeBasalOnlyTbr`, which itself delegates to `tick()`. Each call ran
`ContinuousStateEstimator.updateAndPredict` unconditionally. Between two calls in the same tick
`dtMin ≈ 0`, so `raDecay ≈ 1` and the prediction step separates nothing: the same observation is
applied again, `pRa` takes another `+ qRa` then another `(1 - kRa)`, and the effective Kalman gain
doubles or triples.

The guard was put **inside** `updateAndPredict`, keyed on an observation id. Moving one call site
would not protect against the next call site somebody adds.

### D2 — The key is the CGM sample, not the loop invocation

First attempt keyed on `ctx.currentTime`. Measured on the production corpus
(`autodrive_dataset.csv`, 17 068 rows): **2 610 consecutive rows carry an identical `BG_Current`
*and* `BG_Velocity` less than 60 s apart, and `Estimated_Ra` moved between them on 1 974 of those
(76 %)** — by more than 0.2 mg/dL/min on 405, up to 2.83. So the duplication that actually happens is
*between* invocations, on the same CGM sample. The key is now `ctx.glucoseStatus.date`, with a
fallback to `ctx.currentTime` when the sample carries no date.

Two identities are deliberately separate and must stay separate:

- `tickId` — the invocation. Identifies the **training row**; must match what `flushTickRow` is
  called with.
- `observationId` — the CGM sample. Identifies the **observation**; governs the estimator advance and
  the barrier's acceleration memory.

### D3 — One training row per tick, labelled by the path that owned the dose

`tick()` no longer writes to the data lake. It stages a `PendingTrainingRow`; an engaged row is never
replaced by a shadow one for the same tick. `flushTickRow(tickId)` writes at most one row and is
called from `runDetermineBasalTick` in a `finally`.

`engaged` is a caller-supplied parameter. `tick()` cannot tell a real decision from a T3C proposal
whose SMB the caller strips and which never reaches the pump — and it used to label both as engaged.
That contaminated the very feature added the day before.

### D4 — The safety barrier reads a sensitivity nothing downstream can lower

`enforce` derives `lgh = -siMetabolic * bg` and permits `safeU = (-γh - lfh) / lgh`. A **lower**
sensitivity makes `|lgh|` smaller and the permitted dose **larger**. Anything able to lower that
number is able to loosen the barrier.

The sensitivity is now passed as a parameter, computed as
`max(clamp(profileIsf / 10000), commandedSi)` — the more restrictive of the two, so the change can
only ever tighten. It is a parameter and not a field on `AutoDriveState` because a `copy()` would
re-run the state's `init` checks on the dosing path, and because a second sensitivity in the domain
model is exactly what ADR 0008 step 3 will have to remove.

### D5 — One insulin-action model, calibrated to change nothing

There were **four** constants, not three, and the fourth did not look like one:

| reader | old expression | effective coefficient |
|---|---|---|
| `ContinuousStateEstimator` | `estimatedSI * 0.0012 * iob * bg` | 1.2e-4 |
| `MpcController` | `estimatedSI * 0.05 * iob * bg` | 5.0e-3 |
| `ControlBarrierShield` | `estimatedSI * 0.05 * iob * bg` | 5.0e-3 |
| `AutoDriveState.createSafe` | `estimatedSI.coerceAtLeast(0.1)` | governed all three |

Callers pass an ISF in mg/dL/U divided by 10000 — about 0.004. The floor clamped **every patient to
the same sensitivity**, 22 to 50 times above the value passed, so the profile ISF stopped reaching
the controller and the barrier entirely.

`InsulinActionModel` states the calibration as a time constant:
`effect = (isf / tau) * iob * (bg / 120)`.

- `MPC_TAU_MIN = 75` reproduces the old 5.0e-3 **exactly** at ISF 45.
- `ESTIMATOR_TAU_MIN = 3125` reproduces the old 1.2e-4 **exactly** at ISF 45.

Both are pinned by tests to 1e-12 / 1e-15. The physiological range for the exponential tail of a
rapid analogue is 150–200 minutes, so the controller and the barrier are roughly twice as aggressive
as physiology, and the estimator has effectively had **no insulin term at all**.

### D6 — The floor removal is real, so a floor on the coefficient replaces it

Removing the `0.1` floor makes the barrier proportional to the profile ISF, which is correct: a
resistant patient (low ISF) should be allowed more insulin than a sensitive one. But proportionality
necessarily **loosens** the barrier for every patient below the reference ISF.

Measured on 1 135 exported ticks from this deployment: **median profile ISF is 30, not 45.** The
unfloored coefficient would be 0.67×, i.e. a barrier about **a third more permissive on the median
tick**. That is a hypoglycaemia constraint, and a third is not something to ship on arithmetic.

So `InsulinActionModel.controlCoefficient` floors at `LEGACY_CONTROL_COEFFICIENT = 0.005`. Above the
reference ISF the barrier tightens; at or below it, it stays exactly where it was. **The refactor can
only tighten.**

Side effect worth knowing: that floor also blocks *any* downward multiplier, so the
`aggressionMultiplier ∈ [0.55, 1.08]` of `PkPdIntegration` can no longer loosen the barrier at all.
The `safetySi` anchor now only bites above the reference ISF. Both regimes have a test.

## A3. What was measured, and where

All figures below were computed directly, not estimated. Reproduce before trusting.

Sources — **outside the repo, and they contain personal health data. Do not copy them into the repo,
do not paste their contents into an issue, a commit or a shared document.**

- `/Users/mtr/Downloads/AIMI files/autodrive_dataset.csv` — 17 068 rows, 2026-02-28 → 2026-07-11
- `/Users/mtr/Downloads/AIMI_Support_Package_*/AIMI_Decisions*.jsonl` — 1 135 ticks with a profile ISF

| measurement | value |
|---|---|
| `Estimated_SI` median | exactly 0.1000; 83.7 % of rows ≥ 0.09 |
| barrier intervention, floor active (14 291 rows) | **41.2 %** of ticks; whole dose zeroed on 37.7 % |
| barrier intervention, floor inactive (2 777 rows) | **0.7 %** |
| profile ISF | p10 = 30, median = 30, p90 = 70 |
| insulin term, barrier/MPC | median 1.668 mg/dL/min (p90 10.0, max 32.3) |
| insulin term, estimator | median 0.040 mg/dL/min |
| same term at τ = 150 / τ = 200 | median 0.833 / 0.625 |
| duplicate CGM samples with Ra moving | 1 974 of 2 610 pairs (76 %), max Δ 2.83 |

The barrier is **not** a rarely-binding component. It is the dominant clamp, and it is dominant
because of a `coerceAtLeast` on a value carried in the wrong units, not because of a designed margin.

## A4. Errors corrected

### Mine, made and corrected during this session

1. **Wrote a factual claim into a test class doc that production contradicts.** The doc said the
   barrier "rarely binds" and that "the only clamp that fires is the maxIOB truncation". True of a
   fixture that builds `AutoDriveState` through the constructor and so bypasses `createSafe`; false
   of the pump, where it binds on 41.2 % of ticks. Corrected with the measured numbers. This is the
   most damaging kind of error: written with enough authority to stop the next reader re-measuring.
2. **Keyed the estimator guard on the invocation instead of the CGM sample** — see D2. The guard
   would have caught nothing that actually happens.
3. **Confused the training-row identity with the observation identity** while fixing (2). Would have
   made `flushTickRow` reject every row. Caught before the build; fixed by splitting `tickId` and
   `observationId`.
4. **Put the safety sensitivity on `AutoDriveState`**, which added a `copy()` on the dosing path.
   Kotlin `copy` re-runs the `init` block, so a non-finite estimate would have thrown where it used
   to be tolerated. Replaced by a parameter.
5. **Called `flushTickRow` as a plain statement**, so a throw in the inner tick lost the staged row —
   biasing the training set away from the anomalous ticks, which are the ones worth learning from.
   Moved into a `finally`.
6. **Presented the "policy can loosen the barrier by 45 %" threat model as live.** It was dead at the
   `0.1` floor: `aggressionMultiplier × ISF/10000` coerces to `0.1` in every arm. Confirmed
   empirically — post-floor `Estimated_SI` spans 0.0999 to 0.1006, a ±0.3 % spread, not ±45 %.
7. **Reported "the exit bound would bite 3 % of ticks" as if it measured the safety path.** It was
   measured on `oapsProfile.sens`; the barrier and the MPC read `pkpdRuntime.fusedIsf`. The figure is
   withdrawn and has not been re-measured. See T7.

### Pre-existing, found and fixed

- Estimator advancing 1–3 times per tick, at a rate that depended on which preferences were on.
- Training rows duplicated at the same timestamp, all labelled `Engaged = 1`, including T3C proposals
  that never reach the pump.
- `htr_ra_floor_mgdl_per_min` guaranteed `null` in every export: written inside `if (gate.engage)`,
  read at tick bootstrap from a frozen `val`. The one instrument added to make the HTR floor
  measurable could not measure it.
- `estimated_ra_mgdl_per_min` read at bootstrap, so it carried the **previous** tick's estimate — the
  comparison with the floor, which is the whole point of exporting the two together, was meaningless.
  A correctly-timed `estimated_ra_used_mgdl_per_min` was added.
- `mpcRaFloorMgdlPerMin` wired at one of three call sites; the other two took the `0.0` default in
  silence. Now relayed, and the two T3C paths pass `0.0` explicitly with the reason written down.
- `ControlBarrierShield.lastBgVelocity` is `@Singleton` state: a second `enforce` in the same tick
  read `accel = 0`, disarming the guard that halves γ on a fast fall, and destroyed the tick-to-tick
  derivative. Now keyed per observation — which is also what makes the shadow second call safe.
- The four insulin-action constants and the `0.1` floor (D5, D6).

## A5. What tonight's data must answer

The build now computes two shadow quantities. Neither reaches the pump; both are export-only.

**Verification of the four fixes:**

| check | field |
|---|---|
| estimator advances once per CGM sample | `ra_estimator_advances` +1 per tick; `ra_estimator_replayed_calls` rises when T3C runs |
| one row per tick, correctly labelled | no duplicate timestamps in the CSV; `Engaged = 0` on T3C proposals |
| HTR floor measurable | `htr_ra_floor_mgdl_per_min` non-null, against `estimated_ra_used_mgdl_per_min` |
| dose unchanged | `cbf_permitted_u` reproduces the previous build |

**The two open calibration questions:**

- `ra_aligned_tau_shadow_mgdl_per_min` — the same Kalman filter, same process noise, same clipping,
  only the insulin term aligned on the controller's. If it stays below 0.6 most of the time, the
  projection from medians was too pessimistic and `ESTIMATOR_TAU_MIN` can be aligned. If it sits at
  the ceiling whenever there is IOB, the Ra gates (0.6 / 0.7 / 0.8) and `UndeclaredCobEstimator` must
  move in the same change. **The two meals are the discriminating test**: that is where Ra must rise
  for real, so that is where we see whether the shadow separates a meal from the mere presence of
  insulin.
- `cbf_permitted_u` vs `cbf_permitted_unfloored_u`, with `cbf_profile_isf_mgdl` alongside — the same
  barrier, same state, floor removed. This is the number that decides the ±33 %, measured on the real
  ISF distribution rather than on a median.

---

# Part A-bis — the production day of 2026-08-09, and what it changed

Written 2026-08-10. This supersedes several conclusions in Part A. Read it before Part B.

## The event

An undeclared lunch. No meal mode, COB 0 g throughout. Support package
`AIMI_Support_Package_1786304612047`, 292 ticks.

| | |
|---|---|
| SMB delivered 14:07 → 15:26 | **14.09 U in 79 minutes** |
| peak IOB | **16.75 U**, against a computed physiological budget of **8.11 U** |
| BG | 115 → 225 → 110, still 11.1 U on board at 16:06 |
| outcome | no hypoglycaemia — rescue carbs (ice cream) at 16:10 |

The patient's words: *"ça a corrigé un peu trop"*.

## The cause, and the one it is not

**Not the ISF collapse.** `command_isf_mgdl` did collapse — 46.8 at euglycaemia down to a terminal
9.7 at BG 225, driven by `dynamicDeltaCorrectionFactor` (`OpenAPSAIMIPlugin.kt:707`, exact against the
export to 10 decimals). But `InsulinActionModel.controlCoefficient` floors at 0.005 and the floor was
active on 39 of 45 ticks, so **the MPC and the barrier both reasoned as if the ISF were 45 on every
tick of this event**. The collapse changed their arithmetic by nothing.

**The cause is `aggressiveRiseSmbFloorU`, `max()`-ed after the safety barrier.**
`DetermineBasalAIMI2.kt`, `val v3SmbRaw = maxOf(v3SmbModel, v3SmbFloor)`, downstream of
`safetyShield.enforce`:

```
h      BG     IOB    MPC asked   barrier permitted   delivered
14:41  188.5   9.04     0.000          0.000           1.088
14:46  202.2  10.36     0.000          0.000           1.088
14:51  212.6  11.65     0.000          0.000           1.088
14:56  217.2  12.75     0.000          0.000           1.088
15:01  220.3  13.66     0.000          0.000           1.600
15:06  224.9  15.28     0.000          0.000           1.550
```

Over the whole day: **25 ticks where the delivered dose exceeded what the barrier permitted, +12.87 U
in total.** A per-tick floor with no memory, at a 1.6 U ceiling on 5-minute ticks, is an effective
19 U/h floor for as long as the rise holds. Its KDoc claims it is *"always re-bounded by V3 safety"*.

**Nothing braked the stack.** `InsulinLoadGovernor` computes the right number (`phys_budget_u` 8.11)
and stayed in tier `FULL` up to IOB 16.75 — 2.07x the budget — because `ESCAPE_RISE` pinned the
multiplier on any rise with **no IOB guard**, while `ESCAPE_PROJECTION` three lines below already had
one. `maxIOB` is 20 U, which against a budget of 8.11 is not a brake. The predictive hypo guard fired,
announced 67 mg/dL, and was overridden by `PREDICTIVE_HYPO_MEAL_BYPASS`.

**Harmonia was not absent — it was overruled.** Through the entire rise the pattern catalogue proposed
a SOFT cap of **1.2 U** and Harmonia's SMB arbiter returned `REDUCE` with intent `PROTECTIVE`. Three
components said no or less; a `maxOf()` downstream of all three served anyway.

## Corrections to Part A and to earlier notes

1. **The build boundary was 14:07, not 16:22.** Part A's A5 assumed the new export fields would date
   it. They did not: `ra_estimator_advances` was written **after** the JSONL was serialised, so it
   landed on 7 ticks out of 93, all `Basal_Modulation`. The lunch ran on `6d24095023`. Fixed —
   the counters are now written in `markEstimatorDiagnosticsForExport`, immediately before
   `decisionCtx.toMedicalJson()`, the one point every export path goes through.
2. **The two calibration questions of A5 are still unanswered.** The shadow reached only 7 ticks for
   the reason above. What those 7 do show: on flat glucose at BG 91 with 5.5 U on board and a real Ra
   of 0.00, the aligned-tau shadow reported **0.60 / 0.70 / 0.79** — it invents appearance to explain
   why glucose is not falling faster, and crosses all three gates. Suggestive, not conclusive: n = 7,
   biased to quiet ticks. **A clean day is still needed.**
3. **`sensor_confidence` is no longer the Harmonia blocker.** It measured 0.88 (min 0.77) against
   thresholds of 0.45 / 0.65. The `cgm_first` option is on and works. The memory note claiming it is
   pinned at 0.32 is obsolete.
4. **Harmonia has an SMB path, and it works.** `basal_first_only = true` is the *label of the basal
   production record*, tautological — not a restriction. `HarmoniaSmbArbiter` exists with a
   `LIFT_WITHIN_ENVELOPE` mode. The note claiming the LIFT is "structurally dead downstream" is wrong:
   `RecursiveBeliefResolver` applies it and explicitly protects it from the legacy meal-support
   target. LIFT never fired on this day because the intent was `PROTECTIVE`, which takes an early
   return that can only reduce or accept — correctly, on this meal.
5. **Do not clamp the dose to the barrier.** It was proposed as a "pure reduction" and it is not: it
   would remove **12.87 U, 38 % of the day's SMB**, and it would have delivered nothing during the
   evening rise (BG 130 → 151, barrier at zero on every tick). The barrier's `lfh` was wrong by
   ~12 mg/dL/min during the meal. **The barrier cannot be made binding until its insulin model is
   fixed** — which is the `MPC_TAU_MIN` question deliberately deferred in D5.

## Structural finding: five writers of the final SMB

| site | owner | legitimate |
|---|---|---|
| `finalizeAndCapSMB` | the intended terminal | yes |
| `runMealAdvisorDecisionOrReturn` | meal advisor | yes — user action |
| `applyLegacyMealModes` (x2) | legacy meal modes / prebolus | early-exit paths |
| `runPostBasalEngineLearners…AuditorStage` | **AI auditor** | **no** |

The auditor wrote `finalResult.units = result.bolusU` with no comparison against the terminal, while
its own prompt says *"CONFIRM or SOFTEN only — never invent a lift"*. Third instance in two days of
the same shape: **a constraint stated in documentation and absent from the code.**

## What was implemented on 2026-08-09/10 (uncommitted, 1332 tests green)

- **Export instrumentation fixed** — counters and the Ra shadow now reach every tick.
- **`ESCAPE_RISE` IOB guard** — the escape stops at the physiological budget, matching its neighbour.
  On this event it stops at 14:41, IOB 9.04 vs budget 8.11: the tick the overdose began.
- **Episode budget on the rise floor** — one prebolus per rise, re-arming after 90 idle minutes; only
  what the floor adds *above the model* spends the budget.
- **Pattern catalogue in fractions of the user's ceiling** — the table was absolute units implicitly
  calibrated for `maxSMBHB = 1.6`. Converted against that reference, rounded **down**, max difference
  0.001 U. Read as fractions it is a coherent severity ladder from 19 % to 94 %; read as units it was
  a single-patient table. A fraction can never exceed 1.0, so a pattern may reduce the user's ceiling,
  never raise it.
- **Sealed SMB terminal** — `applySmbUnits(rT, value, owner)` is the only write path; after
  `finalizeAndCapSMB` seals the tick, a write may only lower. `MealAdvisor` keeps its exception, now
  counted. Refusals exported as `smb_seal_refused_count` / `_total_u`.
- **Learning pipeline hardened** — schema-versioned dataset (the `Engaged` feature was perfectly
  collinear with "labelled by the broken pass"; 73.2 % of rows had an uncovered outcome window; the
  positive class is **0.53 %**, not 3 %), non-blocking lock on the dosing path, class-balanced trainer
  objective with a chronological holdout gate and prior correction on save, `SensitivityRatioEstimator`
  given COB/bolus rejection, real delivered basal, time-keyed EMA and persistence.

**The sealed terminal would not have prevented this event.** The floor is an *input* to
`finalizeAndCapSMB`, upstream of the seal. The two brakes are what address it.

---

# Part A-ter — the lunch of 2026-08-10, and the brake that was reverted

Written 2026-08-10, after the build of Part A-bis ran in production. **This is the current state of
knowledge. It reverses one of Part A-bis's changes.**

## The event

The Part A-bis build was installed. Same patient, undeclared lunch again, no meal mode, COB 0.
Support package `AIMI_Support_Package_1786365787997`.

| | 2026-08-09 (no episode budget) | 2026-08-10 (episode budget active) |
|---|---|---|
| peak BG | 225 | **268.7** |
| SMB at peak | 14.06 U | **6.53 U** |
| peak IOB | 16.75 | 8.66 |
| two hours later | 110 (rescue carbs) | still ~205 |

One failure was replaced by its opposite.

## What caused it, and what did not

**Not the pattern catalogue.** The conversion to fractions reproduces the previous behaviour exactly:
`max_smb_hb_u` = 1.6, `smb_cap_u` = 1.20, identical to the previous absolute value. It is exonerated.

> **⚠️ CORRECTED 2026-08-11 — this attribution is wrong. See Part A-quater §AQ5.** The 2026-08-10
> undershoot was the **effort veto after a walk to lunch** (steps15 390–453 at 12:02–12:12, HR 74–84),
> which multiplied the SMB by 0.45–0.56 on 14 ticks. Both lunches ran the *identical* cap chain
> (proposed 1.60 → safety 1.36 → throttle 1.09) and differed only in that multiplier. The paragraph
> below is left as the record of what was believed on 2026-08-10; its own caveat — that the budget
> state was never exported and its absence was inferred — is exactly why it was wrong.

**The episode budget on `aggressiveRiseSmbFloorU`.** The floor appears nowhere in the 2026-08-10 lunch
trace, while on 2026-08-09 it delivered 1.36 then 1.60 U per tick. Doses were small from the very
first tick — 0.29 U at BG 125 with IOB 0.94 — well before any governor action.

**Caveat, and it is mine:** the budget state (`riseFloorSpentU`, time since last contribution) was
**never exported**. The floor's absence is inferred from the trace, not measured. Second time in this
work that a mechanism was shipped without its instrument.

> **⚠️ CORRECTED 2026-08-11.** `PROTECTIVE` is real, but it is *not* the root cause, and
> `LIFT_WITHIN_ENVELOPE` is not the fix: it can only restore RBT's own demand toward the MPC command,
> never exceed it, so it cannot carry a meal at any threshold. See Part A-quater §AQ5.

**The root cause is upstream of both brakes.** On **every tick** of the rise,
`harmonia_smb_authority.insulin_intent` was `PROTECTIVE` — at BG 144, 195, 237, 269, with Ra up to
5.33. Never `MEAL_SUPPORT`, never `NEED_MORE_INSULIN`. `PROTECTIVE` takes an early return in
`HarmoniaSmbArbiter.decide` that can only ACCEPT or REDUCE, so `LIFT_WITHIN_ENVELOPE` is structurally
unreachable. The governed demand caps at the catalogue's 1.20 U and Harmonia reduces it further,
leaving ~0.5 U per tick against a meal driving BG to 268.

**So the floor was not merely bypassing the barrier — it was carrying the entire meal.** The governed
path never has. Removing the bypass without first checking that the legitimate path could carry the
load turned a near-hypo into a sustained hyper.

## What was changed on 2026-08-10

- **The episode budget was removed from the dose path.** `remainingRiseFloorBudgetU` and its eight
  tests are kept, out of the dose path, with the two measurement tables in its KDoc and an explicit
  instruction not to reinstate it until Harmonia's intent switches on a confirmed meal.
- **The `ESCAPE_RISE` IOB guard was kept.** The two brakes bound the same thing — accumulation — but
  not at the same place. The budget cuts at the *start* of a meal, when dosing is still needed; the
  guard cuts at the *physiological budget*, when there genuinely is too much insulin on board. On
  2026-08-09 it would have stopped at IOB 9.04 instead of 16.75. Keeping the guard and dropping the
  budget should land between the two failures.

Suite: 1332 tests, 0 failures. Uncommitted.

## The lesson, stated plainly

The 2026-08-09 brake was designed from a single episode — the one where the floor delivered 14 U too
many — without measuring what the governed path delivered **without** it. It delivered almost nothing.
Removing a bypass is only safe once the legitimate path is shown to carry the load.

## The dinner of 2026-08-10 — a regression I introduced, and a useful signal

Package `AIMI_Support_Package_1786432933407`. The patient observed that the highest SMB of the dinner
was 1.2 U and asked why it could not reach the configured 1.6.

**Answer:** `max_smb_hb_u` did ramp correctly with glucose (0.88 → 1.12 → 1.36 → 1.60), but the meal
patterns propose **0.75 of the ceiling**, so the effective cap is 1.20 and never 1.60. Confirmed:
`smb_cap_u` = 1.200 on every tick of the rise, and the delivered doses sat on it (1.16, 1.19, 1.24).

**The regression — mine, fixed.** The fraction conversion was calibrated against `maxSMBHB = 1.6`
assuming that ceiling constant. It is not: `this.maxSMBHB` is a field **reassigned during the tick**
that ramps with glucose. Multiplying a fraction by it applied the reduction twice:

| BG | cap before the conversion | cap with the bug | cap now |
|---|---|---|---|
| 111 | 1.20 | **0.66** | 1.20 |
| 125 | 1.20 | 0.84 | 1.20 |
| 140 | 1.20 | 1.02 | 1.20 |

Up to **45 % tighter at the start of a meal**, exactly when the prebolus matters. Fixed by reading
`preferences.get(OApsAIMIHighBGMaxSMB)` — the configured setting — instead of the ramped field. The
catalogue reduces a *setting*; the tick's own maxSMB/maxSMBHB selection still bounds the dose
afterwards, so the two bounds act in their own place.

Same failure shape as the episode budget: a claim of neutrality verified at one operating point only.

**Two signals worth carrying forward:**

1. At 21:12 and 21:17 the intent was **`NEED_MORE_INSULIN`**, not `PROTECTIVE`. First observed switch.
   The mode stayed `ACCEPT` because the MPC was not asking above the 1.20 cap, so there was nothing to
   lift. This points Agent 0 at a **mis-calibrated threshold — too late, too rare — rather than a dead
   path.** That is a materially different (and easier) problem than the one Part A-ter states.
2. The day's highest SMB was **1.60 U at 07:42**, with no meal pattern active. The full ramp is
   reachable outside meals and capped at 75 % during them — the inverse of what is wanted.

**Still open:** a `SOFT` cap that is never lifted behaves exactly like a `HARD` one. Either the lift
becomes reachable (Agent 0), or an unlifted `SOFT` must defer to `maxSMBHB` instead of replacing it.

## The Ra shadow, finally measurable — and the answer to A5's first calibration question

The export-ordering fix landed, so `ra_aligned_tau_shadow_mgdl_per_min` now reaches **226 ticks out of
231** instead of 7 out of 93. Measured on `AIMI_Support_Package_1786432933407`, and pooled with the
earlier corpora where the field exists (**413 paired ticks**).

### The aligned insulin term produces a usable signal

| | Ra today | shadow, aligned tau |
|---|---|---|
| median | 0.21 | 0.87 |
| p90 | 1.51 | 5.49 |
| max | 5.33 | 8.13 |
| crosses 0.6 | 23 % | **59 %** |

**The earlier fear was overstated.** On the 65 ticks where the real Ra is ≤ 0.05 — nothing digesting —
the shadow's median is **0.14**, and it crosses 0.6 on only 13 of them. It does *not* invent
appearance from the mere presence of insulin. It separates rest (0.14) from meal (p90 5.49).

The conclusion of A-bis correction 2 — "suggestive, not conclusive, n = 7, biased to quiet ticks" —
resolves in favour of alignment being **viable**, provided the gates move with it.

Also confirmed in production: **`ra_estimator_replayed_calls` = 0**. The once-per-CGM-sample guard
holds; no duplicate advances observed.

### Iso-rate gate thresholds — and why the result is itself a finding

Mapping each current gate to the shadow threshold that reproduces its crossing rate, over 413 ticks:

| gate | crossing rate today | equivalent shadow threshold |
|---|---|---|
| 0.6 | 18.4 % | **4.25** |
| 0.7 | 16.7 % | **4.55** |
| 0.8 | 16.2 % | **4.57** |

Shadow distribution: p50 0.63, p75 2.85, p90 5.22, p95 6.14, p99 7.38.

**The three thresholds compress to almost one value, because they already do today.** 0.6 / 0.7 / 0.8
select 18.4 % / 16.7 % / 16.2 % — 2.2 percentage points apart across the whole range. Three gates that
differ by two points of population are not three gates; they are one gate written three times.

So iso-rate mapping is the wrong method here, and it fails informatively: it should not be used to
pick 4.25 / 4.55 / 4.57. The prior question is **what each of the three gates is supposed to
discriminate**, since today they do not discriminate anything from each other. Answer that first, then
place thresholds on the shadow's distribution by intent rather than by rate-matching.

**Nothing was changed in code for this.** `ESTIMATOR_TAU_MIN` stays at 3125 (behaviour-preserving),
the gates stay at 0.6 / 0.7 / 0.8. The shadow is exported on every tick, so the next packages extend
the sample at no cost and with no dosing risk.

**Deliberately not sequenced with the dosing work.** `OApsAIMIHighBGMaxSMB` is being raised by the
patient, which moves the dose. Changing the Ra gates at the same time would make neither effect
attributable — the exact error made twice this week.

## The open question, and it is now the only one that matters

**Where is `insulin_intent` decided, and why does it never select `MEAL_SUPPORT` on a confirmed
undeclared meal?** Until it does, the loop depends on a floor that ignores the MPC, the barrier and
Harmonia — and every attempt to bound that floor will starve meals. This is the next session's first
task, ahead of everything in Part B.

---

# Part A-quater — the meal intent, traced (2026-08-11)

Written 2026-08-11 by the Agent 0 (`meal-intent`) run. **This answers Part A-ter's closing question
and corrects its diagnosis of the 2026-08-10 lunch.** Everything below was computed from the three
support packages (`1786304612047`, `1786365787997`, `1786432933407`); reproduce before trusting.

## AQ1. Where `insulin_intent` is decided, and what makes it `PROTECTIVE`

Producer: `PhysiologicalTreeBuilder.resolveInsulinIntent`,
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PhysiologicalTree.kt`. Two
`PROTECTIVE` gates, in order:

| gate | line (before this change) | condition |
|---|---|---|
| 1 — hypo / sleep | `PhysiologicalTree.kt:301` | trunk `HYPO_RISK` or `SLEEP_RECOVERY`, or `branches.hypoRisk.confidence >= 0.55` |
| 2 — activity | `PhysiologicalTree.kt:307` | `branches.activity.confidence >= 0.60` **or** `branches.postActivity.confidence >= 0.55` |

**Gate 2 causes essentially all of it, and gate 1 causes none of it on a meal.** Classified over every
`PROTECTIVE` tick in each package:

| package | gate 2 (activity) | gate 1 (hypo/sleep) |
|---|---|---|
| lunch 2026-08-10 | **150 / 150** | 0 |
| dinner 2026-08-10 | **156 / 156** | 0 |
| lunch 2026-08-09 | 74 / 98 | 24 |

At the tick the prompt named — **BG 237, Ra 4.59, COB 0, 12:36 on 2026-08-10** — the tree reported
`hypo_risk 0.06`, `activity 0.00` (steps15 = 0, HR 82), and `post_activity 0.79` made **entirely of
`effort_recent = 0.79`**; the biometric `exercise_afterburn` was only 0.20. The `effort_recent` memory
came from steps15 = 390–453 at 12:02–12:12 with HR 74–84 — walking to lunch, scored `effort = 1.00`.
So a meal branch at confidence 1.00 and a digestion branch at 1.00, on a trunk already
`DIGESTION_ACTIVE`, lost to a 120-minute activity memory of a walk.

## AQ2. The dinner switch at 21:12 — Part A-ter's reading was wrong

Part A-ter says the mode stayed `ACCEPT` at 21:12/21:17 "because the MPC was not asking above the
1.20 cap". It was: `v3_smb_before_u` = **1.600** against a SOFT catalogue cap of **1.200**. The real
reason is the third term of `liftEligible`: `meal_certainty.level` was **`LOW`** on every dinner tick
(BG peaked at 199, and the effort veto was on), so `mealCertaintySupports` was false.

## AQ3. Three independent blockers, all rooted in the same signal

`HarmoniaSmbArbiter.decide` needs `softMeal && riseConfirmed && mealCertaintySupports &&
wantsMoreInsulin && max(mpc, before) > softCap`. Measured at 12:36 on 2026-08-10:

| term | value | why |
|---|---|---|
| `softMeal` | ✅ SOFT 1.20, `MEAL_UNDECLARED_FAST` | — |
| `max(mpc, before) > softCap` | ✅ 1.60 > 1.20 | — |
| `wantsMoreInsulin` | ❌ intent `PROTECTIVE` | AQ1, gate 2 |
| `mealCertaintySupports` | ❌ forced `false` | `RecursiveBeliefResolver.kt:778` |
| `riseConfirmed` | ❌ forced `false` | same |

The two forced values come from `channelOpen`, which required `basalFirstChannel == NONE`. On these
ticks the channel was `HARMONIA_PRODUCTION_BASAL_FIRST`, granted by
`allowsHarmoniaBasalDuringSoftMealSupport` — the exception added so TBR could still act on a soft
meal. **It requires `releaseAuthority == SOFT`, i.e. RBT still holds SMB authority; it was never meant
to revoke the SMB lift, and that is exactly what it did.** `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST`
is the dominant blocker of the whole corpus: 110 / 133 / 144 ticks per day, and every tick of both
lunch rises, with `effective_authority` = `SOFT` throughout.

`MealCertainty` already computes the right answer and is simply not consumed by the tree: it reached
**`HIGH`** (`supportsMealOverProtective`) at 12:32–12:56 on 2026-08-10 (BG 216 → 269) and across
14:17–15:06 on 2026-08-09. Its KDoc already says *"digestion + strong rise → meal support beats
protective"*. The tree is built before it, so the intent never sees it.

## AQ4. What was changed

All uncommitted. Two groups: the governed-path unblocking (A), and the change that actually moves the
dose (B), plus the instruments (C).

**A — make `LIFT_WITHIN_ENVELOPE` reachable on a confirmed meal**

| file | change |
|---|---|
| `patient/PhysiologicalTree.kt` | `resolveInsulinIntent` takes `mealOverridesProtective`; when true, **gate 2 only** is skipped — gate 1 is untouched. New `PhysiologicalTreeBuilder.withMealCertainty(...)` re-resolves the intent after `MealCertainty` exists. |
| `DetermineBasalAIMI2.kt` | calls it right after `lastMealCertainty` is set, gated on `mealCertainty.supportsMealOverProtective`; logs `🌳 TREE_INTENT`. Also passes `autodriveEngine.lastCbfPermittedU` into the RBT context. |
| `recursive/RecursiveBeliefResolver.kt` | the soft-meal basal exception no longer closes the SMB arbitration channel: `basalFirstClosesSmbChannel` is true for T3C, and for Harmonia only when `releaseAuthority != SOFT` (the classic mutex). |
| `patient/HarmoniaSmbAuthorityDecision.kt` | `decide` takes `barrierPermittedU`; it bounds the **LIFT only**, and never below `demandBeforeU`. |

Bounds that already existed and are preserved: the envelope is `min(maxSmbEffectiveU, iobHeadroom)` and
`maxSmbEffectiveU = maxSMBHB.coerceAtLeast(maxSMB)` — **a LIFT already reaches the full `maxSMBHB`**,
so nothing had to change for the patient's decision on that point. HARD catalogue caps re-bind after
the arbiter (`RecursiveBeliefResolver.kt` terminal `hardBindingCapU()`), and everything still passes
through `finalizeAndCapSMB` / `applySmbUnits`, so a lift cannot reach the pump past the seal.

The barrier bounds the lift and not the whole dose on purpose: A-bis correction 5 measured that
clamping the dose to the barrier removes 38 % of a day's SMB, and `cbf_permitted_u` is 0.00 on 46 %
(lunch package) to 58 % (dinner package) of ticks. On the 2026-08-10 lunch rise it was 1.68–2.00 U, so
the bound costs nothing there.

**B — the effort SMB floor on a certain meal (patient decision, 2026-08-11)**

`MealCertaintyBuilder.effortSmbFactorFor(certainty, requestedFactor)` floors the effort multiplier at
**0.75** when — and only when — `MealCertaintyLevel.HIGH` holds. `finalizeAndCapSMB` calls it in place
of reading `lastEffortAssessment.smbFactor` directly.

The floor is a **mitigation, not the fix** (see AQ7). It cannot bypass anything: it is at most 1.0, so
the post-effort dose never exceeds the pre-effort value, which the HARD catalogue caps, the
barrier-bounded arbitration and the seal have already bounded. `HIGH` already requires
`DIGESTION_ACTIVE`, an OK rise, BG above the meal band and terminals with no hypo conflict, so effort
protection is untouched outside a confirmed meal — the 2026-08-10 dinner never reached `HIGH` and does
not move at any floor value.

**C — the instruments that were missing**

Two blind spots have each cost one wrong attribution. Both are now written in
`markEstimatorDiagnosticsForExport`, the one point every export path goes through (the fix from A-bis
correction 1), and the effort fields are reset at tick bootstrap so a basal-only tick exports null:

| field | why |
|---|---|
| `effort_smb_factor_requested` / `_applied` | the multiplier was only recoverable by parsing the narrative — the reason AQ5's 7.01 U is a reconstruction |
| `effort_smb_before_u` / `_after_u` | brackets the reduction in units |
| `effort_smb_floored_by_meal` | did the confirmed-meal floor bite this tick |
| `rise_floor_spent_u` | the episode-budget state A-ter had to infer |
| `rise_floor_minutes_since_contribution` | idle time against `RISE_FLOOR_REARM_MS` |

Suite: **1345 tests, 0 failures** (`:plugins:aps:testFullDebugUnitTest`). New tests:
`HarmoniaSmbArbiterTest` ×5 (full-ceiling lift, barrier bound, barrier never lowers, null barrier,
`MEAL_SUPPORT` lifts), `PhysiologicalTreeBuilderTest` ×4 (the 12:36 veto reproduced, the override, the
no-op case, and that the hypo gate still wins), `MealCertaintyBuilderTest` ×4 (floors only at `HIGH`,
never raises above what effort asked, always a reduction incl. NaN and >1 inputs, and a total effort
stop still keeps a quarter on a certain meal).

## AQ5. What it would have delivered — and the number that actually matters

**It delivers essentially nothing, and the reason is worth more than the change.**

`mpcDemandU` passed to the arbiter is `ctx.v3SmbU`, set at `DetermineBasalAIMI2.kt:5235` to the same
`v3Smb` that becomes `htr.v3SmbBeforeU`. The lift's output can therefore only exceed the baseline when
the pre-arbiter RBT demand already exceeds the V3 command, and `rawLifted` keeps
`max(v3SmbBeforeU, …)` regardless. Measured inside the meal windows, `demand_before_u > v3_smb_before_u`
on **1 tick of each lunch** (+0.15 U and +0.28 U *pre-terminal*, less after the terminal chain).

> **`LIFT_WITHIN_ENVELOPE` is a restore mechanism for RBT's own demand, not a lift above the model.**
> No calibration of the intent threshold can make it carry a meal. The name is misleading.

The real limiter, measured per tick from `MEAL_PRIORITY_CHAIN` in the narrative:

```
2026-08-10 12:36  proposed=1.60 baseLimit=1.60 safety=1.36 throttle=1.09  effort×0.56 → 0.61
2026-08-09 14:41  proposed=1.60 baseLimit=1.60 safety=1.36 throttle=1.09  (no effort)  → 1.09
```

Identical chain, identical throttle. The only difference is `EFFORT_PROTECT_SMB`
(`DetermineBasalAIMI2.kt:13183`), applied last, after even the `hyperReleaseFloorU` restore.

| meal window | delivered | effort-multiplied ticks | factors | U removed by effort (reconstructed) |
|---|---|---|---|---|
| lunch 2026-08-09 | 14.09 U | **0** | — | 0.00 |
| lunch 2026-08-10 | 6.81 U | **14** | 0.45–0.56 | **7.01** |
| dinner 2026-08-10 | 9.17 U | 11 | 0.68–0.85 | 2.67 |

**6.81 + 7.01 = 13.82 U against 14.09 U.** The 14.06 → 6.53 collapse is the effort multiplier, to
within 2 %. **Part A-ter's attribution to the episode budget on `aggressiveRiseSmbFloorU` is not
supported** — that budget was never exported, its absence was inferred, and the effort factor explains
the whole gap. Removing the budget was still the right call for other reasons; the reasoning given for
it was wrong.

**Status of these numbers — read this before quoting them.** The **mechanism is measured**: the two
lunches ran the identical cap chain and `meal_certainty.effort_veto` is true on **0 / 19** ticks of the
2026-08-09 lunch and **14 / 16** of the 2026-08-10 lunch (independently confirmed from the export).
The **7.01 U is a reconstruction, not a measurement**: `EFFORT_PROTECT_SMB` had no before/after export,
so the factor was recovered by parsing `🏃effort×…` out of the narrative and the units were
back-computed as `amount / factor − amount`. It is also open-loop — per tick, no IOB feedback — and a
higher SMB would raise IOB and re-enter the chain on later ticks, so treat it as an upper bound. The
export added in AQ4-C is what settles it on the next package.

## AQ6. The effort floor — the numbers behind the chosen 0.75

The target — between 6.53 U and 14.06 U — is reachable only through the effort multiplier, gated on a
confirmed meal. Modelled per tick on the 2026-08-10 packages, flooring `effortFactor` **only when
`MealCertainty.level == HIGH`**:

| floor | lunch 2026-08-10 | dinner 2026-08-10 |
|---|---|---|
| none (as delivered) | 6.81 U | 9.17 U |
| 0.65 | 7.75 U | 9.17 U |
| **0.75 — chosen by the patient 2026-08-11** | **8.40 U** | 9.17 U |
| 0.85 | 9.06 U | 9.17 U |
| 1.00 (no effort cut on a HIGH meal) | 10.04 U | 9.17 U |

The dinner is untouched at every floor because it never reached `HIGH` — the selectivity is right on
its own: it relaxes at BG 216–269 on a confirmed hyper meal and not at BG 179. Same reconstruction
caveat as AQ5.

**Attribution risk, accepted by the patient.** This ships in the same install as
`OApsAIMIHighBGMaxSMB` 1.6 → 2.2. Two dose-moving changes at once is the error Part A-ter records as
having been made twice; the patient accepts it and will disentangle from the next package. The new
exports make that possible: `effort_smb_factor_requested` vs `_applied` isolates the floor's own
contribution independently of the ceiling change.

## AQ7. Still open

- **The upstream defect is untouched, and the floor is a mitigation, not the fix.**
  `EffortActivityBelief` scored `effort = 1.00` on steps15 = 156 (≈10 steps/min) and kept
  `effort_recent = 0.96` two hours after a walk to lunch at HR 74–84. Until that is calibrated, a
  confirmed meal is still fighting a false exercise belief in three places — the tree's intent gate,
  `MealCertainty.effortVeto`, and the terminal multiplier — and the 0.75 floor only softens the third.
  The fix belongs in `EffortActivityBelief`, which is the single activity path; **do not add a parallel
  one.**
- `LIFT_WITHIN_ENVELOPE` is now reachable but remains a restore mechanism. If a genuine lift above the
  MPC command is wanted, that is a different change and needs its own justification.

## AQ8. What the next day's data must answer

The build to install carries: the intent override, the channel fix, the barrier-bounded lift, the 0.75
effort floor, and `OApsAIMIHighBGMaxSMB` = 2.2. From the next package, with meals timestamped:

| question | field(s) | what would confirm it |
|---|---|---|
| Does the floor fire, and only on a certain meal? | `effort_smb_floored_by_meal`, `effort_smb_factor_requested` vs `_applied` | true only on ticks where `meal_certainty.level == HIGH`; `_applied` = max(`_requested`, 0.75) exactly |
| Is the 7.01 U reconstruction right? | `effort_smb_before_u` / `_after_u` summed over the meal window | replaces the narrative-parsed estimate with a measurement |
| Does the intent actually switch? | `physiological_tree.insulin_intent`, console `🌳 TREE_INTENT` | `NEED_MORE_INSULIN` on the HIGH ticks of a rise, `PROTECTIVE` retained on real activity |
| Is the SMB channel open on a meal now? | `recursive_belief.resolution.harmonia_smb.dominant_blocker` | `BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST` should fall sharply on SOFT meal ticks |
| Does a LIFT ever fire, and does it add anything? | `harmonia_smb_authority.mode`, `reason_codes` incl. `LIFT_BARRIER_BOUND_*` | if `LIFT_WITHIN_ENVELOPE` appears with `demand_after_u` ≤ `v3_smb_before_u` on every tick, AQ5's restore-mechanism finding is confirmed in production |
| Did the rise floor carry anything? | `rise_floor_spent_u`, `rise_floor_minutes_since_contribution` | settles A-ter's inference with a measurement at last |
| Attribution: floor vs the 2.2 ceiling | `physiological_patterns.max_smb_hb_u` alongside the effort fields | separates the ceiling's effect from the floor's |

**Do not change the Ra gates or `MPC_TAU_MIN` on this cycle** — three dose-moving changes would make
none of the above attributable.

---

# Part A-quinquies — the morning under-correction, traced (2026-08-13)

Written 2026-08-13 from package `1786605720362` (292 ticks, 24 h to 09:22) plus the three earlier
packages, 952 distinct ticks pooled. The patient reported that the evening before and that morning
"did not correct enough". **This part supersedes Part A-quater's picture of where the dose is lost,
and it corrects two attributions made during this session itself.** All changes below are
uncommitted. Suite: **1393 tests, 0 failures** (was 1345; this part adds 48).

## AQ5-1. The day

| window | BG | SMB | outcome |
|---|---|---|---|
| 12 Aug morning | 166 → **297** | 9.94 U / 22 ticks | plateau ~1.5 h |
| 12 Aug lunch | 126 → 206 | 6.13 U | handled |
| 12 Aug dinner | 117 → 247 → 164 | 10.30 U | handled |
| 13 Aug morning | 94 → **243** | **4.92 U / 15 ticks** | still 241 at 09:16, IOB 6.2 |

Day: 9.1 % below 70, 24.1 % above 180, min BG 45. The evening worked; the two mornings did not.

## AQ5-2. Root cause of the mornings: one mis-classification, five consequences

`PhysiologicalPhase.STRESS_CORTISOL` was applied to both breakfasts. `PHYSIO_CAP` (its
`smbFloorCapU = 0.75`) was the binding stage on **12 of 15** ticks on 13 Aug and **14 of 22** on
12 Aug.

The trigger is `isStressCortisol`: `heartRateBpm > restingHeartRateBpm + 12`. Measured RHR is **49**,
so the test is true above 61 bpm — **every waking heart rate** (observed 72–96). The natural
experiment is on 12 Aug: at 09:22/09:26 the watch reported no HR, the phase was `MEAL_UNDECLARED`
and the doses were 1.50 and 2.20 U; at 09:31 HR 94 arrived, the phase flipped, and the dose fell to
0.23 U for 70 minutes while BG went to 297. **The dose fell tenfold because a heart rate appeared.**

One classification bites in five places, all verified in code:

```
STRESS_CORTISOL
 ├─ smbFloorCapU 0.75                          (BehavioralRiskPolicy.forPhase)
 ├─ mpcInsulinCostMultiplier 2.5 / maxSmbFraction 0.65
 ├─ maxHtrTier EMERGING
 └─ toPatternId() → STRESS_CORTISOL_ACUTE
      → CausalStatePosterior.buildStressResistanceProb → stress branch 0.997
        → PhysiologicalTree.isConflicting (meal 1.00 vs stress ≥ meal − 0.04) → trunk MIXED
          → MealCertainty.treeStateOf(MIXED) = NONE → level never HIGH
             ├─ the A-quater intent override is unreachable
             └─ the A-quater 0.75 effort floor is unreachable
```

The evening proves the same code works when the root is right: meal 1.00 vs stress 0.483 → not
conflicting → `DIGESTION_ACTIVE` → HIGH → the floor fired on 4 ticks → 1.1–1.75 U per tick.

## AQ5-3. The cortisol discriminator, measured

14 distinct `STRESS_CORTISOL` episodes over 952 ticks, labelled from the BG trajectory (not from the
classifier's own opinion):

| group | n | excursion | peak Δ per 5 min | duration |
|---|---|---|---|---|
| genuine cortisol | 11 | 0–26 mg/dL | 1.5–**9.1** | 0–30 min |
| food | 3 | 112 / 140 | 12.9 / 20.1 | 65–70 min |

Threshold search, requiring the gate to fire at the *start* of the rise:

| candidate | food caught | cortisol false |
|---|---|---|
| Δ ≥ 9 | 2/3 | 1/11 |
| Δ ≥ 10, 11 or 12 | 2/3 | **0/11** |
| Δ ≥ 11 **or** (shortΔ ≥ 5 **and** dev ≥ 1.6 × highBand) | 2/3, both at their first rising tick | **0/11** |

`highBand = OApsAIMIHighBg 140 − target 90 = 50`, so the level clause is BG ≥ 170. The third "food"
episode is a single tick at BG 297 where the phase flickered mid-event; the main episode already
fired. Counterfactual across the cortisol episodes: **14.80 U → 27.22 U** (+12.42 U, 26 ticks),
open-loop upper bound.

**`isAcuteMealSurgeAtDawn` cannot be the escape** — its first clause `dev >= highBand * 0.45` is
BG ≥ 112, which fires on **11 of 11** genuine cortisol episodes. Making the existing dead escape
reachable would have deleted the cortisol path. This is why the escape is a new, tighter test.

## AQ5-4. The hysteresis hold, which would have made the escape worthless

`EndogenousPhaseHysteresis.stabilize` arms `HOLD_TICKS_DEFAULT = 4` on **every** `STRESS_CORTISOL`
tick, then re-applies the held phase — through `BehavioralRiskPolicy.forPhase(STRESS_CORTISOL, …)`
and its 0.75 cap — to the next four `MEAL_UNDECLARED` / `OFF` ticks. Because both breakfasts were
cortisol on the ticks *before* the steep rise, the escape would have been suppressed from 08:11 to
08:26 and first bitten at 08:31, BG 201. **A correct classifier fix would have delivered nothing for
the first 20 minutes of the meal.** Fixed by `Output.breaksEndogenousHold`, set when a steep rise
takes the tick out of the cortisol family; `stabilize` drops the hold on it. The hold still damps
ordinary flip-flop.

## AQ5-5. The effort multiplier — AQ5's reconstruction replaced by measurement

The A-quater exports settle AQ5's 7.01 U estimate. Over 24 h: **the effort multiplier removed
21.71 U**, against 42.26 U delivered. Reduced on 82 of 107 ticks, minimum 0.45. **61 of those 82 had
no live movement** (`effort=0.00`), median 12 steps per 15 min. The confirmed-meal floor fired on
**6 ticks of 107**, and `MealCertainty` reached HIGH on only 10 of 292 — the floor is not a
substitute for fixing the belief.

Two defects, both now fixed:

- **Re-arm.** `stepRate = max(rate5, rate15)` with `rate5 = stepsLast5m / 5`, so ~125 steps in any
  one 5-minute window re-stamps `lastEffortMs` and restarts a 120-minute protection at full
  strength. Observed on 12 Aug: the factor decayed 0.45 → 0.54 then jumped back to 0.45. **Proven,
  not inferred**: 12 ticks had `activity_state = ACTIVE` with `steps15 < 375`, which forces
  `steps5 ≥ 200`, and all 12 had a 15-minute rate of only 13.7–22.8 steps/min. Every provable burst
  in the corpus was uncorroborated at 15 minutes. Fix: onset stays fast (the 5-min window may open
  the current reduction) but only the **15-min** window may arm the cross-tick memory.
- **Decay.** Flat 120-minute linear horizon. Now scaled by how long the corroborated effort lasted,
  30 → 120 min; a ≥30-min session reproduces today's behaviour exactly.

Result: **21.71 U → 14.45 U, 7.26 U returned**, reduced ticks 82 → 42, **zero ticks reduced more
than today, and no tick with real movement loses protection**. Residual left on purpose: 2.97 U over
9 single-tick `ACTIVE` bursts, kept to preserve fast onset; `hrConf` (up to 0.50 of strength) is what
saturates them, and HR is measurably a poor discriminator here — the 25 bpm elevation threshold is
crossed at the *median* of the 1–10 steps/min band.

**Same defect family as the cortisol HR test: a threshold calibrated against a sleeping baseline.**

## AQ5-6. Why lunch and dinner start late — and two wrong answers on the way

The patient's second question. **Three attributions were made in sequence; the first two were wrong
and both were mine.** Recorded because the doc already shows this exact question being mis-answered
twice before.

**Wrong answer 1 — the BG-keyed ceiling.** Median `max_smb_high_bg_u` is 0.05 U up to BG 159, 0.22
at 160–179, 2.20 from BG 200, which looks like a purely reactive ceiling. It is not the cause: the
`MAXSMB_*` selection actually picks the 1.8 U preference at BG 109, and something overrides it
afterwards.

**Partly right — basal-first.** `basalFirstActive` sets `maxSMB = 0.0; maxSMBHB = 0.0` and is gated
on `bg < 110.0`, and every meal starts below 110, so the first tick of every meal has zero SMB
authority by construction. Measured per tick: lunch and breakfast were blocked by **Learner
Prudence**, dinner by **Fragile BG**. The existing exemption `isPersistentRise` has a *level* clause
(`bg > targetBg`), unreachable at BG 103 against a target of 110, and `isConfirmedHighRise` needs
BG > 150, structurally dead below 110. Fixed with an anticipated-rise exemption (rate + 30-min
projection, floor at BG 90 to exclude rescue rebounds). **But it is worth only ≈0.4 U across the
three meals** — not the gate.

**Wrong answer 2 — the tube veto.** `StraightLineTubeAdvisor` infeasible sets
`maxSMB = maxSMBHB = 0.05`. The correlation is real and strong: the 0.05 ceiling on 80 ticks,
`tubeCap=0,00` on 81, intersection 80 — 28 % of the day, including the first rising tick of lunch and
dinner. I called it the dominant gate. **It is not.** Measured:

- Feasibility is *exactly* `minPred < hypoFloor` — the candidate ladder ends at `s = 0.0`, where
  `minAfter == minPred`, so the tube runs **no projection of its own**. It restates an upstream
  forecast.
- Of 53 veto ticks, **38 are falling** (Δ5 ≤ −3) with 5–12 U IOB on descents that reached 41–76
  mg/dL within the hour, and 14 are flat with real IOB load. **52 of 53 vetoes are correct**; the one
  wrong veto is 12 Aug 13:16. Removing the veto would have made those descents worse.
- A graded cap on the infeasible branch is arithmetically impossible: headroom is negative by
  construction, `max(minPred − floor) = −3.0` mg/dL, so a headroom-graded cap yields **0.000 U**.
- The remaining 28 zero-cap ticks are *not* vetoes: 17 are a degenerate objective (`W_BG·bgErr²` and
  `W_EV·evErr²` do not depend on `s`, so with `hyperExcess = 0` the cost reduces to a leaky
  integrator decaying to `0.4 × lastScale` and then to zero), 5 are a quantisation dead band, 6 are
  unexplained by the export.

**And the earlier `minPred = 39` attribution is also only partly right**: `minPred == 39` on 53 of
292 ticks but co-occurs with the veto on only 37 of 81, and at meal onset the veto fired with
`min_pred_mgdl` of 64.3, **73.7** and **116.8** — the last two *above* the 70 floor. The tube ran on
an earlier snapshot than the one exported.

**The real gate is the prediction, and it is still open.** A forecast cannot coherently report a path
minimum of 73.7 while its own terminal is 376 and BG is rising 18 mg/dL per 5 min. Nothing was
changed in the tube; observability was added instead (`adjustments.tube_advisor` with
`deciding_stage`, `branch`, `min_pred_used_mgdl`, `snapshot_source_used`, `kappa_mgdl_per_u`,
`s_max_feasible`, `max_smb_baseline_u`), which settles the 6 unexplained ticks by measurement next
package. **No dosing path was touched by that change.**

## AQ5-7. What changed (all uncommitted)

| file | change |
|---|---|
| `physio/PhysiologicalPhaseClassifier.kt` | `isTooSteepForCortisolAlone`; applied at **both** cortisol sites and to the `morningChrono rebound` branch; `classify` wraps `classifyPhase` to stamp `breaksEndogenousHold` |
| `physio/EndogenousPhaseHysteresis.kt` | the hold releases on `breaksEndogenousHold` |
| `activity/EffortActivityBelief.kt` | memory arms only on 15-min corroboration; horizon scales with effort length; non-finite inputs fail open at 1.0 |
| `DetermineBasalAIMI2.kt` | `BasalFirstPolicyMath` extracted pure and testable, plus the anticipated-rise exemption; `adjustments.tube_advisor` export |
| `control/StraightLineTubeAdvisor.kt` | `Branch` enum and diagnostic fields on `Outcome`; `sMaxFeasible` computed **for export only**, not fed back into the ladder |

48 new tests, including the regression guards that matter: a genuine dawn cortisol ramp is still
`STRESS_CORTISOL`; a sustained walk still produces full protection; a falling or flat BG below 110
still gets basal-first with SMB off; every chosen tube scale still respects the hypo floor.

## AQ5-8. What the next package must answer

| question | field(s) | what would confirm it |
|---|---|---|
| Does the cortisol escape fire on breakfast only? | `physiological_phase.phase` / `reason` in the morning | `MEAL_UNDECLARED` on the steep ticks, `STRESS_CORTISOL` retained on modest dawn ramps |
| Is the hysteresis hold still releasing correctly? | phase on the 4 ticks after a cortisol tick | no cortisol policy on a steep rise; hold still visible on ordinary flip-flop |
| Did the effort fix return the units? | `effort_smb_factor_requested` vs `_applied`, `_before_u` / `_after_u` | ≈7 U less removed; no reduction on still ticks; full reduction during real movement |
| Does the basal-first exemption fire? | `[Basal-First: rise exemption]` marker | present on meal-onset ticks, absent on falling ticks |
| **Which snapshot froze the tube?** | `tube_advisor.min_pred_used_mgdl` vs `dose_terminal_snapshot.min_pred_mgdl`, `snapshot_source_used`, `branch` | settles the 6 unexplained ticks, incl. dinner 20:52 |
| Does `MealCertainty` reach HIGH on breakfast now? | `meal_certainty.level`, `physiological_tree.trunk.global_state` | `DIGESTION_ACTIVE` instead of `MIXED` once the stress branch is not inflated |

**Next ticket, ahead of any further tuning: the prediction that populates `minPredictedBg`.** It is
the gate on 28 % of ticks, the tube is a faithful messenger for 52 of its 53 vetoes, and shooting the
messenger buys ≈0.2 U while removing a working hypo defence.

**Attribution risk.** Four dose-moving changes ship together here (cortisol escape, hysteresis
release, effort calibration, basal-first exemption). That is the error this doc records twice. They
were taken together because they are one causal chain and three of them are individually inert — but
the exports above are what make them separable, and the effort fields separate cleanly from the
phase fields.

---

# Part A-sexies — the sensitivity during a rise, and where the meals are really lost (2026-08-14)

Written 2026-08-14 from package `1786722482068` (288 ticks, 24 h to 17:46) pooled with every earlier
package — **13 017 rows, 11 183 distinct ticks, 2 017 of them carrying the ISF chain**. All changes
uncommitted. Suite: **1 422 tests, 0 failures** (was 1393; this part adds 29).

The patient's question was: what is the right ISF during a rise, so an undeclared meal is corrected
before it becomes a hyper without the hypo that follows an over-correction. **The answer is that the
sensitivity is not what limits the meal, and saying otherwise would be the fourth mis-attribution in
this document.** It is what makes the tail wrong.

## AQ6-1. The sensitivity chain has a third policy term, and it is the dominant one

Part A-bis attributes the ISF collapse to `bgReduction = 1 - ((bg-110)/90) * 0.5`. That is only the
smaller half. `OpenAPSAIMIPlugin.dynamicDeltaCorrectionFactor` also held

```kotlin
if (combinedDelta > 10) {
    val expFactor = exp(-0.3 * (combinedDelta - 10))
    minOf(expFactor, bgReduction)          // ← the steeper the rise, the harder the crush
}
```

At a combined delta of +26 that term returns **0.0073**, i.e. 79 times below the linear term it
replaces. Confirmed two independent ways: the arithmetic, and the exported `isf_dynamic_factor`.

| date | BG | Δ5 | exported `isf_dynamic_factor` | commanded ISF | × static profile |
|---|---|---|---|---|---|
| 08-14 13:22 | 186.6 | +26.4 | 0.01 | **4.54** | **0.15** |
| 08-12 10:31 | 283.7 | +8.9 | 0.07 | 4.85 | **0.069** |
| 08-12 10:46 | 290.5 | +4.6 | **−0.00** | 4.85 | 0.069 |
| 08-12 10:56 | 297.2 | +4.0 | **−0.04** | 4.84 | 0.069 |
| 08-12 11:01 | 291.8 | **−1.6** | 1.20 | **57.77** | **1.93** |

Two things follow that were not known:

1. **The factor goes negative above BG 290**, on 16 of 1 389 rising ticks above BG 110, minimum
   −0.30. The only thing keeping a negative sensitivity off the pump is the absolute
   `coerceIn(5.0, 300.0)` at `OpenAPSAIMIPlugin.kt:863` — which **ADR 0008 records as never having
   bound on any observed tick**. It binds on 4.0 % of ticks, all at BG ≥ 175, median BG 229. Correct
   the ADR.
2. **The commanded value can go below that clamp**, because on the `profile.sens` path the physio
   factor is applied *after* it: 5.00 × 0.908 = 4.54 exactly. A bound before a multiplier again.

The one-tick move at 11:01 is the whole failure in one line: **4.84 → 57.77, ×11.9, because the sign
of delta flipped.** While rising, the loop believes 1 U moves BG by 4.8 mg/dL, so its prediction says
the 10 U on board will do almost nothing and it keeps going. The moment glucose turns it believes 1 U
moves 58 mg/dL, predicts catastrophe, and stops everything. The insulin is already in. BG went
297 → **45** in 110 minutes, every tick from 11:56 delivering 0.00 U.

## AQ6-2. What the sensitivity actually is, measured from outcomes

Estimated over **96 clean descents** (≥ 30 min, ≥ 25 mg/dL fall, COB 0, Ra < 0.30, ≥ 0.8 U absorbed)
as `−ΔBG / insulin absorbed`. EGP is ignored, so every figure is a **lower bound**.

| starting BG | n | median ISF estimate | relative |
|---|---|---|---|
| 70–140 | 27 | 24.3 mg/dL/U | 1.00 |
| 140–200 | 45 | 22.6 mg/dL/U | 0.93 |
| 200–400 | 24 | **18.7 mg/dL/U** | **0.77** |

Pooled median **22.3** against a static profile of 30, i.e. `R ≈ 0.74` — consistent with the 0.79–0.95
in ADR 0008 and showing that the exported `sensitivity_ratio_r = 0.5` is **saturated at its own bound
and wrong by about a third**. That is a separate open item.

**The measured BG dependence is ×1.30 across the whole range.** The chain applied up to ×14
(relative 0.069) and, counting the falling arm, up to ×137 between a fall and a steep rise.

### The rule, and the three regimes the brief names

`S = profile × R × k(BG)`, with `k(BG) = 1 − ((BG − 110)/90) × 0.15`, clamped `[0.75, 1.0]`, and
**no term depending on delta, on its sign, or on the steepness of the rise.** Daytime profile 30:

| regime | today | rule |
|---|---|---|
| BG 130 rising | median 18.9, span **×2.9** (17.3–50.8) | 21.4 |
| BG 220 rising | median 17.0, span **×3.6**, 4.5 on the steep ticks | 17.9 |
| BG 220 falling, IOB ≥ 6 | median 15.2, span **×8.4** (7.8–65.5) | **17.9 — the same number** |

The third regime is where the chain is most wrong and it is the one that produces the hypo: at the
same BG and the same IOB the loop believes 7.8 on some ticks and 65.5 on others, a ×8.4 swing driven
by the 30-minute ISF cache bucket, whose read is `valueAt(size−1)` — **the highest glucose of the
bucket**, so during a rise it locks onto the most crushed value of the window.

## AQ6-3. The load-bearing measurement: the demand is not sensitivity-limited

On 172 rising ticks at BG ≥ 140 with COB 0:

| commanded ISF | n | requirement it implies, `(BG−100)/ISF` | proposal | delivered |
|---|---|---|---|---|
| < 8 | 11 | **34.4 U** | 0.75 | 0.49 |
| 8–15 | 6 | 7.4 U | 1.60 | 1.04 |
| 15–25 | 97 | 4.3 U | 1.60 | 0.97 |
| ≥ 25 | 58 | 1.7 U | 0.75 | 0.48 |

**No monotone relation.** The proposal is quantised at 0.75 / 1.60 / 2.50 U — it is the ceiling
ladder, not a controller output. And `controlCoefficient` floors at `LEGACY_CONTROL_COEFFICIENT`,
which corresponds to ISF 45, so **every ISF below 45 produces the identical coefficient**: the
collapse from 30 to 4.5 changed the MPC's and the barrier's arithmetic by exactly nothing (floor
active on 82 % of ticks in production).

**So fixing the sensitivity adds no insulin to any meal.** It fixes the prediction, the tube advisor,
the hypo guard and the tail. That is worth doing, and it is not the meal fix.

## AQ6-4. Why `model_output_u` saturates at 1.85 U — a CPU guard truncating the domain

`MpcController.buildDoseCandidates` capped the candidate **count** at 400 while keeping the step, so
the loop stopped after 400 entries:

```kotlin
while (dose <= maxSafeDoseU + step * 0.5 && out.size < MAX_DOSE_CANDIDATES) { … dose += step }
```

At `FINE_SEARCH_STEP_U = 0.005` the largest dose the solver could score was `399 × 0.005 = 1.995 U`,
whatever `maxSafeDoseU` said. Then `smbU = bestDose − tbrUph/12` subtracts `profileBasal/4`:
**1.995 − 0.58/4 = 1.850**. Measured maximum over 3 741 ticks: **1.9085**, p90 1.85.

And the ceiling is inverted with respect to need: `isHyperPlateauQuiet` requires `|combinedDelta| <
1.2`, so a **quiet plateau** gets the coarse step and a 5.985 U domain while **every rising tick**
gets the fine step and 1.995 U.

The 2026-08-14 lunch, at the steepest five ticks:

```
13:16  BG 158  Δ+27.1   model 1.852   cap 1.75
13:22  BG 187  Δ+26.4   model 1.848   cap 2.12    ← 0.27 U of configured authority unreachable
13:26  BG 199  Δ+17.8   model 1.845   cap 2.50    ← 0.65 U unreachable
13:31  BG 198  Δ +7.1   model 1.845   cap 2.50
13:37  BG 198  Δ +2.9   model 1.845   cap 2.50
13:42+ BG 204 → 220     model 0.000   cap 2.50    ← the controller asks for nothing at all
```

This is why raising `OApsAIMIHighBGMaxSMB` from 1.6 to 2.2 delivered less than expected: below a
1.995 U domain the truncation is invisible, and it only starts removing authority once the ceiling is
raised past it. Corpus-wide the ceiling bound on **48 of 3 741 ticks (1.3 %)**, 47 on a rise, with at
most **4.11 U** unreachable in total — but 15 of those 48 are 08-12 and 5 are 08-14, i.e. the effect
is concentrated after the ceiling raise and grows from here.

**From 13:42 the model output is 0.000 on every remaining tick of the rise** while BG climbs 204 → 220
with 8–13 U on board. The 0.86 / 1.28 / 1.28 U delivered came entirely from floors. For the second
half of that meal the controller is absent and the dose is a policy ladder.

## AQ6-5. Where the meal units are actually lost

Per-stage sums over the same 172 rising ticks at BG ≥ 140, COB 0 (the floors add between stages, so
this is not a closed budget — each figure is the sum of that stage's own reductions):

| stage | units removed | share of the 234.98 U proposal |
|---|---|---|
| **PKPD guard** (`applySafetyPrecautions`) | **69.07 U** | 29 % |
| **throttle** (`SmbTbrThrottleLogic`) | **32.40 U** | 14 % |
| effort multiplier | 23.27 U | 10 % |
| delivered | 147.96 U | |

Two calibration defects visible in the throttle alone, both worth the next ticket:

- `bgRising = this.bg > this.targetBg` — that is "above target", not rising. On a hyper rise with
  unconfirmed insulin onset the first rule fires and takes **40 %**, and the `> target + 60` rule that
  would have allowed 0.9 is never reached.
- `applySafetyPrecautions` clamps to **`maxSMB`, not `maxSMBHB`** — the low-BG ceiling, at BG 250 on a
  rise.

**This is the meal fix, and it is not touched by anything in this part.**

## AQ6-6. The anticipation gate, traced to one constant

`MealCertaintyBuilder.EFFORT_VETO_OVERRIDE_MIN_BG_MGDL = 200.0`. While the effort veto holds, HIGH
requires BG ≥ 200 and the MED arm is closed by the same `effortBlocksMeal`, so the level falls to LOW.
LOW then closes four doors at once, all from one signal: `supportsMealOverProtective` false → the tree
intent stays `PROTECTIVE` (post-activity gate, `effort_recent` alone) → `HarmoniaSmbArbiter` takes the
ACCEPT/REDUCE early return → `mealCertaintySupports` false kills `liftEligible` → the 0.75 effort
floor is unreachable.

Measured over **20 rise episodes** with ≥ 35 mg/dL excursion:

| signal | median latency from onset | median BG when it arrives | never fired |
|---|---|---|---|
| MealCertainty MED+ | 0 min | — | 4 / 20 |
| MealCertainty HIGH | 10 min | 144 | 8 / 20 |
| intent `MEAL_SUPPORT`+ | 5 min | — | 6 / 20 |
| intent `NEED_MORE_INSULIN` | 15 min | **172** | 8 / 20 |
| the 0.75 effort floor | 15 min | **206** (204–212) | **17 / 20** |

On the 08-14 lunch: LOW through BG 128 → 199 rising +27, effort multiplier ×0.45, HIGH first at
BG 204 — **30 minutes and 76 mg/dL late.**

## AQ6-7. What changed (all uncommitted)

| file | change |
|---|---|
| `autodrive/controller/MpcController.kt` | `buildDoseCandidates` coarsens the **step** instead of truncating the **domain**: `step = max(requested, maxSafeDoseU / (MAX_DOSE_CANDIDATES − 1))`. Candidate cap unchanged. Resolution at a 2.5 U domain becomes 0.0063 U, still finer than the pump's granularity. |
| `ISF/DynamicSensitivityPolicy.kt` (new) | the situational factor, pure and testable. The rise-rate exponential is **removed**; the BG coefficient is 0.5 → **0.15** with a floor at **0.75**, from the 96 descents. The falling arm is untouched on purpose. Adds `floorAgainstProfile`. |
| `OpenAPSAIMIPlugin.kt` | delegates to it; applies the profile-relative **lower** bound on both exits, *after* the physio factor, so it cannot sit before a multiplier that undoes it. |
| `patient/MealCertainty.kt` | new anticipated-rise override of a **stale** effort veto: `Δ5 ≥ 10` **and** `shortAvg ≥ 5` **and** `!effortLive`, everything in `digestionRiseCore` still required. New `Input.shortAvgDeltaMgdl5m` (default 0.0) and `Input.effortLive` (default **true**), so an unwired caller keeps the old behaviour. |
| `DetermineBasalAIMI2.kt` | wires both, plus `effortIsLiveMovement()` — `ACTIVE` only, fails safe to live. |

29 new tests. `MpcControllerCandidateGridTest` previously asserted only that the grid was **capped**,
which is exactly why the truncation survived; it now asserts the last candidate reaches
`maxSafeDoseU` and that no candidate exceeds it.

## AQ6-8. Quantified before proposing, as required

**The sensitivity change is dose-neutral through the MPC and the barrier, provably.**
`controlCoefficient` floors at ISF 45. The highest static profile ISF in the corpus is 70, so the new
lower bound tops out at 35 — still below 45, so the coefficient is unchanged on every tick. What moves
is `profile.sens`, read by 20 prediction consumers, in the **protective** direction: a higher
sensitivity makes every prediction attribute more effect to the insulin already on board.

| change | ticks affected | measured effect |
|---|---|---|
| exponential term removed | 126 of 1 389 rising ticks above BG 110 had a factor below 0.10 (9.1 %), **16 negative** | new factor: min 0.750, median 0.930 (was median 0.684, min −0.304); ratio median ×1.32 |
| profile lower bound | **95 of 2 017 (4.7 %)** below 0.5 × profile | relative ISF median 0.443 → 0.500, minimum 0.069 → 0.500 |
| MPC grid | 48 of 3 741 (1.3 %) at the ceiling, 16 with a cap above 1.995 | ≤ **4.11 U** unreachable corpus-wide, median 0.205 U/tick; ~2 U on the 08-14 lunch alone |
| anticipated-rise override | **7 ticks** newly reach HIGH; 43 qualifying ticks stay blocked by live movement | effort floor returns **0.49 U** (open loop). 3 of the 7 are the onsets of the 08-12 dinner and 08-14 lunch |

**The anticipated-rise override is nearly inert, and that is the honest result.** The `Δ5 ≥ 10` gate is
the binding term and it is set where it is because genuine `STRESS_CORTISOL` ramps peak at 9.1 mg/dL
per 5 min over 952 ticks — lowering it starts deleting a protective classification that works. So the
override fires only on the sharpest meal onsets. It is worth having, since those are the meals that
reach 220+, and it opens the intent and the arbiter arm 25 minutes earlier, not only the effort floor.
It is **not** the meal fix and must not be presented as one.

### Cost on the low-glucose ticks, as required

The corpus holds **408 ticks below BG 75, minimum 40**, including the day with 45 / 57 / 63.

- **Delivered on all 408: 0.000 U, TBR 0.00.** Every protective path is already fully engaged there;
  none of these changes can act on a tick that is already at zero.
- `aboveMealBand` requires BG > target + 30, so **0 of the 408** satisfy the anticipated-rise
  override's precondition. It is structurally unreachable on them, and there is a test asserting it at
  BG 45, 57, 63, 95 and 125.
- The MPC grid change bound on **0 of the 738 ticks below BG 100**; it can only matter where the
  optimum sits at the top of the domain.
- The profile bound only raises the sensitivity, which only tightens the barrier and only makes the
  predicted effect of existing IOB larger. The **upper** half of ADR 0008's `[0.5, 2.0]` bound is
  deliberately **not** applied: 42 ticks (2.1 %) exceed 2.0 × profile and they cluster on descents —
  the 08-12 hypoglycaemia ran at 2.17 × profile from BG 66 down to 45. Capping that would permit more
  insulin during a fall, which needs its own measurement.

## AQ6-9. Corrections to earlier parts

1. **ADR 0008: "clamp [5, 300] absolute; has never bound on any observed tick" is false.** It binds on
   4.0 % of ticks (68 of 1 695), all at BG ≥ 175, median BG 229, and it is the only thing preventing a
   negative commanded sensitivity. The ADR's chain map also omits the `combinedDelta > 10` exponential
   arm entirely, which is the dominant term on exactly the ticks the ADR is about.
2. **Part A-bis attributes the ISF collapse to `bgReduction` alone.** On the steep ticks the
   exponential arm dominates it by up to ×79. The attribution is incomplete, not wrong.
3. **`command_isf_mgdl` is the cached value, not the one the dose uses.** `profile.sens` comes from the
   30-minute bucket cache; the dosing path uses `min(pkpdRuntime.fusedIsf, profile.variable_sens)`,
   which is fresh. `variable_sens` is **not exported**, so every ISF figure in this document — mine
   included — describes what the *predictions* saw. Exporting it is the next instrument owed.
4. **A5's second calibration question is answered.** `cbf_permitted_unfloored_u / cbf_permitted_u` is
   **exactly 1.000 on all 211 ticks where the barrier permits anything** (median, p90 and max all
   1.000), with the coefficient floor active on 82 %. The feared ±33 % does not appear, because
   `cbf_permitted_u` saturates at 1.995 U and is 0.00 on 50 % of ticks. Removing the floor changes the
   permitted dose by nothing measurable on this deployment.

## AQ6-10. What the next package must answer

| question | field(s) | what would confirm it |
|---|---|---|
| Does the commanded sensitivity stop collapsing? | `isf_dynamic_factor`, `command_isf_mgdl` vs `profile_isf_static_mgdl` | factor never below 0.75, never negative; relative ISF never below 0.50 |
| Does the ×11.9 whipsaw go? | `command_isf_mgdl` on consecutive ticks across a peak | no tick-to-tick jump above ×2 at a turning point |
| Does the MPC reach its own ceiling? | `smb_binding_trace.model_output_u` vs `max_smb_high_bg_u` | `model_output_u` above 1.86 U on at least one rising tick with a cap above 2.0 |
| Does the override ever fire? | `meal_certainty.reasons` contains `level_high_digestion_anticipated_rise` | present at meal onset, absent during a real walk |
| Is the throttle the limiter it appears to be? | `throttle_before_u` / `throttle_after_u` on rising ticks | confirms the 32.40 U and settles the `bgRising` mis-naming |

**Attribution risk, stated plainly.** Three dose-touching changes are in this part. The sensitivity
change is provably dose-neutral through the MPC and the barrier, so it can ship with the grid change;
the anticipated-rise override moves the dose on the same meal ticks the grid change does, and the two
are **not** separable from the export as it stands. If they must be separated, ship the grid change
first — it is the one with a measured 2 U on a single meal.

---

# Part B — prompt for the next session

Paste everything below.

---

## Role and standing rules

You are acting as **senior software architect and senior Kotlin engineer** on OpenApsAIMI, a fork of
AndroidAPS. This is a full closed loop that reaches a real insulin pump. A defect causes
hypoglycaemia. Global coherence of the decision architecture matters more than any local fix.

Read `docs/AIMI_NEXT_SESSION.md` Part A first — it is the evidence base, with measured numbers. Then
`docs/adr/` (especially `0008-isf-decision-architecture.md`), `docs/AIMI_ARCHITECTURE_MAP.md`,
`docs/AIMI_ROADMAP.md`.

**Non-negotiable:**

- `CLAUDE.md` applies in full. Repeat its bash rules verbatim in **every** agent prompt you write:
  never `cd && cmd` or `cd; cmd`; never start a command with `awk`, `cut`, `tr`, `sort`, `uniq`,
  `diff`, `which`, `chmod`, `tar`, `pip`, `npm`, `yarn`; use `git -C <abs path>`; allowed starts
  include `git`, `grep`, `find`, `sed`, `head`, `tail`, `cat`, `ls`, `wc`, `python3`, `echo`,
  `./gradlew`.
- **Do not commit or push** until explicitly asked.
- **Do not change code without confirmation**, except where the user says "do it" / "vas y".
- Gradle: `--no-daemon`, redirect to a log (never pipe — a pipe hides the real exit code), then grep
  for `^e: ` and `BUILD FAILED` / `BUILD SUCCESSFUL`.
- The support packages and `autodrive_dataset.csv` contain **personal health data**. Read them where
  they are. Never copy them into the repo, never paste their contents into a commit, an issue or any
  shared document. Aggregate statistics are fine.
- **Measure, do not reason from medians.** Every quantitative claim in Part A was computed from the
  corpus. Hold yourself to the same standard, and say plainly when you have not measured something.
- Do not take an agent's report at face value. Verify its decisive claims against the code yourself
  before acting on them or relaying them.

## Objective

Updated 2026-08-10 after the production day. The session is done when **all** of these hold:

1. The 2026-08-09/10 work is **confirmed from a clean production day** — one where the app is not
   restarted mid-meal. Specifically: the `ESCAPE_RISE` guard leaves `FULL` at the budget, the rise
   floor serves one prebolus per rise, `smb_seal_refused_count` is observable, and the pattern
   catalogue reports `smb_cap_fraction` against `max_smb_hb_u`.
2. The two calibration questions of A5 are **answered with numbers** — they still are not, because the
   shadow reached 7 ticks out of 93 on 2026-08-09 (see A-bis).
3. The barrier's insulin model is decided. Until `MPC_TAU_MIN` is right, the barrier cannot be made
   binding and the floor cannot be clamped to it — see A-bis correction 5.
4. The full module suite passes: `./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon`.
5. `docs/adr/0008-isf-decision-architecture.md` reflects the code — its chain map still stops before
   the sensitivity floor, and the `safetySi` anchor is a construct step 3 removes.

## Agent split

Give each agent the bash rules, the data-privacy rule, and a pointer to Part A **and A-bis**. **Order: Agent 0 first** — it is
listed below Agent 2 for historical reasons but it outranks them, because until the meal intent can
switch, every other bound on the SMB floor starves meals. Then Agent 1 on the next support package,
then 2, 3, 4 in parallel.

### Agent 1 — `data-validation` (blocking)

Analyse the next support package, from a day with **no mid-meal app restart**, with meals timestamped.

- Confirm the five items of objective 1 above, each as confirmed / refuted / not observable, with the
  field and the count.
- Answer the two calibration questions. For the Ra shadow: distribution inside meal windows versus
  outside, and the crossing rate at 0.6 / 0.7 / 0.8 in each. The question is whether the aligned term
  separates a meal from the mere presence of IOB — on 2026-08-09 it reported 0.60–0.79 on flat glucose
  with 5.5 U on board and a real Ra of 0.00, which is the failure mode, on 7 ticks only.
- For the barrier: `cbf_permitted_unfloored_u - cbf_permitted_u` as a ratio, with
  `cbf_profile_isf_mgdl` alongside.

### Agent 2 — `barrier-calibration` (the one that unblocks the rest)

The barrier is the natural bound on the rise floor and cannot be used as one today: its `lfh` was
wrong by ~12 mg/dL/min during the 2026-08-09 meal, it says zero on ordinary rises (BG 130 → 151 with
4–6 U on board), and clamping to it would remove 38 % of a day's SMB.

- Establish what `MPC_TAU_MIN` should be, from data rather than from the 150–200 min literature range.
  `InsulinActionModel` states the calibration as a time constant precisely so this is one number.
- Quantify what each candidate does to the barrier's binding rate and to the delivered dose, on the
  corpus, before proposing a change. Note that τ = 75 reproduces the pre-unification behaviour exactly
  at ISF 45 and that the `LEGACY_CONTROL_COEFFICIENT` floor currently guarantees the refactor can only
  tighten.
- Only once that number is settled: propose clamping the rise floor to the barrier's verdict, with the
  measured cost.

### Agent 0 — `meal-intent` (runs before everything else; see Part A-ter)

`harmonia_smb_authority.insulin_intent` is `PROTECTIVE` on every tick of an undeclared meal, including
at BG 269 with Ra 5.33. That branch of `HarmoniaSmbArbiter.decide` can only ACCEPT or REDUCE, so
`LIFT_WITHIN_ENVELOPE` — the governed equivalent of the crude SMB floor — is structurally unreachable.

- Trace `insulin_intent` back to its producer. Establish whether `PROTECTIVE` is a deliberate
  classification for high IOB, a threshold miscalibration, or a path that simply never selects the
  meal intent in this state.
- State what would have to change for BG 237 with Ra 4.59 and no COB to produce `MEAL_SUPPORT`.
- Implement the smallest change that makes the LIFT reachable on a confirmed meal, bounded by the
  catalogue cap and the barrier. Then quantify, on both corpora, what it would have delivered: the
  target is **between** 6.53 U and 14.06 U, not at either end.
- Export `riseFloorSpentU` and the time since the last floor contribution. Its absence is why the
  2026-08-10 diagnosis rests on inference rather than measurement.

### Agent 3 — `harmonia-authority`

Harmonia computes on 79 % of ticks and reaches the pump on **9 of 292 (3 %)**.

- `HARMONIA_SMB_NO_RBT_AUTHORITY` on 64 % of ticks, and `requested_authority` is `NONE` on 85 % with
  reason `NO_RELEASE`. The gate is **not** truncating — `requested == max_allowed == effective`. RBT
  simply never asks. Find out why `NO_RELEASE` dominates, and whether that is intended.
- Harmonia's **basal** channel is blocked by **SMB-channel** conditions: `smb_zeroed_by_safety` 39 %,
  `smb_already_requested` 19 %, `smb_authority_active` 1 % — 60 % of its blocks. Reducing a basal rate
  and refusing a bolus are different decisions. Confirm and propose.
- `eligible` is true on 166 ticks and only 9 reach the pump. Trace the other 157.

### Agent 4 — `hygiene-and-observability`

Unchanged from the original list: dead constructor dependencies, workers swallowing failures,
`AutodriveAuditor`'s `isfRatio` unit mismatch (re-check the arithmetic — the sensitivity floor is
gone), per-learner liveness in the export, and the three dormant ML components.

## Loop protocol

Run in loop mode. Each iteration:

1. Pick the highest-priority open item. P0 before P1; anything blocking Agent 1's verification first.
2. Do the work. Compile and run the module suite before reporting anything as done.
3. Report: what changed, what was measured, what is still open. Distinguish "tests pass" from
   "confirmed in production" — never merge the two.
4. Update the task list in this file's Part B, so the next iteration starts from truth.

**Ask the user when, and only when:** two readings of a requirement lead to materially different
work; a change would move the dose and no measurement can settle it; or a measurement contradicts
Part A. Do not ask for permission to continue, and do not ask questions you can answer by reading the
code or the corpus.

**Stop when** the five objective conditions hold, and say so plainly with the evidence for each. If
one cannot be met, finish everything else in full and state exactly what is left and why — do not
scale the objective down silently.

## Known-flaky

`aimiNeuralNetworkTest > test training reduces loss()` fails intermittently in the full suite and
passes 3/3 in isolation. Unrelated to this work. Do not chase it; do not let it mask a real failure
either — always list the failing test names, never just the count.

---

# Part C — reusable prompt (2026-08-14, supersedes Part B)

Part B was written on 2026-08-10 and its objective list is out of date: its Agent 0 question is
answered (§AQ1) and its "uncommitted" premise is false. Paste the block below instead. It is
self-sufficient — a session that has read nothing else can start from it.

````text
You are acting as a senior software architect and senior Kotlin engineer on OpenApsAIMI, a fork of
AndroidAPS. This is a full closed loop that reaches a real insulin pump. A defect causes
hypoglycaemia. Global coherence of the decision architecture matters more than any local fix.

Repo: /Users/mtr/StudioProjects/OpenApsAIMI, branch dev_OAPSAIMI.

READ FIRST
- docs/AIMI_NEXT_SESSION.md, the "Current state" section at the top. It is the summary of truth and
  it names the one open blocking question. Then read the part it points at.
- The document grows by accretion: Part A, A-bis, A-ter, A-quater, A-quinquies, each correcting the
  one before. That convention is deliberate and you must preserve it. Never rewrite or delete a
  historical part. When you find a part wrong, add a dated correction and update "Current state" to
  say which part won and why.
- Then docs/adr/0008-isf-decision-architecture.md, docs/AIMI_ARCHITECTURE_MAP.md,
  docs/AIMI_ROADMAP.md.

BASH RULES — verbatim from CLAUDE.md, repeat them in every agent prompt you write
- NEVER use `cd && command` or `cd; command` in Bash calls — triggers security approval prompts
  on Windows. Use absolute paths or `git -C` instead:
    - OK:  `git -C E:/GitHub/AndroidAPS diff HEAD -- path/to/file`
    - OK:  `git diff HEAD -- path/to/file` (CWD is already project root)
    - BAD: `cd E:/GitHub/AndroidAPS && git diff HEAD`
    - BAD: `cd /path; git status`
- NEVER start a command with these — they are NOT in the allowlist and WILL trigger confirmation:
    - BAD: `awk`, `cut`, `tr` -> use `sed` or the Grep/Read tools instead
    - BAD: `sort`, `uniq` -> wrap in `powershell.exe -Command "..."` or use tools
    - BAD: `diff` (standalone) -> use `git diff` which IS allowed
    - BAD: `which` -> use `where` instead (Windows equivalent, is allowed)
    - BAD: `chmod`, `chown` -> not needed on Windows
    - BAD: `tar`, `gzip` -> use `unzip` (allowed) or `powershell.exe -Command "..."`
    - BAD: `pip`, `npm`, `yarn` -> use `python -m pip`, `node ...`, or `powershell.exe`
    - BAD: `gradlew.bat` without `./` prefix -> always use `./gradlew.bat`
    - BAD: Starting a command with a file path (e.g., `E:/Github/.../gradlew.bat build`) -> use
      `powershell.exe -Command "..."` wrapper instead
    - BAD: Compound commands with `&&`, `||`, or `;` as the top-level operator between separate
      commands -> each command must start with an allowed prefix
- Safe patterns that ARE allowed: `git`, `gh`, `./gradlew.bat`, `powershell.exe`, `powershell`,
  `cmd`, `adb`, `curl`, `python`, `java`, `node`, `wsl`, `where`, `grep`, `find`, `echo`, `head`,
  `tail`, `sed`, `rm`, `del`, `ls`, `wc`, `tee`, `xargs`, `cat`, `mkdir`, `cp`, `mv`, `touch`,
  `unzip`, `jar`, `export`
- When spawning agents that use Bash, ALWAYS include this rule in the agent prompt.
- This machine is macOS: the repo is at /Users/mtr/StudioProjects/OpenApsAIMI, the wrapper is
  `./gradlew` (not `./gradlew.bat`), and `python3` is available. Every rule above still applies.

DATA PRIVACY — not negotiable
The support packages /Users/mtr/Downloads/AIMI_Support_Package_*/ and
/Users/mtr/Downloads/AIMI files/autodrive_dataset.csv are real patient health data. Read them where
they are. Never copy them into the repo. Never paste raw rows, names, emails, device ids or any other
identifier into a commit, an issue, a pull request, a document or a chat reply. Aggregate statistics
and single de-identified numbers are fine. Written artifacts must contain no identifiers.

STANDING RULES
- Do not commit or push until explicitly asked. Editing files is fine; committing is not.
- Do not change code without confirmation, except where the user says "do it" / "vas y" / "fix it".
- Gradle: `./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon > build.log 2>&1`, then grep the
  log for `^e: ` and `BUILD FAILED` / `BUILD SUCCESSFUL`. Never pipe to `tail` for pass/fail — a pipe
  reports the pipe's exit code, so a failing suite looks green.
- Measure, do not reason from medians. Every quantitative claim in this document was computed from a
  corpus. Say plainly when you have not measured something.
- Do not take a subagent's report at face value. Verify its decisive claims against the code before
  acting on them or relaying them.
- Distinguish "tests pass" from "confirmed in production". Never merge the two.

THE THREE FAILURE MODES THIS PROJECT KEEPS REPEATING
Each has already caused a wrong change. Check yourself against all three before proposing anything.

1. A constraint stated in documentation and absent from the code. Trust the code, not the prose.
   Recorded instances: the SMB floor's KDoc said it was "always re-bounded by V3 safety" while it was
   max()-ed after the barrier and beat it on 25 ticks, +12.87 U; the AI auditor's own prompt said
   "CONFIRM or SOFTEN only — never invent a lift" while the code wrote finalResult.units
   unconditionally; a test class doc said the barrier "rarely binds" while it bound on 41.2 % of
   production ticks. When you read a claim in a comment, grep for the code that enforces it.

2. A mechanism shipped without the instrument that would show whether it works. If you cannot
   measure it after shipping, you have not shipped it. Recorded instances: the rise-floor episode
   budget's state (riseFloorSpentU, time since last contribution) was never exported, so its blame
   for the 2026-08-10 undershoot was inferred — and wrong, the effort multiplier explained the whole
   gap; the estimator diagnostic counters were written after the JSONL was serialised, so they
   reached 7 ticks out of 93 and dated the build boundary wrongly. Add the export in the same change
   as the mechanism, at markEstimatorDiagnosticsForExport, which every export path goes through.

3. A change designed from a single episode and verified at a single operating point. Recorded
   instances: the episode budget was calibrated on the one day the floor over-delivered 14 U and then
   starved every following meal (BG 268.7 on 6.53 U); the pattern-catalogue fraction conversion was
   verified against maxSMBHB = 1.6 as if that ceiling were constant — it ramps within the tick, so
   the cap came out up to 45 % tighter exactly at meal onset. Before shipping, replay across every
   meal in the corpus and at both ends of any value that moves during a tick.

TWO HARD RULES ON DOSING CHANGES
- Quantify every dosing change on the corpora before you propose it, not after. State what it would
  have delivered on each meal window of every available support package, and give the number as an
  interval when it is open-loop (no IOB feedback) rather than as a point value.
- Never relax a protective path — the tube veto, the hypo gate, the control barrier, basal-first, the
  effort reduction — without stating in the same message what your change would have done on the
  ticks that went to BG 45, 57 and 63. The corpus records min BG 45 on 13 Aug with 9.1 % of that day
  below 70, and the tube's vetoed descents reached 41–76 mg/dL with 5–12 U on board. If you cannot
  produce that number, say so and do not ship the change.

OBJECTIVE
P0, and it blocks the rest: establish what computes `minPredictedBg`, and why it can report a path
minimum of 73.7 mg/dL while its own terminal is 376 and BG is rising 18 mg/dL per 5 min. It gates
28 % of ticks through StraightLineTubeAdvisor's 0.05 U ceiling. Do not "fix" the tube: it is a
faithful messenger, right on 52 of its 53 vetoes, and a headroom-graded cap on its infeasible branch
is arithmetically 0.000 U. Use the adjustments.tube_advisor export (deciding_stage, branch,
min_pred_used_mgdl, snapshot_source_used, kappa_mgdl_per_u, s_max_feasible) to find which snapshot
the tube ran on, then fix the forecast, not its consumer.

Then, in order:
1. Confirm the 2026-08-13 build from the next clean package, using the table in §AQ5-8: does the
   cortisol escape fire on breakfast only, does the hysteresis hold still release, did the effort
   calibration return ~7 U, does the basal-first exemption fire, does MealCertainty reach HIGH on
   breakfast. Report each as confirmed / refuted / not observable, with the field and the count.
2. Decide MPC_TAU_MIN from data, not from the 150–200 min literature range. Quantify what each
   candidate does to the barrier's binding rate and to the delivered dose on the corpus. Only then
   propose bounding the rise floor by the barrier, with the measured cost. Note that tau = 75
   reproduces the pre-unification behaviour exactly at ISF 45 and that LEGACY_CONTROL_COEFFICIENT
   guarantees the refactor can only tighten.
3. The Ra gates. Iso-rate mapping already failed informatively: 0.6 / 0.7 / 0.8 select 18.4 % /
   16.7 % / 16.2 % of ticks, so they are one gate written three times. Decide what each is meant to
   discriminate before placing any threshold.
4. Fix EffortActivityBelief's remaining upstream defect. It is the single activity path — never add a
   parallel one.
5. Make docs/adr/0008-isf-decision-architecture.md match the code; its chain map still stops before
   the sensitivity floor.
6. Full module suite green: ./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon

Never ship more than one dose-moving change per install unless the exports separate them, and say
which export does the separating. Shipping several at once is the error this document records three
times.

AGENT SPLIT
Give every agent the bash rules verbatim, the data-privacy rule, the three failure modes, and a
pointer to "Current state" plus the one part that matters to it.
- prediction-provenance (P0, run first, blocking): the minPredictedBg objective above.
- data-validation: the newest support package, meals timestamped, against the §AQ5-8 table.
- barrier-calibration: MPC_TAU_MIN, then the rise-floor bound.
- harmonia-authority: Harmonia computes on 79 % of ticks and reaches the pump on 3 %. eligible is
  true on 166 ticks and 9 reach the pump — trace the other 157. Check whether the A-quater channel
  fix moved dominant_blocker off BASAL_FIRST_OWNER_HARMONIA_PRODUCTION_BASAL_FIRST.
- hygiene-and-observability: dead constructor dependencies, workers swallowing failures,
  AutodriveAuditor's isfRatio unit mismatch, per-learner liveness, the three dormant ML components.
- CLOSED, do not reopen: meal-intent. Answered in §AQ1 — PhysiologicalTree.resolveInsulinIntent,
  activity gate 2, 150 of 150 ticks.
Before starting any of these, check whether another agent already landed the finding: on 2026-08-14
two were in flight, one on package 1786722482068 and one on ISF-during-rise plus giving the belief
tree and Harmonia real ownership of the dose.

LOOP PROTOCOL
Run in loop mode. Each iteration:
1. Pick the highest-priority open item. P0 first; anything blocking data-validation before the rest.
2. Do the work. Compile and run the module suite before reporting anything as done.
3. Report what changed, what was measured, and what is still open.
4. Update "Current state" at the top of docs/AIMI_NEXT_SESSION.md so the next iteration starts from
   truth, and append a new dated part rather than editing an old one.
Ask the user when, and only when: two readings of a requirement lead to materially different work; a
change would move the dose and no measurement can settle it; or a measurement contradicts the
document. Do not ask permission to continue, and do not ask what the code or the corpus can answer.

STOP WHEN
The P0 question is answered with numbers, items 1–6 above hold, and you have said so plainly with the
evidence for each. If one cannot be met, finish everything else in full and state exactly what is
left and why. Do not scale the objective down silently.

KNOWN-FLAKY
`aimiNeuralNetworkTest > test training reduces loss()` fails intermittently in the full suite and
passes 3/3 in isolation. Unrelated to this work. Do not chase it, and do not let it mask a real
failure either — always list the failing test names, never just the count.
````

---

# Part A-septies — the 39 floor tested against the plateau, and the instrument (2026-08-14, later session)

Written 2026-08-14 in a session that started from the hypothesis stated in Part A-sexies' closing
priority: that the ISF swing saturates the prediction, which trips the tube veto, which produces the
84-minute plateau — one defect seen in two places. **That chain was tested tick by tick on package
`1786722482068` (281 decision ticks, 250 with a tube outcome). Its mechanism holds. Its causality does
not.** Suite: **1 425 tests, 0 failures** (was 1 422; this part adds 3). All changes uncommitted, and
all of them export-only — no dosing path was touched.

## AQ7-1. What the hypothesis got right

| claim | verdict | measurement |
|---|---|---|
| `min_pred_used_mgdl = 39.0` is `NUMERIC_FLOOR_MGDL`, not a forecast | **confirmed** | per-step `coerceIn(39.0, 401.0)` at `AdvancedPredictionEngine.kt:186–189` and `:224–225`. The clip is **absorbing**: once a step lands on it the path stays there for the rest of the horizon |
| it is the deciding input on most vetoes | **confirmed** | `min_pred_used_mgdl == 39.0` on **53 of the 84** `VETO_HYPO_FLOOR` ticks (63 %); 62 of 250 tube ticks overall |
| the veto is exactly "the floor is already breached" | **confirmed** | the candidate ladder ends at `s = 0.0`, where `minAfter == minPred`, so `!anyFeasible` ⇔ `minPred < hypoFloor`. `StraightLineTubeAdvisor.kt:164–166`. Confirms §AQ5-6 |
| the saturation is driven by sensitivity | **confirmed, jointly with IOB** | `BG − IOB × ISF < 39` classifies `hit_numeric_floor` with **116 of 119** recall (3 misses), 45 false alarms of 162. The raw path min hit the floor on **119 of 281 ticks (42 %)** |
| the ISF whipsaws across the peak | **confirmed** | `command_isf_mgdl` 11.9 at 14:21 → 14.1 at 14:26 → **48.6 at 14:36**: ×4.1 in 15 minutes, and it then pins `min_pred` at 39 for two hours |

## AQ7-2. What it got wrong — the plateau is not the veto

Per tick over the 2026-08-14 lunch, joining `tube_advisor`, `smb_binding_trace` and `pkpd_soft_floor`:

```
time    BG    IOB   cmdISF  rawMin  model_u  maxSMBHB  binding_stage        tube_branch
13:42  204.0  7.95   19.3    78.9    0.000     2.50    SAFETY_PRECAUTIONS   GRADED
13:47  217.4  8.88   16.9   101.8    0.000     2.50    SAFETY_PRECAUTIONS   GRADED
13:52  220.4 10.19   11.6   133.3    0.000     2.50    SAFETY_PRECAUTIONS   GRADED
14:01  206.3 11.62   15.4    39.0    0.000     1.50    PKPD_THROTTLE        GRADED
14:12  218.3 11.79   16.9    76.8    0.000     2.50    SAFETY_PRECAUTIONS   GRADED
14:21  219.2 13.05   11.9    79.2    0.000     0.15    SAFETY_PRECAUTIONS   GRADED
14:26  211.7 13.04   14.1    39.0    0.000     0.05    —                    VETO_HYPO_FLOOR
```

**Through the whole plateau the tube was permissive.** `GRADED` on every tick, `max_smb_hb_u` at the
configured 2.50 on most of them. What was zero is **`model_output_u`**, on every tick from 13:42
onward, with the binding stage `SAFETY_PRECAUTIONS` or `PKPD_THROTTLE` — the 29 % and 14 % stages
already measured in §AQ6-5. The 0.13–1.28 U actually delivered came from floors.

**The veto window is 14:26 → 16:32, after the peak, on a falling BG**, and over it BG fell 211.7 →
113.4 and IOB 13.0 → 3.1 with no hypoglycaemia. **The day's minimum BG was 86; zero ticks below 70.**

So the two subjects are **not** one defect:

- the **plateau** is the controller producing no demand while it holds full authority — §AQ6-4's grid
  truncation and §AQ6-5's PKPD guard and throttle;
- the **39 floor** is a forecast that saturates and a consumer that cannot tell saturation from a
  forecast. It gates the *tail* and the *next* meal onset, not this plateau.

The 84 vetoes by trend and level, which is the cost side of any relaxation:

| trend | BG band | vetoes | of which `min_pred == 39` |
|---|---|---|---|
| falling (Δ5 ≤ −3) | 120–180 | 28 | 25 |
| flat | 120–180 | 22 | 10 |
| flat | < 120 | 15 | 7 |
| falling | < 120 | 7 | 4 |
| falling | ≥ 180 | 7 | 7 |
| rising (Δ5 ≥ +3) | < 120 | 5 | 0 |

**35 of 84 are on a hard fall**, where `AdvancedPredictionEngine`'s Guard B
(`delta ≤ ENDO_REVERSION_FALLING_HARD_DELTA_MGDL = −3.0`) suspends the EGP reversion **by design**, so
the insulin-only path is left free to crash to the floor. That is the gap already on file as
"floor-39 Guard B falling uncovered", now quantified: it is 42 % of the vetoes on this package.

## AQ7-3. The floor is not an ISF artefact, and cannot be fixed by fixing the ISF

At 14:26 — BG 211.7, IOB 13.04, COB 0 — the path saturates for **any** sensitivity above
`(211.7 − 39) / 13.04 = 13.2 mg/dL/U`. The measured physiological value from the 96 clean descents is
**22.3**, so a perfectly calibrated sensitivity saturates it too.

The reason is structural: **the insulin-only path has no appearance term.** It computes
`BG − Σ activity × ISF × 5` and, on an undeclared meal, `cobG = 0`, so nothing sits on the other side
of the subtraction. The path therefore predicts a crash whenever `IOB × ISF > BG − 39` — which is the
normal state of a meal being corrected. **Fixing the sensitivity chain will not move this**, and
proposing it as the fix would be the fourth mis-attribution in this document.

## AQ7-4. `variable_sens`, recovered from data that already existed

The instrument the brief asked for turned out to be **partly recoverable without a build**.
`tube_advisor.kappa_mgdl_per_u` is a deterministic function of the tube's sensitivity:

```
kappa = clamp(8 + 22 x (50 / isf), 8, 45) x clamp(6 / dia, 0.82, 1.18) x (1 + margin)
```

`dia` is 300–302 min on every tick of the package (parsed from the narrative), and `margin` is the
`key_aimi_tube_kappa_margin` preference, 0.1. So `isf` inverts. It is invertible on **194 of 281
ticks** — the rest are where the sensitivity is at or below ~29.7 mg/dL/U and the first factor is
pinned at its 45 clamp, which is exactly the band a rising meal sits in.

| comparison | min | median | max |
|---|---|---|---|
| recovered `variable_sens` / `command_isf_mgdl` | 0.703 | **1.067** | **2.652** |
| recovered `variable_sens` / narrative `ISF(fused)` | 0.909 | 1.266 | 1.962 |

They agree within 1.0 mg/dL/U on **1 tick of 194**.

> **`command_isf_mgdl` is not the sensitivity the dose used.** Every ISF figure in this document — in
> Part A-bis, in A-sexies, and in the priority note at the top — describes the *prediction* side. This
> confirms §AQ6-9 correction 3 with a number, and it means the ISF chain has never been measured where
> it is consumed.

Concretely, through the plateau the recovered `variable_sens` sat at **37.3** while `command_isf_mgdl`
read **11.9–17.0**. **The collapse to 4.5 never reached the tube.**

## AQ7-5. What shipped (export-only, no dosing path touched)

| file | change |
|---|---|
| `pkpd/AdvancedPredictionEngine.kt` | a diagnostic mirror of the IOB path with **no clip**, plus a count of the steps the floor absorbed and the sensitivity the engine integrated. Computed before the existing `coerceIn`, which is unchanged |
| `pkpd/AdvancedPredictionCurves.kt` | `insulinPathMinUnclippedMgdl`, `numericFloorClippedSteps`, `effectiveSensitivityUsedMgdlPerU` |
| `pkpd/PkpdSoftFloorPathMin.kt` | carries the three into `pkpd_soft_floor`, plus a derived `floor_saturation_mgdl` = published − unclipped |
| `control/StraightLineTubeAdvisor.kt` | `Outcome.isfUsedMgdlPerU` on all four return paths — kappa cannot stand in for it, since kappa saturates for every sensitivity ≤ ~29.7 |
| `DetermineBasalAIMI2.kt` | `baseline_state.variable_sens_mgdl`, written in `markEstimatorDiagnosticsForExport`; `tube_advisor.isf_used_mgdl_per_u` |

**Why the unclipped mirror is the load-bearing one.** A published minimum of 39 with an unclipped
value of −274 and 40 clipped steps is a saturated arithmetic result. The same 39 with one clipped step
and an unclipped 38 is a real forecast of hypoglycaemia. **These need opposite responses and are today
the same number.** Three new tests pin it: the 14:26 tick reproduced, a benign tick where the two
agree and the clip count is 0, and an assertion that no published point moved.

## AQ7-6. What the next package must answer

| question | field(s) | what would confirm it |
|---|---|---|
| How much of the 39 is saturation rather than forecast? | `pkpd_soft_floor.floor_saturation_mgdl`, `numeric_floor_clipped_steps` | on the veto ticks, a large negative unclipped value and a high clip count means the veto decided on nothing |
| Is `command_isf_mgdl` really the wrong number? | `variable_sens_mgdl` vs `command_isf_mgdl` | settles the ×2.65 by measurement instead of by inverting kappa, and covers the 87 ticks the inversion cannot reach |
| Which sensitivity did the tube believe on a rising meal? | `tube_advisor.isf_used_mgdl_per_u` | the band the kappa inversion is blind to |
| Did the grid fix reach the meal? | `smb_binding_trace.model_output_u` **above 1.9085 U** | the one signature the sensitivity policy and the override cannot produce |

**Open, and in this order.**

1. **The plateau, which is the patient's actual complaint.** `model_output_u` = 0.000 on every tick
   from 13:42 while the tube held full authority. That is the controller, and `SAFETY_PRECAUTIONS`
   (29 %) and `SmbTbrThrottleLogic` (14 %) are the named stages. §AQ6-5 also records two calibration
   defects in the throttle that are still untouched: `bgRising = bg > targetBg` is "above target", not
   rising, and `applySafetyPrecautions` clamps to `maxSMB` rather than `maxSMBHB`.
2. **The 39 floor**, once the new export says how much of it is saturation. The likely shape is an
   appearance term on the insulin-only path, or a consumer that treats a saturated minimum as
   *unknown* rather than as *39* — **not** a relaxation of the veto, which is right whenever the
   unclipped value is genuinely near the floor.
3. **The ISF chain**, last. It fixes the tail and the whipsaw, and §AQ6-3's measurement stands: it
   adds no insulin to any meal.

**Nothing here justifies relaxing a protective path.** On this package the day's minimum was 86 mg/dL
with no tick below 70, so it contains no evidence either way about the descents that reached 41–76 on
the earlier packages. The "52 of 53 vetoes correct" figure is from the 2026-08-13 package and does not
describe this one.

## AQ7-7. The plateau, traced to its actual gate — and §AQ6-4 corrected

Continued the same day, on the priority set out in AQ7-2. **The plateau is the control barrier, and
`model_output_u` was never able to say so.** Suite after this section: **1 428 tests, 0 failures**.

### The export conflates two opposite failures

`smb_binding_trace.model_output_u` is written at `DetermineBasalAIMI2.kt:5268` as `v3SmbModel`, and

```kotlin
val v3SmbModel = if (v3CommandSafe) adCommand!!.scheduledMicroBolus ?: 0.0 else 0.0
```

`adCommand` is the command **after** `ControlBarrierShield.enforce`. So a zero means either the
solver wanted nothing, or the solver wanted a dose and the barrier suspended it. Those have opposite
fixes, and nothing exported could tell them apart.

> **§AQ6-4's "from 13:42 the model output is 0.000 on every remaining tick … the controller is
> absent" is wrong.** The controller was not absent. It was overruled.

Two further things found while confirming this, both worth keeping:

- **`AutoDriveCommand.isSafe` is never set to `false` anywhere in production code.** It is documented
  as "indicates if the command passed internal sanity checks"; only tests ever assign it, always
  `true`. So `v3CommandSafe` reduces to `adCommand != null`, and the console line "🧘 [AUTODRIVE V3]
  Command not safe" can only ever mean "the command was null". **Fourth recorded instance of failure
  mode 1** — a constraint stated in documentation and absent from the code.
- On the plateau `v3SmbFloor` was non-zero (0.700 U at 13:42), and the floor is computed only under
  `if (v3CommandSafe)`. That proves the command was **not** null, so the zero came from the barrier.

### The measurement

`cbf_permitted_u`, already exported, settles it — and it was in the corpus all along:

```
time    BG    IOB   Ra    cbf_permitted_u   cbf_coefficient   model_output_u
13:37  197.8  6.30  5.4         1.995           0.00500           1.845
13:42  204.0  7.95  4.2         1.995           0.00500           0.000
13:47  217.4  8.88  3.2         0.150           0.00500           0.000
13:52  220.4 10.19  2.8         0.000           0.00500           0.000
14:16  219.8 12.59  1.1         0.000           0.00500           0.000
14:26  211.7 13.04  1.8         0.000           0.00500           0.000
```

**`cbf_permitted_u` collapses 1.995 → 0.150 → 0.000 at 13:52 and stays at zero for the rest of the
meal.** `cbf_coefficient_used` is 0.00500 on every tick — `LEGACY_CONTROL_COEFFICIENT` is active
throughout, so the barrier reasoned as if the ISF were 45 the whole time, exactly as Part A-bis said.

### Why the barrier suspends, computed exactly

`ControlBarrierShield.enforce`, with `h = bg − 80`, `p1 = 0.015`:

```
lfh  = -p1 x (bg - 100) - siMetabolic x iob x bg + Ra
lgh  = -siMetabolic x bg
safeU = (-gamma x h - lfh) / lgh          and safeU <= 0 -> Pair(0.0, 0.0), full suspension
```

At 13:52 (BG 220.4, IOB 10.19, Ra 2.81, si 0.005, gamma 0.054 = nominal 0.04 x the 1.35 meal-rise
boost, under its 0.07 cap):

| term | value |
|---|---|
| insulin term `-si x iob x bg` | **−11.23 mg/dL/min** |
| `lfh` | −10.23 mg/dL/min |
| safety boundary `-gamma x h` | −7.58 |
| `safeU` | **−2.40 U → full suspension** |

This replay reproduces production: zero permitted from 13:52 onward.

**The insulin term claims a fall of 56 mg/dL per 5 minutes. The measured fall over that same 5
minutes was 9** (220.4 → 211.5). The barrier's own model overstates insulin action by roughly six
times, and — like the 39 floor and for the same reason — it has **no appearance term** to put on the
other side during an undeclared meal.

### `MPC_TAU_MIN` is a knife edge — do not move it on arithmetic

Same tick, same state, only the time constant changed (the `LEGACY_CONTROL_COEFFICIENT` floor removed
so the change can bite):

| `MPC_TAU_MIN` | `safeU` at 13:52 | at 14:16 (IOB 12.59) |
|---|---|---|
| **75 (today)** | **−2.40 U** | −6.32 U |
| 100 | +0.20 U | −4.23 U |
| 150 | **+5.39 U** | −0.05 U |
| 200 | +10.59 U | +4.13 U |

`safeU` is a ratio whose numerator crosses zero right here, so it is hypersensitive: τ = 150 would
permit **5.39 U in a single 5-minute tick** on this state. **The doc's instinct to defer this was
right, and the reason is now measured rather than assumed.** A τ change alone is not the fix.

### What shipped (again export-only, no dosing path touched)

| file | change |
|---|---|
| `autodrive/safety/ControlBarrierShield.kt` | `Diagnostics` — `h`, `lfh`, `lgh`, the insulin term alone, `activeGamma`, the boundary, `systemEvolution`, `safeU`, `siMetabolic`, `fullySuspended`. Recorded on every `enforce` |
| `autodrive/AutodriveEngine.kt` | `lastMpcRawSmbU` / `lastMpcRawTbrUph` — what the solver asked for **before** the barrier; `lastBarrierDiagnostics`, captured before `recordCoefficientShadow` runs `enforce` a second time and overwrites the shield's record |
| `DetermineBasalAIMI2.kt` | new `adjustments.control_barrier` block, written in `markEstimatorDiagnosticsForExport` |

Three new tests reproduce the 13:52 tick as a barrier suspension, pin the insulin term to
`-0.005 x 10.19 x 220.4` and assert a calm tick is not reported as suspended.

### The open decision, and it needs the patient

The plateau needs the barrier to stop claiming a 56 mg/dL/5 min fall that is not happening. Three
routes, and they are **not** equivalent in risk:

1. **Give `lfh` an appearance term** (`+ Ra` is already there but is the *estimated* Ra, which sat at
   1.1–2.8 while a real meal was absorbing). Most correct, least understood.
2. **Raise `MPC_TAU_MIN` toward physiology.** Measured above: a knife edge, and it loosens the barrier
   on *every* tick including the descents, which is where the corpus records BG 41–76.
3. **Bound the insulin term by what is actually observed** — the barrier already has `bgVelocity`.
   Narrowest, and it can only tighten when the observed fall is fast.

**No dosing change is proposed here**, and none should be made until the next package carries
`control_barrier`. Cost statement as required: on the 408 corpus ticks below BG 75 — the day with 45,
57 and 63 — the delivered dose is already 0.000 U with TBR 0.00, so none of the three routes can act
there; but routes 1 and 2 both loosen the barrier on a **fall**, which is exactly where the veto and
the barrier are doing real work, and neither can be quantified until `safeU` is exported.

## AQ7-8. Route 1 quantified, and refused — the appearance term cannot be made safe on this corpus

The patient chose route 1 of AQ7-7: give `lfh` an appearance term. **It was quantified before any
code was written, and it does not survive the quantification. No dosing change was made.** This
section records the negative result, because the corpus is the only thing that could have produced it.

### The design under test

The barrier's `lfh` already carries `+ estimatedRa`; the defect is that the estimator understates it.
The observation supplies the missing value directly, since `lfh` *is* the natural evolution and the
observed `bgVelocity` *is* that evolution:

```
raObserved = bgVelocity + p1 x (bg - 100) + siMetabolic x iob x bg
raEffective = min(max(estimatedRa, raObserved), CAP)        // gated, never below today's value
```

Replayed on **492 barrier ticks across 9 support packages** (every tick where `cbf_coefficient_used`
is present, i.e. where the barrier actually ran), with γ = 0.054 and CAP = 6.0 mg/dL/min — the p95 of
the measured Ra shadow distribution (§A-ter: p90 5.22–5.49, max 8.13).

`raObserved > estimatedRa` on **311 of the 312 ticks above BG 150**, so the term is not a rare
correction: the estimator understates appearance essentially always in that band.

### The cost, measured — and it is disqualifying

For each gate: how many ticks move from "barrier suspends everything" to "barrier permits a dose",
how many of those were followed by BG < 70 within two hours, and how many of the 2026-08-14 plateau
ticks it helps.

| gate | ticks unsuspended | followed by BG < 70 within 2 h | 08-14 plateau ticks helped |
|---|---|---|---|
| BG ≥ 150 | 128 | **19** | 6 |
| BG ≥ 150, vel ≥ 0 | 59 | 4 | — |
| BG ≥ 150, vel ≥ +0.2 | 50 | **4** | 6 |
| BG ≥ 150, vel ≥ +0.2, IOB < 10 | 45 | 4 | 4 |
| BG ≥ 150, vel ≥ +0.2, IOB < 9 | 35 | 2 | 4 |
| **BG ≥ 150, vel ≥ +0.2, IOB < 8** | 25 | **0** | **0** |
| BG ≥ 150, vel ≥ +0.2, IOB < 7 | 16 | 0 | 0 |

**The gate that removes all hypoglycaemia exposure also removes the entire benefit.** There is no
setting of (BG, velocity, IOB, Ra) that keeps the plateau ticks and drops the dangerous ones.

The four dangerous ticks are one episode — the 2026-08-12 excursion this document already records as
BG 297 → 45:

```
when         BG     IOB    vel    Ra   safeU today  safeU route1   min BG within 2 h
08-12 10:37  287.4  8.59  +0.73  3.67     -0.20         +1.43            57
08-12 10:42  290.5  8.81  +0.62  3.59     -0.48         +1.18            53
08-12 10:46  292.3  9.21  +0.36  3.39     -1.02         +0.77            53
08-12 10:51  297.2  9.59  +0.98  3.14     -1.57         +0.35            45
```

The barrier suspends there today, and it is **right**: the crash came from insulin already on board.
Route 1 would have lifted the ceiling by 0.35–1.43 U at the top of that excursion.

**The 08-14 plateau and the 08-12 crash occupy the same region of the state space** — BG 200–300,
rising slowly, IOB 8–10, estimated Ra 1.1–4.2 versus 3.1–3.7. They overlap on every input the barrier
has. They differ only in what happens next: whether the meal keeps absorbing. **A tick-local rule
cannot separate them.**

### And route 1 cannot reach the late plateau anyway

At the ticks the patient actually complained about — 14:07 to 14:26, IOB 11.6–13.1 — the insulin term
is −14.4 mg/dL/min. The cap is 6.0. Crediting the **maximum physiological appearance ever observed in
this corpus** still leaves `safeU` at −0.78 to −1.94, i.e. still fully suspended:

```
BG 212.6  IOB 11.57  safeU -5.39 -> -0.78
BG 218.3  IOB 11.79  safeU -5.57 -> -1.08
BG 219.8  IOB 12.59  safeU -6.32 -> -1.89
BG 217.1  IOB 12.67  safeU -5.88 -> -1.94
```

**So route 1 is structurally incapable of fixing the second half of the plateau.** Above roughly
IOB 11 at BG 220, no appearance term bounded by physiology can offset `si x iob x bg`.

### What this actually proves

The barrier does not have a missing-appearance problem. It has an **overstated insulin term**:
`si x iob x bg` with `si` floored at 0.005 (ISF 45, `MPC_TAU_MIN` 75) claims −14.4 mg/dL/min at
IOB 13, i.e. a fall of 72 mg/dL per 5 minutes, against a patient whose measured sensitivity is
22.3 mg/dL/U. It saturates on IOB alone, which is exactly why it cannot discriminate a meal that is
still absorbing from one that has finished.

That points back at `MPC_TAU_MIN` and `LEGACY_CONTROL_COEFFICIENT` — measured in AQ7-7 to be a knife
edge — and the knife edge is itself a symptom: `safeU` is a ratio whose numerator crosses zero in
this regime, so every route that moves the numerator is hypersensitive here.

**Nothing was changed. The instruments from AQ7-5 and AQ7-7 stand, and the next package is what
should decide.** The specific thing to look for is `control_barrier.safe_u` alongside
`insulin_term_mgdl_per_min` on a meal: it will show whether the term is as dominant in the wild as it
is in this replay, on ticks where the barrier ran and the corpus could not be re-derived.

---

# Part A-octies — nine parts measured in parallel, and the one bound that survived (2026-08-15)

Written 2026-08-15 from support package `1786797984089` (283 decision ticks, 08-14 14:47 → 08-15
14:42), with cross-checks on `1786722482068` (the 08-14 plateau) and `1786605720362` (the 08-12
crash, BG 297 → 45). Nine parts ran as separate specialists; every decisive claim below was
re-verified in the code by the orchestrator before it was accepted. Suite after this part:
**BUILD SUCCESSFUL, 1460 tests, 0 failures, 12 skipped**. The 12 skipped still include the two
`@Disabled` DIA persistence tests that stayed off through the kernel rewrite. The first run of this
suite failed once on the known-flaky `aimiNeuralNetworkTest > test training reduces loss()` and
passed on the re-run; that test is flaky, not fixed.

## AQ8-1. The day itself

BG 64.1–209.3, mean 120.8, TIR 94 %, five ticks below 70. SMB 52.14 U. Today's lunch had **no
plateau**: peak 208.7 at 13:57, then 208 → 98 in 40 minutes. Two sub-70 excursions, both after a
meal: **64.1 at 11:51** and **65.7 at 08-14 21:56**.

## AQ8-2. Five independent negative results, all on the same state space

This is the finding that outranks the individual parts. Five different routes to "dose more on a
rising meal" were quantified and **all five failed for the same reason**: the legitimate rescue and
the dangerous overdose occupy the same region of the state space, and no tick-local rule separates
them.

| route | where it was tested | why it failed |
|---|---|---|
| appearance term on the barrier `lfh` (route 1) | AQ7-8, 492 ticks / 9 packages | the gate that removes all hypo exposure removes all benefit |
| **observed-velocity bound on the insulin term (route 3)** | this part, 477 barrier ticks | it is route 1 with the cap removed — see AQ8-3 |
| **saturated `min_pred` treated as unknown** | this part, 52 instrumented vetoes | the saturation flag is `IOB ≥ 2.2 U` in disguise — see AQ8-4 |
| **opening Harmonia's basal-first `eligible` term** | this part, 3 packages, ~737 ticks | measured cost and benefit both **0.000 U** |
| **a tick-local bound on the rise floor itself** | this part, exhaustive 499-feature scan | all 38 separating features are artefacts — see AQ8-5 |

**The one thing that did work stopped searching in the same space and went one level up.** See AQ8-5.

## AQ8-3. Route 3 is route 1 with the cap removed — refuse it harder

`lfh = −p1(bg−100) − si·iob·bg + Ra` (`ControlBarrierShield.kt:133-137`). Substituting the
observation-derived appearance `raObserved = v + p1(bg−100) + si·iob·bg` makes the first two terms
cancel exactly:

```
lfh(Ra := raObserved) = v          max |difference| = 2.2e-15 over 323 ticks
```

So the suspension condition becomes `v ≤ −γ(bg−80)`, i.e. a required 5-minute fall of −27.0 at
BG 180, **−37.8 at BG 220**, −59.4 at BG 300. **The fastest fall in 477 barrier ticks is −22.4.**
The condition is physiologically unreachable above about BG 150: at the meal γ, route 3 unsuspends
**207 of 207** ticks and never suspends again.

On the 08-12 excursion it stays permissive all the way down: `safeU` +8.66 / +8.45 / +8.27 / +8.43
at 10:37–10:51 (route 1 gave +1.43 / +1.18 / +0.77 / +0.35), and still **+5.11 and +5.46 at 11:06
and 11:17 while BG was falling toward 45**. Permitted budget over the two hours that ended at BG 45:
**0.59 U today → 36.54 U under route 3.**

The tighten-only variant (`lfh := min(lfh, v)`) is safe and **empty**: it changes `safeU` on 3 of 477
ticks and newly suspends none, because `lfh ≤ v` already holds on 474 of 477.

## AQ8-4. The 39 floor: the instrument worked, and it proved the fix wrong

The AQ7-5 unclipped mirror did its job. Splitting the 52 instrumented `VETO_HYPO_FLOOR` ticks into
SATURATED (deep negative unclipped, high clip count) and REAL gives 42 / 10 — and the forward
outcome is the opposite of the hypothesis:

| class | n | min BG in next 2 h | followed by < 70 |
|---|---|---|---|
| SATURATED | 42 | **64.1** | **9** |
| REAL | 10 | 71.7 | **0** |

A single threshold, **`IOB ≥ 2.2 U`, reproduces the split on 51 of 52 ticks (98.1 %)**. The flag is
`IOB × ISF > BG`, i.e. the high-insulin-load state, which is why it predicts subsequent lows instead
of excluding them. On the 08-12 package, 83 % of proxy-SATURATED floor-39 ticks were followed by
BG < 70 and 44 by BG < 55, and **all 19 ticks of the 270.8 → 45 descent are proxy-SATURATED**.

Relaxing them would have added ≥ +1.81 U at IOB 9.3–10.5 inside the window of the 64.1, and
≥ +1.68 U at IOB 18.3–19.3 inside the window of the 65.7.

**Correction to AQ7-5.** Its premise sentence — "the same 39 with one clipped step and an unclipped
38 is a real forecast" — describes a case with **zero instances** in 220 instrumented ticks. Clip
counts are `{0}` or `{≥ 11}`, never 1–10. Do not design against that case.

## AQ8-5. What actually shipped: the floor must obey the ceiling the ladder chose

The rise floor is not a floor. Of 52.14 U delivered in 24 h, **35.60 U (68.3 %) came from ticks where
`model_output_u` was 0**, and 46.57 U (89.3 %) from ticks where the floor was binding. The governed
path delivered 5.57 U per day.

The exhaustive scan over 499 exported features found **no tick-local bound on the floor's own
inputs**. But one level up, `DetermineBasalAIMI2.kt` capped the floor at
`maxOf(maxSMB, maxSMBHB)` — the floor **ignored which branch of the maxSMB ladder had actually
fired**. On 31 of 50 floor-active ticks the ladder had chosen a lower ceiling and the floor overrode
it.

| episode | ladder chose | floor took | outcome |
|---|---|---|---|
| lunch 13:32 → 14:02 | 1.80 (promoted) | = ladder | clean, 208 → 98 |
| **dinner 20:37 → 20:56** | 1.36 / 1.60 | = `maxSMBHB` | **1.7× override → 65.7** |
| **10:00 meal** | 0.40 / 0.80 | = `maxSMBHB` | **2.75× override → 64.1** |
| 08-14 plateau | promoted (BG ≥ 200) | = ladder | no override |
| 08-12 10:37–10:51 | 2.200 (BG ≥ 250) | = ladder | no override |

**Where the floor worked, the ladder had already promoted and the floor added nothing. Both hypos
are ticks where the ladder refused and the floor overruled it.**

Cost of the change, measured on all four required operating points:

| operating point | now | bounded | delta |
|---|---|---|---|
| dinner → 65.7 | 12.545 U | 7.760 U | **−4.785 U**, IOB peak 19.32 → ~14.5 |
| 10:00 meal → 64.1 | 7.723 U | 2.812 U | **−4.911 U**, IOB at 10:26 10.42 → ~6.2 |
| today's lunch (the rescue) | 8.876 U | 8.536 U | −0.340 U, one tick; the 13:52–14:02 punch-through untouched |
| 08-14 plateau, 90 min | 11.155 U | 11.056 U | **−0.098 U** |
| 08-12 10:37–10:51 | 1.781 U | 1.781 U | **0.000 U** |
| 24 h total | 52.14 U | 38.73 U | −13.41 U (−26 %), 19 ticks |

Of the −13.41 U, 10.04 U is inside the two hypo episodes. The remaining 3.38 U sits on 8 ticks in
four clusters, each followed to resolution: peaks 139.0, 171.7, 166.7, 142.0, each ending at 97.6 /
128.5 / 90.6 / 97.4 **with insulin still on board**. **Added time above 180: 0 minutes. Above 200:
0 minutes.** None of the 8 is a meal the bound would starve.

**The honest reservation.** The bound defers to the ladder, and the ladder's promote branch needs
`slopeFromMinDeviation >= 1.0`. Proved arithmetically from exported BG and delta alone: at dinner
every other branch is excluded by its own precondition, so the ladder took `MAXSMB_STANDARD` and the
only condition that can have failed is **the slope, during a rise of +7.2 to +12.2 mg/dL per 5 min**.
Two readings, not separable from this corpus: either the ladder was right (six episodes out of six
agree — every STANDARD episode crashed after a low peak, every promoted episode resolved cleanly), or
it was blind and happened to be right. The slope instrument shipped in the same change so the next
package can settle it.

## AQ8-6. Six instruments were lying, and several earlier conclusions rest on them

This is the second theme of the day and it is not smaller than the first.

| instrument | what is wrong | who read it |
|---|---|---|
| the whole `control_barrier` block + `cbf_profile_isf_mgdl` | written **one tick early** — `markHtrRaFloorForExport` ran 52 lines before `autodriveEngine.tick`. Lag-1 agreement 75/89, 96/105, 116/127 against lag-0 13/90, 17/106, 28/127 | AQ7-7's table |
| `smb_binding_trace.binding_stage` | names the largest reducer, not the stage that set `final_u`. Wrong on **18 of 53** ticks | AQ6-5's ranking |
| `harmonia_smb_authority` | rebuilt from post-LIFT values, so `smb_u − demand_before_u` is 0.000 by construction; the real `toJsonObject()` was dead code | every "the LIFT only restores" claim |
| `learning.gate_pass` | computed from `learningContextClean()` alone, omitting the modulator term the real guard also requires, and re-read from a newer posterior. **84 disagreements in 173 transitions** | any DIA learning reasoning |
| `rise_floor_spent_u` | a free-running counter. `remainingRiseFloorBudgetU` has **zero call sites**; the reset is unreachable; `RiseFloorEpisodeBudgetTest` mirrors a ledger that does not run | anyone reading the KDoc |
| `variable_sens_mgdl` | read after the endo/activity multipliers, i.e. **post-dose**. Agrees with the tube on **0 of 160** ticks | every ISF figure in this document |

**`command_isf_mgdl` is `ctx.profile.sens` read once at bootstrap** — identical to `profile_isf_mgdl`
on 283/283 ticks. The number the delivered dose used is
`intelligence_snapshot_v1.isf.fused_mgdl_per_u`, which equals `tube_advisor.isf_used_mgdl_per_u` on
142 of 160 ticks.

**AQ7-4's ×2.65 is dead.** It came from inverting the tube's kappa, and
`kappaMinPredDropPerUnit` saturates at 45 for every sensitivity ≤ 29.66 mg/dL/U — 53 of 160 ticks
sit there, including **every delivering lunch tick**. Measured directly this package, the ratio is
0.211–1.148 with p50 0.758, the opposite direction. Do not reuse either number.

**RED_CARPET inflates every "units removed" figure by roughly 2×.** It lifted **+17.6 U on 18 ticks**
(33.8 % of the day's SMB). Of the −29.48 U attributed to `SAFETY_PRECAUTIONS_PKPD`, only −11.74 U
reached the pump; of the throttle's −6.00 U, only −3.07 U. Its 40 % trigger is a cliff: at lunch
13:57 a 23 % cut got no restore, at 14:02 a 46 % cut got a full restore. **There are two live
copies** with different guards (`DetermineBasalAIMI2.kt` ~6642-6714 and ~13247-13336).

## AQ8-7. Parts that returned do-not-code, and why that is the right answer

- **PART E, `SAFETY_PRECAUTIONS_PKPD`.** `bgRising = bg > targetBg` is real (it mislabels 41 % of
  ticks) but worth **0.45 U over 24 h and 0.00 U at lunch and at both hypos**. The second claimed
  bug — "clamps to `maxSMB` instead of `maxSMBHB`" — **does not exist**: the ladder reassigns
  `this.maxSMB` to the context ceiling before the clamp reads it. **Fifth recorded instance of
  failure mode 1.** Applying the "fix" would have added +1.36 U before the 64.1 (nadir → 27.9) and
  +0.50 U before the 65.7, and **0.000 U on the plateau it was meant to help**.
- **PART F, Harmonia.** The top blocker is a label ordering defect, not a bug and not a safety veto:
  `eligible` already fails on `releaseAuthority == NONE`, so 56 of 78 `BASAL_FIRST_OWNER_*` ticks
  named the wrong term. Measured cost of the change it would have justified: **0.000 U on three
  packages**. Harmonia owning no SMB is the documented design
  (`AIMI_ARCHITECTURE_MAP.md:138, 149-151`), not a defect. The A-quater channel fix **does** work —
  `LIFT_WITHIN_ENVELOPE` went 1.2 % → 4.0 % → 7.6 %, and `rbt_no_harmonia_channel` fell 7.2 % → 3.8 %
  — the label was simply unable to show it.
- **PART A, DIA gates.** Both guards the part asked for **already exist and already fire**
  (`PkPdIntegration.kt` skips `update()` entirely; `AdaptivePkPdEstimator.kt` already clamps to the
  bounds). Writing them would have been a no-op that looks like a fix. The real finding: **16 of 17
  lunch ticks re-export a value computed at another tick**, because `recordSkipped` copies the last
  step forward. There is **no once-per-CGM-sample guard** anywhere in `update()`. And structural DIA
  sat at 5.00 because **the anchor was equal to the floor** until 08:42 today — inverting the
  measured pull gives anchor 5.000 before, 6.002 after. Even removing the learn step entirely, the
  drift to 5.50 takes **5 days**; over the 5.58 h this corpus contains it is +0.032 h, i.e. 0.004 U
  on a 13.38 U lunch. Not dose-moving inside one install cycle.

## AQ8-8. The preset wiped a hypo guard, and the reselect is dated to the minute

`applyPkpdInsulinPreset` wrote 15 preference keys, of which **five are not kinetics**: the learning
pace and both prudence sliders. One of them, `OApsAIMISmbTailDamping`, is a **hypo guard** — the
preset wrote 0.85 over the user's 0.76, moving the worst-case tail cut from −24 % to −15 %, and it
widened the ISF fusion window. Both give more insulin.

The reselect is dated **between 08:37:00 and 08:42:01 Paris on 2026-08-15**, by two independent
signals: the anchor jump (`dia_reg_pull_h` −0.0000083 → +0.00198) and the learning-pace quantisation
(per-tick DIA step a multiple of 1.0/1440 before, 0.5/1440 after — ratio exactly 2). It also **halved**
the DIA rate cap.

Regimes for anyone analysing this package: **B** = 00:07–08:37 (102 trace ticks, anchor 5.0, pace
1.0) · **C** = 08:42–09:02 (5 ticks, estimator destroyed and rebuilt, all traces exactly 0.000000 —
**exclude**) · **D** = 09:07–14:42 (67 trace ticks, anchor 6.0, pace 0.5). **Today's lunch is entirely
inside D and internally clean.**

`OApsAIMISmbTailDamping` has **seven** write paths in main, not the four previously recorded, and the
preset was an unlisted one. That is very likely the real cause of the "second simplified slider
resets" report: persistence was never broken, a second writer kept stamping over it.

## AQ8-9. What shipped

One dose-moving change, and six that cannot move a dose. Suite green apart from the known flaky test.

| file | change | moves the dose |
|---|---|---|
| `DetermineBasalAIMI2.kt` | `smbCeilingForFloor` = `maxSMB` instead of `maxOf(maxSMB, maxSMBHB)` | **yes** |
| `DetermineBasalAIMI2.kt`, `quality/SmbBindingTrace.kt` | 11 ladder branch tags (incl. `_CLAMPED` twins for the BG < 120 clamp) + `slope_from_min_deviation`, exported in `smb_binding_trace` | no |
| `DetermineBasalAIMI2.kt` | `markHtrRaFloorForExport` moved after `autodriveEngine.tick` — closes the one-tick barrier lag | no |
| `DetermineBasalAIMI2.kt`, `autodrive/AutodriveEngine.kt` | four KDoc blocks corrected (`command_isf_mgdl`, `variable_sens_mgdl`, the dead `effectiveDiaH`, the stale `createSafe` floor claim) | no |
| `compose/PkpdPresetProfiles.kt`, `compose/PkpdSettingsUi.kt` | preset writes kinetics only; the three other writes move to the first-run wizard | no |
| `pkpd/PkPdIntegration.kt`, `orchestration/IntelligenceSnapshotJson.kt` | `kinetics.dia_accepted_updates` and `kinetics.dia_learn_blocked_by`, taken where the gate is really applied | no |
| `recursive/RecursiveBeliefResolver.kt`, `recursive/RecursiveBeliefModels.kt`, `DetermineBasalAIMI2.kt`, `patient/HarmoniaSmbAuthorityDecision.kt` | blocker label order; the arbiter's real record exported (pre-LIFT `demand_before_u`, `mpc_demand_u`, `envelope_max_u`, `reasons`); eight Doubles finite-guarded | no |
| 3 test files + 1 new | `RiseFloorLadderCeilingTest` (8), preset negative assertions, the estimator staleness contract, the Harmonia label expectation | no |

**A latent bug was found and fixed on the way.** `coerceAtLeast(0.0)` does **not** stop a NaN
(`NaN < 0.0` is false), and the main-route export call is **not** wrapped in `runCatching` — only the
early-exit and T3C routes are. A non-finite arbiter value would have thrown out of `org.json` and
ended the tick after the dose was already decided.

**And the review caught the day's own disease inside the day's own fix.** `lastMaxSmbLadderBranch`
and `lastSlopeFromMinDeviation` were added as tick members with no per-tick reset. A tick can abort
at `ensureWCycleAndLoadGlucoseStatusOrAbort`, which returns **before** the ladder runs but **after**
the trace draft is reset — so the export would have stamped the previous tick's branch tag and slope
onto an otherwise empty trace, with no way for a reader to tell. That is exactly the failure this
part spent the day documenting six times over, reproduced in the instrument built to stop it. Fixed
by resetting both beside `lastSmbBindingTraceDraft`, where the codebase already carries a comment
explaining this precise class of bug for the effort telemetry. **Not measured: how often that abort
path actually fires.** If it is common, `max_smb_ladder_branch` needs the same counter treatment
`dia_accepted_updates` just invented — same problem, same shape.

## AQ8-10. What the next package must answer

| question | field | pass condition |
|---|---|---|
| did the bound land | `smb_binding_trace.autodrive_floor_u` vs `.max_smb_u` | `floor <= max_smb_u` on **100 %** of ticks; today violated on 31/50 |
| is the ladder we now trust sane | `smb_binding_trace.max_smb_ladder_branch`, `.slope_from_min_deviation` | a `STANDARD` tag on a fast rise means the ladder saw nothing. **Note `slopeFromMinDeviation` defaults to 999.0, which is finite** — 999 means "no value" |
| is the barrier lag closed | `control_barrier.h_mgdl + 80 == baseline_state.current_bg_mgdl` **same row** | today it matches the previous row on 66/72 |
| does a Harmonia LIFT add anything | `harmonia_smb_authority.demand_before_u`, `.mpc_demand_u`, `.reasons` | `smb_u − demand_before_u > 0` on some tick, or "restore only" is confirmed in production for the first time. **The key is now `reasons`, not `reason_codes`, and `max_smb_cap_u` moved out of this block** |
| is a DIA step fresh | `kinetics.dia_accepted_updates` | flat counter ⇒ discard `dia_learn_step_h` and `dia_reg_pull_h` for that run |
| does DIA finally move | `kinetics.structural_dia_h` | ≈ 5.13 after 24 h, 5.24 after 48 h on the current prefs. Still 5.00–5.05 after 48 h ⇒ the pull is being cancelled |
| hypo watch | `current_bg_mgdl < 70` | baseline 5 ticks / 25 min |
| hyper watch | time above 200 per meal | baseline: 90 min at 197–220 on 08-14 |

**Re-export after 48 h before any DIA decision.** This corpus holds 5.58 h of the anchor-6.0
configuration and 16 accepted updates. That is not enough to conclude anything.

## AQ8-11. Open, proposed, and deliberately not done

**Proposed as their own parts, none opened:**
1. **RED_CARPET** — 33.8 % of the day's SMB, a cliff trigger, two live copies. Removing the restore
   costs **−2.767 U on the 08-14 plateau**, which is why the ceiling bound (−0.098 U) ranked ahead of
   it. It needs its own install.
2. **`SCENARIO_CONSENSUS` on the permissive side** — at 10:22 it published `min_pred = 154.5` while
   the unclipped path minimum was **−57.1** with 30 clipped steps, and 2.20 U went out. That is on
   the causal path to both of today's lows, and it is upstream in `DoseTerminalSnapshot.kt`.
3. **`slopeFromMinDeviation` itself** — the input the shipped bound now defers to.

**Debt taken on knowingly:**
- No test covers the ladder branch tag; it is verified from the next package instead.
- No test covers `HarmoniaSmbAuthorityDecision.toJsonObject()` — it was dead code, so the suite
  cannot prove the new record is right.
- G-3 (`dose_isf_mgdl_per_u`) not shipped: redundant on 142/160 ticks, non-redundant on the 18
  `pre_v3_rbt` ticks of which 13 delivered insulin.
- PART H's preference instrument not shipped, so a preset change still has to be dated indirectly.
- `remainingRiseFloorBudgetU` and `RiseFloorEpisodeBudgetTest` left in place — dead code removal is
  its own change.
- The two `@Disabled` DIA persistence tests (`PkPdIntegrationTest`) stayed off through the kernel
  rewrite and are still off.

**One open question found while verifying, not chased:** `autodriveEngine.tick` has a **second** call
site in `runT3cAutodriveShadowTick`, which writes the same `last*` fields the barrier export reads.
On ticks where only that shadow branch runs, `markHtrRaFloorForExport` is never called. This may
explain part of why `control_barrier` covers only 72 of 283 ticks. Tracing it needs a per-tick record
of which branch ran, which does not exist.

**Still closed, do not reopen:** meal-intent (AQ1), `minPredictedBg` provenance (A-septies), route 1
(AQ7-8), route 3 (AQ8-3), `MPC_TAU_MIN` on arithmetic, `LogNormalKernel`.

---

# Part A-nonies — the hike day, and the hypoglycaemia the safety guard could not stop (2026-08-18)

Written from support package `1787084643605` (231 decision ticks, 08-17 22:27 -> 08-18 22:16 Paris),
with cross-checks on 22 measurable older packages. **This is the first package built from a device
carrying the Part A-octies instruments**, and all of them are present on 231/231 ticks. It is also
the first corpus with a large, dated exercise block: the patient was in the Alps and hiked about
three hours from 16:00. Note the window starts 08-17 22:27, so the 08-17 hike is **outside** the
corpus: **one exercise episode, no internal replication.**

Nine parts were planned; five completed before the session ran out of budget. Suite after the one
applied change: **BUILD SUCCESSFUL, 1460 tests, 0 failures, 12 skipped.**

## AQ9-1. The day, and the harm

BG min **50.6**, max 233.4, mean 114.6. Five ticks below 70. SMB **18.81 U over 47 ticks**, against
52.14 U on 08-15.

```
15h  8.88 U delivered, BG 113 -> 233.4        the meal
16h-18h  hike, 0.00 U, BG 219.6 -> 69.5       five ticks at 69-73
19h  3.77 U delivered, BG 98.4 -> 168.6
20h  BG 168.8 -> 64.4
21:12  BG 50.6, IOB still 2.92
```

Every tick below 75 delivered **0.00 U with a 0 U/h temp basal**. The loop was already at the floor
at every one of them.

## AQ9-2. What caused it: RED_CARPET undoes the sport guard

Four consecutive ticks, 19:32 to 19:57, carry `Safety sport -> SMB=0` in the narrative. The sport
guard did its job and zeroed the demand four times. Four times `RED_CARPET` put it back.

| tick | BG | IOB | demand | after guard | ladder rung | maxSMBHB | RED_CARPET | delivered |
|---|---|---|---|---|---|---|---|---|
| 19:32 | 148.8 | **0.15** | 2.30 | **0.00** | 0.80 | 1.80 | **1.80** | 0.81 |
| 19:37 | 159.8 | 1.10 | 2.28 | **0.00** | 0.80 | 1.80 | **1.80** | 1.35 |
| 19:52 | 163.0 | 2.99 | 1.92 | **0.00** | 0.80 | 1.80 | **1.80** | 0.98 |
| 19:57 | 168.6 | 4.14 | 1.96 | **0.00** | 0.80 | 1.80 | 1.12 | 0.63 |

**The defect is one missing line.** `criticalSafetyZeroedThisTick` is set in exactly one place in the
whole file, the critical-safety block in `applySafetyPrecautions`. The sport guard sits three lines
below it and did `return 0f` **without setting the flag**, so the RED_CARPET restore condition
(`gatedUnits < proposedUnits * 0.6f`) read the sport zero as a minor cut. RED_CARPET's own comment
says a zero placed by a vital safety is never restored. **Seventh recorded instance of failure
mode 1: the constraint was in the comment and absent from the code.**

RED_CARPET's cap is `if (maxSMBHB > baseLimit) maxSMBHB else baseLimit`, so it also **bypasses the
maxSMB ladder rung entirely** — the very ceiling the A-octies bound made the rise floor respect.

**Applied (uncommitted):** the sport guard's zeroing branch now sets `criticalSafetyZeroedThisTick`.
Quantified afterwards across 22 measurable packages: **8 affected ticks, -7.303 U**.

| package | ticks | units removed | BG in the next 2 h |
|---|---|---|---|
| **08-18** | 4 | **-3.770** | the 50.6 — the benefit |
| 08-12 | 3 | -2.822 | one to 68.6; **two to 212 then 247 — the cost** |
| 07-27 | 1 | -0.712 | **211 -> 257 — the cost** |
| **08-15** | **0** | **0.000 — no-op** | — |
| **08-14 plateau** | **0** | **0.000 — no-op** | — |

The two episodes most at risk are untouched, and the fields are present there, so those are real
zeros and not missing data. The cost is named and not hidden: about 2.5 U removed ahead of two
hyperglycaemic excursions, against 3.77 U removed ahead of a 50.6. That is a judgement, not a
measurement.

**The scaling branch of the sport guard was deliberately left alone.** It has fired **0 times in
14 610 rows**, and structurally so: `guardScale` is 0.0 unless a meal context is active, which needs
a manual flag or COB > 5, and **COB was 0 on all 35 sport-zero ticks in the whole corpus**. It is
unexercised, not safe by construction — `guardScale` is bounded to [0.40, 0.85] and a cut below 0.6
would trip the same restore. Export `guardScale` and the branch taken before deciding.

**A second restore site exists** with its own preference threshold and its own copy of the ladder
bypass. It **does** carry the flag guard, so the applied fix protects both. It was inert on the
ticks read.

## AQ9-3. Two corrections owed to Part A-octies

**1. AQ8-5's -13.41 U is overstated by roughly 25 %.** An independent replay of the same corpus with
the same method gives **-10.631 U**, while reproducing the dinner episode exactly
(0.935+1.100+1.100+1.100+0.550 = **4.785 U**, matching AQ8-5 to three decimals). The difference is
that AQ8-5 did not model the downstream re-clamp `clampSmbToMaxSmbAndMaxIob(smbToGive, maxSMB, ...)`
at the end of `applySafetyPrecautions`. The bound is still monotonically more restrictive than the
old expression and still removes 2.981 U before the 64.1 and 4.785 U before the 65.7 — but its
published effect was too large.

**2. AQ8-10's acceptance test compares two vintages of the same variable.** `autodrive_floor_u` is
written when the floor is clamped; `max_smb_u` is **overwritten later** by `finalizeAndCapSMB` with
the post-tube value, because the tube advisor runs a second time (`pre_v3_rbt`) between the two. The
three surviving "violations" on this package (08-18 03:22, 06:12, 08:12) are therefore an **export
artefact, not a dosing leak**: on all three the delivered dose was inside the ceiling
(0.2112 <= 0.32, 0.0990 <= 0.32, 0.0448 <= 0.08), guaranteed by the hard clamp. Proof in the data:
on the 8 floor-active ticks where the tube scale was < 1 and unchanged between the two publishes,
the floor equals the post-tube ceiling to the last digit (0.18 = 1.8 x 0.1, 0.68 = 0.8 x 0.85,
1.071 = 1.53 x 0.7, 0.32 = 0.8 x 0.4). The fix is to export `autodrive_floor_ceiling_u`, the ceiling
the clamp actually read, and test against that.

## AQ9-4. The shipped bound removed 0.494 U, not 26 %

Daily SMB fell 52.14 -> 18.81 U. A per-tick replay substituting the old expression gives:

- floor's own output: **-4.900 U**
- **delivered insulin: -0.494 U, i.e. 1.5 % of the 33.33 U drop**

On 12 of 32 floor ticks the bound bit *before* a guard that already bound (`PHYSIO_CAP`, the x0.5
PKPD guard, `SAFETY_NET`), so delivered change was 0.000 U. On the day's only large meal the ladder
**promoted** (`CONFIRMED_RISE_HIGH`, `maxSMB == maxSMBHB == 1.80`), so the bound had nothing to
remove. **The remaining -32.8 U is not decomposed, and is deliberately not attributed to exercise:
no exercise or lockout flag is exported, so it cannot be shown.**

A fact that reframes the whole day: **56 of 231 ticks (24 %) had BOTH ceilings at 0.00**, including
**28 consecutive ticks from 15:52 to 18:47**, spanning the entire fall from 232.1 to 69.5. Every
zeroing site sets both, so the old expression would also have given a zero floor there.

**The 233.4 peak is not the bound** (0.060 U on all 12 ticks above 180). What held the dose down
during that rise: the PKPD guard cut **8.236 U** and the throttle a further **2.172 U** — 10.4 U
removed downstream against 8.88 U delivered — plus a ladder that only promoted at BG 176.6, already
80 mg/dL into the rise, plus a **missing tick at 15:12** across the steepest part (+40.3 mg/dL).

## AQ9-5. The step window carried a day total, and the bug was protective

`physiological_tree.branches.activity.reasons` carries `steps15`. On 08-18 it reads 465, 476, then
**13218, 13249, 13249**, then **31**. The corpus maximum is 13249 and the next highest value anywhere
is 717.

**Root cause, found to the unit.** The Health Connect sync reads each window with
`ReadRecordsRequest(...TimeRangeFilter.between(start, end))` and then `response.records.sumOf { it.count }`.
`readRecords` returns whole records that **overlap** the range; it does not clip or apportion them.
Samsung Health writes a finished workout to Health Connect as **one consolidated record** at save
time, so a single 13218-step record covering 16:00-19:20 is credited **in full** to every window it
touches, including the 5-minute one. That is why `steps15min == steps60min == 13218`. The correct
API is `aggregate` / `aggregateGroupByDuration`, which apportions. **Not a fallback, not a unit
mix-up, not a cumulative counter: a windowed read that does not window.**

Nine observed values were reconstructed exactly from that one hypothesis, with no free parameters,
including the recovery to **182 = 31+40+60+51** once the session left the 60-minute window.

**And the corruption was protective.** At 19:22-19:32 the patient had stopped (true counts 31, 40,
60, 51 = 6-10 steps/min). The impossible value forced `exertingNow` (883 steps/min against a 25/min
threshold), hence `State.ACTIVE`, hence `effortIsLiveMovement()` true, hence the `!input.effortLive`
term in `MealCertainty` kept the meal override **shut**. When the reading corrected to 31 one tick
later, the override opened, meal certainty went HIGH, and the confirmed-meal floor raised the effort
multiplier from **0.4728 to 0.75**, putting **+0.499 U** back. `effort_smb_floored_by_meal` is true
on **exactly 1 tick of 231**, and it is that one.

> **Fixing the step signal naively is dose-ADDING: about +1.1 U between 19:32 and 20:07, i.e. into
> the 85 minutes before BG 50.6.** *(Inferred: closed-form arithmetic on the published
> `EffortActivityBelief` formulas, open loop, an upper bound.)*

The load-bearing half of any fix is therefore **not** the plausibility bound but the fallback:
`HealthContextRepository` currently does `steps15Result?.steps ?: 0`. **Mapping unknown to 0 is what
turns a rejected sample into an insulin-adding event.** A guard must hold the last plausible value,
never zero. With hold-last-plausible the 08-18 dose delta would be **0.00 U**.

**A second, independent step path is broken.** `isSportSafetyCondition()` does not read Health
Connect at all; it reads `StepService`, where `getRecentStepCount15Min()` returns **a single
5-minute bucket from 15 minutes ago**, not a 15-minute total, and `getRecentStepCount180Min()`
returns **170 minutes**. Consequence: the `recentBurst` trigger needs `recentSteps10Minutes >= 800`
on what is a 5-minute bucket, i.e. **160 steps/min — structurally unreachable**. The same map is a
plain `LinkedHashMap` written from the sensor thread and iterated elsewhere, while its wear twin was
fixed with `ConcurrentHashMap`. **Two different numbers with the same name, from two sources, by two
algorithms, never exported side by side.**

The corruption is **unique to 08-18** across the four packages checked; 08-15, 08-14 and 08-12 have
plausible maxima (81-89 steps/min) and `s60 > s15` throughout.

## AQ9-6. The architectural finding: a meal always beats exercise

Three components can represent post-exercise recovery. All three lost, and they lost structurally.

- `exercise_afterburn_prob` reached **0.9371** and was >= 0.87 on five ticks. **`EXERCISE_AFTERBURN`
  was dominant on 0 of 231 ticks.**
- The physiological tree carried `activity: ACTIVE, confidence 1.0` **in the same object** as a
  `DIGESTION_ACTIVE` trunk at confidence 1.0. The trunk is what propagates.
- The pattern catalogue emitted `EXERCISE_ACUTE` with a **HARD 0.450 U** cap at 19:27; five minutes
  later there was no activity pattern at all and a **SOFT 1.350 U** meal cap. A 3x swing on the same
  body in one tick.

Two reasons, both in the code. First, `buildExerciseAfterburnProb` takes four inputs — user intent,
the pattern snapshot, thermal recovery burden, a circadian term — and **`EffortActivityBelief` is not
one of them**, so afterburn fell 0.879 -> 0.190 in the same tick where the effort belief still asked
for x0.45. Second, afterburn is discounted **x0.92** (capped at 0.862) while the meal saturates at
**1.000**, and the meal must only lead by 0.08. **Exercise starts 0.08 behind and loses 0.14 before
any evidence is weighed.**

> Every exercise representation is gated on current movement; every meal representation is gated on
> a glucose rise. A post-exercise rebound is by definition a rise without movement, so it reclassifies
> as a meal within one tick of the patient standing still — at every layer at once.

And `EFFORT_MINUTES_CAP = 30.0` with the memory horizon capped at 120 minutes means **a three-hour
hike earns exactly what a thirty-minute walk earns**, while the file's own KDoc says post-exercise
sensitivity "lasts hours".

## AQ9-7. The instruments are missing exactly where they are needed

- The five `effort_smb_*` fields are written only where the multiplier is applied, so any tick that
  returns earlier exports `null`: **44 of 231 ticks (19 %)**, including the whole 16:32-18:42 descent
  and **all 11 ticks below BG 75 — 0 of 11 carry any activity export**.
- `physiological_tree` is absent on **exactly the 25 hike ticks, 16:02 to 18:42**, a 100 % overlap
  with the 25 `dia_learn_blocked_by = read_only_path` ticks.
- **Steps and HR are not fields at all.** They appear only inside the narrative string. The cause of
  a 50.6 hypoglycaemia had to be found by reading a log sentence.
- `steps_last_5m` is never exported, so `exertingNow` is unverifiable from any package.
- No `exercise_lockout_active` flag exists, so the 56 zero-ceiling ticks cannot be explained.
- `physio_context.activity_mode` reads **`Resting` through the whole climb and all five 69-73 ticks**,
  then `Stress/Activity` for two hours after the hike ended.
- `basal_terminal.bg_mgdl` is **two to three ticks stale**: it reports 76.7 when BG is **50.6**, with
  an eventual of 90.7.
- `meal_certainty.soft_corroboration` is computed, exported and pushed into `reasons`, but the level
  function never reads it. A decorative reason string.

## AQ9-8. What shipped, and what must not ship yet

**Shipped (uncommitted), one line plus comment:** the sport guard's zeroing branch sets
`criticalSafetyZeroedThisTick`. No test covers it — the guard is private and 400 lines from
RED_CARPET, and adding a seam would be the refactor the contract forbids in a safety patch. **Its
only evidence is the corpus measurement in AQ9-2.**

**Next, and in this order:**

1. **Export only, no dose.** Fill the 25-tick hole; add `steps_5m/15m/60m`, `hr_avg_5m`,
   `hr_resting`, `effort_state`, `steps_source_device`, `steps_window_algo`,
   `steps_rejected_impossible`, `exercise_lockout_active`, `guardScale` and the sport branch taken,
   and `autodrive_floor_ceiling_u`. Populate at the single point every export path crosses, **not**
   where the multiplier is applied, or the hypo ticks stay null.
2. **The step root fix and its fail-safe together, alone in their install.** `aggregate` instead of
   `readRecords`, plus a plausibility bound whose fallback **holds the last plausible value**.
   Separator: `effort_smb_factor_applied` and `effort_smb_floored_by_meal`.
3. **The RED_CARPET ladder bound**, both call sites, alone. Measured: **-11.646 U on 08-15** and
   **-0.000 U on the 08-14 plateau**. Separator: the sport string in `outcome.narrative` conditions
   the first check and not this one.
4. `StepService`'s mislabelled getters and its unsynchronised map. Separate part.

**Do not ship the step fix before the export.** It adds insulin into the window that ended at 50.6,
and the data needed to price it does not exist yet.

## AQ9-9. Open, and not measured

- **PARTs 4, 5, 6, 7 did not run** (session budget). Still open: is the maxSMB ladder blind — the
  slope was **0.4298 during a +11.5 mg/dL rise at 19:32**, and the ladder promoted on the 15h meal
  but not on the 19h rise, both with COB 0; is the DIA learner training its tail on exercise-driven
  falls, given `dia_accepted_updates` went 19 -> 117 and `structural_dia_h` peaked at 5.1194 before
  settling at 5.0709; does a Harmonia LIFT ever add insulin now that `mpc_demand_u` is exported on
  181 ticks; and why `control_barrier.safe_u` covers only 15 of 231 ticks, with the shadow-mode
  `autodriveEngine.tick` in `runT3cAutodriveShadowTick` as the untested lead.
- **One exercise episode only.** Nothing here is confirmed twice.
- Whether `refreshEffortActivityBelief()` ran at all on the 25 hike ticks is unknown. If it returned
  null, note the asymmetry: `effortIsLiveMovement()` fails **closed** (returns true) while the effort
  multiplier fails **open** (no reduction). One null shuts the meal override and removes the
  protection at the same time.
- The +1.1 U counterfactual is closed-form arithmetic, not a replay.
- The 08-12 20:06/20:11 cost of the applied fix (1.832 U removed, followed by 212 then 247) has no
  counterfactual.
