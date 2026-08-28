# Lot X — REVIEW

Reviewer: senior architect + senior Kotlin/KMP  
Branch: `kmp-aimi-migration-study`  
HEAD at review: `124b6a0fdf` (Lot W) + working tree Lot X  
Date: 2026-08-28

---

## Summary

Eight new files land under `physio/pattern/`. All are the correct files from the Copy list.
No Skip files were pulled in. No Lot W dest file was overwritten. All KMP bans pass.
The `ContextIntent` import is from the `context` package, not `model`. KDoc rules are mostly
correct (one minor inconsistency). Compile log (`/tmp/aimi-lot-X.log`) confirms:

```
> Task :plugins:aps:compileKotlinIosSimulatorArm64
> Task :plugins:aps:compileAndroidMain
BUILD SUCCESSFUL in 57s
```

The port is a faithful copy of the dump. Therapy math is unchanged. The two concerns below
are pre-existing dump design patterns carried over as-is; neither has a medical-safety impact.

---

## Checklist

| check | result |
|---|---|
| Exact 8 Copy files present | ✅ |
| No Skip files included | ✅ |
| No Lot W dest overwritten | ✅ |
| `android.*` | ✅ none |
| `File` | ✅ none |
| `org.json` | ✅ none |
| Compose imports | ✅ none |
| `System.currentTimeMillis` | ✅ none |
| `kotlin.jvm.Volatile` | ✅ none (no `@Volatile` at all) |
| `String.format` / `"%.nf".format` | ✅ replaced with `aimiFmt1` / `aimiFmt2` |
| `ContextIntent` from `context` package | ✅ `app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent` |
| `DetermineBasalAIMI2` in backticks | ✅ `PatternCapHold.kt` line 9 |
| `[docs/…]` KDoc links in backticks | ✅ `PhysiologicalPatternModels.kt` line 11 |
| `nowMs` parameter kept (no wall clock) | ✅ Hysteresis + Export |
| No `@IntKey(225)`, no Hilt, no Metro | ✅ |
| Explicit imports, no FQ names at use site | ✅ |
| JSON: `kotlinx.serialization` builders | ✅ |
| Compile BUILD SUCCESSFUL | ✅ both targets |

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

### 1. `HYPER_INSTALLED` can appear twice in `PhysiologicalPatternSnapshot.active`

**Files:** `PhysiologicalPatternDetector.kt` lines 31–38, 140–146

`matchFromPhase` maps `PhysiologicalPhase.HYPER_INSTALLED` → `PhysiologicalPatternId.HYPER_INSTALLED`
via `toPatternId()`. `matchInsulinTrajectory` independently adds the same id when
`phaseOutput?.phase == HYPER_INSTALLED`. Both fire on the same tick. Neither
`PhysiologicalPatternHysteresis.stabilize()` (normal path) nor
`PhysiologicalPatternPolicy.aggregate()` applies `distinctBy { it.id }`.

**Medical impact:** `HYPER_INSTALLED` has no `smbCapFraction`, no credibility scales, no
suppression flags, so the duplicate entry has zero effect on caps or credibility calculations.
`reasonSummary` may show the id twice in `active.take(4)`, which is cosmetic only.

**Origin:** This is a pre-existing dump design, faithfully copied per "therapy math unchanged".
It is not a regression introduced by this port.

**Suggested fix (not required for this lot):** Add `distinctBy { it.id }` before returning
from `detect()`, or suppress the redundant add in `matchInsulinTrajectory` when phase already
mapped it.

---

### 2. `PhysiologicalPatternHysteresis` is a mutable `object` (singleton)

**File:** `PhysiologicalPatternHysteresis.kt` lines 23–25

```kotlin
private var lastDominant: PhysiologicalPatternId? = null
private var lastDominantAtMs: Long = 0L
private var lastHeldReading: PhysiologicalPatternReading? = null
```

