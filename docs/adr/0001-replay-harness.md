# ADR 0001 — Replay harness with the support corpus as fixtures

**Status:** proposed
**Depends on:** nothing
**Behaviour change:** none

## Context

Every finding in ADR 0002–0006 was produced by replaying support packages with throwaway Python
scripts outside the repository. That work is not reproducible, not reviewable, and not runnable in
CI.

It also caused two wrong conclusions during the audit, both of which survived several rounds of
discussion before a wider replay corrected them:

- A defect was reported as "zero impact on your configuration" after being measured on one good
  day. Replayed on a second day, the same change removed 10.96 U (19.3 % of that day's SMB).
- A defect was ranked first on the strength of 3 days. Replayed on 20 days, its correlation with
  time in range is r = +0.11 — that is, none.

An audit that can be wrong twice on sample size needs a corpus, not more opinions.

## Decision

Add a replay harness under `plugins/aps/src/test/` that:

1. Reads an `AIMI_Decisions_Last24h.jsonl` package and rebuilds a per-tick input record.
2. Runs a pure function under test against every tick.
3. Emits a comparable summary: total SMB, SMB per `origin_owner`, SMB per
   `correction_aggression_tier`, ticks impacted, time in range bands.

Ship a small anonymised subset of the corpus as test resources. Anonymise by dropping the
`Diagnostic_Report.txt`, the Nightscout URL, and the emergency SOS phone number; keep only the
decision stream.

## Acceptance criteria

The harness must reproduce these figures from the recorded packages, as a self-check:

| Package date | Metric | Expected |
|---|---|---|
| 2026-08-04 | total SMB | 56.76 U |
| 2026-08-04 | Autodrive SMB while tier = `REBOUND_GUARD` | 10.96 U |
| 2026-08-03 | total SMB | 27.46 U |
| 2026-08-03 | Autodrive SMB while tier = `REBOUND_GUARD` | 0.00 U |
| 2026-08-02 (2nd user) | Autodrive SMB while tier = `REBOUND_GUARD` | 3.99 U |

## Non-regression rule for all later ADRs

> **The 2026-08-03 package scored 95.4 % time in range with 0 % below 70. No change may alter its
> decision stream unless the ADR states the expected delta and justifies it.**

This is the single criterion that made it possible to reject the rebound-widening option and to
keep ADR 0006.

## Consequences

- Every later ADR becomes measurable before merge instead of after a support package arrives.
- Adds test fixtures to the repository; keep the subset small (5–6 packages is enough to cover
  a hypo day, a hyper day, a good day, and both users).
- Does not touch any dosing path.
