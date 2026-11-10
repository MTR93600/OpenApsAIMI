# AIMI Harmonia / Physiological Tree - Multi-Agent Plan

Status: Lots 1–3 implemented (tree + simulation + production RBT) ; H4 meal-rise bridge **partial** + H4b post-hypo aggressive-rise RBT exit **done** (2026-07-17) ; H5–H7 still open — see `aimi-harmonia-implementation.md` §14 / `AIMI_ROADMAP.md` P3
Scope: harmonize physiological context, catch undeclared meal rise via tree, stabilize BG (with TPO/RBT); production basal-first is conditional, not universal second pass

## Agent 1 - Repository Cartographer

### Markdown files analyzed

- `docs/NON_REGRESSION_CHECKLIST.md`
- `docs/AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md`
- `docs/AIMI_RECURSIVE_BELIEF.md`
- `docs/AIMI_PREDICTION_PHYSIO_PKPD_HARMONY_SYNTHESIS_2026-06-10.md`
- `docs/AIMI_CONTROL_CENTER_ADVISOR_BRIDGE_2026-06-14.md`
- `docs/AIMI_CONTROL_CENTER_PRODUCT_BLUEPRINT_2026-06-10.md`
- `docs/AIMI_PHYSIOLOGICAL_PHASE.md`
- `docs/AI_AUDITOR_RECAP.md`