No synchronization or `@Volatile`. Safe only as long as the loop tick calling `stabilize()`
runs in a single coroutine. The brief says no `@Volatile` was needed and none was added —
that is correct for the current single-threaded loop design.

**Risk if the loop ever parallelizes:** two concurrent calls to `stabilize()` could corrupt
`lastDominant` / `lastDominantAtMs`. Not a current risk, but worth tracking.

**Origin:** Pre-existing dump design. Not introduced by this port.

---

## Suggestions 🟢

### S1. KDoc path style inconsistency in `PhysiologicalPatternId.kt`

`PhysiologicalPatternId.kt` line 5:
```
* See docs/AIMI_PHYSIOLOGICAL_PATTERN_CATALOG.md.
```

`PhysiologicalPatternModels.kt` line 11 (correctly rewritten):
```
* See `docs/AIMI_HARMONIA_SMB_ARBITRATION.md`.
```

The Id file uses a bare path (no brackets, no backticks). It is not a `[...]` KDoc link so it
does not trigger `KDocUnresolvedReference`, and technically does not violate the brief rule 7
which targets `[docs/…]` link syntax. However, the style is inconsistent with the Models file
in the same package. Wrapping in backticks would align both files.

### S2. `CONTEXT_ACTIVITY_INTENT` category in catalog

`PhysiologicalPatternCatalog.kt` line 194 assigns `CONTEXT_ACTIVITY_INTENT` to
`PhysiologicalPatternCategory.ACTIVITY`, while sibling context intents `CONTEXT_ILLNESS` and
`CONTEXT_STRESS_INTENT` are in `PhysiologicalPatternCategory.CONTEXT`. The category drives
which patterns `isMealPatternReading()` skips and how `PhysiologicalPatternId.category` is
reported. The behavior appears intentional (activity-intent suppression matches activity
patterns), but the naming is misleading. Pre-existing dump design.

### S3. `lastHeldReading!!` in `PhysiologicalPatternHysteresis.stabilize()`

Line 30: `return listOf(lastHeldReading!!)`. The null-check on line 29 makes this safe, but
`.let { return listOf(it) }` or an early return would avoid the non-null assertion. Minor.

---

## What Looks Good ✅

- All 8 Copy files are present, nothing extra, nothing missing.
- No Lot W files overwritten; `physio/pattern/` was empty before this lot.
- All KMP bans clean: no android, no File, no org.json, no Compose, no System.currentTimeMillis,
  no kotlin.jvm.Volatile, no String.format.
- `ContextIntent` / `ContextSnapshot` correctly imported from `context`, not `model`.
- `DetermineBasalAIMI2` in `PatternCapHold.kt` KDoc is in backticks. ✅
- `docs/AIMI_HARMONIA_SMB_ARBITRATION.md` in `PhysiologicalPatternModels.kt` KDoc is in backticks. ✅
- `"%.2f".format` → `aimiFmt2`, `"%.1f".format` → `aimiFmt1` in Detector and Policy. ✅
- Explicit imports everywhere; no FQ names at use sites.
- `nowMs` parameter kept in `Hysteresis` and `Export`; no wall clock call.
- `PatternDefinition.capU()` correctly validates `maxSmbHbU > 0 && isFinite()` before computing.
- `PatternCapHold` is a `class` (per-instance), not an object — correct design for a held state.
- `PhysiologicalPatternSnapshot.hardBindingCapU()` correctly handles null `smbCapKind` as HARD.
- `PhysiologicalPatternExport.kt` drops unused `JsonPrimitive` import per report-X.md note.
- JSON export is fully `kotlinx.serialization.json` builders; no `org.json`, no `R.string`.
- Compile BUILD SUCCESSFUL on both `:compileKotlinIosSimulatorArm64` and `:compileAndroidMain`.
- Compose-graph wall disclaimer correctly re-stated in report-X.md.

---

## Verdict

APPROVE_WITH_CONCERNS
