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
- **Protected families**: WCycle, inflammatory diseases (thyroid, endometriosis, CFRD, Verneuil),
  learners and machine learning, physio and the tree. Never delete their keys without the owner.
  Their dead keys (`AimiPhysioSleepDataEnable`, `AimiPhysioHRVDataEnable`, `AimiPhysioDebugLogs`,
  `AimiPhysioLLMProvider`, `AimiEndometriosisHormonalSuppression`, `AimiEndometriosisFlareDuration`,
  `PregnancyDueDate`) stay until the owner decides.
- **Not decided yet, do not change**: the NGR default (unknown age), the meal factor default (50),
  `PkpdEnabled`, `TrajBridgeBasalSurvives` and Cosine gate defaults.

## Order of work (one green commit per step)

| Step | What | Dosing |
|---|---|---|
| P1 | One kinetics resolution for dosing and log, causal posterior of the previous tick | changes |
| P2 | Profile insulin as seed/anchor/bounds, re-seed on insulin change, faster pace, remove Initial/Anchor/Bounds keys and presets, migration | changes |
| P3 | PKPD cleanup: state keys out of settings, dead PKPD keys and constants, Advisor rules that wrote Initial*, dead DIA governor call in `RunT9PhysioEarlyPkpd`, exercise damping floor, PKPD status card | small (floor) |
| T1 | TPO: meal factor ladder on the percent scale; a "step down" never raises a value and moves one rung at most; Advisor and Tuning on the percent scale | changes (protective size as designed) |
| C1 | Control Center disarmed: dose limit keys out of the ladders, manifest writer facts, no write on open; the runtime profile no longer reads the display level (neutral constants) | small |
| S1 | Settings row component (hint, "?" sheet with default and warnings, bounded values) and the Essentials page | none |
| S2 | Feature cards with progressive disclosure, What AIMI learned page, Expert with search; Control Center screen removed | none |
| S3 | Delete the dead keys (not the protected ones), move internal state out of the settings, prefs schema, migration | none |
