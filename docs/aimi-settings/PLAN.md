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
  0.25 h per tick and the peak at most 2 min per tick. The stored context is not used after 15 min.
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
  - A tick is one loop run, not a fixed time. With a 5-minute CGM, 16 ticks are about 80 min. With a
    1-minute CGM (Libre 3, ONE+) the loop can run about every minute, so the same 16 ticks can take
    about 16 min, and the guard is up to 5 times looser in real time.
  - *Open for the owner:* (1) skip the limit when the profile insulin itself changes; (2) should the
    limit be per 5 minutes (step scaled by the time since the last tick, at most one full step)
    instead of per loop run, so 1-minute CGM users get the same real-time guard?
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

## Order of work (one green commit per step)

| Step | What | Dosing |
|---|---|---|
| P1 | One kinetics resolution for dosing and log, causal posterior of the previous tick | changes |
| P1b | Guards on the P1 context shift: governor switches are its off switch, per-tick limit of the dosing DIA (0.25 h) and peak (2 min), one-shot move of the old wizard pace to FASTER | changes (more prudent) |
| P2 | Profile insulin as seed/anchor/bounds, re-seed on insulin change, faster pace, remove Initial/Anchor/Bounds keys and presets, migration | changes |
| P3 | PKPD cleanup: state keys out of settings, dead PKPD keys and constants (the Advisor rules that wrote Initial* were removed in P2 with the keys), exercise damping floor (the dead DIA governor call in `RunT9PhysioEarlyPkpd` was removed in P1), PKPD status card | small (floor) |
| T1 | TPO: meal factor ladder on the percent scale; a "step down" never raises a value and moves one rung at most; Advisor and Tuning on the percent scale | changes (protective size as designed) |
| C1 | Control Center disarmed: dose limit keys out of the ladders, manifest writer facts, no write on open; the runtime profile no longer reads the display level (neutral constants) | small |
| S1 | Settings row component (hint, "?" sheet with default and warnings, bounded values) and the Essentials page | none |
| S2 | Feature cards with progressive disclosure, What AIMI learned page, Expert with search; Control Center screen removed | none |
| S3 | Delete the dead keys (not the protected ones), move internal state out of the settings, prefs schema, migration | none |
