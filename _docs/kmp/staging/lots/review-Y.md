# Lot Y — Architect + Kotlin/KMP review

Reviewer: senior architect + senior Kotlin/KMP  
Branch: `kmp-aimi-migration-study`  
HEAD at review: `5b2c729c92` (Lot X). Lot Y files are in the working tree (uncommitted).  
Scope: 5 new dest files only. No production Kotlin was edited. No commit.

---

## Checklist results

### Copy list exactness

| file | dest existed before? | action | result |
|---|---|---|---|
| `activity/ExerciseHyperOverridePolicy.kt` | no | copied | ✅ |
| `autodrive/controller/MpcController.kt` | no | copied | ✅ |
| `autodrive/learning/PhysiologicalStressMaskBuilder.kt` | no | copied | ✅ |
| `quality/SmbBindingTrace.kt` | no | copied | ✅ |
| `pkpd/PkpdSoftFloorPathMin.kt` | no | copied | ✅ |

No dest file was overwritten. Zero skip events. Lot X `physio/pattern/*` and Lot W classifier / HTR / meal engine / DTS DTO confirmed untouched.

### KMP bans — all 5 files

| ban | result |
|---|---|
| `android.*` import | ✅ none |
| `androidx.*` import | ✅ none |
| `@Composable` | ✅ none |
| `System.currentTimeMillis()` | ✅ none |
| `String.format` / `java.util.Locale` | ✅ none |
| `kotlin.jvm.Volatile` | ✅ none |
| `org.json` in code (comment-only reference in SmbBindingTrace line 181 allowed per brief) | ✅ compliant |
| `aimiFmt3` | ✅ none |

### Metro / DI — MpcController

| rule | result |
|---|---|
| `dev.zacsweers.metro.Inject` kept | ✅ |
| `AppScope` / `SingleIn` kept | ✅ |
| No `javax.inject` | ✅ |
| No `@IntKey(225)` | ✅ |
| No Hilt annotation | ✅ |
| `LTag.APS` → `LTag.AIMI` (both `aapsLogger.debug` calls, lines 195 and 200) | ✅ |
| `aimiFmt2` imported and used (not `aimiFmt3`) | ✅ |

Other 4 files have no `@Inject` and no logger calls — correct per brief.

### KDoc backtick / link conversions

| symbol | file | treatment | correct? |
|---|---|---|---|
| `` `ThyroidEffectModel` `` | ExerciseHyperOverridePolicy line 21 | backtick | ✅ |
| `` `MechanismAttentionGate` `` | PhysiologicalStressMaskBuilder line 38 | backtick | ✅ |
| `` `AdvancedPredictionEngine` `` | PkpdSoftFloorPathMin lines 14, 73, 76 | backtick | ✅ |
| `[PatternCapKind.HARD]` | SmbBindingTrace line 217 | live link | ✅ (dest-resolvable) |
| `[DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL]` | PkpdSoftFloorPathMin line 82 | live link | ✅ (dest-resolvable, import present) |

No `[BracketLink]` to a dump type was found across the 5 files.

### Skip compliance

None of the blocked types (RecursiveBeliefTickContext, RecursiveBeliefModels, HarmoniaDecision, HarmoniaSmbAuthorityDecision, PatientMode, AutodriveEngine, AimiRiskEnvelope, SafetyPredictionTerminalsResolver, DecisionPredictionAuthority) appear as imports or usages in any of the 5 dest files.

### Format rewrites

- `PhysiologicalStressMaskBuilder`: `String.format(Locale.US, "%.2f", …)` → `aimiFmt2` at the `formatMask()` call (line 34). `java.util.Locale` import dropped. Explicit `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2` present. ✅
- `MpcController`: `Double.format(2)` / `"%.${digits}f".format` → `aimiFmt2`. Helper removed. Explicit import present. ✅
- `aimiFmt2` import confirmed in both files via targeted grep.

### Explicit imports

No fully qualified names at use site found in any of the 5 files. `AdvancedPredictionCurves` used in `PkpdSoftFloorPathMin` without an import — correct, it is in the same package (`app.aaps.plugins.aps.openAPSAIMI.pkpd`), so no import statement is required. ✅

