# E4 — External Access Inventory for `DetermineBasalAIMI2.kt`

> **Date:** 2026-10-09  
> **Branch:** `feat/e3-causal-state` (E3 merged)  
> **File:** `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt` (733 KB)  
> **Approach:** Conservative. No behavior change. Only trivial and safe replacements.

## Summary

**Result: NO safe replacements implemented.** All external accesses fall into one of these categories:
- Already handled by the E1 async-cache pattern (acquisition layer)
- Explicitly deferred by the blueprint (config keys)
- Side effects (documented only, per constraints)
- Fundamental services (clock, plugin state) with no snapshot equivalent
- Direct DB reads with semantic risks if replaced

This is a valid E4 outcome per the task definition.

## Category 1: Already handled (E1 async-cache pattern)

These go through `*.refresh(scope = determineIoScope, load = {...})` async caches. The tick reads the cached value. This is the correct acquisition-layer pattern per blueprint section 2.2. **No action.**

| Service | Cache function | Lines |
|---|---|---|
| `persistenceLayer.getTherapyEventDataFromTime` (CANNULA_CHANGE) | `pumpAgeDaysCached()` / `refreshPumpAgeAsync()` | 943-962 |
| `persistenceLayer.getNewestBolusOfType(SMB)` | `latestSmbCached()` / `refreshLatestSmbAsync()` | 964-976 |
| `tirCalculator.calculate` / `averageTIR` (14 calls) | `latestTirWarmupSnapshot()` / `refreshTirWarmupAsync()` | 978-1016 |
| `persistenceLayer.getMostRecentCarbByDate`, `getFutureCob`, `getMostRecentCarbAmount`, `getUserEntryDataFromTime` | `latestCarbContextSnapshot()` / `refreshCarbContextAsync()` | 1018-1060 |
| `tddCalculator.calculate(2)` / `averageTDD` | `tdd2DaysCached()` / `refreshTdd2DaysAsync()` | 1083-1097 |
| `tddCalculator.calculate(30)` / `averageTDD` | `resolveTdd30DaysForLearner()` / `refreshTdd30DaysAsync()` | 1099-1111 |
| `persistenceLayer.getTherapyEventDataFromTime` (SENSOR_CHANGE) | `resolveSensorInsertionMsCached()` / `refreshSensorInsertionAsync()` | 1113-1130 |
| `persistenceLayer.getStepsCountFromTimeToTime` | `stepsCached()` / `refreshStepsAsync()` | 1165-1176 |
| `persistenceLayer.getHeartRatesFromTimeToTime` | `heartRatesCached()` / `refreshHeartRatesAsync()` | 1178-1190 |
| `persistenceLayer.getTemporaryBasalsStartingFromTime` | `refreshTempBasalsAsync()` | 1192-1198 |
| `persistenceLayer.getBolusesFromTime` | `bolusesFromTimeCached()` / `refreshBolusesAsync()` | 1200-1214 |
| `tddCalculator` / `tirCalculator` (invocation-scoped) | `determineBasalInvocationCaches.getTdd*` / `getTir*` | 7791, 8293-8311 |

## Category 2: Deferred by blueprint (config keys)

**170 `preferences.get(...)` reads.** The blueprint (section 7, annex-8) explicitly defers these:

> "109 typed config keys (stay in the Android/iOS shell until listed)"

The `AimiConfigSnapshot` currently only has `schemaVersion`. Until the 109+ keys are typed into the snapshot, these stay in the shell. **No action for E4.**

Examples: `OApsAIMIMaxSMB`, `OApsAIMIHighBg`, `OApsAIMIautoDriveActive`, `autodriveMaxBasal`, `meal_modes_MaxBasal` (lines 1405-6537).

## Category 3: Side effects (document only per constraints)

**4 `notificationManager.post()` calls.** User-facing notifications. Per task constraints: document only, do not touch.

| Line | Notification |
|---|---|
| 5416 | `HYPO_RISK_ALARM` — hypo risk warning |
| 11778 | `HYPO_RISK_ALARM` — menstrual cycle delay warning |
| 12603 | `AUTOMATION_MESSAGE` — trajectory warning |

## Category 4: Fundamental services (no snapshot equivalent)

