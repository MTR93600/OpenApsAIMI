# AIMI - Product Harmony Causal Tree Spec

**Status:** product specification and implementation roadmap  
**Date:** 2026-06-14  
**Scope:** undeclared meals, hyper->hypo sequences, post-hypo physiological recovery, causal tree deployment, PKPD governance, ML alignment

## 1. Product objective

AIMI must not behave like a stack of independent safety and dosing modules.

It must behave like a **single causal intelligence** able to answer, on every tick:

1. What is the body most likely experiencing right now?
2. Is this rise more compatible with a meal, endogenous glucose output, stress, post-hypo rebound, insulin tail, or a mixed state?
3. What insulin amount is needed?
4. How fast should that insulin be expected to act?
5. How much uncertainty remains, and how much prudence should be applied?

The target product is therefore:

- a **causal tree that unfolds** from physiology to action;
- a **stateful memory** of recent hyper and hypo events;
- a **single safety truth** shared by SMB, TBR, Autodrive V3, PKPD, replay export, and Hormonitor;
- an ML layer that **supports** causal arbitration instead of fighting it.

## 2. Product problem seen in current runtime

The codebase already contains most of the required building blocks:

- physiological phase classification;
- meal absorption phases;
- latent physiological state;
- UAM competing hypotheses;
- patient state and patient mode;
- scenario projection with `clinicalFloor` and `scenarioBest`;
- recursive belief;
- PKPD runtime and learned parameters;
- HTR and Autodrive V3.

The remaining weakness is not missing features. It is **causal fragmentation**.

Today, different layers can still disagree on the same situation:

- one layer says "meal";
- another says "dawn endogenous";
- another suppresses meal interpretation;
- another still allows meal-priority SMB;
- another re-opens a late predictive hypo gate and sets TBR to 0;
- exports then report a final state that hides the true sequence of events.

This is not a simple tuning issue. It is a **product coherence issue**.

## 3. Clinical model to preserve

The product must explicitly model the following real-world sequence:

1. An undeclared meal may cause sustained hyperglycaemia.
2. If the meal is under-corrected, the body remains in a meal or resistance state for longer.
3. If correction comes late or too strongly, a hypo or near-hypo descent can follow.
4. That hypo changes physiology:
   - counter-regulatory hormones rise;
   - autonomic stress increases;
   - symptoms and warning responses may be blunted for subsequent lows;
   - rebound hyper can occur without being a meal.
5. After hyper + hypo, the patient may enter an **exhausted recovery state**:
   - less stable;
   - more variable;
   - more sensitive to over-correction in either direction;
   - more likely to produce misleading trajectories.

The product implication is direct:

**a morning rise after a night hyper and a low is not interpreted the same way as a clean morning rise after a quiet night.**

That distinction must exist in the runtime state model, not only in narrative analysis.

## 4. Scientific anchors

These references do not define AIMI thresholds, but they support the product direction.

### 4.1 Antecedent hypoglycaemia changes the next physiological state

- Dagogo-Jack et al. showed that recent hypoglycaemia reduces autonomic and symptomatic defenses against subsequent hypoglycaemia.  
  Link: https://doi.org/10.1172/JCI116302
- Cryer reviewed hypoglycaemia-associated autonomic failure and the persistence of altered counterregulation after antecedent lows.  
  Link: https://doi.org/10.1056/NEJMra1215228
- Rickels reviewed counterregulation and therapeutic implications in type 1 diabetes.  
  Link: https://doi.org/10.1111/nyas.14214

**Product implication:** post-hypo must be a real runtime state, not a single Boolean guard.

### 4.2 Circadian timing and sleep affect glucose handling

- Stenvers et al. reviewed circadian clocks and insulin resistance.  
  Link: https://www.nature.com/articles/s41574-018-0122-1
- Speksnijder et al. reviewed timing of food intake and metabolic outcomes.  
  Link: https://pubmed.ncbi.nlm.nih.gov/38695262/
