# ADR 0007 — The ISF chain carries a dosing policy, and the meal model is discarded

**Status:** proposed (findings recorded, two fixes applied)
**Depends on:** [0002](0002-sensitivity-three-levels.md), [0003](0003-dynisf-cache-read-path.md)
**Behaviour change:** two small fixes applied; the main proposal is not implemented

## Context

ADR 0002 says the sensitivity value is stored in the wrong field, ADR 0003 says it is read stale.
Both are true and both are surface. This ADR records what the chain actually computes.

### Four independent BG reductions, multiplied

`KalmanISFCalculator.computeRawISF` and `OpenAPSAIMIPlugin.dynamicDeltaCorrectionFactor`:

| Stage | at BG 93 | at BG 201 | ratio |
|---|---|---|---|
| `ln(BG/75+1)` denominator | 1.236 | 0.767 | ×0.62 |
| `bgFactor` step table | 1.000 | 0.200 | **×0.20** |
| `dynamicDeltaCorrectionFactor` | 1.000 | 0.492 | ×0.49 |
| **product** | 1.236 | 0.075 | **×0.06** |

The formula intends a sensitivity **16.4× smaller** at BG 201 than at BG 93. Three reductions,
written separately, multiplied. `bgFactor` is a six-step staircase: at BG 179 → 180 the value drops
33 % instantly.

**None of this is physiology.** Nobody measured a patient becoming five times more resistant at 180
than at 95. It is an aggressiveness policy, placed inside the parameter used for prediction.

### But the intended behaviour does not survive the chain

Measured over 285 ticks of one production day:

| Explains the commanded sensitivity | R² |
|---|---|
| the three BG stages above | **0.18** |
| raw BG | 0.16 |
| 5-minute delta | 0.08 |
| IOB | 0.03 |
| cache staleness | 0.02 |

More than half the movement is explained by none of the intended inputs. The Kalman filter and the
blend cancel most of the BG signal that was deliberately injected, and add movement of their own.
The result is neither the reactivity that was designed nor the stability of a physiological
parameter.

### Why a policy inside ISF is dangerous, not just untidy

`eventualBG = bg − iob × ISF`. A deliberately lowered ISF makes the loop believe the insulin already
on board does little, so the prediction stays high, so it doses more.

Observed 2026-06-22: sensitivity pinned at 9 mg/dL/U through a rise to BG 301 with 11.2 U on board
→ `eventual = 301 − 101 = 200` → keep dosing. When it returned to 75, basal went from 7.92 to
0.02 U/h in twelve minutes.

**Steering aggressiveness through ISF blinds the hypo guard exactly when it is needed.**

### The contamination is circular

```
DetermineBasalAIMI2:17289   estimatedSI = variableSensitivity / 10000
ContinuousStateEstimator:48 expectedNaturalDelta = … − (estimatedSI × 0.0012 × iob × bg)
                            innovation = observed − expectedNaturalDelta
                            estimatedRa = f(innovation)          ← the meal model
MpcController:225           si = estimatedSI × METABOLIC_SI_BASE
ControlBarrierShield:66     siMetabolic = estimatedSI × METABOLIC_SI_BASE   ← the safety barrier
```

A dosing policy contaminates the sensitivity, which contaminates the state estimator, the meal
model, the MPC and the safety barrier — which come back to gate the dosing.

### The meal model exists and its magnitude is thrown away

`ContinuousStateEstimator` produces `estimatedRa`, a proper Kalman-style estimate of glucose
appearance in mg/dL/min, with innovation, covariance, weight-scaled physiological clipping and
decay. Every consumer reduces it to a boolean, with a different threshold each time:

```
MealAbsorptionPhaseEngine:94    estimatedRa >= 2.0  → belief += 0.15
AutodriveEngine:203             estimatedRa > 0.6   → aggressiveSignal
AutoDriveGater:76               estimatedRa >= 0.7  → gate
CorrectionAggressionGate:138    estimatedRa >= 0.8  → gate
```

Nowhere does Ra compute a quantity of insulin. The "dose more, a meal is happening" behaviour is
performed by the BG staircase instead.

### The only relative bound is inside an optional feature

`DynIsfTrajectoryTuning` clamps to `[0.58, 1.42] × profileIsf` — the single place in the whole chain
that bounds sensitivity relative to the profile. It sits behind six `noop()` gates. The only other
guard is `coerceIn(5.0, 300.0)`, so wide it never binds.

Measured: **94 of 285 ticks (33 %) fall outside `[0.58, 1.42] × profile`**, extremes ×0.32 and
×2.15. Same shape as `BasalTerminalInvariants` and `meal_mode_exempt`: a correct invariant written
in a branch instead of at the exit.

## Decision

**Same algorithms, different destinations.** AutoISF's logic is sound; its point of application is
not. Of its four levers, exactly one is a sensitivity signal.

| Signal | today | should drive |
|---|---|---|
| `bgAcceleration` | ISF | delivery policy (urgency) |
| `bgBrake` | ISF | delivery policy (restraint) |
| `pp` (post-prandial) | ISF | the meal model |
| `dura` (stuck high) | ISF | **R** — the only genuine sensitivity evidence |
| `bgFactor` staircase | ISF | deleted, replaced by the policy layer |
| Kalman | `rawISF(BG)` | **R**, from outcome observations |
| `estimatedRa` | 4 boolean thresholds | the insulin requirement, quantitatively |

Filtering `rawISF(BG)` is meaningless: it is a deterministic function of an observable, so there is
no hidden state to estimate — only lag. That is why the filter destroys 82 % of the intended signal.

### Not decided yet, on purpose

Routing `Ra` to the requirement assumes `Ra` is good enough to carry it. **There is no measurement
of it** — it was not exported until this ADR added it. Rebuilding around an unmeasured quantity
would repeat the sampling error this investigation already made twice. One support package answers
it.

## Applied in this change

1. **Physiological factor applied once.** It was applied inside `calculateVariableIsf` *and* by the
   caller, so `profile.sens` carried it squared (0.72–1.32 instead of 0.85–1.15) while
   `profile.variable_sens` carried it once — two fields for one quantity, differing by one factor.
   The function now returns the estimate without it; each caller applies it once, on a fresh value.
2. **`fastMedian` renamed to `fastConservative`.** `listOf(a, b).sorted()[1]` on two values is the
   maximum, not a median, and the comment claimed a robustness that does not exist. Behaviour is
   unchanged — the maximum is the conservative choice — but whether max, min or mean is right is a
   calibration question for the replay corpus, not a rename.

## Not applied, and why

Moving the `[0.58, 1.42] × profile` clamp to the exit of `calculateVariableIsf` would bind on 33 %
of ticks in both directions. That is not a no-risk change; it needs the shadow treatment — export
the bounded value beside the real one, compare over a week, then switch.

## Exports added

`isf_kalman_fast_mgdl`, `isf_adj_engine_mgdl`, `isf_fused_slow_mgdl`, `isf_trust_fast`,
`isf_dynamic_factor`, `isf_trajectory_multiplier`, `estimated_ra_mgdl_per_min`, plus
`isf_cache_key` and `isf_cache_glucose_mgdl`.

Together they answer the two open questions: what the unexplained 82 % is made of, and whether `Ra`
can carry the requirement.
