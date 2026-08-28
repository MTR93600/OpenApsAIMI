# Lot P — REVIEW

Reviewer: senior architect + senior Kotlin/KMP reviewer  
Branch: `kmp-aimi-migration-study`  
HEAD at review time: `91b9ce0451` (Lot O, uncommitted Lot P in working tree)  
Files reviewed: 11 new dest files listed in brief-P Copy list

---

## Scope check

| check | result |
|---|---|
| Exactly 11 files copied | ✅ |
| No Skip files copied | ✅ |
| No Lot O files overwritten | ✅ |
| No `physio/gate/` or `physio/thyroid/` existed before | ✅ (confirmed) |
| No `@IntKey(225)` / tick / plugin registration | ✅ |

---

## KMP ban scan

Scanned all 11 files. Grep confirmed no matches for:

| ban | result |
|---|---|
| `android.*` | ✅ clean |
| `java.io.File` | ✅ clean |
| `org.json` | ✅ clean |
| Compose / Activity (Android class) | ✅ clean — `KernelType.ACTIVITY` is T1 naming, not Android `Activity` |
| `System.currentTimeMillis` | ✅ clean — all replaced by `aimiWallClockMs()` |
| `kotlin.jvm.Volatile` | ✅ clean — `import kotlin.concurrent.Volatile` used correctly |
| `javaClass` | ✅ clean |
| `String.format` / `java.util.Locale` | ✅ clean |
| `EnumMap` / `KernelType::class.java` | ✅ clean — `mutableMapOf<KernelType, Double>()` used |
| `ConcurrentHashMap` | ✅ clean — `mutableMapOf` + `AapsLock` |
| `LinkedList` | ✅ clean — `ArrayDeque` used |
| `java.lang.Enum.valueOf` | ✅ clean — `enumValues<T>().firstOrNull` pattern used |

---

## Rewrite audit

### Time — `aimiWallClockMs()`

| file | location | result |
|---|---|---|
| `ThyroidModels.kt` | `ThyroidInputs.timestampMs` default | ✅ |
| `ThyroidPreferences.kt` | `getCurrentInputs()` | ✅ |
| `AIMIPhysioFeatureExtractorMTR.kt` | `PhysioFeaturesMTR.timestamp`; `extractHRVFeatures` `now` | ✅ |
| `AIMIPhysioBaselineModelMTR.kt` | `updateBaseline` `now`; `restoreBaseline` age log | ✅ |
| `AIMIPhysioContextEngineMTR.kt` | `PhysioContextMTR.timestamp` | ✅ |
| `PhysioAggregator.kt` | all four reads | ✅ |

No `System.currentTimeMillis()` anywhere.

### Format — `aimiFmt1` / `aimiFmt2`

- `CosineTrajectoryGate`: `"%.2f"` → `aimiFmt2`, `"%.1f"` → `aimiFmt1` ✅  
- Feature extractor: `Double.format(decimals)` deleted; all call sites → `aimiFmt1` ✅  
- Baseline: same as extractor ✅  
- `ThyroidDiagnosticsLogger`: `String.format(Locale.US, "…%.2f…")` → `aimiFmt2`; no `java.util.Locale` import ✅  
- No `aimiFmt3` anywhere ✅

### Metro

Kept `@Inject` / `AppScope` / `SingleIn` on `CosineTrajectoryGate`, `AIMIPhysioFeatureExtractorMTR`, `AIMIPhysioBaselineModelMTR`, `AIMIPhysioContextEngineMTR`, `PhysioAggregator`. No `@Inject` on thyroid files. No Hilt. No `javax.inject`. ✅

### Logging

`LTag.AIMI` used on feature extractor, baseline, context engine. Cosine gate has injected logger with no new log calls added (per brief). ✅

### `@Volatile`

`import kotlin.concurrent.Volatile` — correct. `@Volatile private var cachedBaseline` in `AIMIPhysioBaselineModelMTR`. Read outside the lock (via `getCurrentBaseline()`), written inside — `@Volatile` ensures visibility. Correct design. ✅

