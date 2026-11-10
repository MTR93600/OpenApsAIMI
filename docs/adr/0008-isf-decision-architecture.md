# ADR 0008 — One variable per question: sensitivity, appearance, urgency

**Status:** proposed (step 1 shipped in shadow)
**Depends on:** [0002](0002-sensitivity-three-levels.md), [0003](0003-dynisf-cache-read-path.md), [0007](0007-isf-chain-roles.md)
**Behaviour change:** none yet — the exit clamp is recorded, not applied

## Context

ADR 0007 established that a dosing policy lives inside the parameter used for prediction. This ADR
maps the chain end to end and decides the target shape for full closed loop.

### The chain as it stands

```
[0] DB short-circuit: getApsResultCloseTo() → returns a previous result and skips everything below,
    including the Kalman update
[1] slow floor      IsfFusion.fused()        median(profile, tdd, tdd×scale)      memory: lastIsf
                                             bounds [min(profile, tdd×0.69), tdd×2.01]
                                             rate limit ±40 %/tick
[2] fast 1          KalmanISFCalculator      1800 / (TDD × ln(BG/75+1)) × bgFactor  memory: filter state
                                             bgFactor = 6-step staircase, 1.0 → 0.2
                                             process noise added per call, not per Δt
[3] fast 2          IsfAdjustmentEngine      AF ln(BG/55) + TDD-EMA                 memory: rate limit
[4] combination     max(fast1, fast2)        median gap ×1.95; the Kalman is discarded on 88 % of ticks
[5] blend           IsfBlender.blend()       (1−w)·slow + w·fast, w = |delta|/10 ∈ [0.1, 0.9]
                                             rate limit ±5 %/tick, ±20 %/h    memory: lastIsf
[6] × dynamicDeltaCorrectionFactor           falling: exp(0.15|d|) capped 1.4
                                             BG>110: 1 − ((BG−110)/90)×0.5  → down to ×0.5
[7] trajectory      DynIsfTrajectoryTuning   ±10 %, six gates
                                             the only relative bound: [0.58, 1.42] × profile,
                                             inside the branch
[8] × physio                                 [0.85, 1.15] — applied once since ADR 0007
[9] clamp [5, 300]                           absolute; has never bound on any observed tick
[10] cache          key = bucket30min + glucose;  read = valueAt(size−1) = highest glucose of bucket

profile.sens         = cache × epsPct × physio
profile.variable_sens = calc × physio
estimatedSI = variableSensitivity / 10000 → state estimator innovation, MPC, ControlBarrierShield
```

### What the measurements say

**Five memories and three rate limiters in series on one number** — `IsfFusion.lastIsf`, the Kalman
state, the `IsfAdjustmentEngine` limiter, `IsfBlender.lastIsf`, and the cache.

**The limiters sit before the multipliers that undo them.** The blender bounds movement to ±5 % per
tick; steps 6, 7 and 8 then multiply by up to ×1.4, ±10 % and ±15 %. Observed: 20 tick-to-tick jumps
≥ ×1.5 in a single day.

This is the lesson the repository already wrote for the basal channel in `BasalTerminalInvariants`:
*"a cap placed before a multiplier does not bound the final value"*. The ISF channel repeats it.

**What moves the value is not what the formula intends.** Over 200 ticks with the terms exported:

| term | R² on the commanded sensitivity |
|---|---|
| delta factor (step 6) | **0.43** |
| blood glucose | 0.37 |
| fast Kalman | 0.30 |
| slow floor | 0.22 |
| IsfAdjustmentEngine | 0.14 |
| fast weight | 0.10 |
| trajectory | 0.09 |

The four BG-dependent stages intend a ×16.4 span; R² = 0.18 of it survives the chain.

**The only relative bound is skippable.** 94 of 285 ticks (33 %) fell outside `[0.58, 1.42] ×
profile`, extremes ×0.32 and ×2.15.

## Decision

Full closed loop has no meal announcement: the system must **detect**, then **react fast**. A
sensitivity must be **stable** for the prediction to be worth anything. Fast and stable in one
variable is a contradiction, and that contradiction is the whole chain above.

