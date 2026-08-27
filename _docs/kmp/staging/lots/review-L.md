# Lot L — Quality Review

Reviewer: senior software architect + senior Kotlin/KMP engineer  
Date: 2026-08-28  
Repo: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`

---

## Verdict: APPROVE_WITH_CONCERNS

Commit is allowed. Two Important issues must be tracked as debt before the next T1 wave.

---

## Spec compliance

| Check | Result |
|---|---|
| Exactly 14 files copied (git status `??`) | ✅ |
| 9 skip files not copied | ✅ |
| No `@IntKey(225)` anywhere | ✅ |
| No tick / plugin move | ✅ |
| No overwrite of existing dest files | ✅ (report confirmed, no dest existed) |

---

## KMP cleanliness (per-file scan)

| Banned symbol | Files with violations |
|---|---|
| `android.*` | none |
| `java.io.File` | none |
| `org.json` | none |
| `System.currentTimeMillis` | none |
| `ResourceHelper` | none |
| `R.string` | none |
| `android.util.Log` | none |
| `java.time` | none |
| `javax.inject` / Hilt | none |
| `String.format` / `java.util.Locale` | none |

Grep of banned symbols across the 14 new files: no hits. ✅

---

## Import / DI rules

| Rule | Status |
|---|---|
| Metro `@Inject` / `@SingleIn(AppScope)` | ✅ GlucoseStatusCalculatorAimi, DynIsfTrajectoryTuning, DynamicBasalController |
| No Hilt `@HiltViewModel` etc. | ✅ |
| `kotlin.concurrent.Volatile` (not `kotlin.jvm.Volatile`) | ✅ IsfSourceTelemetry |
| `@JvmStatic` removed | ✅ KpiCalculator, PerformanceScorer |
| `LTag.APS` → `LTag.AIMI` | ✅ DynIsfTrajectoryTuning |
| `LTag.GLUCOSE` kept on GlucoseStatusCalculatorAimi | ✅ |
| Unused `LTag` import dropped from DynamicBasalController | ✅ |
| Explicit imports for `aimiFmt0/1/2/aimiFmtSigned1` | ✅ all files |
| `NumberFormat(minFractionDigits=4).format(…, SEPARATOR_DOT)` for `%.4f` | ✅ DynIsfTrajectoryTuning line 104 |
| No new `aimiFmt4` helper | ✅ |
| Explicit `import kotlin.math.abs` (no FQN at use site) | ✅ KpiCalculator, PerformanceScorer, DynIsfTrajectoryTuning |
| KDoc unresolvable `[Symbol]` wrapped in backticks | ✅ ContinuousStateEstimator, TrajectoryPeakBias, DetermineBasalaimiSMB2 |
| School English in new/changed comments | ✅ (French dump comments not mass-translated per brief) |

---

## Architecture check — T3cTrajectoryContext inline (Brief check #4)

**Rating: Important (documented stub, not Critical).**

`T3cTrajectoryContext` is inlined at the bottom of `DynamicBasalController.kt` (lines 451–465) with an explicit KDoc:

```
`build()` is not here: it needs `TrajectoryAnalysis`, which is still dump-only.
A later T1 wave should replace this with dump `basal/T3cTrajectoryContext.kt`.
```

Does it silently change therapy math vs freeze?  
No. `computeT3c` takes `trajectory: T3cTrajectoryContext? = null`. Since `build()` does not exist in commonMain, no current caller can construct an instance. The null-safe call `trajectory?.let { ctx -> applyT3cTrajectoryHypoBrake(...) }` is a no-op for all current call sites. Behavior is identical to the freeze when `trajectory = null`.

Is it a future collision risk?  
Yes, exactly as documented. When a later lot copies dump `basal/T3cTrajectoryContext.kt`, there will be two declarations of the same class in the module and it will not compile. The required action is documented in both `report-L.md` and the inline KDoc. No follow-up ADR is needed until that wave.

---

## Findings

### Important 🟡

#### I-1 — DynIsfTrajectoryTuning.kt: 5 companion object constants are dead code

The `private companion object` (lines 157–169) contains:

```kotlin
const val MIN_PARABOLA_CORR: Double = 0.56
const val RISE_DELTA_SCALE: Double = 11.0
const val RISE_PN_SCALE: Double = 9.0
const val RISE_ACC_SCALE: Double = 6.0
const val FALL_DELTA_SCALE: Double = 10.0
```

The file also declares (lines 199–203):

```kotlin
private const val RISE_DELTA_SCALE: Double = 11.0
private const val RISE_PN_SCALE: Double = 9.0
private const val RISE_ACC_SCALE: Double = 6.0
private const val FALL_DELTA_SCALE: Double = 10.0
private const val MIN_PARABOLA_CORR: Double = 0.56
```

The file-level function `trajectoryRiseFallScores` is top-level (not inside the class) and resolves to the **file-level** private constants — not the companion object ones. The companion object copies are never read by any code path.

**Why this matters:** The values are the same today. If a future maintainer updates the companion object constants expecting to affect the rise/fall score math, nothing changes — the file-level ones still govern behavior. The error will be silent. This is the exact failure mode ADR G0 M0.6 warns about (silent divergence that looks like a KMP bug).

**Fix (one-time, before next wave):** Remove the 5 dead constants from the companion object. The unique companion object constants (`PHYSIO_ISF_STACK_SKIP_BELOW`, `BG_GATE_MIN`, `BG_GATE_MAX`, `ACTIVATION_THRESHOLD`, `PROFILE_REL_LOW`, `PROFILE_REL_HIGH`) stay — they are used inside `computeAdjustedIsf`. No therapy math change; values were already identical.

#### I-2 — T3cTrajectoryContext stub (tracked per brief check #4)

As analyzed above. The inlined stub compiles and is safe today. It will cause a duplicate-class compile error if a later lot copies `basal/T3cTrajectoryContext.kt` without removing the inline first.

**Action required before next T1 wave that includes `basal/T3cTrajectoryContext.kt`:**
1. Remove `data class T3cTrajectoryContext` and its `companion object` from `DynamicBasalController.kt` (lines 451–465).
2. Add an import for the new canonical location.
3. Confirm `build()` signature matches the call sites.

---

### Nits 🟢

#### N-1 — UndeclaredCobEstimator.kt: redundant same-package import

```kotlin
package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1  // same package — import not needed
```

Kotlin does not require importing symbols from the same package. The import is harmless but lint may flag it. Consistent with other files in the lot (same pattern appears in several). Not a defect, just cleanup debt.

#### N-2 — IsfSourceTelemetry.kt: multiple declarations on one line

```kotlin
@Volatile var lastKalmanFastIsf: Double? = null; private set
@Volatile var lastIsfAdjEngine: Double? = null; private set
```

Kotlin style prefers one statement per line. Seven fields (lines 108–113, 122, 147, 150) use this semicolon form. From the dump — do not mass-fix, but clean on next touch.

#### N-3 — DynamicBasalController.kt: mutable class properties instead of companion object constants

```kotlin
private val MAX_TBR_MULTIPLIER = 10.0
private val MIN_TBR_MULTIPLIER = 0.0
private val P_WEIGHT = 0.05
private val D_WEIGHT = 0.15
```

These never change and are conceptually constants. `const val` in a companion object would avoid one allocation per instance. With `@SingleIn` this is trivial but idiomatic Kotlin prefers `const` for numeric tunables. Not a defect; the singleton scope makes the cost zero.

#### N-4 — DynamicBasalController.kt: projected BG computed twice in reason string

In `compute()` companion function the value `input.bg + velocity * (horizon / 5.0)` is computed once for `multiplier` and again inside the `reason` string. A local `val projectedBg` would make the log and the logic agree and remove the duplication. Minor readability issue only.

---

## What looks good ✅

- **Copy fidelity:** All 14 files are exactly what the brief listed; nothing extra snuck in.
- **Therapy math preserved:** All computation kernels (quadratic fit, PD controller, ISF scoring, IOB budget, stacking stance) are unchanged from the freeze. The only changes are formatting helpers, log tags, and imports.
- **Safety gates intact:** `InsulinLoadGovernor`, `InsulinStackingStance`, `BasalTerminalInvariants` all keep their reduction-only, fail-open semantics.
- **T3cAnticipation pre-existing:** `T3cAnticipation.kt` referenced by `DynamicBasalController` was already in commonMain before Lot L — not an extra copy.
- **`TimestampedBgSample` pre-existing:** Same — already in the same package. No phantom import needed.
- **Metro DI consistent:** `@SingleIn(AppScope)` on `GlucoseStatusCalculatorAimi`, `DynIsfTrajectoryTuning`, `DynamicBasalController` matches the SMB teacher pattern.
- **No `@IntKey` anywhere:** Plugin registration is deferred correctly.
- **Compile retry documented:** First failure was one missing type; fix was minimal (inline stub); second attempt `BUILD SUCCESSFUL`.
- **KDoc links:** All cross-module or private references use backticks, not checked `[links]`. No spurious module deps added.
- **`NumberFormat` usage:** `DynIsfTrajectoryTuning` correctly uses `NumberFormat(minFractionDigits = 4).format(mult, NumberFormatPlatform.SEPARATOR_DOT)` for the four-decimal multiplier log, with explicit imports — exactly as the brief specifies.

---

## Commit allowed?

**Yes.** No Critical issues. The two Important items are maintenance debt, not correctness bugs. They should be entered as tracked items before the next wave starts.

Before next T1 wave:
- [ ] Remove 5 dead constants from `DynIsfTrajectoryTuning` companion object (I-1)
- [ ] Remove inlined `T3cTrajectoryContext` from `DynamicBasalController.kt` when `basal/T3cTrajectoryContext.kt` is copied (I-2)

---

**APPROVE_WITH_CONCERNS**