| Service | Usage | Why not replaceable |
|---|---|---|
| `dateUtil.now()` | 50 reads — tick clock | This IS the time source. The snapshot's `wallClockEpochMs` comes from here. Cannot self-replace. |
| `activePlugin.activeBgSource` | Line 1767 — CGM class name for logging | Implementation detail for diagnostics, not decision input |
| `activePlugin.activePump` | Lines 4083, 8122 — pump description, connection state | Hardware state, not in snapshot schema |
| `glucoseStatusCalculatorAimi` | 7 reads — recent glucose | Glucose IS in snapshot (`AimiGlucoseSnapshot`), but the calculator provides derived values; replacing would require snapshot schema extension (out of E4 scope) |
| `profileFunction` | 2 reads | Profile IS in snapshot (`AimiProfileSnapshot`), but derived effective profile; schema extension needed |
| `iobCobCalculator` | 3 reads | IOB/COB ARE in snapshot (`AimiInsulinSnapshot`, `AimiMealSnapshot`), but calculator provides derived arrays; schema extension needed |

## Category 5: Direct DB reads (candidates with semantic risks)

### 5a. Line 2002: `runBlocking { persistenceLayer.getBolusesFromTime() }`

```kotlin
prebolus = AimiLegacyPrebolusDelivered { since, minAmount ->
    runBlocking {
        persistenceLayer.getBolusesFromTime(since, true).any {
            (it.type == BS.Type.NORMAL || it.type == BS.Type.SMB) && it.amount >= minAmount
        }
    }
},
```

**Candidate replacement:** Use `bolusesFromTimeCached(since, true)` (already exists, line 1200).

**Why NOT safe:** The `runBlocking` version does a FRESH synchronous DB read. The cached version returns the last async-loaded value, which may be stale if a bolus was just delivered and the cache hasn't refreshed. Missing a recent prebolus changes the SMB decision. **Semantic risk. Not replaced.**

**Future work:** Make the prebolus check use a write-through cache or pass the bolus list in the snapshot.

### 5b. `Therapy(persistenceLayer)` creations (lines 2060, 6698, 10059, 10148, 10167)

These create `Therapy` objects that synchronously read therapy events from DB to determine meal-mode flags (`bfastTime`, `lunchTime`, `dinnerTime`, `snackTime`, `mealTime`, `highCarbTime`).

| Line | Context | Redundant? |
|---|---|---|
| 2060 | `hydrateClocks()` — main hydration via `DecideTherapyExercise` (commonMain) | **No** — this is the primary hydration |
| 6698 | Prebolus window check | **Maybe** — if `hydrateClocks()` ran first, the fields are already set |
| 10059 | Meal-mode check for low-BG guard | **Maybe** — same |
| 10148 | Reason string ("✔"/"✘" for meal mode) | **Maybe** — same |
| 10167 | Autodrive early-check | **Maybe** — same |

**Why NOT safe to remove:** Cannot verify from static analysis alone that `hydrateClocks()` always runs before lines 6698/10059/10148/10167 in every code path. The `Therapy` object also calls `updateStatesBasedOnTherapyEvents(forceRefresh = true)` which may refresh state that the cached fields don't have. Removing these could change meal-mode detection. **Semantic risk. Not replaced.**

**Future work:** Trace all call paths to verify ordering, then replace redundant `Therapy` creations with the already-hydrated fields (`bfastTime`, `lunchTime`, etc.).

## Category 6: Learners (do not touch per constraints)

`basalLearner`, `unifiedReactivityLearner`, `basalNeuralLearner`, `basalMlTrainingCoordinator` — TFLite-based. External by design. **No action.**

## Category 7: False positives

- `java.io` (2 hits): Only in comments (lines 835, 8322). No actual code.
- `org.json` (2 hits): Only in comments (lines 7144, 7168). Code uses `kotlinx.serialization`.
- `System.currentTimeMillis`: 0 hits. Clock goes through `dateUtil`/`aimiWallClockMs`.

## Verification

- `git diff --stat`: No changes to `DetermineBasalAIMI2.kt` (working tree clean for this file)
- This report is the E4 deliverable

## Recommendations for E5/E6

1. **Snapshot schema extensions needed** (out of E4 scope):
   - Typed config keys (109+) → `AimiConfigSnapshot`
   - Derived glucose/profile/IOB values → extend `AimiInputSnapshot`
   - Meal-mode flags → could go in `AimiEngineState` or snapshot

2. **Call-path analysis** for `Therapy` redundancy (lines 6698/10059/10148/10167)

3. **Prebolus cache** — decide on staleness tolerance or write-through
