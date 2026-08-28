# Lot Z — T1 peel: dest-type PKPD settings math wrongly grouped with Compose UI

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `08bc621dae` (Lot Y)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot Y landed `ExerciseHyperOverridePolicy`, `MpcController`, `PhysiologicalStressMaskBuilder`, `SmbBindingTrace`, `PkpdSoftFloorPathMin`. Lot X: `physio/pattern/*`. Lot W: classifier, meal/endo, HTR, `DoseTerminalSnapshot` **DTO**. Cap ~15; this list is **2**.

**TickContext spot-check (do not copy):** `recursive/RecursiveBeliefTickContext.kt` is **not** copy-safe. Lot Y types do not appear on TickContext. Still dump-only:

- `AimiRiskEnvelope` — dest has `AimiRiskPhase` / `IobDecisionSource` / `AimiRiskConstants` / `PredictionPathBounds`. The **data class** still uses dump `DecisionPredictionSource` from `risk/DecisionPredictionAuthority.kt`. The **file** also calls dump `SafetyPredictionTerminalsResolver`, dump `MealCertainty`, and dump `DecisionPredictionAuthority`. Do **not** split. Do **not** copy Authority.
- `SafetyPredictionTerminals` lives in dump `risk/SafetyPredictionTerminalsResolver.kt` (not its own file). That file needs dump `HarmoniaDecisionEngine` / `MealCertainty` / `DecisionPredictionAuthority`. Do **not** split.

**`RecursiveBeliefModels`:** still needs dump `HarmoniaSmbAuthorityDecision`. `PatternCapKind` is dest, but `HarmoniaSmbArbiter` uses same-package dump `HarmoniaAction` in `patient/HarmoniaDecision.kt` (tree). Do **not** copy Models. Do **not** copy `HarmoniaSmbAuthorityDecision` / `HarmoniaDecision`.

**Compose-graph wall after this lot:** this lot copies PKPD **settings math** that lived in dump `compose/` with **no** `@Composable` / `androidx.compose`. That does **not** unblock UAM (`AimiBehaviorRuntimeProfile` still needs dump `AimiAutonomyMode` + `R.string`). Recursive engine still needs dump TickContext (risk types) / Models (`HarmoniaAction` / tree). Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

Dump 324 / dest 239 / dump-not-in-dest **204**. Lot Y types only appear on dump leftovers `DetermineBasalAIMI2` (parked), `AutodriveEngine` (data lake / `MechanismAttentionGate` / `PkPdIntegration`), and `PatientStateRuntimeRefresher` (UAM / latent). Those are **not** this class of leftover.

This lot is the next copy-safe set: two dump files whose types already exist in dest **after Lot Y** (and earlier lots). They sat in `lot-A.txt` next to Compose screens — same class of error as Lot Y finding `ExerciseHyperOverridePolicy` after HTR landed, or Lot U finding `HcRecoveryProxyThermalSource` grouped with Health Connect.

---

## Copy (2 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these two exist at dest (checked 2026-08-28, HEAD `08bc621dae`). Dest has **no** `compose/` folder. Dest `pkpd/` already has `PkpdSmbTailDamping` (slider ↔ damping). Dest `model/DecisionModels.kt` already has `AimiAction.PreferenceUpdate`. Keep dump package `app.aaps.plugins.aps.openAPSAIMI.compose` — that is **not** `androidx.compose`.

Dump scan on these 2: no `android.*`, `File`, `org.json`, `@Composable`, `androidx.compose`, Android Activity, tick (`DetermineBasalAIMI2`), `OpenAPSAIMIPlugin`, or `PkPdIntegration`. No `System.currentTimeMillis()`. No `R.string`. `PkpdPresetProfiles` KDoc names dump `PkpdLearningPace` / `PkpdCorrectionPrudence` / `PkpdTailPrudence` — **same-package this lot** after `PkpdSettingsSupport` lands; keep links. `PkpdTailPrudence` KDoc names dest `PkpdSmbTailDamping` — keep the link. `PkpdSettingsSupport` KDoc names “Compose screen” / UI polarity — comment only.

Copy `PkpdPresetProfiles` **before** `PkpdSettingsSupport` (`detectPkpdInsulinPreset` returns `PkpdInsulinPreset`).

