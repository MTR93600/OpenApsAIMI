# Lot W — deliberate graph: DoseTerminal DTO + HTR + meal/endo phase engines

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
HEAD: `dff20cbd17` (Lot V BLOCKED)  
Dump: `_docs/kmp/staging/openAPSAIMI-android-wip/`  
Dest: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/`  
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. ADR G0 is frozen — do not reopen.

Lot V copy count was 0: no dest-type leftover. This lot is **13 dump copies + 1 in-memory circadian prior** (File persist stays dump). Cap ~15.

**The cut:** `HyperTrajectoryHypoCredibility` only needs `DoseTerminalSnapshot` **companion constants** (`PLATEAU_BG_MGDL`, `FLOOR_ARTEFACT_NEAR_MGDL`, `PLATEAU_FLAT_DELTA_ABS_MGDL`). The **builder** (`DoseTerminalSnapshotBuilder`) needs dump `DecisionPredictionAuthority` + `PredictionAuthorityApplyResult` (UAM / tree / `PkPdRuntime`). **Omit the builder.** Do not copy `DecisionPredictionAuthority`, `PredictionAuthorityApplier`, `UamHypothesisState`, or `PkPdIntegration`. This is a documented park of the builder, not a hunt for leftover DTOs.

Dest already has `HyperSeverityTier`, `HyperTrajectoryReleaseResult`, `ClampPkpdScenarioReconcile`, `MealAbsorptionPhase`, `PhysiologicalPhase`, `PhysioMultipliersMTR`, `BehavioralRiskPolicy`, `CyclePhase`, `CycleTrackingMode`.

**Compose-graph wall after this lot:** recursive engine still needs dump `PhysiologicalPatternSnapshot` / `PatternCapKind` (`physio/pattern/*`). Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. Tick / plugin stay parked.

**Do not copy the whole dump.** Copy only the **Copy** list.

---

## Copy (13 files)

From `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`  
to `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`.

If dest already exists: **skip that file and report**. Do not overwrite.

None of these thirteen exist at dest (checked 2026-08-28, HEAD `dff20cbd17`). Dest `release/` has `HyperSeverityTier.kt` / `HyperTrajectoryReleaseResult.kt` only. Dest `orchestration/` has no `DoseTerminalSnapshot`. Dest `physio/` has phase enums / Lot O DTOs — no classifier / meal engine / hysteresis.

| rel | why |
|---|---|
| `orchestration/DoseTerminalSnapshot.kt` | **DTO + companion constants + `formatLogLine` only.** Omit `object DoseTerminalSnapshotBuilder` and its `build` / `shouldLiftPlateauFloorArtefact`. Drop unused imports (`DecisionPredictionAuthority`, `ClampPkpdScenarioReconcile`, `kotlin.math`) after the omit. Add a short KDoc on the data class: builder stays dump until Authority is T1-clean. |
| `release/HyperTrajectoryHypoCredibility.kt` | uses dest `ClampPkpdScenarioReconcile` + this-lot DTS constants |
| `release/HyperSeverityClassifier.kt` | dest `MealAbsorptionPhase` / `TrajectoryType` / `HyperSeverityTier`; this-lot hypo-credibility `highBgBandMgdl` |
| `release/HyperTrajectoryReleasePreferences.kt` | dest BooleanKey / DoubleKey; this-lot `HyperSeverityClassifier.establishedDevMgdl` (`internal`, same package) |
| `release/HyperTrajectoryMpcFeedForward.kt` | this-lot `HyperSeverityClassifier.Output` |
| `release/HyperTrajectoryReleaseEvaluator.kt` | dest `BehavioralRiskPolicy` / `MealAbsorptionPhase` / `TrajectoryType`; this-lot classifier + hypo-credibility |
| `physio/MealAbsorptionMemory.kt` | dest `MealAbsorptionPhase`; this-lot engine `Output` |
| `physio/MealAbsorptionPhaseHysteresis.kt` | this-lot engine `Output` |
| `physio/MealAbsorptionPhaseEngine.kt` | this-lot memory + hysteresis + hypo-credibility |
| `physio/CircadianMealProfileStore.kt` | **in-memory prior only** — not dump File persist. Needed by `chronoPrior`. |
| `physio/EndogenousCounterRegulatoryDetector.kt` | this-lot classifier `Input` + `HyperSeverityClassifier` |
| `physio/PhysiologicalPhaseClassifier.kt` | this-lot hypo-credibility + severity classifier + endogenous detector; dest WCycle types |
| `physio/EndogenousPhaseHysteresis.kt` | this-lot classifier `Output` |
| `physio/PhysioPhaseFusion.kt` | dest `PhysioMultipliersMTR`; this-lot `classifyWithHysteresis` |

Copy order (same lot, compile once at the end): DTS DTO → HypoCredibility → HyperSeverityClassifier → ReleasePreferences / MpcFeedForward; Memory → Hysteresis → MealEngine; Classifier + EndogenousDetector together; EndogenousPhaseHysteresis; ReleaseEvaluator; PhysioPhaseFusion.

---

## Skip — do not copy this lot

Do **not** copy `DoseTerminalSnapshotBuilder` (leave it in the dump file; dest file must not contain it).

Do **not** copy: `DecisionPredictionAuthority`, `PredictionAuthorityApplier`, `UamHypothesisState`, `PhysioLatentState`, `PkPdIntegration`, `physio/pattern/*`, recursive engine / TickContext / Models / adapters, `TpoTriggerEngine`, `PatientModeOrchestrator`, `AuditorDataStructures`, dual-brain auditor helpers, `WCycleLearner` / File, tick, `OpenAPSAIMIPlugin`.

Five Lot L skips still need Compose / tree / `PkPdRuntime`. **Do not copy them.**

---

## Rewrite on copy (Milos / merge rules)

Keep therapy math. Change only what commonMain needs.

1. **Metro** — none of these 13 use `@Inject`. No Hilt. No `@IntKey(225)`.
2. **Log** — these 13 do not call `aapsLogger`. Do not add log calls. `formatLogLine` stays a string builder.
3. **Time** — no `System.currentTimeMillis()` in this list. Meal engine `Input.nowMs` is a parameter. Keep it.
4. **Format** — no `String.format`. `formatLogLine` already uses Kotlin string templates. Do not add `aimiFmt3`.
5. **`@Volatile`** (`MealAbsorptionMemory`, `MealAbsorptionPhaseHysteresis`, `EndogenousPhaseHysteresis`) — `import kotlin.concurrent.Volatile`. Not `kotlin.jvm.Volatile`.
6. **Explicit imports** — no FQ names at use site. Same-package `HyperSeverityTier` / `MealAbsorptionPhase` / `PhysiologicalPhase` — do not write FQ dest names.
7. **KDoc** — `[docs/…]` paths → backticks. `[DoseTerminalSnapshotBuilder]` → backticks (parked). Unresolvable `[SafetyNet]` if not on this module classpath → backticks. Dest-resolvable `[PhysiologicalPhase]` / `[PhysioMultipliersMTR]` may stay links.
8. **School English** — new or changed comments only. Do not mass-translate dump comments.
9. **Do not** edit dest `ClampPkpdScenarioReconcile` KDoc (it still names the dump builder). Do not add keys.

`DoseTerminalSnapshot` dest file must compile on iOS: no builder, no Authority types.

---

## Compile

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`. No `cd &&`. Redirect to `/tmp/aimi-lot-W.log`. Do not pipe to `tail` for pass/fail.

A commonMain compile is not “AIMI runs on iOS”.

---

## Do not

- Copy the builder, Authority, UAM Compose, pattern catalog, recursive engine, tick, or plugin.
- Overwrite dest `HyperSeverityTier` / `HyperTrajectoryReleaseResult`.
- Split other dump files in this lot (`AdvisorModels`, `AuditorDataStructures`, `PatientModeOrchestrator`).
- Register `@IntKey(225)`. Do not invent AIMI `iosMain`.
- Commit. No push.

---

## Report

`_docs/kmp/staging/lots/report-W.md`: copied, skipped, rewrite notes (`@Volatile`, builder omitted), compile result. State that the builder and Authority stay dump. State that recursive engine still needs `physio/pattern/*`. Return DONE | DONE_WITH_CONCERNS | BLOCKED.
