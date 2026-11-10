# ADR 0004 — Classify before dosing

**Status:** proposed
**Depends on:** [0001](0001-replay-harness.md)
**Behaviour change:** none by itself — it enables 0005 and 0006

## Context

The tick currently doses, then classifies. The function that produces the correction-aggression
tier is named after the fact:

```
DetermineBasalAIMI2.kt:13223  private fun runPostAutodrivePostHypoClassification(...)
                              /** Après la branche Autodrive V3 : ... */
```

`refreshPostHypoDeliveryAuthorityForTick` is called at lines 15846 and 16025; the Autodrive V3
branch runs at line 16067. In this specific ordering the authority happens to be available before
Autodrive — but the naming, the doc comments and the call graph all encode the opposite intent, and
any reordering silently breaks it.

The consequence is that guards are advisory. `PostHypoDeliveryAuthority` exposes `capSmbU`, and
exactly one call site consumes it (`DetermineBasalAIMI2.kt:4690`). Every other channel is free to
ignore the classification, and does.

## Decision

Make the tick a fixed five-stage pipeline, with the stage boundary enforced by types rather than by
call order:

```
1. observe   → immutable tick state (BG, deltas, IOB, COB, sensors)
2. believe   → hypothesis + confidence + age
3. authorise → one DoseAuthority for this tick, computed once
4. propose   → each channel returns a DoseProposal (amount, owner, hypothesis, urgency)
5. commit    → one arbitration + one delivery point
```

No stage may read a value produced by a later stage. `DoseAuthority` is constructed in stage 3 and
passed as a parameter to stage 4 and 5, rather than being read from mutable plugin state
(`lastPostHypoDeliveryAuthority`, `correctionAggressionDecision`).

Rename `runPostAutodrivePostHypoClassification` to reflect stage 2/3, not "post Autodrive".

## Scope of this ADR

This ADR delivers **only the reordering and the parameter passing**. It does not change any
threshold, any cap, or any dose. The replay must show a byte-identical decision stream on all
corpus packages.

Making the commit point structurally unique — so that no channel can write `rT.units` outside
stage 5 — is deliberately **out of scope**. `finalizeAndCapSMB` is documented as "the universal
exit" (`DetermineBasalAIMI2.kt:15298`) and already has at least two documented bypasses
(`:2722` Meal Advisor, `:15020` legacy meal prebolus). Closing those is a separate, larger change
that should follow once 0002–0006 are proven.

## Acceptance criteria

- Replay on every corpus package produces an identical decision stream. Any difference is a defect
  in the refactor, not an improvement.
- No stage-3 or later code reads mutable plugin state for the authority or the tier.
- Tests fail if a stage-2 function is called after a stage-4 function.

## Consequences

- Zero behaviour change makes this the safest step in the set, and it is the precondition for 0005
  and 0006 to be more than local patches.
- It touches a large surface of a 17 000-line file. Do it after 0001 exists, never before.
