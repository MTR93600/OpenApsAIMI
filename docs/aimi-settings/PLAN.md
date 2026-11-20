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
  - *Decided by the owner (step D1):* the limit also holds when the profile insulin itself changes.
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
- **P1d, the context shift needs PK/PD on (owner approved 2026-11-20).** Step P1 applied the causal
  DIA and peak shift to every user, also with PK/PD off. dev_OAPSAIMI never did: it resolved the
  dosing kinetics with no posterior. With the governors on by default and PK/PD off by default, the
  default setup therefore dosed differently from dev (DIA up to +1 h, peak up to +8 min), and the
  direction was not shown to be safe (after a hypo or exercise the longer DIA makes the prediction
  less pessimistic). Now the shift is applied only when PK/PD is on; with PK/PD off it is 0. The
  posterior is still read, and the log reason starts with `pkpd_off:`. With PK/PD on nothing changes.
  Not exact parity with dev: the time limit of P1c still smooths every other change of the dosing
  DIA and peak (profile insulin, physio, site), which dev applied at once. Changes dosing.
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
  restored there is moved to FASTER without telling the user. *Done in step D1 (owner decision):* the
  flag is exported, so a backup made after the update keeps a deliberate NORMAL.
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
  Owner decisions of 2026-11-15 on their seven dead keys (done in step S3b):
  - *Wire* `AimiPhysioSleepDataEnable` and `AimiPhysioHRVDataEnable` (default on, no change): off,
    the reading is ignored by every dosing path. Wire `AimiEndometriosisFlareDuration` (default 4 h):
    a declared pain flare ends by itself after that many hours.
  - *Delete* `AimiPhysioDebugLogs`, `AimiPhysioLLMProvider`, `AimiEndometriosisHormonalSuppression`
    and `PregnancyDueDate` (the Long key), with a migration; a stored old due date is first copied
    into `PregnancyDueDateString` when that text is empty.
- **Not decided yet, do not change**: `PkpdEnabled`, `TrajBridgeBasalSurvives` and Cosine gate
  defaults. (The NGR default for an unknown age and the meal factor default were decided in step D1.)
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
  - *Closed by S2:* the Control Center screen and its ladders are gone, so no rung writes 0.30, 0.45
    or 0.40 any more. A value stored earlier stays and still acts as 0.5 (floor in `SmbDamping`).
- **Open for the owner (from step C1, not decided):**
  - *Stored values of the new constants.* A user who had changed `OApsAIMIPeakGovernorLearnedWeight`
    (it was on the PK/PD Expert screen) or one of the three hidden switches now gets the old default.

- **Notes from step T1 review (behaviour as built):**
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

- **Decided by the owner (T2 follow-up, 2026-11-15):** the ML correction limit keeps 0.55 (Autodrive
  off), 0.72 (on, not authoritative), 0.88 (assisted) and 1.0 (authoritative). The old 0.484 / 0.634
  held a x0.88 from the Control Center display level, which no longer reaches dosing. The difference
  is at most about 0.003 U per SMB, and only with an ML model loaded.

- **Open for the owner (from step S1 review, not decided):**
  - *Learner badge.* The page has only the TPO, LIMITED and RAISED badges. The key map lists no learner
    that writes an Essentials key, so there is nothing to badge yet.
  - *Patient age.* `OApsAIMINightGrowthAgeYears` stays 1-25, default 14. The hint says "if 25 or
    older, set 25". Since step D1 an age never set means an adult (see "Decided by the owner (step
    D1)").
  - *Max SMB and max IOB.* The loop sets these limits again on every decision (SMB limit picked from
    both SMB keys by glucose and rise, lowered by exercise, physio and trajectory; max IOB moved by
    night growth support and trajectory). The page claims no value in use for them and says so. A
    "last decision" value would need a snapshot from the loop.

- **Decided by the owner (from step S1 review, done in step S3, 2026-11-15):**
  - *Levels of age and due date.* `OApsAIMINightGrowthAgeYears` and `PregnancyDueDateString` are SIMPLE
    in the manifest (they are on the Essentials page). On the night growth page the age is its own
    SIMPLE group, above the switch and the night window.
  - *The real temp basal cap on Essentials.* `ApsMaxCurrentBasalMultiplier` (default 4, storage name
    unchanged) is a dose limit row on the Essentials page, after the three max basal rows, with the
    unit "x profile basal". Its value is shown in use only when `applyBasalConstraints` caps with it
    (no meal mode, Autodrive does not act, T3C brittle mode off; one rule, `isSpecialBasalMode`, used
    by both and tested); otherwise it says "not in use now". The hint says: when no meal mode is on,
    Autodrive does not act and T3C brittle mode is off, a temporary basal is never higher than this
    factor times the profile basal; the loop can make this limit lower for a while; otherwise the max
    basal of the active mode is the limit. Known limit: the page shows the stored factor (the outer
    cap). Inside the decision the loop multiplies it by the physio basal factor, so the cap of one
    decision can be lower than the value shown; no dosing change in S3. It keeps its
    old place in the OpenAPS advanced settings. `OApsAIMIMaxMultiplier` stays on the page with its
    adaptive basal hint.

