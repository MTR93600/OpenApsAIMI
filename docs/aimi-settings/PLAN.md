# AIMI settings: simplification plan

Branch `aimi-settings-audit`. Read-only analysis of 2026-11-15: a key map (`aimi_keys_map.csv`,
296 keys, with readers, writers and a verdict per key), a PKPD review, a Control Center review and a
comparison with Trio. This file records the decisions taken with the owner and the order of work.

Not medical advice. Steps marked "changes dosing" must be reviewed and watched after release.

## Decisions taken with the owner

- **PKPD, one source.** The DIA and peak that are logged must be the ones that dose. Today
  `OpenAPSAIMIPlugin` resolves the kinetics with `causalPosterior = null`, and
  `DetermineBasalAIMI2.buildIntelligenceSnapshot` resolves them again with the causal context.
  Fix: one resolution, used for dosing and logged. The dosing path takes the causal posterior of the
  **previous tick** (it is computed inside `DetermineBasalAIMI2`, after the plugin resolves the
  kinetics). Changes dosing.
- **P1 guards (owner approved 2026-11-15, step P1b).** The DIA and peak governor switches are the
  field off switch of the context shift (switch off = no shift). The dosing DIA may move at most
  0.25 h per tick and the peak at most 2 min per tick (since P1c: per 5 minutes, see below). The
  stored context is not used after 15 min.
  Done in P1b:
  - `OApsAIMIDiaGovernorEnabled` and `OApsAIMIPeakGovernorEnabled` stay **real keys** (not
    constants), because they are now the field off switch of the context path. The key map's earlier
    "hardwire" target for them no longer applies; later steps (P3, S3) must keep them. Both are on
    the PK/PD Expert screen: the peak switch was there already, the DIA switch was added in P1b
    (it was reachability debt; the debt list shrank by one).
  - The limit (`DosingKineticsRateLimiter`) holds for every change of the dosing DIA and peak, not
    only the context: a change of the profile insulin also reaches its new DIA and peak step by step
    (DIA 5 h to 9 h takes 16 ticks). No limit on the first tick or after more than 15 min without a
    tick. The export logs the target (`effective_dia_target_h`, `effective_peak_target_min`, also in
    the governor blocks) next to the limited value that dosed (`effective_*`); the adaptation status
    shows the limited value.
  - *Open for the owner:* skip the limit when the profile insulin itself changes.
- **P1c, the limit counts time, not ticks (owner approved 2026-11-15).** In P1b the step was per
  loop run, so on a 1-minute CGM (Libre 3, ONE+) or with extra loop runs the guard was up to 5 times
  looser in real time. Now the allowed step is proportional to the time since the last dosed values:
  0.25 h of DIA and 2 min of peak per 5 minutes (`step = base x gap / 5 min`). On a 5-minute loop
  with regular readings the step is about the same as in P1b: exactly one full step for a 5:00
  gap, a little less or more when the loop run comes early or late (4:50 gives 0.97 of a step, 5:20
  gives 1.07), the same on average. DIA 5 h to 9 h still takes about 80 min. On a 1-minute loop each
  tick moves 1/5 of the step, so the same change also takes about 80 min. Two calls close together
  share one step. After more than 15 min there is still no limit, and none on the first tick. A gap
  of 0 or less allows no move (after a negative gap the time starts again from that call).
  **At most one full step per call (owner choice, prudence first):** after 1 or 2 missed readings
  (a gap of 10 or 15 min) one call still moves at most 0.25 h of DIA and 2 min of peak, as in P1b.
  Summary: more prudent on 1-minute CGMs; the same as P1b on 5-minute CGMs, also after missed
  readings.
- **Automatic writers follow every change.** Whenever a key is removed, made a constant, re-scaled
  or derived, the Advisor, TPO, the tuning engine and the auditor are updated in the same step, so
  none of them writes or recommends something without effect. A final step (A1) lists what they can
  still propose.
