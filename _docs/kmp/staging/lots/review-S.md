# Lot S — REVIEW

Reviewer: senior architect + senior Kotlin/KMP reviewer  
Date: 2026-08-28  
HEAD: `17d159b5b1` (Lot R)  
Lot: S — Lot R leftovers + AutoDrive auditor / NGR / PKPD learner / activity / utils  
Files reviewed: 13 new dest files (untracked, working tree)  
Compile claim: BUILD FAILED attempt 1 → BUILD SUCCESSFUL attempt 2 (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`)

---

## Summary

All 13 files from the brief-S Copy list are present at the correct dest paths. No dest-exists skips. No Skip files copied. No Lot O/P/Q/R file overwritten. Every mandatory rewrite from brief-S sections 1–13 is correctly applied: `aimiWallClockMs()` in `therapy.kt` (three call-sites); `aimiFmt0/1/2` and `NumberFormat.withDecimals` replace all `String.format` / `Locale` / `"%.nf".format` calls; `Regex` with `RegexOption.IGNORE_CASE` replaces `Pattern.compile` in `therapy.kt`; `T.days(1).msecs()` / `T.mins(…).msecs()` replace `TimeUnit`; `@JvmStatic` dropped from `IsfTddProvider` and `ContextExtensions` objects; `kotlin.concurrent.Volatile` (not `kotlin.jvm.Volatile`) used on all three required fields; `AapsLock` + `withLock` replace all `AtomicReference`/`AtomicBoolean`/`AtomicLong` across `AdaptivePkPdEstimator`, `KalmanFilter`, and `therapy.kt`; `removeIf` → collect/`removeAll` in `VirtualSmbState`; `kotlinx.datetime.Instant`/`LocalTime`/`TimeZone` replace `java.time` throughout `NightGrowthResistanceMonitor`; `key.lowercase()` replaces `key.lowercase(Locale.US)` in `AimiDiagnosticsPrefExportPolicy`; `Dispatchers.IO` → `aapsIoDispatcher` in `KalmanFilter` and `therapy.kt`; `measureTimedValue` replaces `measureTimeMillis` in `AimiLogger`; `encodeToByteArray()`/`decodeToString()` replaces `Charsets.UTF_8` in `JsonSafeLogger`; `` `PkPdIntegration` `` wrapped in backticks in `AdaptivePkPdEstimator` KDoc; unused Metro imports dropped from `VirtualSmbState`; `LTag.APS` → `LTag.AIMI` on the three required files. Therapy math unchanged. No KMP-banned API found in any of the 13 files. Compile verified BUILD SUCCESSFUL.

No critical or important issues found. Two cosmetic suggestions below.

---

## Checklist

### Copy list exact match

| rel | dest file present | dest was empty before | overwrite? |
|---|---|---|---|
| `autodrive/learning/AutodriveAuditor.kt` | ✅ | ✅ (no prior auditor) | no |
| `comparison/VirtualSmbState.kt` | ✅ | ✅ | no |
| `pkpd/AdaptivePkPdEstimator.kt` | ✅ | ✅ | no |
| `activity/ActivityManager.kt` | ✅ | ✅ (no prior manager) | no |
| `advisor/auditor/LocalSentinel.kt` | ✅ | ✅ (new subdir) | no |
| `utils/JsonSafeLogger.kt` | ✅ | ✅ | no |
| `utils/AimiLogger.kt` | ✅ | ✅ | no |
| `utils/ContextExtensions.kt` | ✅ | ✅ | no |
| `KalmanFilter.kt` | ✅ | ✅ | no |
| `advisor/diag/AimiDiagnosticsPrefExportPolicy.kt` | ✅ | ✅ (new subdir) | no |
| `trajectory/TrajectoryHistoryProvider.kt` | ✅ | ✅ | no |
| `therapy.kt` | ✅ | ✅ | no |
| `NightGrowthResistanceMonitor.kt` | ✅ | ✅ | no |

Copy order: `JsonSafeLogger` before `AimiLogger` — correct (report confirms). ✓

### No Skip files copied

`MealCorrectionContextResolver.kt`, `activity/ExerciseHyperOverridePolicy.kt`, `basal/T3cAutodriveBasalBridge.kt`, `pkpd/PkpdAbsorptionGuard.kt`, `smb/SmbDampingUsecase.kt` — not present at dest. ✓  
Recursive engine files not copied. ✓  
`physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, `AutodriveEngine`, `TpoTriggerEngine`, `AutodriveDatasetLock` not copied. ✓

### No dest overwrite

Lot O/P/Q/R files intact. Dest `PkPdCore.kt`, `ActivityContext.kt`, `NightGrowthResistanceLearner.kt`, `PhaseSpaceModels.kt`, `InsulinActionState.kt`, `Models.kt`, `AutoDriveModels.kt`, `VirtualGlucoseEngine.kt`, `ValidationUtils.kt` not modified. ✓

---

## KMP ban scan

Ran a pattern search across all 13 files for: `android.*`, `java.io.File`, `org.json`, `System.currentTimeMillis`, `kotlin.jvm.Volatile`, `javaClass`, `String.format`, `java.util.Locale`, `java.util.Calendar`, `CopyOnWriteArrayList`, `kotlin.synchronized`, `java.util.concurrent`, `java.time.`, `removeIf`, `@JvmStatic`, `Dispatchers.IO`.

**Result: zero hits.** All banned APIs removed. ✓

---

## Rewrite verification

### Clock (`aimiWallClockMs`)
- `therapy.kt` `refreshIfNeededAsync` (line 54) — uses `aimiWallClockMs()` ✓
- `therapy.kt` `buildSnapshot` (line 74) — uses `aimiWallClockMs()` ✓
- `therapy.kt` `getTimeElapsedSinceLastEvent` (line 270) — uses `aimiWallClockMs()` ✓
- No `System.currentTimeMillis()` anywhere in the 13 files ✓
- `VirtualIobCalculator` still uses `dateUtil.now()` ✓
- `AdaptivePkPdEstimator.update` still takes `epochMin` parameter ✓
- `TrajectoryHistoryProvider.buildHistory` still takes `nowMillis` parameter ✓

### Format
- `JsonSafeLogger.formatUS(decimals)`: both `Double` and `Float` overloads use `NumberFormat.withDecimals(decimals).format(this.toDouble(), NumberFormatPlatform.SEPARATOR_DOT)` ✓
- `ContextExtensions.BgSnapshot.toShortString`: uses `aimiFmt1(delta5)` ✓
- `NightGrowthResistanceMonitor.decayResult`: `aimiFmt2(smb)` / `aimiFmt2(basal)` in decay reason string ✓
- `NightGrowthResistanceMonitor.buildActiveReason`: `aimiFmt1(slope)` in active reason string ✓
- Persistence string `" (persistence ${max(3, persistenceCount)}×5')"` kept as Kotlin string ✓
- `AimiDiagnosticsPrefExportPolicy`: `key.lowercase()` (no Locale argument) ✓
- `ActivityManager`: `aimiFmt1(recoveryBucket)` / `aimiFmt1(smoothedScore)` ✓
- `LocalSentinel`: `aimiFmt0` / `aimiFmt1` / `aimiFmt2` throughout ✓
- No `"%.nf".format(...)`, no `String.format`, no `java.util.Locale` ✓
- No `aimiFmt3` ✓

### Regex (`therapy.kt`)
- `extractDateFromDeleteEvent`: `Regex("delete (\\d{2}/\\d{2}/\\d{4})", RegexOption.IGNORE_CASE)` ✓
- `find(note ?: "")` / `groupValues?.get(1)` ✓
- No `java.util.regex.Pattern` ✓

### TimeUnit → T (`therapy.kt`)
- `T.days(1).msecs()`, `T.mins(15).msecs()`, `T.mins(60).msecs()` ✓
- `import app.aaps.core.data.time.T` present ✓
- No `java.util.concurrent.TimeUnit` ✓

### `@JvmStatic`
- `IsfTddProvider.isfTdd()` and `set()` — no annotation ✓
- `ContextValidator`, `ContextSerializer`, `ContextPluginRegistry` — objects with no `@JvmStatic` ✓

### `@Volatile` source
- `KalmanFilter.kt` — `import kotlin.concurrent.Volatile` at line 14, used on `cachedTdd7Days`, `cachedTdd2Days`, `cachedTdd1Day`, `tddRefreshInFlight` ✓
- `AdaptivePkPdEstimator.kt` — `import kotlin.concurrent.Volatile` at line 5, used on `IsfTddProvider.isf` ✓
- `NightGrowthResistanceMonitor.kt` — `import kotlin.concurrent.Volatile` at line 9, used on `NightGrowthResistanceMode.latestResult` ✓

### Atomics → `AapsLock`
- `AdaptivePkPdEstimator`: one `lock = AapsLock()` guards reads/writes of `state`, `acceptedUpdateCount`, `status`; `incrementAndGet` → `count += 1` under lock; `params()` / `statusSnapshot()` / `update` signatures preserved ✓
- `KalmanFilter.KalmanISFCalculator`: `tddLock = AapsLock()` guards `tddRefreshInFlight`; `@Volatile var` on cache fields; `aapsIoDispatcher` on `ioScope` ✓
- `therapy.kt` companion: `lock = AapsLock()` guards `snapshot` + `refreshInFlight`; `aapsIoDispatcher` on `ioScope` ✓
- `NightGrowthResistanceMonitor.NightGrowthResistanceMode.latestResult`: `@Volatile var` (brief allows either `@Volatile var` or `AapsLock` here) ✓

### `removeIf` → collect/`removeAll` (`VirtualSmbState`)
```kotlin
val oldBoluses = virtualBoluses.filter { it.timestamp < threshold }
virtualBoluses.removeAll(oldBoluses)
val oldTemps = virtualTempBasals.filter { it.end < threshold }
virtualTempBasals.removeAll(oldTemps)
```
Prune behaviour preserved ✓. No `removeIf` ✓.

### NGR `java.time` → `kotlinx.datetime`
- Imports: `kotlinx.datetime.Instant`, `kotlinx.datetime.LocalTime`, `kotlinx.datetime.TimeZone`, `kotlinx.datetime.toLocalDateTime` ✓
- Constructor default: `TimeZone.currentSystemDefault()` ✓
- `evaluate(now: Instant, …)` — `Instant` is `kotlinx.datetime.Instant` ✓
- Night check: `isWithinNight` uses `time >= start && time <= end` / `time >= start || time <= end` (wrap window) ✓
- Duration minutes: `(now.toEpochMilliseconds() - start.toEpochMilliseconds()) / 60_000L` ✓
- No `java.time.*` ✓

### `Dispatchers.IO` → `aapsIoDispatcher`
- `KalmanFilter.kt` — `CoroutineScope(SupervisorJob() + aapsIoDispatcher)` ✓
- `therapy.kt` — `CoroutineScope(SupervisorJob() + aapsIoDispatcher)` ✓
- `refreshBlocking` uses `runBlocking(aapsIoDispatcher)` ✓
- `TrajectoryHistoryProvider` uses `withContext(Dispatchers.Default)` — this is correct; `Dispatchers.Default` is a public KotlinX Coroutines API available on all targets (only `Dispatchers.IO` is internal on Native) ✓

### `measureTimedValue` (`AimiLogger`)
- `AimiLogger.measure` uses `measureTimedValue(block)` from `kotlin.time` ✓
- No `measureTimeMillis` ✓
- `timed.value` returned; `timed.duration.inWholeMilliseconds` logged ✓

### UTF-8 (`JsonSafeLogger`)
- `JsonSafeLogger.isValidUtf8`: `encodeToByteArray().decodeToString() == this` ✓
- No `Charsets.UTF_8` ✓

### Metro / DI
- `AutodriveAuditor`: `@SingleIn(AppScope::class)` + `@Inject` + `AppScope` imports ✓
- `TrajectoryHistoryProvider`: `@SingleIn(AppScope::class)` + `@Inject` + `AppScope` imports ✓
- `AimiLogger`: `@SingleIn(AppScope::class)` + `@Inject` + `AppScope` imports ✓
- `ActivityManager`: `@Inject constructor()` (no `SingleIn` — correct, dump has no `SingleIn`) ✓
- `VirtualSmbState.kt` — unused `Inject`/`SingleIn`/`AppScope` imports dropped ✓
- No Hilt. No `javax.inject`. No `@IntKey(225)`. No tick. No plugin registration. ✓

### Explicit imports / no FQCN at use site
- `AutodriveAuditor`: `import …autodrive.models.AutoDriveState` and `AutoDriveCommand` ✓
- `AdaptivePkPdEstimator`: `PkPdParams`, `Kernel`, `InsulinActivityState`, `InsulinActivityStage`, `InsulinActivityWindow` — all in the same `pkpd` package (no import needed, same package) ✓
- `ActivityManager`: `ActivityContext`, `ActivityState` — same `activity` package ✓
- `ContextExtensions`: `ContextPlugin` stays that name; `OpenAPSAIMIPlugin` not imported ✓
- `AimiLogger`: unused `formatUS` / `AtomicLong` imports dropped ✓

### KDoc backticks
- `AdaptivePkPdEstimator` companion KDoc: `` `PkPdIntegration` `` in backticks ✓
- `[PkPdLearningConfig.minWindowMin]` — resolves (same file) ✓
- No `[LoopPlugin.*]` or other cross-module unresolvable links ✓

### LTag
- `AimiLogger`, `KalmanFilter`, `TrajectoryHistoryProvider` — all use `LTag.AIMI` ✓
- No new log calls added to files that did not log ✓

---

## Cross-reference / compile checks

- `InsulinActivityStage` is in `PkPdCore.kt` (same `pkpd` package as `AdaptivePkPdEstimator`) — no import needed ✓
- `ActivityStage` enum has values `RISING`, `PEAK`, `FALLING`, `TAIL` — `TrajectoryHistoryProvider.estimatePkpdStage` returns `ActivityStage.FALLING` ✓
- `AapsLock.withLock` is `inline` (verified in `AapsLock.kt` line 46) — `return` inside `lock.withLock { … }` in `AdaptivePkPdEstimator.update` is a valid non-local return ✓
- `TB.end` is a computed property `get() = timestamp + duration` (verified in `TB.kt` line 54–55) ✓
- `LTag.AIMI` exists (verified in `LTag.kt`) ✓
- `aimiWallClockMs` defined in `AimiWallClock.kt` (same package) ✓
- `aimiFmt0/1/2` defined in `AimiFmt.kt` (same package) ✓
- `aapsIoDispatcher` is an `expect val` in `AapsIoDispatcher.kt` ✓
- `PhaseSpaceState` is in `PhaseSpaceModels.kt` (same `trajectory` package as `TrajectoryHistoryProvider`) ✓
- `InsulinWeibullCurve` is a separate `InsulinWeibullCurve.kt` in `pkpd` — correctly imported in `TrajectoryHistoryProvider` ✓

Compile log: `/tmp/aimi-lot-S.log` — tail shows `BUILD SUCCESSFUL in 54s`. Both requested tasks ran.

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

None.

---

## Suggestions 🟢

**S1 — Redundant same-package imports** (`therapy.kt` line 9, `NightGrowthResistanceMonitor.kt` lines 3–4)

Both files import symbols from their own package:
- `therapy.kt`: `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs`
- `NightGrowthResistanceMonitor.kt`: `import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1` / `aimiFmt2`

In Kotlin, same-package symbols do not need importing. These are harmless (the build passes) but IDE lint may flag them as unused. Removing them is a cosmetic improvement.

**S2 — `VirtualIobCalculator` loop unpacking** (`VirtualSmbState.kt` line 113)

```kotlin
for ((pos, i) in (0 until len).withIndex()) {
```
Since `(0 until len).withIndex()` yields `IndexedValue(index=n, value=n)`, `pos == i` always. Using the simpler `for (i in 0 until len)` and `array[i] = iob` is cleaner. Not a correctness concern — it is carried from the dump.

---

## What Looks Good ✅

- `AapsLock` pattern applied consistently across `AdaptivePkPdEstimator`, `KalmanFilter`, and `therapy.kt` — matches the OnlineLearner teacher pattern from Lot R exactly.
- `measureTimedValue` in `AimiLogger.measure` — correct teacher-style fix; `measureTimeMillis` is deprecated-as-error on Native and leaves `result` uninitialized.
- NGR `kotlinx.datetime` migration is complete and correct: constructor default zone, epoch-ms arithmetic for duration, wrap-around night window logic — all match the brief specification.
- `Dispatchers.Default` (not `aapsIoDispatcher`) used in `TrajectoryHistoryProvider.buildHistory` — correct; only `Dispatchers.IO` is internal on Native; `Default` is fine for CPU-bound history computation.
- `encodeToByteArray()`/`decodeToString()` in `JsonSafeLogger.isValidUtf8` — clean KMP-safe UTF-8 check.
- `recordSkipped` in `AdaptivePkPdEstimator` is called only from within `lock.withLock { … }`, making direct field access of `status` correct without re-entrancy.
- All medical safety parameters (ISF Kalman bounds, NGR multiplier clamps, sentinel stacking thresholds, activity ISF cap) carried unchanged from dump — therapy math preserved ✓.

---

## Parked items (not this lot — status unchanged)

- Recursive engine: still blocked (`MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier`).
- `TpoTriggerEngine`: still needs dump `PatientMode`.
- `MpcController`: still needs dump `HyperTrajectoryMpcFeedForward`.
- `advisor/tuning/TuningContextEngine.kt`: dump `AdvisorMetrics`.
- `pkpd/AdvancedPredictionEngine.kt`: dump `PredictionPhysioModulation`.
- `hormonitor/viewer/HormonitorLabels.kt`: `Locale.getDefault()` language — no commonMain locale without `iosMain`.
- Dual-brain auditor helpers: dump `AuditorVerdict`.
- `AutodriveDatasetLock`: `ReentrantLock` / T2 file lake.
- `physio/pattern/*`, remaining `release/*`, `wcycle/*` adjusters, rest of `patient/*`, meal/endogenous hysteresis, remaining thermal clients, AutoDrive engine graph, remaining TPO — all still parked.

---

APPROVE