- **Notes from step S2:**
  - *Before/after preview.* After S2 every setting row on a card writes only its own key, so the
    before/after preview of the Control Center has no user in the settings. Add it back when a card
    gets a control that changes several keys at once.
  - *Protected-family dead keys.* Decided 2026-11-15, done in step S3b (see "Protected families").
  - *What the Advisor and TPO no longer propose.* The Control Center Advisor and the family bridge
    (the "move Meal capture up one step" style suggestions and their family write-back) are gone, and
    the AI coach notes no longer use the Control Center display levels. TPO starts a session with the
    same rule as before, now read from the Autodrive switches. No other Advisor, Tuning or TPO
    proposal changed.

- **Decided by the owner (step D1, 2026-11-15).** These were the open items of steps P1b, P2, T1 and
  S2. Done in step D1 (see below):
  1. *xDrip 1-minute switch:* deleted. The reader was lost in the port and the owner keeps "all
     readings" for every xDrip user, so the switch had no effect. Its stored value is removed.
  2. *Night growth support (NGR) with an age never stored:* treated as an adult, so NGR is off. The
     rule (`NightGrowthActivation`, used by the loop and the Essentials page): the NGR switch if it was
     stored, else (age stored and age under 18), else off; T3C brittle mode still turns NGR on. A stored
     age or a stored switch keeps the behaviour of before. Changes dosing.
  3. *Meal factors:* the default stays 50 (= 50 %, sleep 60) and the person can change it. The labels
     now say that the value is a percent of the SMB. No code or default change.
  4. *DIA and peak governors:* keep the fixed envelope DIA 4-24 h, peak 30-240 min. Moving them onto
     the profile bounds would change dosing for every user.
  5. *TPO "one rung at most" is read per step:* kept. A pack that asks for 2 steps still moves 2 rungs.
  6. *Guided meal mode screen:* keeps showing and editing the values of its four modes before the start.
  7. *Contraceptive:* also shown on the illness card, on the endometriosis page (same key, one more
     row). It stays on the women's cycle card, and search still opens that card.
  8. *Dosing DIA and peak limit:* also holds when the profile insulin itself changes (as built in P1b).
  9. *PK/PD pace flag:* exported, so a backup made after the update keeps a deliberate NORMAL pace.
  10. *`OApsAIMIActivityBasalCapFactor`:* stays ADVANCED.
  11. *Test repair:* `PreferenceKeyRoundTripTest` accepts the 14 Omnipod stored names that the iPhone
      Dash keys share on purpose with the Android keys, from the one allow list `SHARED_STORED_KEYS`.

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
| S3b | Protected-family dead keys (owner decision 2026-11-15): wire sleep, HRV and the flare duration; delete four keys with a migration (the old due date is copied first) | only when a person turns sleep or HRV off, or forgets to switch a flare off |
| D1 | Owner decisions on the open items: delete the xDrip 1-minute switch, NGR off when no age was ever stored, meal factor labels in percent, contraceptive also on the illness card, PK/PD pace flag exported, key round trip test repaired | changes for people who never stored an age nor the NGR switch (NGR off, before on) |
| D2 | NGR switch key default off, so the switch row shows what the loop does when neither the switch nor the age is stored (the case "stored age under 18, no stored switch" was fixed in A1) | none |
| S4 | Targets link: a tap on the Essentials targets opens the profile screen (new platform action slot, Android and iOS) | none |
| A1 | Final audit of the automatic writers (Advisor, TPO, tuning, auditor, learners); NGR switch row shows the state the loop uses; S4 review leftovers | none |

## Step S3 (done 2026-11-15, no dosing change)

- **32 dead keys deleted** from the key enums, each checked first by a repo-wide search of its enum
  name and its storage name (no reader, no writer, no screen): basal kicker, zero resume, anti stall,
  positive delta release, plateau band, R2 confident, `EnableBasal`; the night growth multipliers,
  clamp, rise slope, duration, eventual margin and decay; `FCLFactor`, Autodrive target, BG,
  deviation and V3 enhanced gater; force limits, log size, unstable mode state; the context provider
  and its four API keys. Their title strings and the old XML titles went too.
- **Stored values are removed** by a new preference migration (`aimiDeadKeysRetired`, names in
  `AimiRetiredSettings`): on Android start-up and on every import (an old backup writes none of them
  and lists none as unknown). The four context API keys are wiped this way. Only names are logged.
  iOS runs the migrations on an import only, so the AIMI plugin start also removes these names, on
  both platforms (`AimiRetiredSettings.removeStored`): an iPhone that imported an Android backup with
  a context API key before this build loses it too. The import already ignored names it does not know
  (it never writes them).