- **PKPD, the profile insulin is the source.** The seed, the anchor and the bounds of the PKPD
  learner come from the insulin of the running profile (`iCfg.dia`, `iCfg.peak`), not from AIMI
  keys. Bounds: DIA from 0.75 x to 1.25 x the profile DIA, peak from `max(35, 0.75 x profile peak)`
  to 1.35 x the profile peak (35 is the hard limit of the insulin editor; a peak of 35 is valid, the
  owner uses a modified insulin with a 28 min peak). When the profile insulin changes, the learned
  state starts again from the profile values. The keys `OApsAIMIPkpdInitial*`, `OApsAIMIPkpdAnchor*`
  and `OApsAIMIPkpdBounds*` and the insulin presets of the PKPD screen go away. Changes dosing.
- **PKPD learning must be faster.** Field data (24 h, 286 ticks): 12 accepted steps, each capped at
  0.0017 h (NORMAL pace 0.5 h/day x 5 min), and each pulled back about 0.0007 h toward an anchor below
  the learned value. Net at most about 0.02 h/day. Fix in this order: anchor = profile insulin (no
  more pull against learning), a faster pace (about 2 h/day and 20 min/day) as the default, a larger
  gain. Then check daily exports. If it still does not converge, design an episode-based learner
  (corrections without carbs, 3-5 h windows). Changes dosing.
- **Pace of existing wizard users (owner decided 2026-11-15, done in P1b).** The old PK/PD wizard
  always saved NORMAL (0.5 h/day, 5 min/day) without asking. A one-shot migration
  (`PkpdLearningBounds.migrateOldWizardPaceOnce`, flag `BooleanNonKey.OApsAIMIPkpdPaceMigrated`,
  not exported) moves exactly the stored pair 0.5 / 5.0 to FASTER (2.0 / 20). Any other stored value
  is kept, also when only one of the two matches. It runs once, so a user who picks NORMAL after the
  update keeps it. Changes dosing for the users it moves. Restore cases: an import never clears the
  flag, so restoring an old backup (0.5 / 5.0) on the same phone keeps that pace; on a new phone or
  fresh install the flag starts false, so a NORMAL pace picked on purpose after the update and
  restored there is moved to FASTER without telling the user. *Open for the owner:* make the flag
  exportable, so a backup made after the update keeps a deliberate NORMAL.
- **PKPD keeps** the ISF fusion (one prudence control) and the trajectory part (peak trajectory
  nudge, `DynIsfTrajectory*`). Everything else is derived, internal, a constant, or deleted.
- **Control Center: replace.** Its family levels write fixed ladders into dose limits, the level read
  back is not the level applied, and `readAimiBehaviorRuntimeProfile` reads the display level on every
  tick, so a display calculation changes dosing. Keep the before/after preview with a confirm dialog.
- **Settings: reorganise the Trio way**, keep what is needed, verify every application path:
  Essentials (dose limits with the value in use, patient inputs incl. age) - feature cards (on/off,
  one intensity, dependent settings only when on) - What AIMI learned (read only) - Changes (the
  existing audit) - Expert with search. One setting row everywhere: label, one-line hint, a "?"
  sheet that starts with "Default: X" and ends with warnings, bounded values.
- **No setting is lost in the reorganisation.** Every key that is kept (not deleted as dead, not
  turned into internal state) must stay reachable by the user after each step: on a page, in a
  feature card, or at least in Expert with search. Keys keep their storage names, so stored values
  survive the update. This is checked by a test (all kept keys reachable from the settings tree;
  the manifest reachability debt may only shrink), not only by review. The reorganisation is only
  worth doing where it makes the settings easier to understand.
- **Protected families**: WCycle, inflammatory diseases (thyroid, endometriosis, CFRD, Verneuil),
  learners and machine learning, physio and the tree. Never delete their keys without the owner.
  Their dead keys (`AimiPhysioSleepDataEnable`, `AimiPhysioHRVDataEnable`, `AimiPhysioDebugLogs`,
  `AimiPhysioLLMProvider`, `AimiEndometriosisHormonalSuppression`, `AimiEndometriosisFlareDuration`,
  `PregnancyDueDate`) stay until the owner decides.
