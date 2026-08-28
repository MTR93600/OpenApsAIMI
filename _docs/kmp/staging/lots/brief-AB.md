# Lot AB — deliberate graph: UAM DTO + latent + patient mode + TPO trigger

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `dda907cf30` (Lot AA BLOCKED)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot AA copy count was 0: no dest-type leftover. This lot is **8 dump copies**. Cap ~15.

**The cut:** `PatientMode` lives in `PatientModeOrchestrator.kt`, which only needs `PatientStateSnapshot` + `UamHypothesisId`. The snapshot engine needs `CausalStatePosterior` + `UamHypothesisState` + `PhysioLatentState`. The **UAM builder** (`UamHypothesisStateBuilder`) needs Compose `AimiBehaviorRuntimeProfile`. **Omit the UAM builder.** Do not copy `AimiControlCenterSupport`, `AimiAutonomyMode`, or Compose screens. This is a documented park of the builder, not a hunt for leftover DTOs.

`PhysioLatentStateBuilder` is dest-type complete once the UAM **DTO** lands (Health Connect snapshot, pattern, classifier, `InflammationAdjuster`, `SourceSensor` are already dest). Copy the full latent file.

Dest already has `PatientEventMemory`, `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `ThermalBeliefDigest`, `ContextSnapshot`, `CorrectionAggressionGate`, `PostHypoAggressiveRiseExit`, `TpoTickInput` / `TpoEpisodeLedger` / `TpoPackId`.

**Compose-graph wall after this lot:** recursive engine still needs dump TickContext (`DecisionPredictionSource` in Authority) / Models (`HarmoniaAction` / tree). Dual-brain auditor still needs `AuditorVerdict`. `MealCertainty.fromTreeAndEnvironment` still needs the tree. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked. Dump `compose/` **screens** stay T2. UAM **builder** stays dump.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (8 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these eight exist at dest (checked 2026-08-28, HEAD `dda907cf30`). Dest `physio/` has classifier / meal engine / pattern / health-context — no UAM / latent. Dest `patient/` has `PatientEventMemory` DTO only — no snapshot / orchestrator / posterior. Dest `safety/` has the rebound gate — no `PostHypoDeliveryAuthority`. Dest `tpo/` has models / ledger — no `TpoTriggerEngine`.

| rel | why |
|---|---|
| `physio/UamHypothesisState.kt` | **enum + DTO only.** Omit `object UamHypothesisStateBuilder`. Drop unused Compose / pattern / gate imports. Add a short KDoc on the data class: builder stays dump until `AimiBehaviorRuntimeProfile` is T1-clean. |
| `physio/PhysioLatentState.kt` | DTO + builder. Dest classifier / meal engine / pattern / `HealthContextSnapshot` / `InflammationAdjuster`; this-lot UAM DTO |
| `patient/CausalStatePosterior.kt` | dest meal engine / pattern / thermal / `PatientEventMemory`; this-lot UAM DTO + latent + same-lot `UserIntentSummary` |
| `patient/PatientStateSnapshot.kt` | dest context / meal / phase / pattern / thermal / `PatientEventMemory`; this-lot UAM + latent + posterior builder |
| `patient/PatientModeOrchestrator.kt` | dest `MealAbsorptionPhase`; this-lot snapshot + `UamHypothesisId` |
| `patient/PatientEventMemoryCalculator.kt` | dest `TimestampedBgSample` / `PatientEventMemory`; this-lot latent DTO |
| `safety/PostHypoDeliveryAuthority.kt` | dest rebound gate / `PostHypoAggressiveRiseExit`; this-lot `PatientMode`. Rewrite `lowercase(Locale.US)` → `lowercase()` |
| `tpo/TpoTriggerEngine.kt` | dest `TpoTickInput` / ledger / `TuningStepTier`; this-lot `PatientMode` + `CausalStateId` |

Copy order (same lot, compile once at the end): UAM DTO → latent → posterior + snapshot together → orchestrator → calculator → post-hypo authority → TPO trigger.

---

## Skip — do not copy this lot

Do **not** copy `UamHypothesisStateBuilder` (leave it in the dump file; dest file must not contain it).

Do **not** copy: `AimiBehaviorRuntimeProfile`, `AimiControlCenterSupport`, Compose screens, `DecisionPredictionAuthority`, `PredictionAuthorityApplier`, `DoseTerminalSnapshotBuilder`, `MealCertainty`, `HarmoniaDecision`, `HarmoniaSmbAuthorityDecision`, `PhysiologicalTree`, recursive engine / TickContext / Models / adapters, `MealCorrectionContextResolver` (still dump `HarmoniaAction`), `basal/T3cAutodriveBasalBridge`, `pkpd/PkpdAbsorptionGuard`, `smb/SmbDampingUsecase`, `PkPdIntegration`, `WCycleLearner` / File, tick, `OpenAPSAIMIPlugin`.

Four Lot L skips: `MealCorrectionContextResolver` still needs dump `HarmoniaAction`. The other three stay Compose / tree. **Do not copy them.**

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 8 use `@Inject`. No Hilt. No `@IntKey(225)`.
2. **Log** — these 8 do not call `aapsLogger`. Do not add log calls.
3. **Time** — no `System.currentTimeMillis()`. `nowMs` / `timestampMs` stay parameters.
4. **Format** — no `String.format`. Do not add `aimiFmt3`.
5. **Locale** — `PostHypoDeliveryAuthority`: `REASON_CODE.lowercase(Locale.US)` → `lowercase()` (Kotlin common; no `java.util.Locale`).
6. **Explicit imports** — no FQ names at use site in files this lot copies. Same-package types — do not write FQ dest names.
7. **KDoc** — `[docs/…]` paths → backticks. Parked `[UamHypothesisStateBuilder]` → backticks. `[AdaptivePkPdEstimator]` in posterior is dest but other package — use a resolvable FQ link. Dest-resolvable same-module types may stay links.
8. **School English** — new or changed comments only. Do not mass-translate dump comments.
9. **Do not** overwrite dest `PatientEventMemory`, Lot Z `compose/` PKPD math, Lot Y / X / W dest files. Do not add keys.

`UamHypothesisState` dest file must compile on iOS: no builder, no Compose types.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-AB.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy the UAM builder, Authority, tree / Harmonia, recursive engine, tick, or plugin.
- Overwrite dest `PatientEventMemory` or dest Lot Z / Y / X / W files.
- Split other dump files in this lot (`AdvisorModels`, `AuditorDataStructures`, `AimiControlCenterSupport`, `DecisionPredictionAuthority`, `MealCertainty`, `HarmoniaDecision`).
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-AB.md`: copied, skipped, rewrite notes (UAM builder omitted, Locale), compile result. State that the UAM builder stays dump. State that TickContext / Models / tree stay dump. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