### `AapsLock` in place of `synchronized`

`AapsLock` / `withLock` is the established teacher pattern. Verified in `OpenAPSSMBPlugin.kt` line 204-205:

> `/** Guards [dynIsfCache]. Was `synchronized(dynIsfCache)`, which is JVM only. */`  
> `private val isfCacheLock = AapsLock()`

`AIMIPhysioBaselineModelMTR` uses one `historyLock` for all four history maps — correct; the brief specified "one lock for every history map". `PhysioAggregator` uses separate `stepLock` / `hrLock` for each buffer — correct. **APPROVED.**

### Collections

| file | replacement | result |
|---|---|---|
| `CosineTrajectoryGate.calculateWeights` | `EnumMap` → `mutableMapOf<KernelType, Double>()` | ✅ |
| `AIMIPhysioBaselineModelMTR` | `ConcurrentHashMap` → `mutableMapOf` + `AapsLock` | ✅ |
| `PhysioAggregator` | `LinkedList` → `ArrayDeque`; `removeFirst()` kept | ✅ |

### `ThyroidPreferences.enumValue`

```kotlin
private inline fun <reified T : Enum<T>> enumValue(name: String, default: T): T {
    val raw = name.ifBlank { default.name }
    return enumValues<T>().firstOrNull { it.name == raw } ?: default
}
```

Matches Lot M `WCyclePreferences` pattern. Blank / unknown → default. No `java.lang.Enum`. ✅

### Thyroid enum isolation

No import of `wcycle.ThyroidStatus` anywhere in `physio/thyroid/`. All five thyroid files use same-package `ThyroidStatus` (`EUTHYROID`, `HYPER_MILD`, `HYPER_MODERATE`, `HYPER_SEVERE`, `NORMALIZING`, `UNKNOWN`). ✅

### Explicit imports / FQ names

- `import kotlin.math.abs` present in both files that call `abs(...)` ✅  
- `aimiFmt1`, `aimiFmt2`, `aimiWallClockMs` all imported at each use site ✅  
- No fully qualified names at use site anywhere in the 11 files ✅

### KDoc

`CosineTrajectoryGate` KDoc: `[app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard]` — `TrajectoryGuard.kt` exists at dest (confirmed by Glob). FQCN link is resolvable. ✅  
No `[docs/…]` path strings present. No unresolved `[Symbol]` links. ✅

---

## Compile

Log: `/tmp/aimi-lot-P.log`

```
BUILD SUCCESSFUL in 49s
93 actionable tasks: 14 executed, 79 up-to-date
```

Both `:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain` executed. No `^e:` lines.

A commonMain compile is **not** "AIMI runs on iOS". ✅

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

### 1. `AIMIPhysioBaselineModelMTR.restoreBaseline()` — logging inside the lock

`restoreBaseline` holds `historyLock` for the entire body including two `aapsLogger.info(...)` calls. Logging inside a mutex extends lock hold time and, if the logger acquires its own lock, creates a potential deadlock order. The same method also calls four `putAll(...)` under the lock — which is necessary — but the log calls are not.

**Suggested fix** (not required to unblock merge, but should be addressed in a cleanup lot):  
Move both `aapsLogger.info(...)` calls outside the `withLock { }` block, capturing the relevant values into local `val`s inside the lock before releasing.

### 2. `PhysioAggregator.cleanup()` re-samples the clock

`cleanup()` calls `aimiWallClockMs()` independently, while the callers (`addStepDelta`, `addHeartRate`) already computed `now`. Under normal conditions this is harmless (a few microseconds). Under a mocked clock (future tests) this skew will cause cleanup to use a different time than insertion. Pass `now: Long` into `cleanup` to keep it consistent.