- **Not decided yet, do not change**: the NGR default (unknown age), the meal factor default (50),
  `PkpdEnabled`, `TrajBridgeBasalSurvives` and Cosine gate defaults.
- **Open for the owner (from step P2 review, not decided):**
  - *DIA and peak governors.* P2 moves only the learner bounds onto the profile insulin. The governors
    (on by default, they also clamp with PK/PD off) keep the fixed envelope DIA 4-24 h, peak
    30-240 min (the old key defaults; values a preset wrote are no longer read). Moving them to the
    profile bounds would change dosing for every user and needs the owner's yes.
- **Open for the owner (from step P3, not decided):**
  - *Exercise damping and the Control Center.* Since P3 `SmbDamping` floors the exercise damping at
    0.5. The Control Center ladder still writes 0.30 and 0.45 on its two lowest rungs, which now
    both act as 0.5. P3 did not change the ladder (raising a rung would change dosing on that rung);
    step C1 takes the ladders apart and should drop or raise these rungs.
    *Done in C1:* the ladder keeps its five positions (0.30, 0.45, 0.60, 0.72, 0.85), so a stored
    value finds the same rung and a step moves the same number of rungs as before, but a step never
    writes less than 0.5: the two lowest rungs both write 0.5. The effective damping after every
    step is the same as before C1, also from an old stored 0.30 (one step up acts as 0.5, two give
    0.60).
  - *Late fat damping and the Control Center (from C1 review, not decided).* `SmbDamping` floors
    the late fat damping at 0.5 in the same way, so the 0.40 rung of the Stability ladder
    (0.40, 0.55, 0.70, 0.80, 0.90) acts as 0.5 and shows a value with no effect. C1 did not change
    it (raising the rung changes dosing on that rung).
- **Open for the owner (from step C1, not decided):**
  - *Stored values of the new constants.* A user who had changed `OApsAIMIPeakGovernorLearnedWeight`
    (it was on the PK/PD Expert screen) or one of the three hidden switches now gets the old default.

- **Open for the owner (from step T1 review, not decided):**
  - *"One rung at most" is read per step.* A TPO pack that asks for 2 steps (post-hypo STRONG,
    exhausted MODERATE/STRONG, exhausted hypo floor) still moves 2 rungs (Max SMB 1.80 -> 1.00), as
    before T1. Capping at 1 per pack would make TPO less protective. Say if it should be per pack.
  - *Above the ladder a step down goes to the top rung* (Max SMB 3.0 or 5.0 -> 2.40; before T1 it
    snapped to the top rung and went one more, 1.80). Below the ladder it cuts 15 % (0.30 -> 0.255).
  - *Below the ladder a step up first goes to the lowest rung* without using a step, the same size
    as before T1 (tube hypo floor 70 with 2 steps -> 80). Only the old lowering above the top rung
    (90 -> 88) is gone.

