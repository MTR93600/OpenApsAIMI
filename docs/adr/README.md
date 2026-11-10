# AIMI Architecture Decision Records

Decisions taken after a data-driven audit of the AIMI dosing path, August 2026.

## Why these ADRs exist

A user reported very different loop behaviour from another user on the **same build**
(`4.0.0.0-dev.AIMI.030726`). The investigation started as a preference comparison and ended on a
shared root cause in the sensitivity path. These ADRs record what was measured, what was decided,
and how to verify each change.

## Evidence corpus

All numbers in these ADRs come from AIMI support packages (`AIMI_Decisions_Last24h.jsonl` +
`Diagnostic_Report.txt`).

| Property | Value |
|---|---|
| Packages available | 38 (April → August 2026) |
| Days usable for gate analysis | 20 (those exporting `correction_aggression_tier`) |
| Users | 2 |
| Declared carbs (COB > 0) | **0 packages out of 38** |

Two caveats that apply to every measurement below:

1. **Selection bias.** Support packages are exported when something goes wrong. The corpus
   over-represents bad days and cannot be read as a normal-use distribution.
2. **No glucose simulation.** Every "insulin removed" figure is insulin accounting on recorded
   decisions. None of these ADRs claims what blood glucose would have resulted.

## Reading order

The ADRs are ordered by implementation sequence, not by importance. Each one assumes the previous
ones are in place.

| Order | ADR | Title | Risk | Behaviour change |
|---|---|---|---|---|
| 1 | [0001](0001-replay-harness.md) | Replay harness with the support corpus as fixtures | none | no |
| 2 | [0003](0003-dynisf-cache-read-path.md) | Fix the dynamic ISF cache read path | medium | yes |
| 3 | [0002](0002-sensitivity-three-levels.md) | Separate static / dynamic / command sensitivity | medium | yes |
| 4 | [0004](0004-classify-before-dose.md) | Classify before dosing (tick order) | low | enables |
| 5 | [0005](0005-continuous-authority.md) | Continuous authority scalar instead of tiers | medium | yes |
| 6 | [0006](0006-autodrive-consumes-authority.md) | Autodrive V3 consumes the dose authority | low | yes |
| — | [0007](0007-isf-chain-roles.md) | The ISF chain carries a dosing policy; the meal model is discarded | — | two small fixes applied |
| — | [0008](0008-isf-decision-architecture.md) | One variable per question: sensitivity, appearance, urgency | — | step 1 in shadow |

**Implementation order is not ADR order.** 0003 ships before 0002: it is the narrower change and it
stabilises the value that 0002 then renames and rewires. 0006 stays last despite a good
cost/benefit ratio — its correlation with time in range is r = +0.11 over 20 days, so promoting it
would repeat the sampling error this audit already made twice.

ADR 0002 and 0003 are the root cause. ADR 0004 to 0006 address amplifiers that turn a single
over-correction into a multi-hour oscillation.

## What was deliberately not decided

- **Late fat/protein detection.** `isLateFatProteinRise` is provably inoperative (its `lowIOB`
  test compares an IOB against a max-SMB preference), but fixing it brings no measurable benefit:
  the signatures of a real meal and of a fat tail are not separable from CGM + IOB alone. Measured
  on 2026-08-04: real meal at 16:01 was +50 mg/dL over 45 min with IOB 8.34 and a falling IOB
  slope; the fat tail at 18:31 was +46 mg/dL over 40 min with IOB 6.08 and a falling IOB slope.
  Recorded as technical debt, not as a fix.
- **Widening the post-hypo rebound classification.** Simulated at thresholds 85/60 and 90/70.
  It removes 15.6 % to 24.5 % of SMB on a day that scored 95 % time in range, and captures true
  meal onsets as false positives. Rejected on evidence.
- **Reducing the option surface** (83 AIMI boolean keys, 96 boolean keys wired into the preference
  screen, 2 of them dead). Real, but structural and not on the critical path. To be revisited after
  0002–0006.
