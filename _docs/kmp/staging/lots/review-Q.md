# Lot Q — REVIEW

Reviewer: senior architect + senior Kotlin/KMP  
Branch: `kmp-aimi-migration-study`  
HEAD at review: `8563bdc01c` (Lot P) + 11 uncommitted working-tree files  
Date: 2026-08-28

---

## Summary

All 11 Copy-list files are present in dest. No Skip files were copied. No Lot O/P dest files were
overwritten. All required KMP rewrites (clock, format, Calendar, concurrent, `@Volatile`,
`roundToLong`, `@JvmStatic`) are applied correctly. The `CyclePhase` import is
`wcycle.CyclePhase`; `ContextIntent.CyclePhase` does not appear anywhere in the thermal files.
Compile log `/tmp/aimi-lot-Q.log` confirms BUILD SUCCESSFUL in 50 s on attempt 2 for both
`:plugins:aps:compileKotlinIosSimulatorArm64` and `:plugins:aps:compileAndroidMain`.
No critical issues. Two minor style items are noted below; neither blocks commit.

---

## Checklist

### Copy list — exact match

| rel | dest exists before lot? | copied? |
|---|---|---|
| `control/StraightLineTubeAdvisor.kt` | no (`control/` folder did not exist) | ✅ |
| `prediction/NaiveEventualBgSignGuard.kt` | no | ✅ |
| `AIMIAdaptiveBasal.kt` | no | ✅ |
| `physio/thermal/ThermalBaselineStore.kt` | no | ✅ |
| `physio/thermal/ThermalDataCache.kt` | no | ✅ |
| `physio/thermal/ThermalBeliefEngine.kt` | no | ✅ |
| `physio/AIMIDecisionOrchestratorShadowMTR.kt` | no | ✅ |
| `pkpd/TrajectoryPeakBias.kt` | no | ✅ |
| `pkpd/TrajectoryPeakMismatchScorer.kt` | no | ✅ |
| `pkpd/InsulinActionProfiler.kt` | no | ✅ |
| `pkpd/RealTimeInsulinObserver.kt` | no | ✅ |

No file from the Skip list was found in dest. No Lot O/P file was overwritten.

### KMP bans — not present in any of the 11 files

| banned symbol | result |
|---|---|
| `android.*` import | ✅ absent |
| `java.io.File` | ✅ absent |
| `org.json` | ✅ absent |
| Compose / Activity | ✅ absent |
| `System.currentTimeMillis()` | ✅ absent |
| `kotlin.jvm.Volatile` | ✅ absent (uses `kotlin.concurrent.Volatile`) |
| `javaClass` | ✅ absent |
| `String.format` | ✅ absent |
| `java.util.Locale` | ✅ absent |
| `java.util.Calendar` | ✅ absent |
| `CopyOnWriteArrayList` | ✅ absent |
| `AtomicReference` | ✅ absent |
| `kotlin.synchronized` | ✅ absent |
| `@IntKey(225)` | ✅ absent |
| `javax.inject` | ✅ absent |

Broad Grep across the 11 new files produced zero hits for every banned pattern.

### Rewrites

#### 1. Metro / DI
`StraightLineTubeAdvisor` and `AIMIAdaptiveBasal` carry `@SingleIn(AppScope::class)` and
`@Inject constructor`. The other 9 files have no `@Inject`. No Hilt. No `@IntKey(225)`. ✅

#### 2. LTag
`StraightLineTubeAdvisor` and `AIMIAdaptiveBasal` use `LTag.AIMI`. No log calls added to the
9 non-logging files. ✅

#### 3. Clock (`aimiWallClockMs`)
- `ThermalBeliefEngine.build` fallback `nowMs` (line 37):
  `val nowMs = window.fetchedAtMs.takeIf { it > 0L } ?: aimiWallClockMs()` ✅
- `InsulinActionProfiler.calculate` `now` (line 30):
  `val now = aimiWallClockMs()` ✅  
  Signature unchanged (no new `now` parameter). ✅  
  No `System.currentTimeMillis()` anywhere in the 11 files. ✅

#### 4. Format (`aimiFmt0` / `aimiFmt1` / `aimiFmt2`)
- `StraightLineTubeAdvisor`: `aimiFmt0`, `aimiFmt1`, `aimiFmt2` all used; imports present. ✅
- `AIMIAdaptiveBasal.pureSuggest`: `aimiFmt0`, `aimiFmt2`; `d0`/`d2` variables deleted. ✅
  `suggest` keeps injected `fmt.to0Decimal` / `fmt.to2Decimal`. ✅
- `ThermalBeliefEngine.buildNarrative`: `aimiFmt1(abs(deltaVsBaseline))`. ✅
- `RealTimeInsulinObserver.buildReason`: `aimiFmt2(corr)` / `aimiFmt2(residual)`. ✅
- No `aimiFmt3`. No `String.format`. No `java.util.Locale`. ✅

#### 5. `roundToLong` (`NaiveEventualBgSignGuard`)
Extension form used:
```
import kotlin.math.roundToLong
...
val rawRounded = (bgMgdl - (iobUnits * sensMgDlPerU)).roundToLong().toDouble()
...
val naive = if (shouldCollapse) bgMgdl.roundToLong().toDouble() else rawRounded
```
Both call sites are extension calls, not the free-function form that failed in attempt 1. ✅

#### 6. `@JvmStatic` (`AIMIAdaptiveBasal.pureSuggest`)
No `@JvmStatic` annotation. Function kept in `companion object`. ✅

#### 7. `@Volatile` (`ThermalDataCache`)
`import kotlin.concurrent.Volatile` (not `kotlin.jvm.Volatile`).  
`@Volatile private var window = ThermalDataWindowMTR()` — no `AtomicReference`. ✅

