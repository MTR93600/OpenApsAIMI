# Lot U — REVIEW

Reviewer: senior architect + senior Kotlin/KMP reviewer  
Date: 2026-08-28  
HEAD: `f06d626dcc` (Lot T)  
Lot: U — dest-type leftovers (auditor tracker, ML breaker, physio outcomes, thermal RHR/HRV proxy)  
Files reviewed: 4 new dest files (untracked, working tree)  
Compile claim: BUILD SUCCESSFUL attempt 1 (`:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain`)

---

## Summary

All 4 files from the brief-U Copy list are present at the correct dest paths. No dest-exists skips. No Skip files copied. No Lot O/P/Q/R/S/T file overwritten. Every mandatory rewrite from brief-U is correctly applied: `System.currentTimeMillis()` → `aimiWallClockMs()` in all three files that required it; `kotlin.jvm.Volatile` → `kotlin.concurrent.Volatile` on the tracker's two fields; `AtomicInteger` / `AtomicLong` → `AapsLock` + `var` in the circuit breaker; `java.time` → `kotlinx.datetime` with device-local `TimeZone.currentSystemDefault()` and the exact `DatePeriod` / `Instant` / `LocalDateTime` / `LocalTime` / `atStartOfDayIn` / `toInstant` call pattern specified in the brief. Therapy math is unchanged. No HC SDK types in any of the four files. Explicit imports throughout. `internal` kept on `HcRecoveryProxyThermalSource` and `TrainingCircuitBreaker`. Compile verified BUILD SUCCESSFUL (both tasks ran, not UP-TO-DATE).

No Critical Issues. No Important Issues.

---

## Checklist

### Copy list exact match

| rel | dest file present | dest was empty before | overwrite? |
|---|---|---|---|
| `advisor/auditor/AuditorStatusTracker.kt` | ✅ | ✅ (only `LocalSentinel.kt` was there) | no |
| `ml/TrainingCircuitBreaker.kt` | ✅ | ✅ (new `ml/` folder) | no |
| `physio/AIMIPhysioOutcomes.kt` | ✅ | ✅ (no prior outcomes file) | no |
| `physio/thermal/HcRecoveryProxyThermalSource.kt` | ✅ | ✅ (no prior recovery proxy) | no |

### No Skip files copied