| rel | why |
|---|---|
| `compose/PkpdPresetProfiles.kt` | insulin preset clamps + learned-state reclamp; dest `DoubleKey` / `Preferences` only. Grouped with Compose UI by dump folder — no Compose runtime |
| `compose/PkpdSettingsSupport.kt` | dest `PkpdSmbTailDamping` + dest `AimiAction.PreferenceUpdate` + dest keys. **Omit** `pkpdPrefsSnapshotFrom` (dump `PkpdPrefsSnapshot` in `AdvisorModels.kt`). Do **not** split `AdvisorModels` |

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

### Remaining Lot L skips (4 — Lot Y took exercise override)

| rel | Missing type(s) still dump-only / not T1-clean |
|---|---|
| `MealCorrectionContextResolver.kt` | `PatientMode` / orchestrator / snapshot / `PhysioLatentState` / `UamHypothesisState` (**Compose** `AimiBehaviorRuntimeProfile`) / `HarmoniaAction` / `PostHypoDeliveryAuthority` |
| `basal/T3cAutodriveBasalBridge.kt` | `GlobalPhysiologicalState`, `PhysiologicalRiskLevel`, `PhysiologicalTreeSnapshot` |
| `pkpd/PkpdAbsorptionGuard.kt` | `PkPdRuntime` lives in `PkPdIntegration.kt` (**Compose**) |
| `smb/SmbDampingUsecase.kt` | same `PkPdRuntime` / Compose file |

### Hunted dest-type leaves (not copy-safe — do not copy)

Looked again after Lot Y. These are **not** the Compose-folder grouping error above.

| rel | why not copy-safe |
|---|---|
| `compose/PkpdSettingsUi.kt` / `AimiPkpdSettingsScreen.kt` / `AimiControlCenterScreen.kt` | `@Composable` / `androidx.compose`. Do **not** copy |
| `compose/AimiBehaviorRuntimeProfile.kt` | dump `AimiAutonomyMode` in `AimiControlCenterSupport.kt` (`@StringRes` + `R.string`) |
| `compose/AimiControlCenterSupport.kt` / `AimiControlCenterSnapshot.kt` / `AimiControlCenterAdvisor.kt` / `AimiBehaviorFamilyRegistry.kt` | `R.string` / `@StringRes` / dump `AimiStringKey` |
| `keys/AimiStringKey.kt` | `titleResId` + `R.string` + dump `UnifiedActivityProviderMTR` |
| `autodrive/AutodriveEngine.kt` | dest `MpcController` is not enough; dump data lake / `MechanismAttentionGate` / `PkPdIntegration` |
| `patient/PatientStateRuntimeRefresher.kt` | dest `PhysiologicalStressMaskBuilder` is not enough; dump UAM / latent |
| `advisor/tuning/TuningContextEngine.kt` | dump `AdvisorMetrics` in `AdvisorModels.kt`. Do **not** split |
| `pkpd/AdvancedPredictionEngine.kt` | dump `PredictionPhysioModulation` (resolver still UAM / `PkPdRuntime`). Do **not** split |
| `pkpd/PredictionPhysioModulation.kt` | DTO is primitives; **same file** has resolver (`UamHypothesisState` / `PhysioLatentState` / `PkPdRuntime`). Do **not** split |
| `advisor/auditor/DualBrainHelpers.kt` / `DecisionModulator.kt` / `AuditorStableContextGuard.kt` / `AuditorPromptBuilder.kt` | dump `AuditorVerdict` / `AuditorInput`. Do **not** split |
| `tpo/TpoTriggerEngine.kt` | dump `PatientMode` + `CausalStateId`. Do **not** split orchestrator / posterior |
| `tpo/TpoSessionManager.kt` | dump `AdvisorHistoryRepository` (`android` + clock) + `System.currentTimeMillis()` |
| `tpo/TpoPersistence.kt` / `TpoLlmValidator.kt` / `TpoOrchestrator.kt` / `TpoNotificationManager.kt` / `TpoUiSupport.kt` | `File` / `org.json` / `android` / Compose / `R.string` |
| `hormonitor/viewer/HormonitorLabels.kt` | `Locale.getDefault()` language (FR vs other). No commonMain locale without `iosMain` |
| `autodrive/safety/AutoDriveGater.kt` | dump `HealthContextRepository` + dump `MealChannelHint` (in Models) |
| `autodrive/learning/MechanismAttentionGate.kt` | `org.json` + dump `AimiStorageHelper` |
| `safety/PostHypoDeliveryAuthority.kt` | dump `PatientMode` |
| `quality/ReplayQualityExport.kt` | UAM / `PatientMode` / Models / authority gate |
| `physio/UamHypothesisState.kt` | **Compose** `AimiBehaviorRuntimeProfile` |
| `physio/PhysioLatentState.kt` | dump `UamHypothesisState` (same package, no import). Do **not** split |
| `patient/PatientStateLoopCache.kt` / `PatientStateSnapshot.kt` / `CausalStatePosterior.kt` | UAM / latent / tree / meal-certainty graph |
| `wcycle/WCycleAdjuster.kt` | dest `WCycleEstimator` is not enough; dump `WCycleLearner` (`android.*` + `File` + `org.json`) |
| `basal/BasalDecisionEngine.kt` | `android.content.Context` + `R.string` + `System.currentTimeMillis()` |
| `llm/LlmHttpRetry.kt` | `android.util.Log` + `Thread.sleep` |
| `orchestration/AimiLoopGate.kt` / `AimiLoopRuntimeGuard.kt` / `AimiLoopTickRecovery.kt` | tick lock (`ReentrantLock` / dump telemetry) |
| `DoseTerminalSnapshotBuilder` (in dump `orchestration/DoseTerminalSnapshot.kt`) | dump Authority. Dest DTO already exists — do **not** overwrite dest |
| `patient/PatientModeOrchestrator.kt` | not T1-clean |
| `pkpd/PkPdIntegration.kt` | Compose |
| tick / `OpenAPSAIMIPlugin` | parked |

