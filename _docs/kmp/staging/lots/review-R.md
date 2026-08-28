# Lot R — REVIEW

Reviewer: senior architect + senior Kotlin/KMP reviewer  
Date: 2026-08-28  
HEAD: `f94b504ebb` (Lot Q)  
Lot: R — TPO / AutoDrive / Basal / Context / OREF  
Files reviewed: 15 new dest files (untracked, working tree)  
Compile claim: BUILD SUCCESSFUL attempt 2 (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`)

---

## Summary

All 15 files from the brief-R Copy list are present at the correct dest paths. No dest-exists skips. No Skip files copied. No Lot O/P/Q file overwritten. Every mandatory rewrite from brief-R section 8 is applied: `aimiWallClockMs()` replaces all `System.currentTimeMillis()` occurrences (parameters kept); `aimiFmt0/1/2` and `NumberFormat.withDecimals` replace all `String.format` / `Locale` / `"%.nf".format` calls; `OrefReasonParser` rewrites `Pattern` → `Regex`; `OnlineLearner` replaces `AtomicLong`/`AtomicReference` with `AapsLock` and the `removeIf` with a collect-then-remove pattern; `BasalHistoryUtils` uses `kotlin.concurrent.Volatile`; `AutoDriveModels` drops `@JvmStatic`; context files import `context.ContextIntent` exclusively; `ContextIntentDeserializer` applies the K2 `toFloat()` paren fix; Metro annotations preserved on the seven injected classes; `LTag.APS` → `LTag.AIMI` on all six required files. Therapy math untouched. No KMP-banned API found anywhere in the 15 files.

One **Important** (must-fix) issue: unused import in `VirtualGlucoseEngine.kt`. One minor indentation artifact in `ContextInfluenceEngine.kt` worth a cosmetic fix.

---

## Checklist

### Copy list exact match

| rel | dest file present | dest was empty before | overwrite? |
|---|---|---|---|
| `tpo/TpoLadderSupport.kt` | ✅ | ✅ (no prior `TpoLadderSupport`) | no |
| `tpo/TpoDeltaBuilder.kt` | ✅ | ✅ | no |
| `tpo/TpoPreferenceKeys.kt` | ✅ | ✅ | no |
| `advisor/oref/OrefReasonParser.kt` | ✅ | ✅ | no |
| `advisor/oref/OrefAnalysisReport.kt` | ✅ | ✅ | no |
| `autodrive/models/AutoDriveModels.kt` | ✅ | ✅ (no `autodrive/models/`) | no |
| `autodrive/safety/ControlBarrierShield.kt` | ✅ | ✅ (no `autodrive/safety/`) | no |
| `autodrive/estimator/ContinuousStateEstimator.kt` | ✅ | ✅ (no `autodrive/estimator/`) | no |
| `autodrive/learning/OnlineLearner.kt` | ✅ | ✅ (only `AutodriveDatasetSchema.kt` existed) | no |
| `basal/BasalHistoryUtils.kt` | ✅ | ✅ (no prior `BasalHistoryUtils`) | no |
| `basal/BasalPlanner.kt` | ✅ | ✅ | no |
| `context/ContextIntentDeserializer.kt` | ✅ | ✅ (only `ContextIntent.kt` existed) | no |
| `context/ContextParser.kt` | ✅ | ✅ | no |
| `context/ContextInfluenceEngine.kt` | ✅ | ✅ | no |
| `comparison/VirtualGlucoseEngine.kt` | ✅ | ✅ (only KPI/scorer/`ComparisonData` existed) | no |

No Skip files copied. No Lot O/P/Q overwrite.

### KMP-ban scan (all 15 files)

| ban | status |
|---|---|
| `android.*` | ✅ none |
| `java.io.File` | ✅ none |
| `org.json` | ✅ none |
| Compose / Android Activity class | ✅ none |
| `System.currentTimeMillis()` | ✅ none in code; KDoc French comment in `ContinuousStateEstimator` is allowed per brief |
| `kotlin.jvm.Volatile` | ✅ none; `BasalHistoryUtils` uses `kotlin.concurrent.Volatile` |
| `javaClass` | ✅ none |
| `String.format` / `java.util.Locale` | ✅ none |
| `java.util.Calendar` | ✅ none |
| `CopyOnWriteArrayList` | ✅ none |
| `kotlin.synchronized` | ✅ none |
| `java.util.regex.Pattern` | ✅ none; `OrefReasonParser` fully rewritten |
| `AtomicLong` / `AtomicReference` | ✅ none; `OnlineLearner` rewritten to `AapsLock` |
| `java.util.concurrent` | ✅ none |
| `Math.max` / `Math.abs` (java.lang) | ✅ none; `OnlineLearner` uses `kotlin.math.max/abs` |
| `@JvmStatic` | ✅ none; `AutoDriveModels.createSafe` dropped it |
| `removeIf` (JVM extension) | ✅ none; `OnlineLearner` uses filter+remove |

### Rewrite verification

#### Time (`aimiWallClockMs`)
- `ContinuousStateEstimator.updateAndPredict`: `nowMs: Long = aimiWallClockMs()` — parameter kept ✅
- `ContextParser.parse` / `parsePreset`: `val now = aimiWallClockMs()` ✅
- `BasalHistoryUtils.FetcherProvider`: `nowProvider: () -> Long = { aimiWallClockMs() }` — parameter kept ✅
- `ContextIntentDeserializer`: already used `aimiWallClockMs` — kept ✅
- `OnlineLearner.learnAndUpdate`: takes `currentEpochMs: Long` — no wall-clock call added ✅

#### Format (`aimiFmt0/1/2` + `NumberFormat.withDecimals`)
- `OrefAnalysisReport`: `"%.0f"` → `aimiFmt0`, `"%.1f"` → `aimiFmt1`, `"%.2f"` → `aimiFmt2` ✅
- `BasalPlanner`: deleted `fmt1`/`fmt2`; call sites use `aimiFmt1`/`aimiFmt2` ✅
- `OnlineLearner`: `"%.2f".format(currentState.bgVelocity)` → `aimiFmt2`; `format(3)` helper uses `NumberFormat.withDecimals` ✅
- `ContextInfluenceEngine`: `"%.1fU".format(it)` → `"${aimiFmt1(it)}U"` ✅; both `Float.format` and `Double.format` helpers use `NumberFormat.withDecimals` ✅
- `ControlBarrierShield`, `ContinuousStateEstimator`: `Double.format(digits)` helper → `NumberFormat.withDecimals` ✅
- No `aimiFmt3` added ✅

#### Pattern → Regex (`OrefReasonParser`)
- `Pattern.compile(…, Pattern.CASE_INSENSITIVE)` → `Regex(…, RegexOption.IGNORE_CASE)` ✅
- `matcher(reason).find()` / `group(1)` → `.find(reason)` / `.groupValues[1]` ✅
- No `java.util.regex.Pattern` import ✅
- EU comma decimal handling preserved via `parseNumericToken` (`raw.replace(',', '.')`) ✅
- Parse behaviour unchanged ✅

#### K2 `toFloat()` paren fix (`ContextIntentDeserializer`)
- Pattern: `(obj.getValue("conf").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.")).toFloat()` ✅
- Applied to every `confidence` and `units` field (all 9 occurrences) ✅
- Type stays `Float` (not `Number`) ✅

#### `@Volatile` (`BasalHistoryUtils`)
- `import kotlin.concurrent.Volatile` ✅ (not `kotlin.jvm.Volatile`) ✅

#### `@JvmStatic` (`AutoDriveModels.createSafe`)
- Annotation absent; function present ✅

#### `AtomicLong`/`AtomicReference` → `AapsLock` (`OnlineLearner`)
- `AapsLock` + `withLock` from `app.aaps.core.interfaces.concurrent` ✅
- All reads/writes of `learnedSensitivityFactor`, `predictionHistory`, `evaluatedFeedbackCount`, `releaseCount`, `lastFeedbackAt`, `lastError`, `snapshot` inside `lock.withLock {}` ✅
- `incrementAndGet` → `+= 1` under lock ✅
- `statusRef.get()` / `.set(…)` → `var snapshot` ✅
- `removeIf { }` → `.filter { ... }` / `.forEach { predictionHistory.remove(it) }` ✅
- `learnAndUpdate` / `statusSnapshot` signatures preserved ✅

#### Metro
- `@Inject constructor` + `@SingleIn(AppScope::class)` on: `ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `BasalPlanner`, `ContextParser`, `ContextInfluenceEngine`, `VirtualGlucoseEngine` ✅
- No Hilt, no `javax.inject` ✅
- No `@IntKey(225)` ✅
- Other 8 files: no `@Inject` ✅

#### LTag
- `LTag.AIMI` on: `ControlBarrierShield`, `ContinuousStateEstimator`, `OnlineLearner`, `ContextIntentDeserializer`, `ContextParser`, `ContextInfluenceEngine` ✅
- No `LTag.APS` in any of the 15 files ✅
- `BasalPlanner` injects `AAPSLogger`; no log calls added ✅
- `VirtualGlucoseEngine` has no log calls — correct ✅

#### Explicit imports / no FQN at use site
- `AutoDriveModels`: `import app.aaps.core.data.model.SourceSensor` → `SourceSensor?` at property ✅
- Context files: `import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent` ✅
- No star-import of `model.ContextIntent` ✅
- Nested `Activity`/`Illness`/`Stress`/`HypoRecovery` stay `context.ContextIntent` nested types ✅
- `ContextIntent.Activity` in deserializer is the user-intent type, **not** Android `android.app.Activity` ✅
- `TpoDeltaBuilder`: no `kotlin.math.roundToInt` ✅
- `OnlineLearner`: `import kotlin.math.abs` + `import kotlin.math.max` ✅

#### KDoc
- `AutoDriveModels.htrTierOrdinal` KDoc: `` `HyperSeverityTier.ordinal` `` as plain backtick-code in text (not `[HyperSeverityTier.ordinal]` link) ✅
- `ControlBarrierShield` comment: `` `PkPdIntegration` `` stays backticks ✅
- `OrefAnalysisReport` KDoc: `OrefPersonalSignalGate` referenced as `[OrefPersonalSignalGate]` — same package so it **does** resolve; no issue ✅
- No retargeting of dest `InsulinActionModel` / `ContextIntent` / TAP-G KDoc ✅

#### Therapy math
- CBF barrier arithmetic unchanged (h, lfh, lgh, safeU formula, MaxIOB enforcement) ✅
- PSE Kalman update, RA decay, shadow filter unchanged ✅
- OnlineLearner gradient step, saturation bounds unchanged ✅
- BasalPlanner hypo guards, kicker, anti-stall thresholds unchanged ✅
- OrefReasonParser numeric parse behaviour identical to dump ✅

#### Context identity separation
- `context.ContextIntent` and `model.ContextIntent` are distinct; context files never import `model.ContextIntent` ✅
- `ContextInfluenceEngine`: `ContextMode` defined in this file ✅

#### No-add rules
- `@IntKey(225)` absent ✅
- No tick, no plugin registration ✅
- No inter-module `project()` deps added ✅
- No `aimiFmt3` ✅
- No new AIMI `iosMain` source set ✅

---

## Findings

### Important Issues 🟡 (must-fix)

#### I-1 — `VirtualGlucoseEngine.kt` line 3: unused import `OapsProfile`

```
import app.aaps.core.interfaces.aps.OapsProfile
```

`OapsProfile` is never referenced in the class body. The method signature uses only `Double` primitives. The import is a dump artifact: the brief says "dest `OapsProfile` only" as an allowed dependency, not that it must appear in the code. Kotlin compiler will warn on `--all-warnings`; IDE will mark as unused. Should be removed.

**Fix**: Delete line 3 from `VirtualGlucoseEngine.kt`.

---

### Suggestions 🟢

#### S-1 — `ContextInfluenceEngine.kt` line 283: indentation artifact

```kotlin
    private fun processIllness(
        illnesses: List<Illness>,
        currentBG: Double,
        iob: Double,
mode: ContextMode,      // ← should be indented 8 spaces
        reasoning: MutableList<String>
    ): IntentInfluence {
```

The `mode: ContextMode,` parameter is at column 0 instead of being indented with the other parameters. Compiles fine. Cosmetic fix for consistency.

---

### What Looks Good ✅

- **Safety math integrity**: All three numerical engines (CBF, PSE Kalman, OnlineLearner gradient descent) have their therapy arithmetic intact. Bounds, guard thresholds, and saturation limits match the dump design.
- **K2 `toFloat()` fix**: All 9 `confidence`/`units` field parses in `ContextIntentDeserializer` use `(doubleOrNull ?: error(…)).toFloat()`. This is the correct K2-safe form; the attempt-1 failure was real and properly diagnosed.
- **`AapsLock` migration**: `OnlineLearner` is a clean conversion. The single `lock.withLock {}` block covers all mutable state. The `removeIf` replacement collects stale keys first, which is correct for modifying a `MutableMap` inside a lock without a `ConcurrentModificationException`.
- **Context identity separation**: `context.ContextIntent` and `model.ContextIntent` are cleanly isolated. The three context files all import the right one. `ContextIntent.Activity` is used as the user-intent nested type, confirmed not the Android class.
- **`kotlin.concurrent.Volatile`**: Correctly applied (not the JVM-only `kotlin.jvm.Volatile`). The brief requirement is met.
- **`OrefReasonParser` Regex rewrite**: The pattern rewrite is correct and preserves all parse behaviour including the EU-decimal heuristic (comma replacement in `parseNumericToken`). The static `patterns` map avoids repeated Regex compilation.
- **Order compliance**: `TpoLadderSupport` → `TpoDeltaBuilder` ordering is correct; `AutoDriveModels` → CBF/PSE/learner ordering is correct.
- **No skip-file bleed**: None of the 5 remaining Lot L skips, recursive engine files, or other blocked files appear in the dest tree.
- **`BasalHistoryUtils` safety**: The `safeFetch` wrapper with `catch (_: Throwable)` is a good defensive guard for the history provider; no unsafe throws will reach `BasalPlanner`.
- **Medical safety constants**: `AutoDriveState.init` block enforces BG in [30, 600], IOB ≥ 0, estimatedSI > 0, patientWeight in [40, 250], hour in [0, 23]. `createSafe` catches any residual `IllegalArgumentException` and returns a safe default state with BG=100, IOB=0.

---

## Recursive engine (still blocked — informational)

`RecursiveBeliefTickContext` still needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier`. `RecursiveBeliefModels` still needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind`. Classifier / engine / adapters still hang on UAM Compose + `DoseTerminalSnapshot`. Not copied this lot; blocked status unchanged.

---

## Verdict

APPROVE_WITH_CONCERNS

Must-fix before merge or further lot work: **I-1** (remove unused `OapsProfile` import from `VirtualGlucoseEngine.kt`). All other rewrite requirements are met. No therapy math changed. No KMP-banned API found. Compile reported BUILD SUCCESSFUL on attempt 2.

APPROVE_WITH_CONCERNS