### Kotlin/XML/Gradle files analyzed

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateSnapshot.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/CausalStatePosterior.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientEventMemory.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientModeOrchestrator.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRepository.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresher.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentation.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/PhysioLatentState.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/physio/UamHypothesisState.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorDataStructures.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorDataCollector.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorPromptBuilder.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/advisor/auditor/AuditorOrchestrator.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/context/ContextLLMClient.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/context/ContextManager.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/context/ui/ContextActivity.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/llm/LlmWorldConservativePreamble.kt`
- `plugins/aps/src/main/res/layout/activity_context.xml`
- `plugins/aps/src/main/res/values/strings.xml`
- `core/keys/src/main/kotlin/app/aaps/core/keys/BooleanKey.kt`

### Current AIMI decision flow

1. `DetermineBasalAIMI2` computes physiology, meal absorption, UAM, PKPD, safety, RBT/T3C, SMB/TBR and final result.
2. `PatientStateEngine` consolidates physiological phase, meal absorption, latent physio, UAM, pattern catalog, thermal belief, user context and event memory.
3. `PatientModeOrchestrator` turns that patient state into a readable mode and strategy.
4. `PatientStateRuntimeRepository` publishes the latest patient runtime for AIMI Context and refreshers.
5. `AIMI_Decisions.jsonl` exports `physio_latent_state`, `uam_hypotheses`, `patient_state`, `patient_mode`, `recursive_belief` and safety/risk blocks.

### Existing concepts already implemented

- `CausalStatePosterior`: fast meal, prolonged meal, dawn endogenous, post-hypo recovery, stress resistance, exercise afterburn, inflammatory drift, absorption uncertainty.
- `PatientEventMemory`: recent hyper load, recent hypo load, post-hyper exhaustion and correction fragility.
- `PhysioLatentState`: meal probability, endogenous drive, circadian SI, transient resistance, sleep debt, autonomic stress, inflammation, hormonal circadian, post-hypo rebound, sensor confidence.
- `UamHypothesisState`: meal, dawn, stress, post-hypo and late-fat hypotheses.
- `PatientModeOrchestrator`: strategy hints for meal, basal bridge, conservative observation, hypo recovery and PKPD reassessment.
- `LlmWorldConservativePreamble`: shared read-only LLM reasoning guard.
- `AIMI Context`: already reads `PatientStateRuntimeRepository` for the patient understanding screen.

### Existing documentation that must be preserved

- The causal tree direction in `docs/AIMI_PRODUCT_HARMONY_CAUSAL_TREE_2026-06-14.md`.
- The RBT rule that engines publish leaves/signals rather than hidden contradictory decisions.
- The physio activation nuance in `docs/AIMI_PHYSIOLOGICAL_PHASE.md`: `AimiPhysioAssistantEnable` neutralizes multipliers, while classifiers may still run for scenario/HTR.
- The non-regression gates for AIMI, ML JSON/CSV, physio, dashboard, and Hormonitor in `docs/NON_REGRESSION_CHECKLIST.md`.

### Critical files that must not be modified without tests

- `DetermineBasalAIMI2.kt` because it owns final SMB/TBR/basal decisions and JSONL export.
- `BooleanKey.kt` because preference defaults can silently change behavior.
- `DecisionPredictionAuthority.kt`, `SafetyPredictionTerminalsResolver.kt`, `AimiRiskEnvelope.kt`, `RecursiveBeliefResolver.kt`, `RbtResolutionBridge.kt` because they affect clinical authority.
- ML files such as `AimiSmbTrainer.kt`, `SmbRefinementFeatureSchema.kt` because training must remain asynchronous and schema-compatible.

## Agent 2 - LLM World Architect

### Existing prompts found

- `advisor/auditor/AuditorPromptBuilder.kt`
- `context/ContextLLMClient.kt`
- `advisor/AiCoachingService.kt`
- `advisor/meal/AIVisionProvider.kt`
- `advisor/meal/MealVisionUserPrompt.kt`
- `physio/AIMILLMPhysioAnalyzerMTR.kt`
- `tpo/TpoLlmValidator.kt`

### Prompt files needing LLM World consolidation

- Already using shared preamble: Auditor, Context LLM, AI Coach, Meal Advisor user-context guard, Physio Analyzer, TPO validator.
- Candidate for future richer Harmonia world context: Auditor input schema and AI Coach narrative payload.

### Proposed shared LLM World structure

- Clinical world: hypo, hyper, medical uncertainty, non-prescription, safety gates.
- Physiological world: digestion, meal, activity, post-effort, sleep, recovery, stress, hormones, inflammation, resistance, sensitivity, IOB, COB, sensor.
- Algorithmic world: SMB, basal, ISF, targets, Autodrive, RBT, T3C, snapshots, async ML, logs.
- User world: short explanation, lower cognitive load, non-blaming language, autonomy.
- Tree world: roots, trunk, branches, leaves, fruits, seasons.
- Non-regression world: existing behavior, tests, rollback and compatibility.

### Prompt migration strategy

Lot 1 does not rewrite prompts. It documents that Harmonia should enter prompts only as read-only context after the runtime snapshot and JSON schema stabilize. The existing `LlmWorldConservativePreamble` remains the shared guard. Future prompt migration should add one optional `physiologicalTree` payload to Auditor/Advisor prompts without changing output contracts.

### Regression risks

- Adding Harmonia to a prompt can alter LLM recommendations even without Kotlin dose changes.
- Any LLM prompt must keep "no free dose", "no command pump", and "do not override deterministic gates".
- Prompt changes should be separately tested with golden JSON outputs.

## Agent 3 - Physiological Tree Architect

### Existing related classes

- `PatientStateSnapshot`
- `CausalStatePosterior`
- `PatientEventMemory`
- `PatientModeOrchestrator.Decision`
- `PhysioLiveDigest`
- `ThermalBeliefDigest`
- `PhysioLatentState`
- `UamHypothesisState`

### Proposed model

Create a minimal tree model in the patient package:

- `PhysiologicalTreeSnapshot`
- `PhysiologicalRoots`
- `PhysiologicalTrunk`
- `PhysiologicalBranches`
- `PhysiologicalLeaves`
- `PhysiologicalFruits`
- `PhysiologicalSeasons`
- `PhysiologicalSignalState`
- `GlobalPhysiologicalState`
- `PhysiologicalRiskLevel`
- `DataCoherenceLevel`
- `PhysiologicalTreeBuilder`

### Why this model fits AIMI

The model is not a second clinical truth. It unfolds the existing patient state into a stable product object that can be logged, exported, displayed, and later passed to Auditor/Advisor/Meal Advisor. It is contextual and explanatory in Lot 1; it has no insulin action channel.

### Integration points

- `PatientRuntimeSnapshot`: stores the latest Harmonia tree.
- `PatientStateRuntimeRepository.publish`: computes or accepts the tree when patient state is published.
- `PatientStateRuntimeRefresher`: rebuilds the tree when live body signals or context intent changes.
- `PatientStatePresentationBuilder`: appends the compact tree summary to AIMI Context physiology text.
- `DetermineBasalAIMI2`: exports `adjustments.physiological_tree` and adds a compact runtime log only when the tree is enabled.

### Future extensions

- Add optional read-only `physiologicalTree` block to Auditor input.
- Add Advisor family hints from tree leaves.
- Add Meal Advisor contextual hints for false-meal conflicts and late-fat uncertainty.
- Add fruits/outcome feedback from replay cohorts after enough historical validation.

### What is explicitly out of scope for this lot

- No SMB/basal/ISF modification.
- No direct RBT authority modification.
- No synchronous ML.
- No new pump command or LLM command path.
- No full tree UI redesign.

## Agent 4 - Safety & Non-Regression Officer

### Existing safety gates found

- LGS/hypo and predictive hypo gates.
- Post-hypo delivery authority.
- Sport/activity lockout.
- Meal conflict and false-meal suppression.
- MaxIOB and maxSMB caps.
- IOB surveillance and anti-stacking.
- Safety risk export.
- RBT authority gate and T3C basal-first safety loss conditions.
- PKPD learning guards and causal learning quality.

### Behaviors that must remain identical

- Final SMB amount.
- Final TBR/basal rate and duration.
- ISF and target calculations.
- Autodrive V3 authority.
- T3C basal-first authority.
- ML training/inference timing.
- Existing JSON/CSV write permissions and file paths.
- Hormonitor schema compatibility.

### Regression risks

- Accidentally appending too much text to reason/logs.
- Creating an always-on new preference with unclear default.
- Exposing tree to LLM prompts before golden prompt tests exist.
- Changing `PatientRuntimeSnapshot` constructor call sites without defaults.

### Required tests

- Builder minimal data -> valid tree.
- Insufficient data -> `UNKNOWN` or `PARTIAL`, no crash.
- Low sensor confidence -> `SENSOR_UNCERTAIN` or degraded coherence.
- Meal rise -> digestion/meal branches active.
- Resistance/exhaustion -> resistance branch active.
- Low BG/post-hypo -> hypo branch active.
- Missing steps/HR and missing ML -> no crash.
- Disabled tree -> no tree summary and no export payload.
- Serialization -> JSON object contains no insulin dosing command fields.

### Required build commands

- `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:testFullDebugUnitTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRefresherTest`
- `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:compileFullDebugKotlin`

### Blockers

No blocker identified for Lot 1 if the implementation stays contextual, read-only and gated by the existing physio assistant preference.

## Agent 5 - Implementation Agent

### Files created

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PhysiologicalTree.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PhysiologicalTreeBuilderTest.kt`

