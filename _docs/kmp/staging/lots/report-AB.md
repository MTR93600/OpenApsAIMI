# Lot AB — CODE report

Status: **DONE**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `dda907cf30` (Lot AA BLOCKED)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`

No commit. No push. Dump not restored. Plugin not registered (`@IntKey(225)` not added). Dest `PatientEventMemory`, Lot Z `compose/` PKPD math, and Lot Y / X / W dest files were **not** overwritten.

A `commonMain` compile is **not** “AIMI runs on iOS”.

**UAM builder stays dump.** Dest `UamHypothesisState` is the enum + data class only (`AimiBehaviorRuntimeProfile` / Compose).

**TickContext is still blocked** on dump `AimiRiskEnvelope` (`DecisionPredictionSource` in Authority) and dump `SafetyPredictionTerminals` (resolver file). **Models still needs dump `HarmoniaAction`**. Dual-brain auditor still needs `AuditorVerdict`. `MealCertainty.fromTreeAndEnvironment` still needs the tree. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked. Dump `compose/` **screens** stay T2.

---

## Copied (8) — dest did not exist

| rel | notes |
|---|---|
| `physio/UamHypothesisState.kt` | enum + DTO only. Builder omitted. Dropped Compose / pattern / gate imports. KDoc: builder stays dump until `AimiBehaviorRuntimeProfile` is T1-clean |
| `physio/PhysioLatentState.kt` | DTO + builder. Dest classifier / meal engine / pattern / `HealthContextSnapshot` / `InflammationAdjuster`; this-lot UAM DTO |
| `patient/CausalStatePosterior.kt` | dest meal / pattern / thermal / `PatientEventMemory`; this-lot UAM + latent + `UserIntentSummary`. `[AdaptivePkPdEstimator]` → FQ dest link |
| `patient/PatientStateSnapshot.kt` | dest context / meal / phase / pattern / thermal / `PatientEventMemory`; this-lot UAM + latent + posterior builder |
| `patient/PatientModeOrchestrator.kt` | dest `MealAbsorptionPhase`; this-lot snapshot + `UamHypothesisId` |
| `patient/PatientEventMemoryCalculator.kt` | dest `TimestampedBgSample` / `PatientEventMemory`; this-lot latent DTO |
| `safety/PostHypoDeliveryAuthority.kt` | dest rebound gate / rise exit; this-lot `PatientMode`. `lowercase(Locale.US)` → `lowercase()`. kotlinx `put(Double else JsonNull)` split so the type is `JsonElement` |
| `tpo/TpoTriggerEngine.kt` | dest `TpoTickInput` / ledger / `TuningStepTier`; this-lot `PatientMode` + `CausalStateId` |

No dest file was overwritten. Zero dest-exists skips.

---

## Skipped — not this list

| rel | reason |
|---|---|
| `UamHypothesisStateBuilder` (dump `physio/UamHypothesisState.kt`) | Compose `AimiBehaviorRuntimeProfile`. Dest DTO already exists — do **not** overwrite dest |
| `recursive/RecursiveBeliefTickContext.kt` | dump `AimiRiskEnvelope` + dump `SafetyPredictionTerminals` |
| `risk/AimiRiskEnvelope.kt` | dump Authority + `MealCertainty` + resolver. Not split |
| `risk/SafetyPredictionTerminalsResolver.kt` | dump Harmonia / `MealCertainty` / Authority. Not split |
| `risk/DecisionPredictionAuthority.kt` | still dump tree / `MealCertainty` / `PostHypoDeliveryAuthority` now dest but file also needs dump UAM **builder** inputs / `PkPdRuntime`. Not split this lot |
| `recursive/RecursiveBeliefModels.kt` | dump `HarmoniaSmbAuthorityDecision` / dump `HarmoniaAction` |
| `patient/HarmoniaSmbAuthorityDecision.kt` / `HarmoniaDecision.kt` / `PhysiologicalTree.kt` | dump tree / `HarmoniaAction` |
| `patient/MealCertainty.kt` | `fromTreeAndEnvironment` needs dump tree / Harmonia env |
| `MealCorrectionContextResolver.kt` | still dump `HarmoniaAction` |
| `basal/T3cAutodriveBasalBridge.kt` / `pkpd/PkpdAbsorptionGuard.kt` / `smb/SmbDampingUsecase.kt` | remaining Lot L skips (tree / Compose `PkPdRuntime`) |
| tick / `OpenAPSAIMIPlugin` | parked |

Four Lot L skips stay four (`MealCorrection` still needs dump `HarmoniaAction`). **Not copied.**

---

## Rewrite notes

- UAM builder omitted (Compose). Therapy scores on the DTO are unchanged.
- `PostHypoDeliveryAuthority`: `lowercase(Locale.US)` → `lowercase()` (no `java.util.Locale` on Native).
- `PostHypoDeliveryAuthority.toJsonObject`: split `put(Double else JsonNull)` / nullable `Double?` so kotlinx `put` sees one `JsonElement` branch. JSON keys and null meaning unchanged.
- Causal posterior KDoc: `[AdaptivePkPdEstimator]` → FQ dest link.
- No Metro. No `aimiFmt3`. No new `project()` deps. No `@IntKey(225)`.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

Redirect: `/tmp/aimi-lot-AB.log`.

Attempt 1 **FAILED**: `PostHypoDeliveryAuthority.kt` `put("max_smb_u", Double else JsonNull)` inferred `Any`.

Attempt 2 **BUILD SUCCESSFUL** after the `put` split.

A `commonMain` compile is **not** “AIMI runs on iOS”. `HoldAimiEngine` stays Hold. Tick last.

---

## Review

APPROVE.

- Spec: only the Copy list. UAM builder parked like Lot W DTS builder. No leftover hunt. No Authority / tree / Compose screens.
- Quality: Locale + kotlinx `put` are commonMain rewrites, not therapy edits. Dump math copied as-is.
- TickContext / Models / tree still dump. Next graph is Harmonia / tree / `MealCertainty`, or Authority still blocked on those.

Return DONE.
