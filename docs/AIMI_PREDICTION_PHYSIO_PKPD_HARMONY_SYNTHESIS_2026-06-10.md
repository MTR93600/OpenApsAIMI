# AIMI Prediction / Physio / PKPD Harmony Synthesis

## Objective

Bring the prediction stack, physiological interpretation tree, PKPD adaptation, and asynchronous SMB refinement into one coherent body-state model so that:

- unannounced meal handling is more precise,
- dawn / cortisol / post-hypo / stress contexts are less likely to be mistaken for meals,
- PKPD learning does not drift on physiologically dirty contexts,
- replay and product-quality analysis expose the real cause behind each decision.

## What Was Implemented

### Lot 1. Unified causal posterior

A new causal layer was added in `patient/CausalStatePosterior.kt` and injected into `PatientStateSnapshot`.

The posterior now scores:

- `FAST_MEAL`
- `PROLONGED_MEAL`
- `DAWN_ENDOGENOUS`
- `POST_HYPO_RECOVERY`
- `STRESS_RESISTANCE`
- `EXERCISE_AFTERBURN`
- `INFLAMMATORY_DRIFT`
- `ABSORPTION_UNCERTAIN`

The posterior is built from existing variables already present in AIMI:

- meal absorption phase and belief,
- latent physio state,
- UAM multi-hypothesis state,
- physiological patterns,
- user context intent,
- thermal belief,
- false-meal suppression state.

It also exposes:

- `mealConfidence`
- `protectiveConfidence`
- `learningQuality`
- dominant cause and confidence

### Lot 2. Patient-mode orchestration aligned with causal verdict

`PatientModeOrchestrator` now uses the causal posterior before falling back to older heuristics.

This means:

- strong causal fast meal can force `FAST_MEAL` even if legacy signals are only partial,
- strong dawn / post-hypo / inflammatory / stress causality can block meal-biased interpretations earlier,
- reasons visible in product/replay now include dedicated causal reason codes.

### Lot 3. Prediction authority aligned with meal vs non-meal causality

`DecisionPredictionAuthorityResolver` now consumes the causal posterior.

Main changes:

- meal uplift can be triggered by causal meal evidence, not only legacy meal flags,
- non-meal suppression now integrates causal protective evidence,
- `FAST_MEAL` can unlock scenario uplift earlier,
- dawn / post-hypo / stress / inflammatory / uncertain contexts more clearly keep PKPD authority when appropriate.

### Lot 4. RBT coherence

RBT extended signals now carry:

- causal dominant state,
- causal dominant confidence,
- causal meal confidence,
- causal protective confidence,
- causal learning quality.

`RecursiveBeliefResolver` now uses those values to arbitrate meal vs non-meal interpretation with the same body-state view as the main loop.

### Lot 5. PKPD contextual learning guard

`PkPdIntegration` now takes the causal posterior and applies it in two places:

1. Runtime factors
- absorption factor now considers causal meal support, dawn brake, post-hypo brake, and protective burden,
- sensitivity factor now reacts to causal dawn / stress / inflammatory / post-hypo states.

2. Learning hygiene
- PKPD learning is now blocked when `learningQuality` is too low,
- causal protective contexts can explicitly skip estimator updates,
- skip reasons are logged with causal labels.

This reduces the risk of teaching DIA / peak from the wrong physiological explanation.

### Lot 6. ML schema upgrade

`SmbRefinementFeatureSchema` now adds three causal features:

- `causalMealConfidence`
- `causalProtectiveConfidence`
- `causalLearningQuality`

Impact on the pipeline:

- `uammodel.tflite` runtime input now receives the causal context,
- the async CSV-based refinement path trains on the same causal view,
- low-quality causal contexts are filtered out before training.

This keeps the asynchronous learner from overfitting meal-like trajectories that are actually dawn, rebound, or protective physiology.

### Lot 7. Replay / quality export