#### 8. Calendar / collections / atomics (`ThermalBaselineStore`)
- Local hour: `Instant.fromEpochMilliseconds(sample.timestampMs).toLocalDateTime(TimeZone.currentSystemDefault()).hour`
  — device local (matches dump `Calendar.getInstance(Locale.US)` default-zone semantics). ✅
- Imports: `kotlinx.datetime.Instant`, `kotlinx.datetime.TimeZone`, `kotlinx.datetime.toLocalDateTime`. ✅
- `nightlyMediansC`: `mutableListOf<Double>()` + one `AapsLock()` + `withLock` on every read/write. ✅
  Pattern matches `PhysioAggregator` / `AIMIPhysioBaselineModelMTR` from Lot P. ✅
- No `java.util.concurrent`, no `Calendar`. ✅

#### 9. `CyclePhase` import
`ThermalBeliefEngine` line 5: `import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase`. ✅  
No `ContextIntent` import in any thermal file (Grep confirmed 0 hits). ✅

#### 10. Explicit imports / no FQ names at use site
All files import their cross-package symbols explicitly. `AIMIDecisionOrchestratorShadowMTR`
uses same-package `PhysioMultipliersMTR` and `InflammationLatentStateMTR` without import
(same-package rule). `RealTimeInsulinObserver` uses same-package `ActivityStage` and
`InsulinActionState` without import. Both are correct. ✅

`NaiveEventualBgSignGuard` imports `InsulinActivityStage` from `pkpd` (cross-package). ✅

No FQ names at use site. ✅

#### 11. KDoc backticks
- `NaiveEventualBgSignGuard` line 19: `` `DetermineBasalAIMI2.round` `` (backtick code span). ✅
- `TrajectoryPeakBias` line 13: `` `docs/research/TAP_G_PEAK_GOVERNOR_RFC.md` `` (backtick code span). ✅
- Dest `TapPeakGovernor` / `CleanPostBolusWindow` / `InsulinWeibullCurve` KDoc not touched. ✅

#### 12. School English / French dump comments
New or changed comments are school English. French comments in `AIMIAdaptiveBasal`,
`RealTimeInsulinObserver`, `InsulinActionProfiler` are preserved per brief rule 11. ✅

#### 13. No new inter-module `project()` deps added. ✅

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

None.

---

## Suggestions 🟢

### S1 — Missing blank line between last import and KDoc in `StraightLineTubeAdvisor.kt`
`import dev.zacsweers.metro.AppScope` (line 12) is immediately followed by the `/**` KDoc on
line 13 with no blank separator. Kotlin convention and the rest of the codebase use one blank
line between the import block and the first declaration. Does not affect compile or runtime.

### S2 — `!!` on `maxOrNull()` / `minOrNull()` in `ThermalBeliefEngine.circadianDisruptionScore`
Lines 137:
```kotlin
val spread = deadbanded.maxOrNull()!! - deadbanded.minOrNull()!!
```
The `samples.size < 4` guard above makes both calls safe. The `!!` is technically correct but
a reviewer has to trace the guard to confirm it. A `?: 0.0` fallback or `requireNotNull` would
self-document the invariant. This is preserved therapy math per brief rule — do not change
without discussion.

### S3 — `suggest` vs `pureSuggest` soft-floor condition divergence (expected, informational)
`suggest` (instance method) checks `abs(input.delta) < 3.0` as a fallback in `softFloorActive`,
while `pureSuggest` (companion, no injected log) uses the simplified form without that branch.
This divergence is from the original dump. Per brief rule "Keep therapy math" — do not unify
unless the original was intentional.

---

## What Looks Good ✅

- Clean separation: Metro on exactly the two injected classes; the 9 pure-math objects/classes have no DI annotation.
- `ThermalBaselineStore` Calendar→kotlinx.datetime migration is faithful to the device-local semantics of the original dump.
- `ThermalDataCache` `@Volatile` idiom is minimal and correct for reference-replacement (no CAS needed here).
- `roundToLong` fix (attempt 1 vs 2) correctly identifies the Kotlin extension-not-function distinction.
- `NaiveEventualBgSignGuard` two-path collapse logic (peak-activity + post-hypo) is clearly commented and safely handles `null` lookback.
- `TrajectoryPeakMismatchScorer.rssAfterScaleFit` uses a `require` guard on list sizes — defensive and appropriate.
- `AIMIDecisionOrchestratorShadowMTR` is clearly a shadow-only class (KDoc, no enact path) and visibility matches the physio package convention.
- No `ContextIntent.CyclePhase` bleed — the two `CyclePhase` types remain separate.
- Compile confirmed BUILD SUCCESSFUL attempt 2 (50 s, both requested tasks, zero errors, only pre-existing Metro deprecation warnings on unrelated androidMain files).

---

## Compile

| attempt | log | result |
|---|---|---|
| 1 | `/tmp/aimi-lot-Q.log` | **BUILD FAILED** — `NaiveEventualBgSignGuard` used `roundToLong(x)` free-function form (not valid for Kotlin extension) |
| 2 | `/tmp/aimi-lot-Q.log` | **BUILD SUCCESSFUL in 50s** — `:plugins:aps:compileKotlinIosSimulatorArm64` + `:plugins:aps:compileAndroidMain` both compiled. Warnings are pre-existing Metro deprecations on `LoopPlugin` / `RunningModeExpiryScheduler` etc. — not introduced by this lot. |

A commonMain compile is not "AIMI runs on iOS". No plugin registration. No tick. No enact.

---

APPROVE_WITH_CONCERNS