- Boege et al. showed adverse glucose effects from circadian misalignment.  
  Link: https://pubmed.ncbi.nlm.nih.gov/32998085/
- Henry et al. reviewed meal timing and glycaemic outcomes.  
  Link: https://doi.org/10.1038/s41387-020-0109-6

**Product implication:** the same BG geometry should not be treated identically at all times of day or after all nights.

### 4.3 Post-hypo instability can persist beyond the immediate event

- Shahid and Lewis reported increased glucose variability and rebound patterns after hypoglycaemia in open-source AID data. This is a preprint, so it should be treated as directional evidence rather than final proof.  
  Link: https://arxiv.org/abs/2405.08970

**Product implication:** the recovery state should decay gradually, not disappear as soon as BG normalises.

### 4.4 Undeclared meal detection should use trajectory semantics, not thresholds alone

- Tavasoli and Shakeri proposed interpretable CGM-feature-based meal detection for artificial pancreas settings. This is also a preprint, useful as a design orientation.  
  Link: https://arxiv.org/abs/2507.00080

**Product implication:** meal detection should be driven by causal competition and trajectory signatures, not only by immediate rises.

## 5. Target causal tree

The runtime should converge to one dominant state and several competing probabilities.

### 5.1 Primary states

Recommended state family:

- `FAST_MEAL`
- `PROLONGED_MEAL`
- `LATE_FAT_PROTEIN`
- `DAWN_ENDOGENOUS`
- `STRESS_RESISTANCE`
- `POST_HYPO_RECOVERY`
- `EXERCISE_AFTERBURN`
- `INFLAMMATORY_DRIFT`
- `ABSORPTION_UNCERTAIN`
- `STABLE_BASELINE`

### 5.2 Composite or mixed states

These should exist as explicit runtime mixtures, not hidden contradictions:

- `MEAL_PLUS_RESISTANCE`
- `POST_HYPO_PLUS_DAWN`
- `POST_HYPER_EXHAUSTED`
- `MEAL_VS_ENDOGENOUS_AMBIGUOUS`
- `TAIL_RISK_PLUS_RISE`

### 5.3 Dominant product rule

At any tick:

- one state is dominant;
- several states remain available as competing causes;
- safety, PKPD, SMB, TBR, HTR, and telemetry all read from the same causal summary.

## 6. Required memory model

The engine should remember not only "what is happening now" but also "what just happened to the body".

### 6.1 Event memory windows

Recommended windows:

- `acute_window_0_90m`
- `short_window_90m_6h`
- `recovery_window_6h_24h`
- `fatigue_window_24h_48h`

### 6.2 Events to remember

- sustained hyper exposure;
- rapid correction exposure;
- near-hypo or hypo exposure;
- repeated corrective SMB/TBR sequences;
- sleep debt or strong autonomic burden;
- meal-like unresolved rises.

### 6.3 Derived product states

Recommended derived memory signals:

- `recent_hyper_load`
- `recent_hypo_load`
- `post_hypo_recovery_score`
- `counterregulatory_rebound_score`
- `post_hyper_exhaustion_score`
- `correction_fragility_score`

These signals should decay over time but remain visible to the decision tree.

## 7. Runtime decision order

The product should follow this order on each tick:

1. Build raw observations:
   - BG, delta, short delta, long delta, IOB, COB, trajectory, HR, HRV, sleep, thermal drift, time-of-day, recent actions.
2. Build physiological and meal evidence:
   - `PhysiologicalPhaseClassifier`
   - `MealAbsorptionPhaseEngine`
   - `PhysioLatentState`
   - `UamHypothesisState`
3. Build recent-event memory:
   - recent hyper burden
   - recent hypo burden
   - post-hypo recovery
   - exhausted recovery after hyper->hypo
4. Build unified causal posterior:
   - dominant state
   - competing states
   - confidence and uncertainty
5. Build authoritative risk envelope:
   - one clinical floor
   - one best scenario
   - one final decision hypo truth
6. Decide insulin need:
   - meal replacement
   - correction need
   - bridge basal need
   - protection need