`ReplayQualityExport` now exports:

- dominant causal state,
- causal confidence,
- causal meal confidence,
- causal protective confidence,
- causal learning quality,
- causal quality tags.

This makes replay analysis clinically interpretable instead of only technically descriptive.

## Observable Behavior: Before / After

### 1. Unannounced meals

Before:
- relied more heavily on trajectory + meal-like rise + UAM support,
- could stay ambiguous longer when physiology was mixed.

After:
- stronger discrimination between `FAST_MEAL` and competing protective contexts,
- earlier meal uplift when the causal posterior is decisively meal-oriented,
- better prolonged-meal continuity through second-wave / late-fat logic.

### 2. Dawn / cortisol / endogenous rise

Before:
- false meal suppression existed, but decision, PKPD, RBT, and ML did not all share the same verdict.

After:
- dawn/endogenous interpretation is propagated consistently through patient mode, prediction authority, PKPD, replay, and ML filtering,
- less chance of escalating meal logic on a hormonal/endogenous rise.

### 3. Post-hypo / alcohol / rebound contexts

Before:
- post-hypo protection depended more on local heuristics than on a unified causal state.

After:
- post-hypo recovery is a first-class causal state,
- meal interpretation is more explicitly suppressed,
- PKPD learning is less likely to absorb rebound-driven noise.

### 4. Stress / inflammatory / HRV-like resistance contexts

Before:
- resistance signals existed, but their propagation into PKPD learning and replay quality was incomplete.

After:
- resistance and inflammatory drift become explicit causal explanations,
- PKPD sensitivity scaling reacts more coherently,
- learning skips dirty protective contexts instead of slowly biasing DIA / peak.

### 5. Product observability

Before:
- replay could say what happened, but not always clearly why the engine believed it.

After:
- replay and patient-facing summaries expose the dominant cause,
- quality tags now reveal whether a tick was meal-driven, protective, or unclean for learning.

## ML Impact Detail

This change touches ML in two different ways.

### Runtime inference impact

The inference vector for SMB refinement now contains three new causal features. This means the model can distinguish:

- a real meal-like rise,
- a protective non-meal rise,
- a low-quality ambiguous context.

Expected effect:

- less refinement aggressiveness in misleading physiological contexts,
- more stable refinement when meal evidence is truly causal and not only geometric.

### Async training impact

The CSV-based async training path now drops rows when causal learning quality is low or protective burden is too high.

Expected effect:

- fewer corrupted training rows,
- slower but cleaner learning,
- less risk of “teaching the model the wrong lesson” from dawn, rebound, or inflammatory windows.

### Practical consequence

Short term:
- ML may appear slightly more conservative on mixed contexts.

Medium term:
- model adaptation should become more stable and more clinically legible.

## Preferences / Activation

No new user preference was added in this lot.

So:

- no extra preference must be enabled,
- no new toggle is required to activate the new causal harmony path,
- existing AIMI / physio / PKPD / ML preferences remain the entry points.

## Validation Performed

Targeted unit tests were run successfully on:

- patient state engine
- patient mode orchestrator
- patient presentation builder
- decision prediction authority
- PKPD integration
- SMB refinement feature schema
- async trainer guard surface
- replay quality export
- recursive belief physio gating

## Remaining Product Opportunities

The current lot materially improves harmony, but the next product step would be:

1. Replay cohort scoring by causal family.
2. Dedicated synthetic datasets for dawn / rebound / inflammatory false-meal confusion.
3. Shadow dashboards comparing `legacy verdict` vs `causal verdict` over time.
4. Optional future calibration of causal thresholds per patient phenotype.

## Bottom Line

The stack is now more coherent because trajectory, physiology tree, PKPD, RBT, replay, and ML all read the same body-state interpretation layer.

This does not merely add more logic. It reduces contradictions between modules, improves false-meal protection, and makes the learned behavior safer to adapt over time.