**Three variables, three time constants, three owners.**

| Variable | Question it answers | Time constant | Consumers | Bound |
|---|---|---|---|---|
| **S** — sensitivity | how strongly does insulin act for this patient now | hours | prediction, MPC, safety barrier | `[0.5, 2.0] × profile`, **at the exit, unconditional** |
| **Ra** — appearance | is glucose arriving, how fast | minutes | the insulin requirement | weight-scaled physiological clip (already present) |
| **U** — urgency | how much of the requirement to deliver now | instantaneous | SMB cap, interval, delivered fraction | explicit policy |

### Invariants

1. **S is bounded relative to the profile, at the exit, with no condition.** Not in a branch.
2. **S never encodes a policy.** The `bgFactor` staircase and the `dynamicDeltaCorrectionFactor`
   reduction leave it.
3. **Ra drives the requirement quantitatively.** Not four boolean thresholds with four different
   values.
4. **U carries the aggressiveness and never touches the prediction.** This is what serves the
   original need — reacting fast to a detected meal — without blinding the hypo guard.

### Where the AutoISF levers go

Of its four, exactly one is a sensitivity signal.

| lever | today | belongs to |
|---|---|---|
| `dura` (stuck high) | ISF | **S** — genuine evidence of resistance |
| `bgAcceleration` | ISF | U |
| `bgBrake` | ISF | U |
| `pp` (post-prandial) | ISF | Ra |

### What this removes

Five memories become one (S). Three rate limiters become one, at the exit. Four BG-dependent stages
become a single term, inside U.

## Replayed on production data

Same day, the decomposition against what actually happened:

| | actual | proposed S |
|---|---|---|
| span | ×5.1 | **×2.6** |
| jumps ≥ ×1.5 | **14** | **2** |
| max tick-to-tick jump | ×3.19 | ×2.33 |

The remaining ×2.6 is almost entirely the user's own profile step (70 → 30 = ×2.33), and the two
jumps are those transitions. No spurious movement.

The undeclared meal of 2026-08-06 20:21 shows the point directly:

```
time    BG    d5     U      S   | actual ISF
20:11   98  +1.7  1.00     28   |  39
20:31  123 +13.7  1.50     28   |  31
20:41  153 +13.3  1.69     28   |  16   ← collapse
21:21  181  +6.4  2.03     28   |  20
21:52  174  -5.8  1.34     28   |  17
```

Today the loop expresses "dose harder" by crushing the sensitivity from 39 to 16 — that is, by lying
to its own prediction. In the decomposition S holds at 28 and U rises to 2.03: same dosing intent,
prediction intact.

Slow adaptation converged to `R` = 0.79–0.95 across days, i.e. this user is 5–20 % more resistant
than their profile states.

## Migration

| # | Step | Measurable on the corpus | Risk |
|---|---|---|---|
| **1** | Exit clamp `[0.5, 2.0] × profile` — **shipped in shadow** | yes, 33 % of ticks | medium when applied |
| 2 | Collapse the redundant rate limiters into one, at the exit | yes | low |
| 3 | Move `bgFactor` and `dynamicDeltaCorrectionFactor` out of S into U | yes | **high** — the real switch |
| 4 | `Ra` drives the requirement, replacing the four thresholds | yes | high |
| 5 | S learned from outcomes, replacing the Kalman on `rawISF(BG)` | yes | high |

Step 1 buys the most safety for the least change: it does not make the system cleverer, it stops it
leaving the domain where the profile still means something.

## Shipped with this ADR

`recordProfileRelativeShadow` computes what an unconditional exit clamp would command and exports it
as `isf_profile_relative_shadow_mgdl` and `isf_profile_relative_bound_hit`. **Nothing reads it.**
Comparison over a week decides whether step 1 is applied, and with which band.

## Open, and deliberately so

The requirement calculation of step 4 needs `Ra` calibration, and there is exactly **one** clean
production meal curve so far (2026-08-07 13:42, 0.80 → 3.90 → 0.00). The structure can be designed
now; the coefficients need three or four measured meals. Designing them on n = 1 would repeat the
sampling error this investigation already made twice.