### Files modified

- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRepository.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresher.kt`
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentation.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentationBuilderTest.kt`
- `plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresherTest.kt`

### Integration completed

- `PhysiologicalTreeBuilder` builds a concrete Harmonia tree from the existing patient state, patient mode, live physio digest and thermal belief.
- `PatientRuntimeSnapshot` now carries `physiologicalTree`, so the tree is part of the shared AIMI runtime state rather than a detached log.
- `PatientStateRuntimeRepository.publish` and `publishRuntime` preserve the tree through the existing runtime channel.
- `PatientStateRuntimeRefresher` refreshes the tree after live body-signal updates only when the last loop tick already enabled Harmonia, avoiding accidental activation from a context refresh alone.
- `PatientStatePresentationBuilder` consumes the compact tree summary in AIMI Context.
- `DetermineBasalAIMI2` builds the tree during the real loop tick, adds a short console line only for loop ticks, and exports `adjustments.physiological_tree` in `AIMI_Decisions.jsonl`.
- The exported JSON includes `insulin_authority = none_lot1_context_only`, making the Lot 1 boundary explicit.

### Feature flag / disable mechanism

Use existing `BooleanKey.AimiPhysioAssistantEnable`. If it is false, Lot 1 keeps the tree absent from runtime presentation/export, preserving historical behavior.

### Behavior preserved when disabled

No `physiological_tree` JSONL block, no Harmonia line in the patient presentation, and no new reason/log summary.

