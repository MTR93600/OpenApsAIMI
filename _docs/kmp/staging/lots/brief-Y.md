# Lot Y — T1 peel: dest-type leftovers after Lot X pattern catalog

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `5b2c729c92` (Lot X)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot X landed all 8 `physio/pattern/*` files (`PatternCapKind`, `PhysiologicalPatternSnapshot`, catalog, detector, policy, hysteresis, cap hold, export). Lot W already landed classifier, meal/endo engines, HTR, and the `DoseTerminalSnapshot` **DTO**. Cap ~15; this list is **5**.

**TickContext spot-check (do not copy):** `recursive/RecursiveBeliefTickContext.kt` is **not** copy-safe. Classifier / meal engine / pattern snapshot / HTR classifier are dest now, but two fields are still dump-only:

- `AimiRiskEnvelope` — dest has `AimiRiskPhase` / `IobDecisionSource` / `AimiRiskConstants` / `PredictionPathBounds` in `risk/IobConsensus.kt` + `risk/PredictionPathMath.kt`. The **data class** still uses dump `DecisionPredictionSource` from `risk/DecisionPredictionAuthority.kt`. The **file** `AimiRiskEnvelope.kt` also calls dump `SafetyPredictionTerminalsResolver`, dump `MealCertainty`, and dump `DecisionPredictionAuthority`. Do **not** split. Do **not** copy Authority (UAM / tree / `PhysioLatentState` / `CausalStatePosterior`).
- `SafetyPredictionTerminals` lives in dump `risk/SafetyPredictionTerminalsResolver.kt` (not its own file). That file needs dump `HarmoniaDecisionEngine` / `MealCertainty` / `DecisionPredictionAuthority`. Do **not** split.

**`RecursiveBeliefModels`:** still needs dump `HarmoniaSmbAuthorityDecision`. `PatternCapKind` is dest, but `HarmoniaSmbArbiter` uses same-package dump `HarmoniaAction` in `patient/HarmoniaDecision.kt` (tree). Do **not** copy Models this lot. Do **not** copy `HarmoniaSmbAuthorityDecision` / `HarmoniaDecision`.

**Compose-graph wall after this lot:** recursive engine still needs dump TickContext (risk types) / Models (`HarmoniaAction` / tree). Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (5 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these five exist at dest (checked 2026-08-28, HEAD `5b2c729c92`). Dest `activity/` has `ActivityContext` / `ActivityManager` / `EffortActivityBelief` — no exercise override. Dest `autodrive/` has no `controller/`. Dest `autodrive/learning/` has auditor / online learner / dataset schema — no stress mask. Dest has no `quality/`. Dest `pkpd/` has curves / governors / learner — no soft-floor path-min.

Dump scan on these 5: no `android.*`, `File`, `org.json`, Compose, Android Activity, tick (`DetermineBasalAIMI2`), `OpenAPSAIMIPlugin`, or `PkPdIntegration`. No `System.currentTimeMillis()`. `SmbBindingTrace` comments name `org.json` — comment only; writes use kotlinx.serialization. `PhysiologicalStressMaskBuilder` KDoc names dump `MechanismAttentionGate` — backticks. `PkpdSoftFloorPathMin` KDoc names dump `AdvancedPredictionEngine` — backticks. `ExerciseHyperOverridePolicy` KDoc names `ThyroidEffectModel` without importing it — backticks.

The five files do not depend on each other. Copy in any order.

| rel | why |
|---|---|
| `activity/ExerciseHyperOverridePolicy.kt` | Lot L skip now unblocked: dest `HyperTrajectoryHypoCredibility.highBgBandMgdl` |
| `autodrive/controller/MpcController.kt` | Lot V leftover now unblocked: dest `HyperTrajectoryMpcFeedForward` + dest `AutoDriveState` / `AutoDriveCommand` / `InsulinActionModel` / `HyperSeverityTier`. Keep Metro `@Inject` / `@SingleIn` / `AppScope` |
| `autodrive/learning/PhysiologicalStressMaskBuilder.kt` | Lot X unblock: dest `PhysiologicalPatternSnapshot` / `PhysiologicalPatternId` + dest classifier / `HealthContextSnapshot` / `PhysioContextMTR` / `PhysioDecisionTraceMTR` / `CorrectionAggressionGate` / `InflammationAdjuster`. Rewrite `String.format` + `Locale` |
| `quality/SmbBindingTrace.kt` | Lot X unblock: dest `PatternCapKind` only (plus kotlinx.serialization). Keep `internal` |
| `pkpd/PkpdSoftFloorPathMin.kt` | Lot W DTO unblock: dest `DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL` + dest `AdvancedPredictionCurves` |

---

## Skip — do not copy this lot

### TickContext / Models / Harmonia (checked, still blocked)

| rel | why not this lot |
|---|---|
| `recursive/RecursiveBeliefTickContext.kt` | dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority file) + dump `SafetyPredictionTerminals` (resolver file) |
| `risk/AimiRiskEnvelope.kt` | dump Authority + `MealCertainty` + `SafetyPredictionTerminalsResolver`. Do **not** split the data class |
| `risk/SafetyPredictionTerminalsResolver.kt` | dump `HarmoniaDecisionEngine` / `MealCertainty` / Authority. Do **not** split the DTO |
| `risk/DecisionPredictionAuthority.kt` | UAM / tree / `PhysioLatentState` / `CausalStatePosterior`. Do **not** split `DecisionPredictionSource` |
| `recursive/RecursiveBeliefModels.kt` | dump `HarmoniaSmbAuthorityDecision` |
| `patient/HarmoniaSmbAuthorityDecision.kt` | dest `PatternCapKind` is not enough; same-package dump `HarmoniaAction` in `HarmoniaDecision.kt` |
| `patient/HarmoniaDecision.kt` | dump `PhysiologicalTreeSnapshot` |
| `recursive/RecursiveBeliefEngine.kt` / `BeliefLeafRegistry.kt` / `BeliefLeafAdapter.kt` / `BeliefLeafAdapterRegistry.kt` | need TickContext |
| `recursive/RecursiveBeliefParadox.kt` / `RecursiveBeliefResolver.kt` | TickContext + dump Harmonia arbiter |
| `recursive/CredibilityCascade.kt` / `RbtChaosEvaluator.kt` / `RbtResolutionBridge.kt` / `UnfoldExporter.kt` | need Models snapshot types |
| `recursive/RecursiveBeliefReleaseCalculator.kt` | dest HTR evaluator now, but still dump TickContext |
| `recursive/RecursiveBeliefAuthorityGate.kt` | dump UAM / `PatientMode` / `PhysioLatentState` / snapshot |