- **Internal state out of the settings:** the meal advisor trigger, its carbs estimate and time, the
  four legacy prebolus values and the context intents JSON are `INTERNAL_STATE` (no entry, no row, no
  audit row, no reset); storage names kept. No setting is written by the loop alone any more, so the
  audit screen lost its "runtime state" row kind.
- **Left on purpose:** `DEAD_KEYS` held only the seven protected-family keys and
  `OApsxdriponeminute` (marked on the key). The owner decided both later: the seven keys in step S3b,
  the xDrip switch in step D1 (deleted).
- **Advisor, TPO, tuning, auditor:** none of them wrote or proposed a deleted key or an internal state
  key, so none of their proposals changed. The user manual no longer tells the person to change the
  kicker step, the zero resume delay or the night growth basal multiplier (keys with no effect).
- **Reachability:** every manifest entry is now a setting the person owns, and a test walks the
  settings tree and fails if any of them loses its page, card or Expert row. The debt went from 23
  to 18 (the five context keys were deleted).

## Step S3b (2026-11-15, protected-family dead keys, owner decisions)

- **Sleep and HRV switches wired** (`PhysioDataSwitches`). Off, the reading is absent, never a
  stand-in number, at every place it enters AIMI: the raw read of the physio run (so the features,
  the rolling baseline, the context and the language model step never see it), the inputs and the
  result of the live health snapshot (no warm-up or merge carries an older value forward; with a
  switch off the merge also takes the fresh thermal belief, because the last one was built from the
  old sleep debt and HRV depression and the latent state reads it), the stored
  context each time a dosing path reads it (a context stored before the switch was turned off is
  valid for 20 hours; a poor sleep or low HRV verdict is removed and the state and recommendations are
  classified again with the engine's own rule, `AIMIPhysioContextEngineMTR.classify`), and the HRV of
  the thermal recovery proxy on both platforms. So sleep debt, poor sleep, the latent sleep debt score,
  the patient mode POOR_SLEEP_DAY, the TPO poor-sleep pack trigger, HRV depression, the stress and
  pattern readings and the RBT leaves all see "no data". Both default on: no change for anyone who
  does not touch them. The therapy "sleep" event (a declaration, not watch data) is not affected.
  Rows: a new section on the physio card (ADVANCED), with a hint.
- **Pain flare ends by itself** (`EndometriosisFlareClock`). The start time is internal state
  (`AimiLongKey.EndometriosisFlareStartedAt`): set when the flare is switched on, by a watcher the
  AIMI plugin runs on the live value of the switch (`EndometriosisFlareSwitchWatcher`, so it does not
  wait for a loop run and works while endometriosis is off), or by the loop when a flare is on with no
  start (switched on before this build, or by an import); cleared when it is switched off, by the
  watcher (also when endometriosis is off) or by the loop. (A first version listened to
  `EventPreferenceChange`, which nothing in the app sends, so a fast off and on kept the old start.)
  The switch is read as a `StateFlow`, which can merge an off and an on that come very close together;
  then the old start stays and the flare is shorter, never longer, which is the safe side. After `AimiEndometriosisFlareDuration` hours the flare
  no longer changes insulin (reason `PainFlare(Ended)`). The loop does not switch the setting off
  (no setting is written by the loop alone); the illness card shows the time left, or that the flare
  ended and how to start a new one. The duration row is on the endometriosis page of the illness card
  (EXPERT, as in the key map), with a hint; the pain flare switch got a hint too.
- **Deleted, with their stored values** (`AimiRetiredSettings`, same migration as S3, on Android
  start-up, on every import, and at the AIMI plugin start on both platforms): `AimiPhysioDebugLogs`,
  `AimiPhysioLLMProvider`, `AimiEndometriosisHormonalSuppression` and the Long `PregnancyDueDate`.
  Before the old due date is removed it is copied into `PregnancyDueDateString` when that text is
  empty (a text the person typed is kept). The day is the UTC day nearest to the stored milliseconds,
  which is the right day for a midnight stored in UTC or in any local time zone from UTC-12 to UTC+12;
  a value before 2000 is not copied. Nothing in the code history ever wrote this key, so only a
  hand-edited backup can hold one. Their titles and four unused strings (the two old sleep and HRV
  summaries, and the physio debug title and summary) went too.
- **DEAD_KEYS:** after S3b it held only `OApsxdriponeminute`; step D1 deleted that key, so the set is
  now empty.
- **Advisor, TPO, tuning, auditor:** none of them reads, writes or proposes any of the seven keys or
  the new start time, so none of their proposals changed and nothing they can propose lost or gained
  an effect. TPO revert and session data do not hold these keys.
- **Dosing:** none with the defaults. A person who turns sleep (or HRV) off loses every sleep (or
  HRV) based adjustment; these are mostly protective (less insulin after a poor night or with low
  HRV), so turning one off can mean slightly more insulin in those situations. A pain flare left on
  now stops raising the basal and damping the SMBs after the duration (default 4 h). A person whose
  backup held only the old due date with pregnancy mode on gets the gestation factors back.

## Step D1 (2026-11-15, owner decisions on the open items)

- **xDrip 1-minute switch deleted** (`BooleanKey.OApsxdriponeminute`, storage `key_use_Aimi_xdripOM`,
  and its title). Its stored value is removed like the S3 keys (`AimiRetiredSettings`: on Android
  start-up, on every import, and at the AIMI plugin start on both platforms). `DEAD_KEYS` is now
  empty, and the manifest no longer scopes the `OApsxdrip` name prefix.
- **NGR without a stored age.** `NightGrowthActivation.resolve` and `isEnabledBySwitchOrAge` take the
  stored age (null when never stored). New state `OFF_NO_AGE`; the Essentials page says "Age not set:
  night growth support is off. Set the age if the person is under 18." The two "default age" texts
  are gone. Other readers of the age:
  - `GlucoseStatusCalculatorAimi` (night slope threshold): the adult age (18) without a stored age.
    It gives the same threshold as the old default 14 (both above 12), and its result
    (`isNightGrowthCandidate` of the AIMI features) has no reader today.
  - The NGR config (learner, monitor, slot cap) keeps the key default 14 without a stored age. Without
    a stored age NGR only runs when its switch is stored on or with T3C brittle mode, and both must
    work as before; the monitor stops at age 18, so an adult age there would switch them off.
  - The "What AIMI learned" status (`ngrEnabled`) now uses the same rule (`NightGrowthActivation.read`).
    Before it showed only a stored switch, so NGR running by age (or by T3C) was shown as off.
- **NGR pages follow the loop (review of D1).**
  - The settings audit: for the age and the switch, "never stored" is a state of its own, so a stored
    14 or a stored `true` (equal to the key default) still gets a row, its default column says "Not
    set", and "Reset" removes the stored value. Before, the reset wrote 14 (or `true`) and turned NGR
    on for an adult.
  - The Essentials line and a new read only row "Night growth support now" (first row of the night
    growth page, `ApsIntentKey.AimiNightGrowthState`, prefs schema updated) read the state again every
    second while open. Observing the values was not enough: storing 14 when nothing was stored sends
    no new value, but turns NGR on.
  - A loop-level test (`ShellDecisionTraceTest`) checks the NGR config: off with nothing stored, on
    with a stored switch (age 14, the monitor active), on with a stored age 12, off with 30.
- **Display mismatch of the switch (from the D1 review): decided in step D2 for "nothing stored",
  and in step A1 for every case.** Since A1 the switch row shows the state the loop uses. See
  "Step D2" and "Step A1".
- **Still open for the owner (not decided):** with nothing stored, the age row shows 14 (the key
  default) and the age "?" sheet on the Essentials page says "Default: 14 years", while the loop takes
  the person for an adult. The state row and the Essentials line say the real state. Options: show
  "not set" on the age row, or give the age no default. Not changed, because it changes what the key
  means.
- **Meal factor labels.** The seven meal factor keys (meal, breakfast, lunch, dinner, high carb, snack,
  sleep) are titled "<mode> mode: SMB size (percent)" and got a help text: percent of the SMB that AIMI
  calculates while the mode is on, 100 = the full SMB, 50 (the default) = half (sleep: 60), the learned
  reactivity is applied on top and the max SMB limits still apply. Note: the code multiplies the SMB it
  calculates (`SmbInstructionExecutor`), not the max SMB limit, so the text says "of the SMB". The
  guided mode screen label reads "SMB size in percent (100 = full, 50 = half)". Fourteen old unused
  "Reactivity" strings went. The Advisor/Tuning proposal labels (`TuningPreferenceLabels`) now read
  "Lunch mode: SMB size (percent)" and "Dinner mode: SMB size (percent)", and the guided mode screen
  summary no longer says "reactivity". No default and no code path changed.
- **Contraceptive** has one more row on the endometriosis page of the illness card, after the
  endometriosis switches.
- **PK/PD pace flag** (`BooleanNonKey.OApsAIMIPkpdPaceMigrated`) is exported (prefs schema updated).
- **Key round trip test.** `SHARED_STORED_KEYS` moved from `PreferenceKeySnapshotTest` to a shared test
  file (`SharedStoredKeys.kt`); `PreferenceKeyRoundTripTest` accepts a shared name that resolves to
  the other key of its pair. Any other clash still fails.
- **Advisor, TPO, tuning, auditor:** none of them reads, writes or proposes the xDrip switch, the NGR
  age or the NGR switch, so nothing they propose changed. The meal factor proposals (Advisor and Tuning
  raise, TPO step down) keep working on the same keys and scale. They can no longer propose anything
  about the xDrip switch (they never did).
- **Dosing:** a person who never stored an age and never stored the NGR switch had NGR on (default age
  14) and now has it off: no extra night-time max IOB, SMB and basal boosts from NGR. Anyone with a
  stored age or switch, or with T3C brittle mode, is not affected.
- **Release notes line:** "Night growth support (for children and teens) is now off when no age was
  set in AIMI. If the person is under 18, set the age on the AIMI Essentials page to turn it back on.
  Not medical advice; talk with your care team before changing these settings."

## Step D2 (2026-11-15, owner decision, no dosing change)

- **NGR switch default off.** `BooleanKey.OApsAIMINightGrowthEnabled` (storage `key_oaps_aimi_ngr_enabled`)
  now has the default `false` (was `true`). With neither the switch nor the age stored, the switch
  row on the night growth page shows off, which is what the loop does since D1. With a stored age
  under 18 and no stored switch the row also showed off, but the loop runs NGR: fixed in step A1 (the
  row now shows the state the loop uses). The storage name, the title and the level did
  not change; a stored value (on or off) keeps its meaning.
- **Readers checked (all modules, Android and iOS, Advisor, TPO, tuning, auditor, export, Essentials,
  audit).** Only two read the switch for a decision, and both use `getIfExists`, so the key default is
  never used: `NightGrowthActivation.read` (Essentials line, night growth state row, "What AIMI
  learned" status) and `DetermineBasalAIMI2.buildNightGrowthResistanceConfig` (the loop). The export
  writes only stored values (`PrefsTransfer`), so it is not affected. No Advisor, TPO, tuning or
  auditor code reads, writes or proposes the switch. The only `get` of the switch is the switch row
  itself, which is the display this step fixes. No reader had to change.
- **Audit.** Unchanged: the switch stays one of `NightGrowthActivation.STORED_OR_NOT_KEYS`. A stored
  `false` (now equal to the key default) still gets a row with "Not set" as its default, because a
  stored `false` keeps NGR off with a stored age under 18 while a switch never stored lets that age
  turn it on. "Reset" still removes the stored value.
- **Texts.** The state row help (`aimi_ngr_state_summary`) now says that with the switch never set the
  age decides, even when the switch row shows off. The switch has no "?" sheet (only Essentials rows
  have one, and the switch is not on the Essentials page). Comments and KDoc that said "stored `true`
  equal to the key default" were updated.
- **Tests.** `AimiNightGrowthSwitchDefaultTest` (commonTest, so it also runs on iOS): the key default
  is off; for all 18 combinations of switch (stored on / off / never) x age (stored 12 / 30 / never) x
  T3C brittle mode (on / off) the state is the same with the old and the new switch default, and equal
  to the D1 rule written out by hand; the loop rule agrees with the state; with nothing stored the
  switch row shows what the loop does. `ShellDecisionTraceTest` (loop level): the NGR config of the
  loop for the same 18 combinations, with a store whose `get` of the switch answers on and then off,
  gives the D1 rule each time.
- **prefs schema:** not changed. It records the stored name, the type and the export flag, not the
  default.
- **Advisor, TPO, tuning, auditor:** none of them reads, writes or proposes the NGR switch, so nothing
  they propose changed, and there is nothing they can no longer propose.
- **Dosing:** none. For every stored or unstored combination the loop decides as before.
- **Decided by the owner (from the D2 review, done in step A1, 2026-11-15):** with a stored age under
  18 and no stored switch the loop runs NGR, but after D2 the switch row showed off. The owner chose
  option (a): the switch row shows the state from `NightGrowthActivation.read`, with a line that says
  why, and a tap stores the opposite of what it shows. See "Step A1".

## Step S4 (2026-11-15, owner decision, no dosing change)

- **Targets link.** A tap on the profile targets of the Essentials page opens the profile screen (the
  profile management screen of the drawer). The hint then says "Tap here to open the profile screen
  and change them." The targets themselves stay read only on the page.
- **New platform slot.** `AimiPlatformActions` with `AimiPlatformActionSlot.PROFILE_SCREEN`
  (commonMain, `ports/`), in the style of `AimiPlatformPreferenceSlot`: a platform that cannot open
  the screen returns null, and the page then shows no link and the old hint ("Change them on the
  profile screen"), never a dead link. Android (`AndroidAimiPlatformActions`) and iOS
  (`IosAimiPlatformActions`) both answer it. Both hand the request to the app navigator through
  `PendingNavigation`, which `ComposeMainActivity` and the iOS `AapsAppHost` collect, so the drawer's
  protection check (BOLUS, edit mode only with the settings level) is asked first. The desktop host
  does not collect it and has no AIMI plugin, so it has no binding.
- **Known limit.** The profile screen opens on top of the settings route. Back returns to the AIMI
  settings, not to the Essentials page, because the open AIMI page is kept with `remember`, not as
  saved state of the route (read from the code, not checked on a device yet). For the owner to accept
  or not; check it on a device before a release. Keeping the open page with
  `rememberSaveable` would fix it in a later step.
- **Tests.** `AimiEssentialsContent` asks the slot once and hands the action to the page as is
  (commonTest, so it also runs on iOS); a Compose test taps the targets block and checks the action
  runs, and that without a link the block has no click action and the hint does not say "tap".
- **Advisor, TPO, tuning, auditor:** no key added, removed or changed, so nothing they read, write or
  propose changed.
- **Dosing:** none.

## Step A1 (2026-11-15, final audit of the automatic writers, no dosing change)

### Night growth switch row shows the state the loop uses (owner decision, display only)

- The NGR switch row on the night growth page (`BooleanKey.OApsAIMINightGrowthEnabled`) shows the
  state from `NightGrowthActivation.read`, not the stored switch: on when the switch is stored on,
  when the switch was never set and the stored age is under 18, or when T3C brittle mode is on; off
  in every other case. A line under it says why: "Set by hand", "Set by the age (the switch was never
  set)", "Age not set: off", or "Set by T3C brittle mode: it stays on while T3C brittle mode is on".
- A tap stores the opposite of what the row shows on screen, as an explicit value (the state that
  was drawn, not a fresh read: the row reads the state again only once a second and the age row is
  on the same page). A stored switch wins over the age, as before. With T3C brittle mode on, a tap stores off but the row stays on (T3C keeps NGR
  on), and the line says so.
- The audit reset still removes the stored switch, so the age decides again.
- How: `AimiNightGrowthSwitch` (rule, tap, reason) and `AimiNightGrowthSwitchRow`. The night growth
  page lists the switch as `AimiNightGrowthSwitch.row(key)`, a `BooleanKeyWithRow` (new in core/ui):
  the same key, drawn by its own row. The shared `AdaptivePreferenceItem` honours it, so the row is
  the same on the AIMI page and on the page that the app search opens (`PreferenceScreenView`, shared
  appshell code, Android and iOS). The key string, title and rules are unchanged, so search, the
  manifest and the reachability test see it as before. The state is read again every second while the
  row is shown and right after a tap.
- Tests: `AimiNightGrowthSwitchRowTest` (commonTest, also on iOS): for all 18 combinations of switch
  (stored on / off / never) x age (stored 12 / 30 / never) x T3C (on / off) the row shows exactly what
  the loop rule gives, and a tap stores the opposite of the shown state; a tap after the store changed
  (age entered, row not read again yet) still stores the opposite of what was on screen; the child
  case of the D2 review; the audit reset; the night growth page lists the switch as its own row and no
  other key of the tree is. `AimiNightGrowthSwitchContentTest` (Compose): the row draws the state and
  the reason, and a tap passes the state on screen; the child case (age 12, switch never set) shows
  on and "Set by the age" both on the AIMI page (`AimiPreferenceSubScreen`) and through the shared
  renderer of the app search (`addPreferenceContent`). `AdaptivePreferenceItemsTest` (core/ui): a
  `BooleanKeyWithRow` is drawn by its own row, not by the shared switch.
- Dosing: none. The loop reads the same keys in the same way. What is new is only what the row shows,
  and that a tap from a shown "on" stores off (before A1, with a stored age under 18 and no stored
  switch, the row showed off and a tap stored on, which changed nothing for the loop).

### Audit of the automatic writers

Every preference write and every recommendation of the Advisor, TPO, the tuning engine, the auditor
and the learners, after the whole reorganisation. "Screen" is where the person sees and undoes the
value. Every user setting below also has a row in "Changes" (the settings audit) with its writer and a
reset. The two PK/PD learner values (`OApsAIMIPkpdStateDiaH`, `OApsAIMIPkpdStatePeakMin`) are internal
state: no Changes row and no reset, by design (steps P3 and S3).
`AimiAutomaticWritersAuditTest` (commonTest) runs the proposal code over many stored states and
periods and checks for every key and value it produces: the key is a setting the person owns (manifest
entry with the USER writer), it is reachable in the settings tree and not in the reachability debt,
the Changes page shows it and its reset restores the default, the value is inside the key bounds, a
meal factor is on the percent scale and an automatic raise stops at 100, the tail damping stays in
0.70 to 0.92 (never in the legacy zone the loop rewrites), and an exercise or late fat damping is
never proposed below the 0.5 floor. TPO keys must be on the TPO white list.

| Writer | Key | Still a reader? | Scale | Screen |
|---|---|---|---|---|
| Advisor metric rule (hypos) | `OApsAIMIMaxSMB` (x0.8 when above 1.5) | loop SMB limit | U | Essentials |
| Advisor metric rule (low TIR) | `OApsAIMILunchFactor` (+10, at most 100) | `SmbInstructionExecutor` | percent of the SMB | Meals card, lunch mode |
| PK/PD advisor | `OApsAIMIPkpdEnabled` (on) | PK/PD | switch | PK/PD card |
| PK/PD advisor | `OApsAIMIIsfFusionMaxFactor` (+/-0.1) | ISF fusion | factor 1.0-2.0 | PK/PD screen |
| PK/PD advisor, tuning, TPO | `OApsAIMISmbTailDamping` | `PkPdIntegration` | floor 0.70-0.92 | PK/PD screen |
| Advisor service | `OApsAIMIRecursiveBeliefShadow`, `OApsAIMIRecursiveBeliefAuthority` | RBT | switch | Autodrive card |
| Advisor service | `OApsAIMIHyperTrajectoryRelease` | HTR | switch | Autodrive card |
| Advisor service, tuning, TPO | `OApsAIMIPkpdPragmaticReliefEnabled`, `OApsAIMIPkpdPragmaticReliefMinFactor`, `OApsAIMIRedCarpetRestoreThreshold`, `OApsAIMIPriorityMaxIobFactor`, `OApsAIMIPriorityMaxIobExtraU` | PK/PD guard, red carpet, priority max IOB | switch, factors, U | PK/PD Expert screen |
| Tuning, TPO | `OApsAIMIHighBGMaxSMB` | loop SMB limit at high BG | U | Essentials |
| Tuning, TPO | `OApsAIMIDinnerFactor` | `SmbInstructionExecutor` | percent of the SMB | Meals card, dinner mode |
| Tuning, TPO | `OApsAIMIStraightLineTubeAdvisorEnabled`, `AimiTubeAggressiveness`, `AimiTubeHypoFloorMgdl` | tube advisor | switch, factor, mg/dL | Expert, trajectory |
| TPO | `OApsAIMISmbExerciseDamping`, `OApsAIMISmbLateFatDamping` (only lowered to 0.85 / 0.90) | `SmbDamping` (floor 0.5) | SMB multiplier | Activity card (exercise), PK/PD Expert screen (late fat) |
| Tuning apply | `AimiTuningContextSelection` (the chosen context) | loop (`hypoGuardActive`), RBT signals | text | Profile Advisor screen (in the reachability debt of the settings tree) |
| PK/PD learner | `OApsAIMIPkpdStateDiaH`, `OApsAIMIPkpdStatePeakMin` | PK/PD | h, min | internal state, read only on the PK/PD status card |
| PK/PD one-shot migrations | pace keys (0.5/5 -> FASTER once), tail damping (stored 0.55 or less -> 0.85 once) | PK/PD | h/day, min/day, floor | PK/PD screen |
| Plugin start (one-shot) | `OApsAIMIautoDriveActive` on, when the old classic Autodrive key was on | Autodrive | switch | Autodrive card |
| T2 one-shot repair | meal factors below 2 -> default | `SmbInstructionExecutor` | percent | Meals card |

- **Auditor:** writes no preference. Its ISF and target factors (plus or minus 15 %) are applied in
  memory under its own switches; it proposes no key change.
- **AI coach (`AiCoachingService`):** writes nothing. Its prompt lists only values that still exist
  (Max SMB, profile, the unified reactivity learner, Autodrive max basal and MPC step, PK/PD learned
  values, ISF fusion max, effective tail damping). The Control Center levels left it in S2.
- **Fixed in A1:** the OREF "PKPD: DIA, peak, damping" card told the person to use "the advisor cards
  above as a checklist" for DIA and peak. Those cards were removed in P2 (DIA and peak come from the
  profile insulin). The text now says to review the insulin of the profile (its DIA and peak, where
  the PK/PD learning starts) and the SMB damping together. Text only.
- **Fixed in A1 (from the A1 review), PK/PD advisor texts:** the ISF fusion card said "raise the ISF
  fusion (Max x2.0)" while it writes one step of 0.1; it now says "one small step, to 1.2" (the value
  it writes). The tail damping reason was built without its value, so the stored reason showed a raw
  placeholder; it now shows the new floor. Values in these texts are rounded to two decimals (text
  only; the written value is unchanged). The PK/PD advisor titles and texts, the Advisor score labels
  and the "profile looks good" card were French in the English strings file; they are now plain
  English. 19 French Advisor strings that no code used any more were deleted (English file only).
  Test: `PkpdAdvisorReasonTextTest`.
