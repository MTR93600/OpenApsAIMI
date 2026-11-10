# AIMI Prediction Harmonization - Lot 2

## Objective

Lot 2 pushes physiology into the PKPD prediction path itself, not only into the final arbitration layer.

The goal is to make the prediction stack more harmonious when Autodrive V3 works without declared meals:

- support true undeclared meals earlier
- damp false meal interpretations during cortisol/stress/endogenous rises
- reuse the same physiological variables already present in the codebase
- avoid building a second competing model beside the existing PKPD runtime

## Existing variables reused

This lot intentionally reuses the names and factors already present in the project:

- `PkPdRuntime.fusedIsf`
- `PkPdRuntime.weightKineticFactor`
- `PkPdRuntime.physioAbsorptionFactor`
- `PkPdRuntime.physioSiFactor`
- `MealAbsorptionPhaseEngine.Output.phase`
- `MealAbsorptionPhaseEngine.Output.belief`
- `MealAbsorptionPhaseEngine.Output.mealDeliveryPriority`
- `UamHypothesisState.mealCompatibleProb()`
- `UamHypothesisState.competingNonMealProb()`
- `UamHypothesisState.suppressMealInterpretation`
- `PhysioLatentState.mealProb`
- `PhysioLatentState.endogenousGlucoseDrive`
- `PhysioLatentState.autonomicStress`
- `PhysioLatentState.transientResistanceProb`
- `PhysioLatentState.postHypoReboundProb`
- `PhysioLatentState.falseMealSuppression`

## What changed

### 1. Physiological modulation now enters PKPD prediction

New file:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/PredictionPhysioModulation.kt`

It builds a bounded `PredictionPhysioModulation` from:

- PKPD runtime
- meal absorption output
- UAM hypothesis state
- latent physiological state
- UAM confidence

This modulation influences:

- effective insulin sensitivity used by prediction
- insulin impact factor
- carb impact factor
- UAM momentum factor
- hybrid momentum factor
- momentum decay factor

### 2. `AdvancedPredictionEngine` now accepts this modulation

Updated file:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdvancedPredictionEngine.kt`

The prediction engine still keeps the same path structure:

- `IOB`
- `COB`
- `UAM`
- `ZT`
- `hybrid`

But the curves are now shaped by bounded physio-aware modifiers instead of a neutral momentum-only path.

### 3. PKPD runtime now receives an implicit meal context when evidence converges

Updated file:

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`

New helper:

- `buildPkpdMealContext(...)`

This helper activates PKPD meal context not only from manual meal flags / COB, but also from converging implicit evidence:

- meal absorption active
- meal delivery priority
- `mealCompatibleProb()` high enough
- UAM confidence supportive
- non-meal competition not dominating
- no `falseMealSuppression`

This is the key product piece for undeclared meals.

### 4. Early PKPD prediction now runs after early PKPD runtime bootstrap

This ensures the first prediction of the tick can already consume:

- learned/fused PKPD sensitivity
- weight-aware kinetics
- physio absorption factor

instead of staying fully neutral at the beginning of the tick.

## Behavior impact

### True undeclared meal

Expected behavior:

- stronger meal context enters PKPD earlier
- COB / hybrid path can rise more credibly
- UAM momentum is preserved or slightly reinforced
- final arbitration has a better upstream signal to work with

### Cortisol / stress / endogenous rise

Expected behavior:

- `falseMealSuppression` can now damp prediction itself
- UAM momentum is reduced
- hybrid rise decays faster
- carb impact is reduced

This lowers the risk of misclassifying a hormonal/stress rise as a meal trajectory.

### Post-hypo rebound

Expected behavior:

- rebound and endogenous signals reduce meal interpretation quality
- prediction becomes less eager to escalate meal-like rise
- final SMB/basal logic receives a more coherent upstream trajectory

## Logs to monitor

New prediction audit line:

- `PKPD_PRED_MOD: ...`

Example fields:

- `src`
- `sens`
- `ins`
- `carb`
- `uam`
- `hyb`
- `decay`
- `meal`
- `nonMeal`
- `suppress`

Existing complementary lines remain useful:

- `PRED_AUTHORITY: ...`
- `PKPD_PHYSIO: ...`
- `PRED_DIVERGENCE: ...`

## Preferences / activation

No new user preference was added in this lot.

So there is nothing extra to activate in settings for this feature to run.

It plugs into the current PKPD / physio / UAM stack automatically.

## Validation

Targeted tests passed on this lot:

- `AdvancedPredictionEngineTest`
- `PredictionPhysioModulationResolverTest`
- `PkPdIntegrationTest`
- `AimiRiskEnvelopeBuilderTest`
- `DecisionPredictionAuthorityResolverTest`

## Product reading

Lot 1 made the final prediction authority coherent.

Lot 2 makes the upstream PKPD trajectory itself more coherent.

Together, they reduce the gap between:

- what the body-state interpretation believes
- what the trajectory engine predicts
- what the insulin decision finally does
