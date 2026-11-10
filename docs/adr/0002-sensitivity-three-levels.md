# ADR 0002 — Separate static, dynamic and command sensitivity

**Status:** proposed
**Depends on:** [0001](0001-replay-harness.md), [0003](0003-dynisf-cache-read-path.md)
**Behaviour change:** yes

> **Ordering note.** 0003 ships first despite the higher number. It stabilises the value; this ADR
> then fixes what the value *means* and where it is stored.

## Context

`OapsProfileAimi.sens` is the field the whole oref maths treats as the **static baseline**
sensitivity. It is read at more than fifteen sites in `DetermineBasalAIMI2` under names that all
claim to be the profile value: `profileIsf`, `baseSensitivity`, `snapshotProfileIsf`.

It does not hold the profile value.

`OpenAPSAIMIPlugin.kt:1284`:

```kotlin
sens = profile.getIsfMgdl("OpenAPSAIMIPlugin") * physioMults.isfFactor, // ?? ISF Modulation
```

`ProfileSealed.kt:345-351` exposes two distinct methods, and this call site uses the second:

```kotlin
override fun getProfileIsfMgdl(): Double = toMgdl(isfBlocks.blockValueBySeconds(...))   // static

override fun getIsfMgdl(caller: String): Double =
    if (aps?.usingDynamicIsf() ...) aps.getIsfMgdl(this, caller) ?: ...                 // dynamic
    else getProfileIsfMgdl()
```

So `profile.sens` = **dynamic ISF × physiological factor**. A derived value is written into the
slot that represents its own input.

Downstream code then derives from it again:

```
DetermineBasalAIMI2.kt:1985  val earlySens      = ctx.profile.sens / earlyAutosensRatio
DetermineBasalAIMI2.kt:8846  val baseSensitivity = fusedSensitivity ?: profile.sens
DetermineBasalAIMI2.kt:8845  val dynSensitivity  = profile.variable_sens ?: profile.sens
```

The intent is visible and correct elsewhere: `calculateVariableIsf` (`OpenAPSAIMIPlugin.kt:737`)
correctly starts from `getProfileIsfMgdl()`. Only the storage and the read path are wrong.

## Measured effect

`profile_isf_mgdl` in `AIMI_Decisions_Last24h.jsonl` is `ctx.profile.sens`
(`DetermineBasalAIMI2.kt:1749`). One user's profile contains exactly two ISF values, 70 and 30
(`LocalProfile_isf_0`). The exported field over 24 h:

| Package | min | max | values < 15 mg/dL/U | consecutive-tick jumps ≥ ×2 |
|---|---|---|---|---|
| 2026-06-22 | 9 | 86 | 5 % | 3 (up to ×7.1) |
| 2026-07-22 | 4 | 74 | 2 % | 2 (up to ×11.4) |
| 2026-08-03 | 9 | 89 | 0 % | 6 |
| 2026-08-04 | 4 | 78 | 4 % | 12 (up to ×6.3) |
| 2026-08-02 (2nd user) | 5 | 92 | 4 % | 8 (up to ×4.6) |

A 23× spread on a two-valued profile.

### Why it matters for dosing

`eventualBG ≈ bg − iob × sens`. On 2026-06-22 the value sat at 9 mg/dL/U for the whole rise from
BG 186 to 301:

- 13:13 — BG 301, IOB 11.2 → eventual = 301 − 101 = **200**. The loop sees a persistent hyper
  despite 11 U on board and keeps dosing.
- 13:42 — value returns to 75 → eventual goes negative. Basal drops from **7.92 to 0.02 U/h in
  twelve minutes**.

This is the same structural defect that produces post-hypo rebound over-correction, seen from the
hyper side: not a missing guard, but an unstable reference feeding every prediction.

## Decision

Three named levels, each immutable within a tick, and never written back into one another:

| Level | Meaning | Source |
|---|---|---|
| `profileIsfMgdl` | user profile block, static | `Profile.getProfileIsfMgdl()` |
| `dynamicIsfMgdl` | derived from profile + TDD + PKPD + CGM geometry | `calculateVariableIsf` |
| `commandIsfMgdl` | dynamic × physiological factor, what the command actually uses | plugin |

`OapsProfileAimi.sens` carries **`profileIsfMgdl`**. Consumers that want the dynamic or command
value ask for it by name. `variable_sens` keeps its current role as the dynamic carrier.

## Migration

1. Add `profile_isf_static_mgdl` and `command_isf_mgdl` to the JSONL export **before** changing any
   read, so the corpus captures both under the current behaviour.
2. Change `OpenAPSAIMIPlugin.kt:1284` to `profile.getProfileIsfMgdl()`, and carry the physiological
   factor as a separate field rather than folding it into `sens`.
3. Walk the fifteen `profile.sens` read sites one by one. For each, decide explicitly which of the
   three levels it wants. Expect most to want the command value — that is fine, as long as it is
   the value they *ask for*.
4. Rename the exported field. Keep the old key populated for one release so existing analysis
   scripts do not break silently.

## Acceptance criteria

- On every corpus package, `profile_isf_static_mgdl` takes only values present in that user's
  profile blocks.
- No consecutive-tick jump ≥ ×2 on `profile_isf_static_mgdl`.
- `DetermineBasalAIMI2.kt:1985` (`profile.sens / earlyAutosensRatio`) is reviewed explicitly — it
  currently divides an already-modulated value by an autosens ratio.

## Consequences

- This changes dosing on every tick. It is the largest behaviour change in this ADR set and must
  go through a shadow period: export both values, compare, then switch.
- Every past support-package analysis that used `profile_isf_mgdl` as a baseline is wrong,
  including several performed during this audit.
- Fixing this before ADR 0005 is mandatory. A continuous authority scalar is pointless if the
  parameter it modulates moves by a factor of 23 within a day.