- **Checked, left as is (no wrong write, no dead key):**
  - The tuning engine's hypo plan lowers the four pragmatic relief values (min factor, red carpet
    restore, priority max IOB factor and extra) also while pragmatic relief is off, and the TPO
    protective packs lower them in the same plan that switches relief off. These values only act while
    relief is on, so the change has no effect until relief is on again. It is a protective value kept
    for later, not a dead key; skipping it would change dosing later, so A1 does not change it. TPO
    reverts both together at the end of a session.
  - `SafetyAggressionPlugin` (Max SMB x0.7 on a low) is never registered with `AimiPluginManager`, so
    it never proposes anything. It is dead code; deleting it is a separate cleanup.
- **Advisor, TPO, tuning, auditor:** A1 removed, re-scaled or re-derived no key, so nothing they can
  propose changed in A1.

### Tests of step A1

- The full CI suite (`testFullDebugUnitTest testDebugUnitTest jvmTest testAndroidHostTest
  :desktop:shell:test`) and `:plugins:aps:iosSimulatorArm64Test` pass, except 5 tests in two modules
  that A1 does not touch: `:pump:dana:danar` `MsgHistoryBolusTest` (2) and `:shared:impl`
  `DateUtilImplOldTest` (3). They fail the same way on a clean HEAD (`dc62795584`, run on 2026-11-16
  with the A1 work stashed), so they existed before A1. They read dates and times, so they likely
  depend on the time zone or the date of the run.

