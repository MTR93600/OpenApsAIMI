# Lot M — QUALITY REVIEW

Reviewer: senior software architect + senior Kotlin/KMP engineer  
Date: 2026-08-28  
Files reviewed: 7 (6 new + 1 edited)

---

## Scope

Files reviewed against `brief-M.md`, the dump originals, and prior lot reports.

| file | status in lot |
|---|---|
| `trajectory/PhaseSpaceModels.kt` | new (copied + rewrite) |
| `trajectory/TrajectoryGuard.kt` | new (copied + rewrite) |
| `trajectory/TrajectoryMetricsCalculator.kt` | new (copy as-is) |
| `wcycle/WCyclePreferences.kt` | new (copied + enum rewrite) |
| `basal/T3cTrajectoryContext.kt` | new (copied, full `build()`) |
| `inflammatory/InflammationAdjuster.kt` | new (copy as-is) |
| `basal/DynamicBasalController.kt` | edited (inline stub removed) |

---

## KMP Ban Checks

| ban | status |
|---|---|
| `android.*` | none in any of the 6 new files ✅ |
| `java.io.File` | none ✅ |
| `org.json` | none ✅ |
| Compose / Activity | none ✅ |
| `javax.inject` | none; Metro used correctly ✅ |
| `System.currentTimeMillis()` | none; replaced with `aimiWallClockMs()` ✅ |
| `java.lang.Enum.valueOf` | none; replaced with `enumValues<T>().firstOrNull` ✅ |
| `String.format` / `java.util.Locale` | none ✅ |
| `R.string` / `ResourceHelper` | none ✅ |

---

## Rewrite Checks

### Clock (PhaseSpaceModels.kt)

Dump had `System.currentTimeMillis()` at two default parameter sites
(`TrajectoryWarning.timestamp` and `TrajectoryAnalysis.timestamp`).
Copied file replaces both with `aimiWallClockMs()` from the correct import.
✅ Correct.

### Enum (WCyclePreferences.kt)

Dump: `runCatching { java.lang.Enum.valueOf(T::class.java, p.get(key).ifBlank { default.name }) }.getOrElse { default }`

Copied: `enumValues<T>().firstOrNull { it.name == raw } ?: default` with blank-guard `.ifBlank { default.name }`.

Behaviour is equivalent: blank input → `default.name` → lookup → default returned. Unknown name → no match → default returned.
✅ Correct.

### Format (PhaseSpaceModels.kt, TrajectoryGuard.kt)

- `aimiFmt0` / `aimiFmt1` / `aimiFmt2` used with explicit imports. ✅
- Three-decimal format uses `NumberFormat(minFractionDigits = 3).format(value, NumberFormatPlatform.SEPARATOR_DOT)`. ✅
- Signed two-decimal: `sign + aimiFmt2(abs(value))`. ✅
- No `aimiFmt3` or `aimiFmtSigned2` invented. ✅

### Metro / Log (TrajectoryGuard.kt)

- `@Inject` / `@SingleIn(AppScope::class)` kept. No Hilt. No `@IntKey(225)`. ✅
- `LTag.APS` replaced by `LTag.AIMI` in all log calls. ✅

### Explicit imports (PhaseSpaceModels.kt)

- `import kotlin.math.acos` present; `acos(cosAngle)` used by short name. ✅
- `import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage` present; `ActivityStage.TAIL` used by short name. ✅
- `NumberFormat` and `NumberFormatPlatform` imported explicitly. ✅

---

## T3cTrajectoryContext — Stub vs Freeze Check

**Critical question**: is the copied `build()` faithful enough that `DynamicBasalController` behaviour is not silently weaker than it was with the stub?

### Comparison

Stub deleted from `DynamicBasalController.kt` (confirmed from `git diff`):

```kotlin
data class T3cTrajectoryContext(
    val minPredBg: Double,
    val eventualPredBg: Double,
    val lgsThresholdMgdl: Double,
    val trajectoryAnalysisActive: Boolean,
    val convergenceVelocity: Double?,
    val energyBalance: Double?,
    val trajectoryTypeName: String?
) {
    companion object {
        fun guardBg(ctx: T3cTrajectoryContext): Double =
            min(ctx.minPredBg, ctx.eventualPredBg)
    }
}
```