- **Decided by the owner (from step T1 review, done in step T2, 2026-11-15):**
  - *Ceiling of automatic meal factor raises: 100 (neutral).* The Advisor raise and the Tuning
    meal-rise raise (shared constant `AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING`) stop at 100
    instead of 120. A suggestion never proposes a meal factor above 100 (95 -> 100, not 105); a
    value at or above 100 gets no raise proposal and is not lowered by it. Above 100 only the user,
    by hand.
  - *Poor-sleep STRONG pack: no change.* It keeps the T1 behaviour: it only lowers an exercise or
    late fat damping value above 0.85 / 0.90 to that ceiling, so on the defaults it proposes
    nothing. It does not lower the defaults (no new dosing change).
  - *Meal factors the old TPO wrote on the 1.0 scale: one-shot repair.* Every stored meal factor
    (`OApsAIMIMealFactor`, BF, lunch, dinner, high carb, snack, sleep) below 2 goes back to its key
    default (50, sleep 60) once; any other value is kept. Done flag `aimi_meal_factor_scale_repaired`
    (exported). The AIMI plugin start runs it and sets the flag (Android and iOS). The preference
    migrations run it too, on Android start-up and on every import, but only while the store has no
    flag, and they never write the flag: an old backup is repaired on import, a backup made after
    the repair keeps its values. Each repair is logged.
  - *Autonomy mode follows the Autodrive switches again (C1 follow-up).* The profile dosing uses
    keeps the neutral display levels of C1, but its autonomy mode comes from the real Autodrive
    switches, with the same mapping as before C1: Autodrive off gives Observation, on but not
    authoritative gives Recommendations or Assisted (hyper trajectory release), authoritative or
    recursive belief authority gives full authority. With the key defaults (all on) nothing changes.

- **Open for the owner (from step T2 review, not decided):**
  - *ML correction limit with Autodrive off or not authoritative: 0.55 / 0.72 or 0.484 / 0.634?*
    The step asked for the pre-C1 numbers 0.484 (Autodrive off) and 0.634 (on, not authoritative)
    and also asked to keep every display level neutral. These two cannot both hold: before C1 the
    numbers held a x0.88 factor that came from the old default protection level 1, and protection
    level is a display level that stays neutral (2). T2 therefore gives 0.55 (Observation), 0.72
    (Recommendations), 0.88 (Assisted) and 1.0 (authoritative), which is a little less prudent than
    before C1 for users with Autodrive off or not authoritative. Confirm 0.55 / 0.72, or say to add
    a fixed x0.88 to the autonomy part only for the non-authoritative modes (no display level comes
    back). The code is not changed until the owner answers.

## Order of work (one green commit per step)

| Step | What | Dosing |
|---|---|---|
| P1 | One kinetics resolution for dosing and log, causal posterior of the previous tick | changes |
| P1b | Guards on the P1 context shift: governor switches are its off switch, per-tick limit of the dosing DIA (0.25 h) and peak (2 min) (per 5 minutes since P1c), one-shot move of the old wizard pace to FASTER | changes (more prudent) |
| P1c | The P1b limit counts time: 0.25 h and 2 min per 5 minutes since the last tick, so 1-minute CGMs get the same real-time guard | changes (more prudent on 1-min CGMs; about the same as P1b on 5-min, at most one step per call) |
| P2 | Profile insulin as seed/anchor/bounds, re-seed on insulin change, faster pace, remove Initial/Anchor/Bounds keys and presets, migration | changes |
| P3 | PKPD cleanup: state keys out of settings, dead PKPD keys and constants (the Advisor rules that wrote Initial* were removed in P2 with the keys), exercise damping floor (the dead DIA governor call in `RunT9PhysioEarlyPkpd` was removed in P1), PKPD status card | small (floor) |
| T1 | TPO: meal factor ladder on the percent scale; a "step down" never raises a value and moves one rung at most; Advisor and Tuning on the percent scale | changes (protective size as designed) |
| C1 | Control Center disarmed: dose limit keys out of the ladders, manifest writer facts, no write on open; the runtime profile no longer reads the display level (neutral constants) | small |
| T2 | T1/C1 follow-ups: one-shot repair of meal factors below 2 (old TPO scale), automatic meal factor raises stop at 100, dosing autonomy mode from the real Autodrive switches again | small (repaired users get the default meal SMB back; raises stop lower; ML correction limit lower with Autodrive off or not authoritative) |
| S1 | Settings row component (hint, "?" sheet with default and warnings, bounded values) and the Essentials page | none |
| S2 | Feature cards with progressive disclosure, What AIMI learned page, Expert with search; Control Center screen removed | none |
| S3 | Delete the dead keys (not the protected ones), move internal state out of the settings, prefs schema, migration | none |