7. Apply PKPD interpretation:
   - structural learned parameters
   - contextual non-persisted modifiers
8. Apply safety and product policy once:
   - same truth for SMB and TBR
9. Export one coherent story:
   - no conflict between decision, safety, patient mode, and Hormonitor.

## 8. Undeclared meal product behaviour

### 8.1 What "good" looks like

An undeclared meal should be:

- detected early enough to avoid a long severe hyper;
- not mistaken for dawn, cortisol, or rebound if meal evidence is genuinely strong;
- escalated in a staged way, not by all modules independently;
- allowed to remain a meal across first wave, inter-wave, second wave, and late fat if the kinetics support it.

### 8.2 Required arbitration rules

- A true meal can override hormonal suspicion only if trajectory and absorption evidence remain consistent.
- A slow dawn-like rise with `COB ~= 0` and weak meal kinetics must not unlock meal-priority correction.
- A post-hypo rebound after an overnight low must not be upgraded to a meal just because BG rises again.
- A mixed state must remain mixed when evidence is ambiguous. The engine should not flip between "meal" and "not meal" every few ticks.

### 8.3 Product observable before/after

Before:

- rise classification may flip between meal and endogenous explanations;
- meal interpretation can be suppressed while meal mode remains active;
- safety can still zero TBR after a meal-support narrative has already been built.

After:

- meal handling remains stable across the whole wave when evidence is coherent;
- endogenous rebound states stay protected from false meal escalation;
- the narrative, the delivered insulin, and the export all tell the same story.

## 9. Hyper -> hypo -> exhausted recovery product behaviour

## 16. Implemented Lots On This Branch

The following product-harmony lots are now implemented in code on this branch.

### 16.1 Lot 1 - Shared meal intent for safety

Implemented:

- `MealSafetyContext` now carries `inferredMealSignal`
- `buildMealSafetyContext()` now reuses:
  - `lastMealAbsorptionOutput.mealDeliveryPriority`
  - `lastPatientModeDecision`
  - `lastPatientState.causalPosterior.supportsMealInterpretation(...)`
  - `falseMealSuppression`
- `setTempBasal()` now reads the same inferred meal intent when no context is passed
- Autodrive V3 direct TBR path now forwards the same meal safety context
- `HypoLgsBlockReason` no longer re-triggers `PREDICTED_MIN_CURVE` when predictive suppression has already concluded that the low forecast is meal-compatible or otherwise suppressed

Product effect:

- undeclared-meal handling and predictive hypo protection now read from the same semantic meal story
- late safety re-entry is reduced when the system is already in coherent meal support mode

### 16.2 Lot 2 - Short event memory for hyper -> hypo burden

Implemented:

- new runtime object: `PatientEventMemory`
- exported fields:
  - `recent_hyper_load`
  - `recent_hypo_load`
  - `post_hyper_exhaustion_score`
  - `correction_fragility_score`
- memory is built once per patient-state refresh from:
  - recent BG history
  - `minBgInLastMinutes(...)`
  - `postHypoReboundProb`
  - thermal recovery burden

Product effect:

- the tree can distinguish a clean rise from a rise happening after an exhausting hyper/hypo chain
- recovery prudence no longer depends only on a single instantaneous post-hypo signal

### 16.3 Lot 3 - Protective preemption in the causal tree

Implemented:

- `PatientModeOrchestrator` now preempts meal branches when:
  - `falseMealSuppression` is active
  - protective confidence materially exceeds meal confidence
  - the dominant cause is protective or uncertainty-led

Protected states:

- `DAWN_ENDOGENOUS`
- `STRESS_RESISTANCE`
- `INFLAMMATORY_DRIFT`
- `ABSORPTION_UNCERTAIN`

Product effect:

- cortisol-like, dawn-like, or recovery-driven rises are less likely to be upgraded into meal logic when the protective context is already stronger

### 16.4 Lot 4 - PKPD contextual fragility

Implemented:

- PKPD structural learning remains unchanged for persisted `DIA` and `peak`
- PKPD contextual modulation now also reads `PatientEventMemory`
- exhausted recovery can now:
  - slightly reduce the contextual absorption factor
  - slightly increase the contextual sensitivity factor

Product effect:

- after a hard hyper -> hypo sequence, the runtime becomes more conservative without corrupting structural PKPD learning
- `DIA` and `peak` stay structural; fragility acts only as a non-persisted context modifier

### 16.5 Lot 5 - Hormonitor dual-action semantics

Implemented:

- `PhysioDecisionTraceMTR` now keeps:
  - `final_loop_decision_type`
  - `smb_action_type`
  - `basal_action_type`
  - `decision_conflict_flags`
- adapter helpers now preserve SMB and TBR semantics independently
- Hormonitor export and daily outcomes now count SMB from `smb_action_type` and basal actions from `basal_action_type`
- schema version moved to `1.4.0`

Product effect:

- one loop can now truthfully report both an SMB and a TBR action
- analytics no longer undercount SMB simply because a later basal write replaced the last decision string

### 16.6 Lot 6 - ML training cleanup without runtime tensor drift

Implemented:

- runtime `SmbRefinementFeatureSchema.INPUT_SIZE` is unchanged
- async CSV training now accepts extra optional columns:
  - `eventMemoryPostHyperExhaustionScore`
  - `eventMemoryCorrectionFragilityScore`
  - `decisionConflictFlags`
- `AimiSmbTrainer` now filters rows through `shouldUseCsvRowForTraining(...)`
- rows are rejected from training when:
  - decision conflict flags are present
  - correction fragility is high
  - exhaustion is high in a protective context

Product effect:

- `uammodel.tflite` runtime compatibility is preserved
- `AimiNeuralNetwork` asynchronous refinement becomes less likely to learn from unstable, dual-action, or recovery-burdened loops

## 17. Observable Before / After

Before:

- an undeclared-meal rise could be recognized by one layer, then partially negated by predictive hypo logic later
- dawn or stress rises could still leak into meal-support behavior if local meal cues were strong enough
- hyper -> hypo sequences influenced physiology, but that burden was not explicitly represented as a short-term product memory
- Hormonitor could lose the SMB semantic if a later basal action overwrote the final loop string
- ML refinement could train on rows that mixed unstable recovery context and dual delivery semantics

After:

- inferred meal intent is shared by safety, TBR, Autodrive V3, and meal-support logic
- false-meal suppression can now genuinely preempt meal branches when protective evidence dominates
- post-hyper/post-hypo burden is visible as explicit event memory and influences both the causal tree and PKPD contextual prudence
- Hormonitor can expose SMB plus basal action in the same loop without semantic loss
- asynchronous ML refinement is cleaner because fragile or conflicted rows are filtered out before training

## 18. ML Impact Detail

What does not change:

- the on-device runtime tensor shape used by `uammodel.tflite`
- the base runtime feature vector consumed inside dosing
- the persisted PKPD structural learning model

What changes:

- the CSV written for asynchronous refinement now includes event-memory and decision-semantic audit columns
- the trainer becomes more selective and refuses rows that are likely to represent unstable recovery or mixed loop semantics

Expected ML consequence:

- fewer training rows, but cleaner rows
- slightly slower data accumulation
- better alignment between what the trainer learns and what the product considers a clinically coherent loop

## 19. Preference Impact

No new preference has to be activated for these lots.

The implemented changes operate by reusing existing runtime state and existing product families. They are intended to improve default harmony, not to expand the raw preference surface.

### 9.1 What the product must understand

When a patient goes through a large evening hyper followed by a low or near-low in the morning:

- the low is not automatically proof of a bug;
- it may be the delayed consequence of the previous hyper and insulin delivery;
- however, the next ticks must account for a new vulnerable state.

### 9.2 Required state transitions

Recommended transition logic:

- `SUSTAINED_HYPER` -> `AGGRESSIVE_CORRECTION_WINDOW`
- `AGGRESSIVE_CORRECTION_WINDOW` -> `POTENTIAL_HYPO_DESCENT`
- `POTENTIAL_HYPO_DESCENT` -> `POST_HYPO_RECOVERY`
- `POST_HYPO_RECOVERY` + prior high burden -> `POST_HYPER_EXHAUSTED`
- `POST_HYPER_EXHAUSTED` decays slowly toward `STABLE_BASELINE`

### 9.3 Behavioural consequences

During `POST_HYPO_RECOVERY` or `POST_HYPER_EXHAUSTED`:

- reduce trust in geometry-only meal interpretation;
- reduce aggressive correction stacking;
- increase weight of tail activity and clinical floor;
- increase hysteresis before returning to pure hyper-release logic;
- prefer stable bridge strategies over repeated contradictory micro-actions.

## 10. PKPD governance target

The product should stop using PKPD as a single bucket for multiple physiological realities.

### 10.1 Structural PKPD

Persist slowly learned components:

- `baseDiaHrs`
- `basePeakMin`
- `baseTailProfile`
- site and weight-related baseline kinetics

### 10.2 Contextual PKPD

Apply non-persisted modifiers per tick:

- `effectivePeakShiftMin`
- `effectiveTailScale`
- `effectiveAbsorptionFactor`
- `effectiveSiFactor`
- `effectiveCorrectionBrake`

### 10.3 Product rule

Physiology should change:

- confidence,
- expected shape,
- absorption speed,
- insulin sensitivity,

without immediately rewriting the long-term PKPD identity of the patient.

### 10.4 Recommended causal use

- `FAST_MEAL` -> less brake on fast correction, preserve meal support
- `PROLONGED_MEAL` / `LATE_FAT_PROTEIN` -> longer support, slower return to neutral
- `STRESS_RESISTANCE` -> slower effective action, stronger uncertainty, cautious escalation
- `POST_HYPO_RECOVERY` -> stronger tail awareness, less stacking aggression
- `POST_HYPER_EXHAUSTED` -> avoid oscillatory correction loops

## 11. HRV and physiology - correct product use

HRV should not directly become a dose calculator.

The stronger product use is:

- HRV, sleep, temperature, HR and recovery context modify the **probability of branches**;
- they also modify **confidence**, **uncertainty**, and **damping**;
- they should only indirectly influence dose through the unified causal posterior and contextual PKPD.

Recommended rule:

- physiology is a **belief shaper** first;
- PKPD is an **effect shaper** second;
- dose is the **result** of both, not the direct output of HRV.

## 12. ML target role

The ML stack should refine the tree, not replace it.

### 12.1 What ML should predict

Recommended ML outputs:

- probability of meal-like trajectory;
- probability of endogenous rise;
- probability of post-hypo rebound;
- probability of stress/resistance;
- residual correction amount after deterministic safety;
- confidence of prediction cleanliness for training.

### 12.2 What ML should not do

ML should not:

- overrule the unified safety truth;
- learn from contradictory labels where meal is both suppressed and active;
- learn from telemetry where SMB was delivered but the final export says only `suspend`;
- absorb post-hypo physiology into permanent PKPD drift.

### 12.3 Training data hygiene

Training rows should be tagged with:

- dominant causal state;
- competing state probabilities;
- post-hypo recovery score;
- recent hyper burden;
- export consistency status;
- contradictory decision flags.

Rows with strong semantic contradictions should be excluded or down-weighted.

## 13. Concrete code implications

### 13.1 Single safety truth

Unify the final hypo gate across:

- `SafetyPredictionTerminalsResolver`
- `PredictiveHypoEvaluator`
- `setTempBasal()`
- Autodrive V3 direct TBR path

Target outcome:

- no late `minPredictedCurve` LGS reopen when early safety already suppressed the artefact in a meal or hyper-compatible context.

### 13.2 Patient mode ordering

Ensure `falseMealSuppression` and post-hypo recovery can influence patient mode **before** the final meal mode is emitted.

Target outcome:

- no `PROLONGED_MEAL` narrative while replay quality simultaneously states meal interpretation is suppressed.

### 13.3 Hormonitor truth model

Split exported decision semantics into separate fields:

- `smb_action_type`
- `basal_action_type`
- `final_safety_state`
- `dominant_causal_state`
- `decision_conflict_flags`

Target outcome:

- a day with real SMB deliveries can no longer be summarised as `decision_count_smb = 0`.

### 13.4 Recovery-state integration points

Recommended integration targets:

- `PhysioLatentState`
- `PatientStateSnapshot`
- `PatientModeOrchestrator`
- `ScenarioProjectionPair`
- `RecursiveBeliefTickContext`
- `PkPdIntegration`
- replay and Hormonitor export

### 13.5 Existing code anchors

The target design should extend, not replace, the current AIMI runtime anchors:

- `DetermineBasalAIMI2.kt`
- `safety/PredictiveHypoEvaluator.kt`
- `safety/HypoLgsBlockReason.kt`
- `risk/SafetyPredictionTerminalsResolver.kt`
- `patient/PatientModeOrchestrator.kt`
- `patient/CausalStatePosterior.kt`
- `physio/MealAbsorptionPhaseEngine.kt`
- `physio/PhysioLatentState.kt`
- `physio/UamHypothesisState.kt`
- `pkpd/PkPdIntegration.kt`
- `recursive/RecursiveBelief*`
- `physio/AIMIInsulinDecisionAdapterMTR.kt`
- `physio/AimiHormonitorStudyExporterMTR.kt`

These are already the natural product insertion points for:

- unified causal posterior,
- post-hypo memory,
- single final safety truth,
- meal suppression ordering,
- contextual PKPD,
- semantic export cleanup.

## 14. Non-regression acceptance criteria

### 14.1 Observable behaviour before/after

Before:

- high BG may still end in TBR 0 even when meal context and safety pass remain active;
- meal-suppressed and meal-priority states can coexist;
- post-hypo morning rises can be misread as meals or raw hyper;
- Hormonitor can under-report SMB.

After:

- safety, patient mode, basal, SMB, and export agree on the same causal story;
- undeclared meal support remains strong when evidence is meal-consistent;
- post-hypo rebound and exhausted recovery are treated as protected states;
- PKPD adapts in a contextual and intelligible way;
- ML sees cleaner labels and more stable outcome semantics.

### 14.2 Replay scenarios to validate

- clean dawn without meal;
- true breakfast without declared carbs;
- prolonged evening meal with late fat;
- severe evening hyper followed by near-hypo morning descent;
- morning rise after low and poor sleep;
- stress-like rise with elevated HR and depressed HRV;
- post-hypo rebound with low meal evidence and active insulin tail.

## 15. Recommended implementation lots

### Lot 1 - unified causal posterior

Build one authoritative causal posterior per tick with dominant and competing states.

### Lot 2 - post-hypo and exhausted recovery memory

Add explicit memory scores and transitions for hyper->hypo and post-hypo recovery.

### Lot 3 - single final hypo truth

Remove late contradictory hypo gating between early safety and final TBR application.

### Lot 4 - patient mode and meal suppression ordering

Resolve mixed states before emitting patient mode and meal priority.

### Lot 5 - PKPD structural vs contextual split

Keep slow learning separate from fast physiological modulation.

### Lot 6 - Hormonitor and replay semantic cleanup

Export the actual action sequence and conflict flags.

### Lot 7 - ML data contract cleanup

Train only on causally coherent and export-coherent rows.

## 16. Final product position

The distinctive value of AIMI is not only to deliver insulin more aggressively or more safely.

Its distinctive value is to **understand what the body is doing now, what it has just been through, and what that means for the next decision**.

That means:

- a meal is not just a rise;
- a rebound is not just a rise;
- a stressed dawn is not just a rise;
- a body that went through hyper then hypo is not the same body as one waking after a stable night.

The product becomes exceptional when the loop keeps that continuity of meaning from physiology to final action.