### S4 review leftovers

- `PendingNavigation` KDoc now says it carries in-app taps too (the AIMI Essentials profile link), not
  only notification taps.
- The port list above `IosAppGraph` names the eight platform ports and points to the Android
  `AimiPlatformPortsWiredTest`, which lists the same eight; that test no longer says the ports are
  absent on iOS.
- New test (`AimiPlatformPortsWiredTest`, Android root graph): the Essentials page that the AIMI
  settings tree opens gets a non-null profile link from the graph's `AimiPlatformActions`, and a tap
  on it parks the profile management request for the navigator.
- Android, `navController` null at the moment of a tap: checked, a request cannot be lost.
  `AapsAppRoot` calls `onNavControllerReady` while it composes, before its collector of
  `PendingNavigation` starts, and nothing sets the field back to null. Written as a comment at
  `ComposeMainActivity` (`onPendingNavigation`). iOS uses its own relay and is not affected.
- The ML correction limit comments (`AimiBehaviorRuntimeProfile.NEUTRAL`,
  `AimiControlCenterDisarmTest`) now say the owner kept 0.55 / 0.72 / 0.88 / 1.0.

### What the Advisor and TPO can propose now

Plain English, after all steps up to A1. Every proposal of the Advisor and of the tuning engine is
shown first and changes nothing until the person accepts it. TPO changes values by itself for a
while, shows a badge, and puts them back at the end of its session.