`MealCorrectionContextResolver.kt`, `activity/ExerciseHyperOverridePolicy.kt`, `basal/T3cAutodriveBasalBridge.kt`, `pkpd/PkpdAbsorptionGuard.kt`, `smb/SmbDampingUsecase.kt` — not present at dest. ✓  
Recursive engine files (`RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters / resolvers / cascades) not copied. ✓  
`physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner` / `WCycleCsvLogger`, `AutodriveEngine`, `TpoTriggerEngine`, `AutodriveDatasetLock`, `OuraApiThermalClient`, `AIMIHealthConnectPermissions`, `HormonitorLabels`, `RtInstrumentationHelpers`, `AimiSmbTrainer`, `BasalMlTrainingCoordinator` not copied. ✓

### No dest overwrite

Lot O/P/Q/R/S/T files intact. Dest `ThermalDataMTR.kt` / `ThermalDataOrigins.kt` / `AIMIPhysioDataModelsMTR.kt` / `LocalSentinel.kt` not modified. ✓  
No KDoc retargeted in those files. ✓

---

## KMP ban scan — all 4 files

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
| `AtomicInteger` / `AtomicLong` | ✅ none |
| `java.util.concurrent` | ✅ none |
| Health Connect SDK | ✅ none |

---

## Rewrite verification

### Time — `aimiWallClockMs()`

**`AuditorStatusTracker`:**
- `updateStatus`: `lastUpdateMs = aimiWallClockMs()` ✅
- `getStatus`: `val ageMs = aimiWallClockMs() - lastUpdateMs` ✅
- 300_000 ms stale window (`maxAgeMs: Long = 300_000`) kept ✅
- `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs` explicit ✅

**`TrainingCircuitBreaker`:**
- Default clock: `private val clock: () -> Long = { aimiWallClockMs() }` ✅
- Injectable `clock` parameter kept ✅
- `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs` explicit ✅

**`AIMIPhysioOutcomes.ProbeResult`:**
- `val probeTimestamp: Long = aimiWallClockMs()` ✅
- `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs` explicit ✅

**`HcRecoveryProxyThermalSource`:**
- `fun build(..., nowMs: Long = aimiWallClockMs())` — `nowMs` parameter kept ✅
- `import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs` explicit ✅

### `@Volatile` — `kotlin.concurrent.Volatile`

**`AuditorStatusTracker`:**
- `import kotlin.concurrent.Volatile` — correct multiplatform annotation ✅
- `@Volatile private var currentStatus: Status = Status.OFF` ✅
- `@Volatile private var lastUpdateMs: Long = 0L` ✅
- Not `kotlin.jvm.Volatile` ✅

### JVM-only atomics → `AapsLock` (`TrainingCircuitBreaker`)

- `private val lock = AapsLock()` ✅
- `import app.aaps.core.interfaces.concurrent.AapsLock` explicit ✅
- `import app.aaps.core.interfaces.concurrent.withLock` explicit ✅
- `failures` and `coolingUntilMs` are plain `var` fields ✅
- `isOpen`: entire body inside `lock.withLock { ... }` ✅
- `recordFailure`: entire body inside `lock.withLock { ... }`, `failures += 1` (not `incrementAndGet`) ✅
- `reset`: `lock.withLock { failures = 0 }` ✅
- Companion constants: `DEFAULT_MAX_FAILURES = 3`, `DEFAULT_COOLDOWN_MS = 6L * 60 * 60 * 1000` ✅
- No `java.util.concurrent` ✅

`reset()` only resets `failures`, not `coolingUntilMs`. This is correct: `isOpen` is `failures >= maxFailures && now < coolingUntilMs`. After reset, `failures = 0 < 3`, so `isOpen` is `false` regardless of `coolingUntilMs`. Semantics preserved from dump. ✓

### `java.time` → `kotlinx.datetime` (`HcRecoveryProxyThermalSource`)

All 9 brief-specified imports present:

| import | present |
|---|---|
| `kotlinx.datetime.DatePeriod` | ✅ |
| `kotlinx.datetime.Instant` | ✅ |
| `kotlinx.datetime.LocalDateTime` | ✅ |
| `kotlinx.datetime.LocalTime` | ✅ |
| `kotlinx.datetime.TimeZone` | ✅ |
| `kotlinx.datetime.atStartOfDayIn` | ✅ |
| `kotlinx.datetime.plus` | ✅ |
| `kotlinx.datetime.toInstant` | ✅ |
| `kotlinx.datetime.toLocalDateTime` | ✅ |

Rewrite pattern verification:

- Device-local zone: `val zone = TimeZone.currentSystemDefault()` — not UTC ✅
- Group-by local date: `Instant.fromEpochMilliseconds(it.timestamp).toLocalDateTime(zone).date` ✅
- Day start: `date.atStartOfDayIn(zone).toEpochMilliseconds()` ✅
- Next day: `date.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()` ✅
- 08:00 sample stamp: `LocalDateTime(date, LocalTime(8, 0)).toInstant(zone).toEpochMilliseconds()` ✅

### Proxy math and labels (`HcRecoveryProxyThermalSource`)

- `RHR_BPM_TO_DELTA_C = 20.0` constant kept ✅
- 0.65/0.35 mixing coefficients on `delta` kept ✅
- `coerceIn(-1.2, 1.2)` kept ✅
- `inferOriginLabel` using `.lowercase()` kept ✅
- `HC_INFERRED` / Garmin+Oura / Garmin / Oura origin labels kept ✅

### Explicit imports and package access

**`HcRecoveryProxyThermalSource`** (package `physio.thermal`):
- `ThermalSampleMTR` and `ThermalDataOrigins` are in the same package → short names, no import needed ✅
- `HRVDataMTR` and `RHRDataMTR` in sibling package `physio` → explicitly imported ✅

**`TrainingCircuitBreaker`** marked `internal`. ✅  
**`HcRecoveryProxyThermalSource`** marked `internal`. ✅  
`AuditorStatusTracker` is `object` with default visibility. ✅  
`AIMIPhysioOutcomes` types (`FetchOutcome`, `ProbeResult`, `PhysioPipelineOutcome`) are not `internal` — correct for shared physio DTOs. ✅

### `AIMIPhysioOutcomes` — DTO only, no HC SDK

- Three types: `FetchOutcome` (enum), `ProbeResult` (data class), `PhysioPipelineOutcome` (enum) ✅
- Health Connect named only in comments — no HC SDK types imported ✅
- Not `AIMIHealthConnectPermissions` (that file is T2, stays parked) ✅

### `AuditorStatusTracker` — no dump `AuditorVerdict`

- Enum values (`OK_CONFIRM`, `OK_SOFTEN`, etc.) are primitive String constants — no reference to dump `AuditorVerdict` ✅
- `Status.isActive()` / `isError()` / `isOffline()` / `isSkipped()` use `name.startsWith()` — no dump dependency ✅

### `TrainingCircuitBreaker` — no dump trainers

- No import of `AimiSmbTrainer`, `BasalMlTrainingCoordinator`, or any dump type ✅
- No `@IntKey(225)`, no `ApsPluginRegistrations` ✅

### Metro / DI

None of the four files use Metro, Hilt, or `javax.inject`. Correct — brief specifies "none of these four have Metro." ✅

### KDoc

`TrainingCircuitBreaker` KDoc links `[maxFailures]`, `[cooldownMs]`, `[clock]`, `[reset]` — all resolve as constructor parameters / member functions. ✅  
`AIMIPhysioOutcomes` KDoc comments mention Health Connect by name — these are plain comments, no unresolvable `[...]` KDoc links that would trigger `KDocUnresolvedReference`. ✅  
Dest `ThermalDataMTR` / `ThermalDataOrigins` / `LocalSentinel` KDoc not edited. ✅

### Strings / JSON / prefs

No `R.string`, `ResourceHelper`, `org.json`, `TextResolver`. No new preference keys. ✅

### Therapy math

`HcRecoveryProxyThermalSource` median/proxy/mix math unchanged (only datetime, clock, import rewrites). ✅  
`TrainingCircuitBreaker` failure/cooldown logic unchanged (only atomics → AapsLock rewrite). ✅

---

## Compile

Log: `/tmp/aimi-lot-U.log`

```
> Task :plugins:aps:compileKotlinIosSimulatorArm64
> Task :plugins:aps:compileAndroidMain
BUILD SUCCESSFUL in 49s
```

Both target tasks ran (not UP-TO-DATE). ✅  
No `e: file:` errors in the log. ✅

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

None.

---

## Suggestions 🟢

### 1. Benign diagnostic race in `AuditorStatusTracker.getDetailedMessage()`

In the STALE branch (line 97), `currentStatus.message` is read directly from the `object` field rather than using the status from the `Pair` returned by `getStatus()`. A concurrent `updateStatus()` call between the two could produce "STALE (Xm old, last=OFF)" with `currentStatus` showing the newer non-OFF value, or vice versa.

This is a display-only diagnostic string and the race window is tiny. The dump has it; keeping it is correct per "Therapy math unchanged." Flag for cleanup in a future lot if the auditor display grows more important.

### 2. Apparent double-weighting of HRV in `HcRecoveryProxyThermalSource`

The `hrvProxy` variable already applies a `* 0.35` coefficient:
```kotlin
val hrvProxy = ((baselineHrv - dayAvg) / 40.0) * 0.35
```
Then the delta mixes it again with `* 0.35`:
```kotlin
val delta = (rhrProxy * 0.65 + hrvProxy * 0.35).coerceIn(-1.2, 1.2)
```
The effective HRV weight is `0.35 × 0.35 = 0.1225`. This looks unusual but is copied faithfully from the dump per brief ("Therapy math unchanged"). Mentioning in case the AIMI author intended `0.35` total weight — worth confirming, but not a migration error.

### 3. Emoji in KDoc of `AIMIPhysioOutcomes`

The dump KDoc headers use emoji (📊, 🔍, 🎯). The brief says "no mass-translate dump comments," so keeping them is correct. The workspace rule against emoji applies to new content only. No action needed — noting for awareness.

---

## What Looks Good ✅

- `kotlin.concurrent.Volatile` used correctly — not `kotlin.jvm.Volatile`. Exactly what the brief requires.
- `AapsLock` rewrite on `TrainingCircuitBreaker` is clean: one lock guards both `failures` and `coolingUntilMs`; every read/write path is covered; `incrementAndGet` replaced with `failures += 1`; the injectable `clock` lambda pattern is idiomatic.
- kotlinx.datetime rewrite in `HcRecoveryProxyThermalSource` uses the exact function set from the brief — no leftover `java.time` symbols, device-local zone preserved.
- `HcRecoveryProxyThermalSource` does not import Health Connect — it builds `ThermalSampleMTR` entirely from dest `RHRDataMTR` / `HRVDataMTR`, which are already in commonMain. The Lot T grouping mistake is correctly undone.
- `AIMIPhysioOutcomes` is clean DTOs — zero HC SDK types despite the HC context in comments. The Lot T parking mistake (grouped with HC fetch) is correctly undone.
- `AuditorStatusTracker` stands alone — no `AuditorVerdict`, no `AuditorDataStructures`, no Harmonia or tree types.
- All 4 files compile on both `compileKotlinIosSimulatorArm64` and `compileAndroidMain`.
- `internal` visibility preserved on `TrainingCircuitBreaker` and `HcRecoveryProxyThermalSource`. Other types correctly visible for use by future dest callers.

---

## Blocked — still not copy-safe (same as report-U)

Recursive engine (`RecursiveBeliefTickContext`, `RecursiveBeliefModels`, engine / adapters) still needs dump `MealAbsorptionPhaseEngine` / `PhysiologicalPhaseClassifier` / `HyperSeverityClassifier` / `DoseTerminalSnapshot` (UAM Compose). Not unblocked by this lot.  
`TpoTriggerEngine` still needs dump `PatientMode`.  
`MpcController` still needs dump `HyperTrajectoryMpcFeedForward`.  
`physio/pattern/*`, remaining `release/*`, `WCycleAdjuster` / `WCycleFacade` / `WCycleLearner`, AutoDrive engine graph, remaining TPO, Lot T leftovers (`TuningContextEngine`, `AdvancedPredictionEngine`, `HormonitorLabels`, dual-brain auditor helpers, `RtInstrumentationHelpers`) — all still parked.

---

APPROVE