### Therapy math

Unchanged except format/import/KDoc/unused `JsonArray` drop. Not re-evaluated for algorithmic correctness (out of scope for a migration review).

### School English

New and changed comments are in plain English. French comments in `MpcController` were preserved as-is (per brief rule 8). ✅

### Compile (verified against actual `/tmp/aimi-lot-Y.log`)

```
BUILD SUCCESSFUL in 1m 1s
93 actionable tasks: 14 executed, 79 up-to-date
```

Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain` completed with EXIT 0. Only pre-existing compiler warnings were present (single-`@Inject` constructor advisory in unrelated files; not introduced by Lot Y). No new errors or warnings from any of the 5 new files.

A `commonMain` compile is **not** "AIMI runs on iOS".

---

## Per-file findings

### `activity/ExerciseHyperOverridePolicy.kt`

**What looks good:**
- Clean `object` with a single-purpose `Input` data class. All fields are `val`. ✅
- `HyperTrajectoryHypoCredibility.highBgBandMgdl` referenced correctly (dest type, no import needed — same package? No, different package — imported at line 3). ✅
- `ThyroidEffectModel` referenced only in a doc comment using backticks, no import, no dependency. ✅
- `resolveBasalFactor` uses `kotlin.math.max` + `.coerceAtMost` — both are KMP-safe. ✅

**Observation (non-blocking):**
- The `buildInput()` factory at lines 55–71 is a pure pass-through that provides no additional logic over calling `Input(...)` directly. This is existing dump code; preserving it is correct per brief. No action needed.

---

### `autodrive/controller/MpcController.kt`

**What looks good:**
- `buildDoseCandidates` companion function with the full KDoc explanation of the domain-widening fix. Clear and correctly implemented: step is coarsened rather than the domain being cut. ✅
- `Companion.buildDoseCandidates(...)` call at line 170 — explicit qualifier, avoids any resolution ambiguity inside the class body. ✅
- `horizonDoseLabel` guards safely against absent horizon keys with `?: "n/a"`. ✅
- `OApsAIMIMpcInsulinUPerKgPerStep` confirmed present in `core/keys/src/commonMain/kotlin/app/aaps/core/keys/DoubleKey.kt`. ✅
- `lgsThreshold + lgsBuffer` safety fallback adds 1_000_000.0 cost — numeric literal with underscores for readability. ✅
- `HyperSeverityTier.entries.getOrElse(…) { HyperSeverityTier.OFF }` — safe ordinal lookup. ✅

**Observation (non-blocking):**
- Line 139: `100.0 * dawnCostMult.coerceAtMost(4.0)` — `dawnCostMult` is always 1.0 or 4.0, so the `.coerceAtMost(4.0)` is a no-op. Existing therapy math; preserved per brief.
- Emoji in log strings (`🧮 [MPC]`, `🛡️`, etc.) are carried over from the dump. Brief says to preserve French comments; emoji in strings are the same category. Not a KMP issue. Style note only.

---

### `autodrive/learning/PhysiologicalStressMaskBuilder.kt`

**What looks good:**
- `PhysiologicalStressMask.formatMask()` private extension correctly delegates to `aimiFmt2`. ✅
- `combineSignals` uses a probabilistic OR (`1 - ∏(1 - p_i)`) — mathematically sound for combining independent signals. ✅
- `PhysiologicalPatternSnapshot?.hasAny` and `maxConfidence` extension functions are `null`-safe (`this?.… == true`, `?: 0.0`). ✅
- All types used (`PhysiologicalPatternId`, `PhysiologicalPatternSnapshot`, `CorrectionAggressionGate`, `InflammationAdjuster`, `HealthContextSnapshot`, `PhysioContextMTR`, `PhysioDecisionTraceMTR`, `PhysiologicalPhaseClassifier`) are dest types. ✅
- `internal` visibility on both `PhysiologicalStressMask` and `PhysiologicalStressMaskBuilder`. ✅

No issues found.

---

### `quality/SmbBindingTrace.kt`

**What looks good:**
- `internal` on all three data types (`SmbBindingStage`, `SmbBeforeTerminalProtectionsReplay`, `SmbBindingTrace`) and `Draft`. ✅
- `putNullable` private extension handles all primitive types plus `JsonElement` — exhaustive for any value the callers produce. ✅
- `finiteOrNull()` file-private extension prevents `NaN` / `Inf` from reaching the JSON output. ✅
- `Draft.build()` filters stages to `finiteStages` before writing — defensive against partially-built data. ✅
- `replayCapsBeforeTerminalProtections` correctly applies `PatternCapKind.HARD` only — `SOFT` proposals do not call `min()`. Consistent with the KDoc. ✅
- `buildJsonArray` / `buildJsonObject` from `kotlinx.serialization.json` — no `org.json` in code. ✅

**Observation (non-blocking):**
- `Draft` is `internal data class` nested inside `internal data class SmbBindingTrace`. The redundant `internal` on `Draft` is harmless and makes visibility explicit. Fine to leave.

---

### `pkpd/PkpdSoftFloorPathMin.kt`

**What looks good:**
- `FLOOR_ARTEFACT_NEAR_MGDL` delegates to `DoseTerminalSnapshot.FLOOR_ARTEFACT_NEAR_MGDL` — single source of truth maintained. ✅
- `resolveSoftPathMin` makes Guard A explicit in code with a clear comment. ✅
- `insulinOnlyPathMin` is null-safe: filters finite values, uses `minOrNull()`. ✅
- `liftFloorBandPoints` correctly uses `.coerceAtLeast(NUMERIC_FLOOR_MGDL.toInt())` so the soft floor cannot be raised below the numeric floor. ✅
- `fromCurves` `reason` string covers all branches with no `else -> "unknown"` fallback needed (the when is exhaustive for the boolean combination). ✅
- `AdvancedPredictionCurves` is in the same package — no import required. ✅

No issues found.

---

## Blocked items (unchanged from report-Y.md)

**TickContext** is still blocked on dump `AimiRiskEnvelope` (`DecisionPredictionSource` is inside `DecisionPredictionAuthority.kt`, a dump file with UAM / tree / latent state) and dump `SafetyPredictionTerminals` (resolver file needs `HarmoniaDecisionEngine` / `MealCertainty` / Authority). The block is not only `PatternCapKind` — those dest types are now present, but the two remaining dump fields prevent copy.

**Models** still needs dump `HarmoniaAction` (in `HarmoniaDecision.kt`, a dump tree file). `PatternCapKind` being dest does not unblock `HarmoniaSmbArbiter`.

Dual-brain auditor still needs `AuditorVerdict`. `TpoTriggerEngine` still needs `PatientMode`. `DoseTerminalSnapshotBuilder` / Authority stay dump. Tick / plugin stay parked.

Remaining Lot L skips: 4 (`MealCorrectionContextResolver`, `T3cAutodriveBasalBridge`, `PkpdAbsorptionGuard`, `SmbDampingUsecase`).

---

## Critical Issues 🔴

None.

## Important Issues 🟡

None.

## Suggestions 🟢

None that require action before commit. The non-blocking observations above are all inherited dump code preserved per brief.

## What Looks Good ✅

- All 5 KMP bans pass across all 5 files.
- Metro annotations exactly correct on `MpcController`; other 4 files correctly have no `@Inject`.
- `LTag.AIMI` used in both log calls.
- `aimiFmt2` (not `aimiFmt3`) imported and used wherever format was needed.
- All KDoc backtick / link decisions match the brief rules.
- `AdvancedPredictionCurves` same-package usage: no spurious import.
- `org.json` is comment-only in `SmbBindingTrace`; all writes use `kotlinx.serialization.json`.
- `internal` visibility maintained on all quality types.
- BUILD SUCCESSFUL on both `compileKotlinIosSimulatorArm64` and `compileAndroidMain`. No new warnings.
- Skip list compliance: no blocked type was copied, no dest file was overwritten.

---

APPROVE