### Remaining TODOs

- Add read-only Harmonia payloads to Auditor/Advisor/Meal Advisor prompts only after golden prompt tests exist.
- Add outcome "fruits" from replay cohorts once enough validated data is available.
- Add a dedicated UI tree view later; Lot 1 intentionally limits UI impact to the existing AIMI Context summary.

## Agent 6 - Test Agent

### Tests added

- `PhysiologicalTreeBuilderTest.build_returnsNullWhenDisabled`
- `PhysiologicalTreeBuilderTest.build_createsStableTreeWithMinimalCoherentData`
- `PhysiologicalTreeBuilderTest.build_degradesWhenSensorConfidenceIsLow`
- `PhysiologicalTreeBuilderTest.build_marksMealAndDigestionBranchesForFirstWave`
- `PhysiologicalTreeBuilderTest.build_marksResistanceWhenEndogenousAndStressSignalsDominate`
- `PhysiologicalTreeBuilderTest.build_keepsHypoRecoveryAsHighPriorityContext`
- `PhysiologicalTreeBuilderTest.build_handlesMissingWearableAndMlWithoutCrash`
- `PhysiologicalTreeBuilderTest.toJsonObject_exportsContextOnlyWithoutInsulinCommandFields`

### Existing tests impacted

- `PatientStatePresentationBuilderTest` now verifies that AIMI Context contains the compact `Tree:` summary when Harmonia is present.
- `PatientStateRuntimeRefresherTest` now verifies that a physio refresh keeps the tree available after an enabled loop snapshot.

### Regression tests

- Builder disabled path protects historical behavior.
- JSON serialization test confirms no direct insulin command fields such as `smb_u`, `tbr_uph`, or `bolus_u`.
- Missing wearable/ML test confirms optional signals do not crash the runtime.
- Targeted presentation/runtime tests confirm the object is consumed through real runtime paths.

### Commands executed

- `git diff --check`
- `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:testFullDebugUnitTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilderTest --tests app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRefresherTest`
- `GRADLE_USER_HOME=/tmp/gradle-home ./gradlew --no-daemon :plugins:aps:compileFullDebugKotlin`

### Results

- `git diff --check`: pass.
- Targeted tests: pass after rerunning outside the restricted sandbox because Gradle file-lock sockets were blocked by sandbox permissions.
- Kotlin compile: pass after rerunning outside the restricted sandbox for the same Gradle file-lock socket reason.

## Agent 7 - Documentation & Product Agent

### Documentation created/updated

- This plan.
- `docs/aimi-harmonia-implementation.md`

### User-facing summary

Harmonia makes the current body-state interpretation easier to understand: `Tree: resistance probable | conf 72% | risk moderate | sensor ok`.

### Developer-facing summary

Harmonia is a contextual object derived from existing causal state. It is published through existing runtime/export paths, consumed by AIMI Context, and remains non-dosant in Lot 1.

### Next recommended lot

Add read-only Harmonia context to Auditor/Advisor/Meal Advisor payloads with golden prompt tests and without changing output schemas or insulin authority.

## Agent 8 - Final Reviewer

### Analysis completed before code

- [x] yes

### Markdown files reviewed

- [x] yes

### LLM World integrated or migration planned

- [x] yes

### Tree remains first step

- [x] yes

### No shadow-only implementation

- [x] yes - runtime state, AIMI Context presentation, and JSONL export consume the tree

### No insulin behavior changed silently

- [x] yes - non-dosant context only with explicit `insulin_authority = none_lot1_context_only`

### No SMB regression

- [x] yes - SMB logic was not modified

### No basal regression

- [x] yes - basal/TBR logic was not modified

### No ISF regression

- [x] yes - ISF logic was not modified

### No AutoDrive regression

- [x] yes - AutoDrive logic was not modified

### No preference regression

- [x] yes - no new preference planned

### ML remains async

- [x] yes - no ML runtime call added

### Feature can be disabled or neutralized

- [x] yes - existing `AimiPhysioAssistantEnable`

### Tests added

- [x] yes

### Tests executed

- [x] yes

### Build executed

- [x] yes

### Documentation updated

- [x] yes

### Remaining risks documented

- [x] yes
