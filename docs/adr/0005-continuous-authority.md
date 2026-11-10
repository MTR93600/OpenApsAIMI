# ADR 0005 — Continuous authority scalar instead of tiers

**Status:** proposed
**Depends on:** [0002](0002-sensitivity-three-levels.md), [0003](0003-dynisf-cache-read-path.md), [0004](0004-classify-before-dose.md)
**Behaviour change:** yes

## Context

`CorrectionAggressionGate` classifies each tick into three buckets — `FULL`, `MODERATE`,
`REBOUND_GUARD` — and each boundary is a cliff:

```
CorrectionAggressionGate.kt:20   REBOUND_MIN_BG_LOOKBACK_MGDL = 75.0
CorrectionAggressionGate.kt:17   REBOUND_BG_MARGIN_MGDL       = 30.0
CorrectionAggressionGate.kt:181  if (input.minBgLookback75m >= REBOUND_MIN_BG_LOOKBACK_MGDL) return false
CorrectionAggressionGate.kt:182  if (input.bg >= input.targetBg + REBOUND_BG_MARGIN_MGDL) return false
```

### Measured cost of the cliffs

On 2026-08-04 the evening produced four correction cycles totalling 24.77 U of SMB over seven
hours. The third and largest — 9.98 U of SMB plus 6.30 U/h basal, driving IOB to 13.23 and ending
at BG 58 — was classified `FULL`, with every guard released, because the preceding trough was
**78 mg/dL**.

Three mg/dL separated the largest over-correction of the night from a rebound classification.

The same pattern appears in the sensitivity path (ADR 0002/0003): a small input change produces a
large output change, and the system oscillates. Cliffs are the common shape.

The cliffs also interact badly with sensor noise. The 2nd user's CGM exceeds 15 mg/dL per 5 min on
6.6 % of intervals against 2.8 % for the 1st user; on that trace, ±3 mg/dL of noise flips the whole
correction regime.

## Decision

Replace the three-bucket tier with a **continuous authority scalar in [0, 1]**, computed once per
tick in stage 3 of ADR 0004, and multiplied by every channel that proposes a dose.

The existing tiers become points on the curve, not states:

| Current tier | Equivalent authority |
|---|---|
| `FULL` | 1.0 |
| `MODERATE` | ≈ 0.6 |
| `REBOUND_GUARD` | ≈ 0.3 |

Both current cliffs become smooth transitions:

- rebound proximity: instead of `minBgLookback75m < 75`, a ramp over roughly 70 → 90 mg/dL;
- target margin: instead of `bg >= target + 30`, a ramp over roughly target+20 → target+60.

Keep `Tier` as a **derived label for logs and UI only**. Nothing may branch on it.

### Companion rule — weight the authority by hypothesis age

A young hypothesis opens only part of its authority. This is the direct answer to the measured
lag in the classifier: on 2026-08-04, the UAM layer labelled the post-hypo rebound as `MEAL` for
about 30 minutes, then flipped to `POST_HYPO`. When it flipped, doses collapsed on their own from
1.20 U to 0.03–0.31 U.

The guard works. It arrives after the insulin. The classifier cannot be made faster — the
signatures are not separable (see README, "what was deliberately not decided") — so the engagement
must be made slower while the belief is young. This is standard robust control: reduce gain when
the estimate is uncertain.

## Calibration constraint

Simulation of a *pure widening* of the rebound classification (thresholds 85/60 and 90/70, with no
continuity and no age weighting) gave:

| Package | 85/60 | 90/70 |
|---|---|---|
| 2026-08-04 (12.3 % > 180, 4.6 % < 70) | −25.4 % SMB | −33.4 % |
| 2026-08-03 (**95.4 % time in range**) | **−15.6 %** | **−24.5 %** |
| 2026-08-02, 2nd user (13 % < 70) | −44.5 % | −57.2 % |

Removing a quarter of the insulin from a day that scored 95 % is not acceptable. The authority
curve must be calibrated so that the 2026-08-03 package moves by **less than 5 %** of total SMB.
That is the binding constraint on the shape of the ramps.

## Acceptance criteria

- 2026-08-03 replay: total SMB within 5 % of recorded.
- 2026-08-04 replay: reduction concentrated in the four evening cycles, not spread across the day.
- No tick where the authority changes by more than a configured maximum from the previous tick.
- `grep` shows no branch on `Tier` outside logging and UI.

## Consequences

- This is where the measured benefit is, and it is also the change most likely to need two or
  three calibration rounds on the corpus. Budget for that.
- The scalar makes the ×0.3 cap of ADR 0006 a point on a curve rather than one more constant.