### Remaining Lot L skips (4 after this lot takes exercise override)

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose**) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Other files unblocked on pattern types only — still not T1-clean

| rel | why not copy-safe |
|---|---|
| `physio/UamHypothesisState.kt` | **Compose** `AimiBehaviorRuntimeProfile` |
| `physio/PhysioLatentState.kt` | dump `UamHypothesisState` (same package, no import). Do **not** split |
| `patient/PatientStateLoopCache.kt` | dump `UamHypothesisState` |
| `patient/PatientStateSnapshot.kt` / `CausalStatePosterior.kt` | UAM / latent / tree / meal-certainty graph |
| `quality/ReplayQualityExport.kt` | UAM / `PatientMode` / Models / authority gate |
| `autodrive/safety/AutoDriveGater.kt` | dump `HealthContextRepository` + dump `MealChannelHint` |
| `autodrive/learning/MechanismAttentionGate.kt` | `org.json` + dump `AimiStorageHelper` |
| `safety/PostHypoDeliveryAuthority.kt` | dump `PatientMode` |
| `tpo/TpoTriggerEngine.kt` | dump `PatientMode` + `CausalStateId` |
| `advisor/tuning/TuningContextEngine.kt` | dump `AdvisorMetrics` in `AdvisorModels.kt`. Do **not** split |
| `pkpd/AdvancedPredictionEngine.kt` | dump `PredictionPhysioModulation` (resolver still UAM / `PkPdRuntime`). Do **not** split |
| `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` | dump `AuditorVerdict` |
| `DoseTerminalSnapshotBuilder` (in dump `orchestration/DoseTerminalSnapshot.kt`) | dump Authority. Dest DTO already exists — do **not** overwrite dest |
| `patient/PatientModeOrchestrator.kt` | not T1-clean |
| `pkpd/PkPdIntegration.kt` | Compose |
| tick / `OpenAPSAIMIPlugin` | parked |

Five Lot L skips become **four** after Copy. `WCycleAdjuster` / File learners, runtime patient repos, Health Connect, trainers — still parked.

Do **not** copy dest-already-present Lot X `physio/pattern/*` or Lot W classifier / HTR / meal-endo / DTS DTO.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — `MpcController` already uses `dev.zacsweers.metro.Inject` / `AppScope` / `SingleIn`. Keep them. No Hilt. No `javax.inject`. No `@IntKey(225)`. The other four have no `@Inject`.
2. **Log** — `MpcController`: `LTag.APS` → `LTag.AIMI`. The other four do not call `aapsLogger`. Do not add log calls.
3. **Time** — no `System.currentTimeMillis()`. `SmbBindingTrace.timestampMs` is a field. Keep it.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format`. Use `aimiFmt0` / `aimiFmt1` / `aimiFmt2`. Do **not** add `aimiFmt3`.
   - `PhysiologicalStressMaskBuilder` `String.format(Locale.US, "%.2f", …)` → `aimiFmt2`. Drop `java.util.Locale`.
5. **`@Volatile`** — none of these 5 use it. Not added.
6. **Explicit imports** — no FQ names at use site.
7. **KDoc** — `[docs/…]` paths → backticks. Unresolvable dump types → backticks: `[MechanismAttentionGate]`, `[AdvancedPredictionEngine]`, `[ThyroidEffectModel]`. Dest-resolvable `[PatternCapKind.HARD]` / `[DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL]` / `[HyperTrajectoryHypoCredibility]` may stay links. Do **not** edit dest `InsulinActionModel` / `ContextInfluenceEngine` KDoc (they already name dump `MpcController` / `ExerciseHyperOverridePolicy`).
8. **School English** — new or changed comments only. Do not mass-translate dump French comments on `MpcController`.
9. **JSON** — keep kotlinx.serialization builders on `SmbBindingTrace`. No `org.json`. No `R.string`.

Do **not** add keys. Do **not** register the APS plugin.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-Y.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy TickContext, Models, Harmonia, Authority, UAM, `PhysioLatentState`, builder, tick, or plugin.
- Split `AimiRiskEnvelope` / `SafetyPredictionTerminalsResolver` / `DecisionPredictionAuthority` / `HarmoniaDecision` / `PhysioLatentState` / `UamHypothesisState` / `AdvisorModels` to manufacture a Copy list.
- Overwrite Lot X dest `physio/pattern/*` or Lot W dest classifier / HTR / meal engine / DTS DTO.
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-Y.md`: copied, skipped, rewrite notes (`LTag.AIMI`, `aimiFmt2` on stress mask), compile result. State that TickContext is still blocked on dump `AimiRiskEnvelope` / `SafetyPredictionTerminals` (not only `PatternCapKind`). State that Models still needs dump `HarmoniaAction`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