Four Lot L skips stay four. `WCycleAdjuster` / File learners, runtime patient repos, Health Connect, trainers — still parked.

Do **not** copy dest-already-present Lot Y / Lot X `physio/pattern/*` / Lot W classifier / HTR / meal-endo / DTS DTO.

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — neither file uses `@Inject`. No Hilt. No `javax.inject`. No `@IntKey(225)`.
2. **Log** — neither file calls `aapsLogger`. Do not add log calls.
3. **Time** — no `System.currentTimeMillis()`.
4. **Format** — no `String.format`, no `java.util.Locale`, no `"%.nf".format`. Do **not** add `aimiFmt3`.
5. **`@Volatile`** — neither file uses it. Not added.
6. **Explicit imports** — no FQ names at use site. `PkpdSettingsSupport` must import dest `app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping` and dest `app.aaps.plugins.aps.openAPSAIMI.model.AimiAction`. Same-package `PkpdInsulinPreset` — no FQ dest name.
7. **KDoc** — `[docs/…]` paths → backticks. After both files land, same-package `[PkpdLearningPace]` / `[PkpdCorrectionPrudence]` / `[PkpdTailPrudence]` / `[PkpdInsulinPreset]` may stay links. Dest-resolvable `[PkpdSmbTailDamping]` / `[DoubleKey]` may stay links. `[PkpdPrefsSnapshot]` (parked mapper) → backticks. Do **not** edit dest `PkpdSmbTailDamping` KDoc.
8. **School English** — new or changed comments only.
9. **JSON** — neither file uses JSON. No `org.json`. No `R.string`.
10. **Omit** — `PkpdSettingsSupport`: drop `pkpdPrefsSnapshotFrom` and unused `PkpdPrefsSnapshot` / `BooleanKey` imports if they become unused. Add a short KDoc on the file: prefs snapshot mapper stays dump until `AdvisorModels` is T1-clean. Do **not** split `AdvisorModels` to extract `PkpdPrefsSnapshot`.

Do **not** add keys. Do **not** register the APS plugin. Do **not** invent AIMI `iosMain`. Do **not** copy `PkpdSettingsUi.kt`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-Z.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy TickContext, Models, Harmonia, Authority, UAM, `PhysioLatentState`, builder, tick, or plugin.
- Copy `@Composable` screens in dump `compose/` (`PkpdSettingsUi`, Control Center, PKPD settings screen).
- Split `AimiRiskEnvelope` / `SafetyPredictionTerminalsResolver` / `DecisionPredictionAuthority` / `HarmoniaDecision` / `PhysioLatentState` / `UamHypothesisState` / `AdvisorModels` to manufacture a Copy list.
- Overwrite Lot Y dest files or Lot X dest `physio/pattern/*` or Lot W dest classifier / HTR / meal engine / DTS DTO.
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-Z.md`: copied, skipped, rewrite notes (`pkpdPrefsSnapshotFrom` omitted), compile result. State that TickContext is still blocked on dump `AimiRiskEnvelope` / `SafetyPredictionTerminals` (Lot Y types did not unblock it). State that dump `compose/` **screens** stay T2. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