**Suggested fix** (low priority, test-hygiene):
```kotlin
private fun cleanup(buffer: ArrayDeque<TimestampedValue>, maxRetentionMs: Long, now: Long) {
    val threshold = now - maxRetentionMs
    while (buffer.isNotEmpty() && buffer.first().ts < threshold) buffer.removeFirst()
}
```
Call sites pass the already-computed `now`.

---

## Suggestions 🟢

### 3. `PhysioAggregator` — `WINDOW_15M` / `WINDOW_60M` should be `companion object` constants

```kotlin
private val WINDOW_15M = 15 * 60 * 1000L
private val WINDOW_60M = 60 * 60 * 1000L
```

These are instance-level `val` properties. For constants that never vary, prefer `companion object { private const val ... }` (or at least `val` in companion). With `@SingleIn` there is only one instance, so no real overhead, but the declaration is misleading.

### 4. `CosineTrajectoryGate` — dead log block at lines 152–154

```kotlin
if (abs(clampedSens - 1.0) > 0.05 || abs(clampedShift) > 5) {
     // Only log high impact changes to avoid spam, or rely on caller to log
}
```

The entire `if` block body is a comment. Remove the `if` entirely; the comment can be added to the `return` statement instead.

### 5. `ThyroidEffectModel.calculateEffects` — unreachable `else` branch

The `when` expression contains `else -> 0.0` but `EUTHYROID` and `UNKNOWN` are already handled by the early-return guard. Add a comment, or replace with an explicit exhaustive `when` (omitting `else`) so future enum additions cause a compile error here.

---

## What Looks Good ✅

- **Therapy math is unchanged** — all multiplier formulas, cosine similarity, softmax, percentile, Z-score, and normalizing-phase gate logic are preserved verbatim from the dump.
- **Thyroid enum isolation** — the two `ThyroidStatus` enums (`physio.thyroid` Basedow vs `wcycle` hypothyroid) are correctly kept separate; no import pollution.
- **`AapsLock` usage** — mirrors the SMB teacher pattern exactly, with the same KDoc rationale. Correct lock-per-resource design (one `historyLock` in baseline, separate `stepLock`/`hrLock` in aggregator).
- **`enumValues<T>()` pattern** — matches Lot M `WCyclePreferences`; blank/unknown falls to default.
- **`kotlin.concurrent.Volatile`** — correct import (not `kotlin.jvm.Volatile`).
- **No `aimiFmt3`** — only `aimiFmt0/1/2` exist; none of the 11 files introduce or call `aimiFmt3`.
- **`ArrayDeque` migration** — `removeFirst()` in `cleanup` is the right Kotlin `ArrayDeque` API; no `removeAt(0)` or Java-style calls.
- **`mutableMapOf` for `EnumMap`** — correct replacement in `calculateWeights`; the `forEach`-fill pattern is preserved.
- **No inter-module `project()` deps added**.
- **BUILD SUCCESSFUL** — both iOS simulator and Android main targets, no errors.

---

## Blocked items (unchanged from report)

The recursive engine is still blocked:

- `RecursiveBeliefTickContext` — needs dump `MealAbsorptionPhaseEngine`, `PhysiologicalPhaseClassifier`, `PhysiologicalPatternSnapshot`, `HyperSeverityClassifier`. Lot O landing `PhysioContextMTR` / `PhysioMultipliersMTR` did **not** unblock it.
- `RecursiveBeliefModels` — needs `HarmoniaSmbAuthorityDecision` → dump `PatternCapKind`.
- Engine / adapters / paradox / resolver / cascade / chaos / release / authority gate — need TickContext.

`physio.thyroid.ThyroidStatus` (`EUTHYROID`, `HYPER_MILD`, `HYPER_MODERATE`, `HYPER_SEVERE`, `NORMALIZING`, `UNKNOWN`) is **not** `wcycle.ThyroidStatus` (`EUTHYROID`, `HYPOTHYROID_TREATED`, `HASHIMOTO`, `THYROIDECTOMY`). They were not merged. This is correct.

---

APPROVE_WITH_CONCERNS
