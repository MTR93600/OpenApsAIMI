# Lot T — REVIEW

Reviewer: senior architect + senior Kotlin/KMP reviewer  
Date: 2026-08-28  
HEAD: `c71a283cca` (Lot S)  
Lot: T — WCycle math + gestational autopilot + in-engine decision plugins  
Files reviewed: 7 new dest files (untracked, working tree)  
Compile claim: BUILD SUCCESSFUL attempt 1 (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`)

---

## Summary

All 7 files from the brief-T Copy list are present at the correct dest paths. No dest-exists skips. No Skip files copied. No Lot O/P/Q/R/S file overwritten. Every mandatory rewrite from brief-T is correctly applied: `kotlinx.datetime.LocalDate` / `Clock.System.todayIn` / `daysUntil` / `DatePeriod` replace all `java.time` in `WCycleEstimator` and `GestationalAutopilot`; `Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour` replaces `LocalTime.now().hour` in `EndocrineAmplitudeGovernor`; `NumberFormat.withDecimals(3).format(x, NumberFormatPlatform.SEPARATOR_DOT)` replaces `String.format("%.3f", x)` in the governor `fmt`; `kotlin.math.round` replaces `Math.round` in `GestationalAutopilot`; `removeIf` → collect / `removeAll` in `AimiPluginSystem`; `LTag.AIMI` used throughout `AimiPluginSystem` (not `LTag.APS`); unused `LTag` import dropped from `EndometriosisAdjuster`; Metro kept on `GestationalAutopilot` and `AimiPluginManager`; no `@IntKey(225)`, no `OpenAPSAIMIPlugin`; `EndometriosisAdjuster` does not use `WCycleLearner` or `File`; `EndocrineAmpAxis` declared in `EndocrineAmplitudeGovernor` (not in dest `WCycleBelief`); explicit imports throughout. No KMP-banned API found in any of the 7 files. Compile verified BUILD SUCCESSFUL.

One pre-existing dump arithmetic bug in `StableControlPlugin` (operator precedence — not introduced by migration; therapy math is faithfully copied). One unused constructor param in `EndometriosisAdjuster` (from dump). Neither blocks merge.

---

## Checklist

### Copy list exact match

| rel | dest file present | dest was empty before | overwrite? |
|---|---|---|---|
| `wcycle/WCycleEstimator.kt` | ✅ | ✅ (no prior estimator) | no |
| `wcycle/EndocrineAmplitudeGovernor.kt` | ✅ | ✅ (no prior governor) | no |
| `wcycle/EndometriosisAdjuster.kt` | ✅ | ✅ (no prior adjuster) | no |
| `advisor/gestation/GestationalAutopilot.kt` | ✅ | ✅ (new subdir) | no |
| `plugins/AimiPluginSystem.kt` | ✅ | ✅ (new subdir) | no |
| `plugins/impl/SafetyAggressionPlugin.kt` | ✅ | ✅ (new subdir) | no |
| `plugins/impl/StableControlPlugin.kt` | ✅ | ✅ | no |

Copy order: `plugins/AimiPluginSystem.kt` before `plugins/impl/*.kt` — correct (report confirms; impls import `AimiDecisionPlugin` which lives in that file). ✓

### No Skip files copied

`MealCorrectionContextResolver.kt`, `activity/ExerciseHyperOverridePolicy.kt`, `basal/T3cAutodriveBasalBridge.kt`, `pkpd/PkpdAbsorptionGuard.kt`, `smb/SmbDampingUsecase.kt` — not present at dest. ✓  
Recursive engine files (`RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / resolvers) not copied. ✓  
`physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, `AutodriveEngine`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `HcRecoveryProxyThermalSource`, `OuraApiThermalClient`, `HormonitorLabels` not copied. ✓

### No dest overwrite

Lot O/P/Q/R/S files intact. Dest `WCycleTypes.kt` / `WCyclePreferences.kt` / `WCycleBelief.kt` / `DecisionModels.kt` / `InflammationAdjuster.kt` / `AimiPhysioInputs` (in `AIMIPhysioDataModelsMTR.kt`) not modified. ✓  
Dest `WCycleBelief` / `DecisionModels` KDoc not edited. ✓

---

## KMP ban scan — all 7 files

| ban | result |
|---|---|
| `android.*` | ✅ none |
| `java.io.File` | ✅ none |
| `org.json` | ✅ none |
| Compose | ✅ none |
| Android `Activity` | ✅ none |
| `System.currentTimeMillis` | ✅ none |
| `kotlin.jvm.Volatile` | ✅ none |
| `javaClass` | ✅ none |
| `String.format` | ✅ none |
| `java.util.Locale` | ✅ none |
| `java.time` | ✅ none |
| `removeIf` | ✅ none (rewritten in AimiPluginSystem) |
| `kotlin.synchronized` | ✅ none |

Grep across all 7 dest files: zero hits. ✓

---

## Rewrite verification

### Time — kotlinx.datetime

**WCycleEstimator:**
- `estimate(now: LocalDate = Clock.System.todayIn(TimeZone.currentSystemDefault()))` — `LocalDate` is `kotlinx.datetime.LocalDate`. ✅
- `lengthOfMonth`: `LocalDate(year, month, 1).daysUntil(first.plus(DatePeriod(months = 1)))` — replaces `lengthOfMonth()`. ✅
- `withDayOfMonth(d)` → `LocalDate(year, month, d)`. ✅
- `minus(DatePeriod(months = 1))` — replaces `minusMonths(1)`. ✅
- `cycleStart.daysUntil(now)` — replaces `ChronoUnit.DAYS.between`. ✅
- `candidate <= now` — replaces `!candidate.isAfter(now)`. ✅
- `import kotlinx.datetime.minus` and `import kotlinx.datetime.plus` both present for `DatePeriod` arithmetic. ✅

**GestationalAutopilot:**
- `dateProvider: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) }` ✅
- `calculateState(dueDate: LocalDate)` — `LocalDate` is `kotlinx.datetime.LocalDate`, not `java.time.LocalDate`. ✅
- `today.daysUntil(dueDate)` — replaces `ChronoUnit.DAYS.between(today, dueDate)`. ✅
- 280-day / week math intact. ✅

**EndocrineAmplitudeGovernor:**
- `hourOfDay: Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour` — replaces `LocalTime.now().hour`. ✅
- Luteal dawn window `4..7` kept. ✅
- `import kotlinx.datetime.toLocalDateTime` present. ✅

### Format

**EndocrineAmplitudeGovernor `fmt`:**
```
private fun fmt(x: Double): String =
    NumberFormat.withDecimals(3).format(x, NumberFormatPlatform.SEPARATOR_DOT)
```
Replaces `String.format("%.3f", x)`. Private `fmt` name kept. No `aimiFmt3`. ✅  
Both `import app.aaps.core.data.format.NumberFormat` and `import app.aaps.core.data.format.NumberFormatPlatform` explicit. ✅

### Math.round

**GestationalAutopilot line 97:**
```kotlin
resistanceFactor = round(factor * 100.0) / 100.0
```
`kotlin.math.round` via `import kotlin.math.round`. Two-decimal resistance factor kept. ✅

### removeIf

**AimiPluginSystem.unregister (lines 58-61):**
```kotlin
fun unregister(id: String) {
    val matching = plugins.filter { it.id == id }
    plugins.removeAll(matching)
}
```
Collect-then-`removeAll` pattern. Unregister-by-id behaviour preserved. ✅

### LTag

`LTag.AIMI` on both `register` and `collectActions` in `AimiPluginManager`. Not `LTag.APS`. ✅  
`EndometriosisAdjuster`: unused `LTag` import dropped; no log calls in that file. ✅

### Metro

`@SingleIn(AppScope::class)` + `@Inject constructor()` on `GestationalAutopilot`. ✅  
`@SingleIn(AppScope::class)` + `@Inject constructor(aapsLogger: AAPSLogger)` on `AimiPluginManager`. ✅  
No Metro on the other five files. ✅  
No Hilt, no `javax.inject`. ✅  
No `@IntKey(225)`, no `ApsPluginRegistrations`. ✅

### Not OpenAPSAIMIPlugin

`AimiDecisionPlugin` and `AimiPluginManager` are standalone in-engine decision extensions.  
No `OpenAPSAIMIPlugin` import or reference anywhere. ✅  
No tick or APS plugin registration. ✅

### EndometriosisAdjuster — no WCycleLearner / File

Only imports: `AAPSLogger`, `BooleanKey`, `DoubleKey`, `Preferences`, `AimiPhysioInputs`. ✅  
`WCyclePreferences` and `ContraceptiveType` accessed without import — same `wcycle` package. ✅  
`calculateFactors(bg: Double, delta: Double, inputs: AimiPhysioInputs? = null)` signature intact. ✅

### EndocrineAmpAxis location

Declared as a top-level enum at the bottom of `EndocrineAmplitudeGovernor.kt`. Not in dest `WCycleBelief`. ✅

### Explicit imports

All files use short names at use site with explicit `import` statements. No fully-qualified inline names found. ✅

### KDoc / backticks

`EndocrineAmplitudeGovernor` KDoc uses plain text (no unresolvable `[...]` links). ✅  
Dest `WCycleBelief` / `DecisionModels` KDoc not touched. ✅

### Strings / JSON / prefs

No `R.string`, `ResourceHelper`, `org.json`. Keys read through `Preferences.get(BooleanKey.AimiEndometriosisEnable)`, `BooleanKey.AimiEndometriosisPainFlare`, `DoubleKey.AimiEndometriosisBasalMult`, `DoubleKey.AimiEndometriosisSmbDampen`, `DoubleKey.OApsAIMIMaxSMB`, `DoubleKey.OApsAIMILunchFactor`. No new keys added. ✅

### Therapy math

WCycle phase bands (menstruation 0–4, follicular 5–46%, ovulation 46–54%, luteal 55–end) preserved. ✅  
EndocrineAmplitudeGovernor amplitude and hypo-dampen logic unchanged. ✅  
GestationalAutopilot resistance curve (trimester bands, 280-day math) unchanged. ✅  
EndometriosisAdjuster suppression / pain-flare / rapid-drop logic unchanged. ✅  
SafetyAggressionPlugin and StableControlPlugin logic unchanged from dump. ✅

---

## Compile

Log: `/tmp/aimi-lot-T.log`

```
> Task :plugins:aps:compileKotlinIosSimulatorArm64
> Task :plugins:aps:compileAndroidMain
BUILD SUCCESSFUL in 50s
93 actionable tasks: 14 executed, 79 up-to-date
```

Both target tasks ran (not UP-TO-DATE). ✅  
Metro compiler warnings on `@Inject`-annotated constructors match existing dest Metro files — pre-existing, not introduced by this lot. ✅

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

### 1. Pre-existing arithmetic bug in `StableControlPlugin.kt` line 29

```kotlin
val newValue = (lunchFactor + 0.1 * 10.0).roundToInt() / 10.0
```

Due to operator precedence, `0.1 * 10.0` evaluates to `1.0` before the addition. For any `lunchFactor` value, this produces a result in the range `0.1–0.2` — far below the intended increment. The correct formula is `((lunchFactor + 0.1) * 10.0).roundToInt() / 10.0`.

**Dump fidelity check:** The dump file `_docs/kmp/staging/openAPSAIMI-android-wip/plugins/impl/StableControlPlugin.kt` line 29 is byte-for-byte identical. The migration did not introduce this bug — it was copied faithfully from the dump as required by "Therapy math unchanged."

**Classification:** Pre-existing dump defect, not a migration error. The spec requires faithful copy; fixing it in this lot would violate "Therapy math unchanged." Flag for a dedicated fix lot.

### 2. Unused constructor parameter in `EndometriosisAdjuster.kt`

`private val logger: AAPSLogger` is injected at the constructor but is never called in the file. The dump is the same — the spec says to drop the unused `LTag` import (done) but does not say to remove `AAPSLogger`. Dead constructor param from the dump. Flag for cleanup in a future lot.

---

## Suggestions 🟢

None beyond the flagged pre-existing issues above.

---

## What Looks Good ✅

- All 7 `kotlinx.datetime` rewrites are semantically correct and use the right import set (`DatePeriod`, `daysUntil`, `todayIn`, `toLocalDateTime`, `TimeZone`, `Clock`).
- `removeIf` → collect / `removeAll` is clean and correct.
- `NumberFormat.withDecimals(3)` + `NumberFormatPlatform.SEPARATOR_DOT` is the right common-main pattern matching prior lots.
- `kotlin.math.round` is explicit and correctly preserves two-decimal rounding.
- `EndometriosisAdjuster` stays entirely within dest types — no `WCycleLearner`, no `File`, no `WCycleAdjuster`.
- `AimiDecisionPlugin` / `AimiPluginManager` are cleanly isolated from `OpenAPSAIMIPlugin`; no tick wiring, no `@IntKey(225)`.
- Exception catch in `AimiPluginManager.collectActions` uses `Exception` (common-main compatible — not `java.lang.Exception`). ✅
- Copy order respected: `AimiPluginSystem.kt` landed before the two `impl/` files that import `AimiDecisionPlugin` from it.
- Dest `WCycleBelief` / `WCycleTypes` / `WCyclePreferences` / `DecisionModels` not touched.
- Metro warnings in the compile log are pre-existing and unrelated to this lot.

---

## Blocked — still not copy-safe (same as report-T)

Recursive engine (`RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters) still needs dump `MealAbsorptionPhaseEngine` / `PhysiologicalPhaseClassifier` / `HyperSeverityClassifier` / `DoseTerminalSnapshot` (UAM Compose). Not unblocked by this lot.  
`TpoTriggerEngine` still needs dump `PatientMode`.  
`MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.  
`physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner`, AutoDrive engine graph, Lot S leftovers (`TuningContextEngine`, `AdvancedPredictionEngine`, `HormonitorLabels`, dual-brain auditor helpers) — all still parked.

---

APPROVE_WITH_CONCERNS