- **The Advisor can propose:**
  - a smaller Max SMB (80 % of it) when there are too many lows and Max SMB is above 1.5 U;
  - a larger lunch mode SMB (10 points more), never above 100 %, when time in range is low and lows
    are rare;
  - to turn PK/PD learning on when it is off and time in range is below 70 %;
  - a little more or less ISF fusion headroom (max factor), and a stronger or weaker SMB tail damping,
    inside the slider band, depending on whether highs or lows dominate;
  - to turn on the RBT shadow export, RBT authority or hyper trajectory release when Autodrive is on
    and they are off;
  - to turn pragmatic relief on, or to move its four values (min factor, red carpet restore, priority
    max IOB factor and extra units) up when highs dominate or down when lows are frequent, only while
    relief is on;
  - review cards with no action (basal share, carb ratio, ISF, insulin and damping, MPC step).
- **The tuning engine (Profile Advisor, one bundle the person accepts) can propose:** small steps of
  Max SMB, high BG Max SMB, lunch and dinner mode SMB size (raise at most to 100 %), the pragmatic
  relief switch and values, the straight-line tube switch, its aggressiveness and hypo floor, and the
  SMB tail damping, in the direction of the chosen context (meal rise, hyper, hypo guard, mixed).
- **TPO can change for a while (and put back):** Max SMB and high BG Max SMB (one rung down per step),
  the priority max IOB factor and extra units, the pragmatic relief min factor and red carpet restore,
  stronger SMB tail damping, lunch and dinner mode SMB size (down only), switching pragmatic relief and
  the tube advisor off, a weaker tube and a higher tube hypo floor, and, after a poor night, the
  exercise and late fat damping lowered to 0.85 / 0.90 when they are above that.
- **They can no longer propose (removed in earlier steps):** the Control Center "move a family up or
  down" suggestions and their writes (S2); the PK/PD start DIA and peak (P2, they come from the
  profile insulin); a meal factor raise above 100 (T2); a TPO "step down" that raises a value, or a
  poor-sleep pack that raises the damping (T1); anything about the keys deleted in S3, S3b and D1.
