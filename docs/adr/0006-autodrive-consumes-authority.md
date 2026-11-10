# ADR 0006 — Autodrive V3 consumes the dose authority

**Status:** proposed
**Depends on:** [0004](0004-classify-before-dose.md), [0005](0005-continuous-authority.md)
**Behaviour change:** yes

## Context

Autodrive V3 is not one channel among several. On 2026-08-04 it owned **56.39 U of the 56.76 U**
of SMB delivered — 99 %. Arbitration between channels is not the problem; a single dominant channel
ignoring the guards computed for it is.

The wiring already exists. `PostHypoDeliveryAuthority` returns `maxSmbU = 0.0` when the tick is a
post-hypo rebound, and Autodrive consumes it:

```
DetermineBasalAIMI2.kt:4690   val v3Smb = lastPostHypoDeliveryAuthority.capSmbU(v3SmbRaw)
```

It does not fire, because `PostHypoDeliveryAuthority.evaluate` requires three conditions and the
third is defeated upstream:

```kotlin
if (input.gate?.tier != Tier.REBOUND_GUARD) return INACTIVE                       // satisfied
if (input.patientMode != PatientMode.POST_HYPO_RECOVERY) return INACTIVE          // satisfied
if (CorrectionAggressionGate.hasIndependentMealEvidence(input.aggressionInput))
    return INACTIVE                                                              // <- fires
```

On every affected tick the UAM layer labels the rebound as a meal, which makes
`hasIndependentMealEvidence` true:

```
20:51  BG=127  SMB=0.71  POST_HYPO_RECOVERY  uam=MEAL       false_meal=False
21:06  BG=145  SMB=1.16  POST_HYPO_RECOVERY  uam=MEAL       false_meal=False
21:21  BG=186  SMB=0.86  POST_HYPO_RECOVERY  uam=MEAL       false_meal=False
21:26  BG=183  SMB=0.22  POST_HYPO_RECOVERY  uam=POST_HYPO  false_meal=True    <- flip
21:31  BG=178  SMB=0.03  POST_HYPO_RECOVERY  uam=POST_HYPO  false_meal=True
```

## Measured effect

Autodrive SMB delivered on ticks the system itself classified `REBOUND_GUARD`:

| Package | Amount | Share of that day's SMB |
|---|---|---|
| 2026-07-26 | 14.79 U | 25.0 % |
| 2026-08-04 | 10.96 U | 19.3 % |
| 2026-08-02 (2nd user) | 3.99 U | 16.7 % |
| 2026-07-25 | 2.26 U | 7.2 % |
| 2026-07-31 | 1.35 U | 4.6 % |
| 15 other days | 0.00 U | 0 % |

**Honest caveat on priority.** Across the 20-day corpus, the correlation between this defect firing
and time in range is **r = +0.11** — none. Days where it fires average 88 % time in range against
81 % for days where it does not; they are also the higher-insulin days (40.1 U against 28.8 U).
The genuinely bad days in the corpus (51 % time in range, 49 % above 180) never trigger it at all.

This is a real defect — insulin delivered against the system's own classification — but it is
**not** the explanation for the bad days. It is fixed because it is wrong, not because it is the
dominant failure mode.

## Decision

1. **Do not treat a rise as independent meal evidence while a rebound is suspected.** In
   `hasIndependentMealEvidence`, when `postHypoHint == REBOUND_SUSPECTED`, drop
   `isConfirmedHighRise` from the accepted evidence. Keep COB, declared meal mode, and a recent
   carb estimate — those are genuine external evidence. A strong rise out of a hypo is the
   definition of a rebound, not proof of a meal.
2. **Cap, do not cut.** Replace `maxSmbU = 0.0` with the authority scalar of ADR 0005 (≈ ×0.3 at
   full rebound). At 05:20 on 2026-08-04 the user was at BG 185 with a real hyper to treat;
   suppressing SMB entirely is the wrong answer and is how guards end up disabled by users.
3. **Every channel multiplies by the authority, not just Autodrive.** With 0004 in place this is a
   parameter, not a lookup.

## Expected effect on the corpus

| Package | Cut (×0) | Cap (×0.3) |
|---|---|---|
| 2026-08-04 | −19.3 % | −13.5 % |
| 2026-08-02 (2nd user) | −16.7 % | −11.7 % |
| **2026-08-03 (95.4 % TIR)** | **0 %** | **0 %** |

The zero on the reference day is the reason this ADR is low risk: it can only act on ticks the
system has already labelled a rebound, and that day has none.

## Acceptance criteria

- 2026-08-03 replay: decision stream unchanged.
- 2026-08-04 replay: the 18 affected doses are capped, and the reduction matches −13.5 % ± 1 point.
- A test covers each of the four `INACTIVE` exits of `PostHypoDeliveryAuthority.evaluate`,
  including a strong post-hypo rise with no COB and no declared meal, which must stay active.
- `PostHypoAggressiveRiseExit` (BG ≥ target+30 **and** delta > 15 mg/dL per 5 min) requires two
  consecutive ticks. On the 2nd user's trace, 6.6 % of intervals exceed 15 mg/dL per 5 min from
  sensor noise alone.

## Consequences

- Users prone to rebound will see higher and longer post-hypo peaks. State this before shipping,
  not after — it is the correct trade against a 13 % time below 70, but it will be reported as a
  regression if discovered.
- `PostHypoDeliveryAuthority.Decision` is not exported to `AIMI_Decisions.jsonl` today, which is
  why the failing condition had to be inferred rather than read. Export it as part of this ADR:
  `active`, `reason_tag`, `max_smb_u`, `smb_before_cap_u`, `smb_after_cap_u`.