Copied `T3cTrajectoryContext.kt` is **byte-for-byte identical to the dump**.
Fields: same seven. `guardBg`: same implementation.
`build()` added: extracts `convergenceVelocity`, `energyBalance`, `trajectoryTypeName` from a
nullable `TrajectoryAnalysis` — safe, correct, matches dump.

`applyT3cTrajectoryHypoBrake` in `DynamicBasalController.kt` accesses:
- `T3cTrajectoryContext.guardBg(ctx)` — present ✅
- `ctx.lgsThresholdMgdl` — present ✅
- `ctx.energyBalance` — present ✅
- `ctx.trajectoryAnalysisActive` — present ✅
- `ctx.trajectoryTypeName` — present ✅
- `ctx.convergenceVelocity` — present ✅

**Verdict on this check: behaviour is identical, not weaker.** The new full class is a strict superset of the old stub. ✅

### No duplicate declaration

`grep` confirms `data class T3cTrajectoryContext` appears exactly once in the codebase —
in `basal/T3cTrajectoryContext.kt`. The git diff shows the stub was removed cleanly. ✅

---

## Findings

### 🟡 Unused imports — TrajectoryMetricsCalculator.kt

Lines 5–6:
```kotlin
import kotlin.math.min
import kotlin.math.pow
```

Neither `min(...)` nor `.pow(...)` is called anywhere in this file.
`max` is used (lines 120, 197, 202); `sqrt` is used; `abs` is used; `min` and `pow` are not.
These are likely carry-overs from the dump that should have been pruned on copy.

They cause IDE "Unused import" warnings and reduce signal-to-noise in the import block.
Not a compile error (confirmed BUILD SUCCESSFUL) and not safety-relevant.

**Fix**: remove both unused imports from `TrajectoryMetricsCalculator.kt`.

---

### 🟢 TrajectoryGuard.fmt3() — new NumberFormat per call

`fmt3()` (line 366–367) and the equivalent local `fmt3` lambda in `PhaseSpaceModels.toConsoleLog()`
both instantiate `NumberFormat(minFractionDigits = 3)` each time they are called.
`toConsoleLog` is fine — it creates one instance per analysis log event.
`TrajectoryGuard.fmt3()` is a private function that creates one per invocation.

This is on the logging path (not a hot path), so the impact is negligible.
A `companion object` or `lazy` cached instance would be slightly cleaner but is not required.

---

### ✅ DynamicBasalController.kt — scope of change confirmed

`git diff` shows exactly the deleted stub block. Nothing else changed in this file.
The "later T1 wave" comment is gone. ✅

---

### ✅ No inter-module deps added

No `implementation(project(":..."))` or `api(project(":..."))` changes. ✅

---

### ✅ No @IntKey(225) added

Plugin registration correctly deferred. ✅

---

### ✅ TrajectoryGuard lastAnalysis

`private var lastAnalysis: TrajectoryAnalysis?` on a `@SingleIn` singleton is unsynchronised
shared mutable state. In the tick-based, single-threaded AIMI algorithm path this is safe.
The field is used only for UI cache; a stale read would be cosmetic, not safety-relevant.
This pattern comes from the dump; not a regression introduced by this lot.

---

## Compile

Reported: `BUILD SUCCESSFUL` (iOS simulator arm64 + androidMain). No retry.
A `commonMain` compile is not "AIMI runs on iOS".

---

## Summary

All six files copied and correctly rewritten per spec. The inline stub in
`DynamicBasalController.kt` is cleanly removed. `T3cTrajectoryContext.build()` is
a faithful dump copy; `DynamicBasalController` behaviour is not weaker. All KMP bans
respected. All mandatory rewrites (clock, enum, format, Metro, LTag) verified. Compile passes.

One minor issue found: two unused imports (`min`, `pow`) in `TrajectoryMetricsCalculator.kt`.
No blocking or safety issues.

---

APPROVE_WITH_CONCERNS
