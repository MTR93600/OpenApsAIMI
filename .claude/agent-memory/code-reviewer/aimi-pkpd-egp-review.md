# AIMI PKPD / EGP endogenous-reversion review notes (2026-07-22)

## Where the physics live
- `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/AdvancedPredictionEngine.kt`
  — single source of truth for IOB/COB/UAM/ZT/hybrid curve math incl. the EGP
  (endogenous-reversion) drift-toward-baseline term. `predict()` is the legacy hybrid-only
  wrapper around `predictCurves()`; both share the exact same physics, so a change here never
  needs a parallel edit elsewhere in this file.
- `AdvancedPredictionCurves.kt` — plain data class of the resulting series + a few derived
  `Terminal` getters (`hybridTerminal`, `uamTerminal`, `cobTerminal` = `series.lastOrNull()`).
  New fields should be appended last with a default — 10+ call sites across main+test use
  named/positional-prefix args only, so this is safe to do repeatedly.
- `PkpdSoftFloorPathMin.kt` — telemetry/JSONL packaging AND the actual value
  (`softPathMinMgdl`) that gets spliced back into the published int prediction series via
  `applySoftFloorToPredSeries` → `liftFloorBandPoints` in `DetermineBasalAIMI2.kt`, which feeds
  `rT.predBGs` → `minPredictedAcrossCurves` → hypo protection/LGS. **This is the real
  dosing-relevant path**, not just a debug log — always check it, not only the engine curves.

## Known fragility (open, not yet fixed as of 2026-07-22)
- `PkpdSoftFloorPathMin.kt` has its OWN local `ENDO_REVERSION_BASELINE_MGDL = 80.0` constant
  (comment: "must match AdvancedPredictionEngine's"), used in `resolveSoftPathMin` as
  `min(hybridTerminal, 80.0)`. When the engine's baseline logic changes (e.g. the 2026-07-22
  Guard A: baseline capped at `min(80, max(currentBG, floor))` instead of a flat 80), this local
  constant is NOT updated to match and becomes a silent, unenforced invariant — it only stays
  harmless because `hybridTerminal` (fed in from the engine) already respects the new dynamic
  cap. There is no test asserting `PkpdSoftFloorPathMin.fromCurves(...).softPathMinMgdl` stays
  bounded correctly for the engine's own edge-case scenarios (e.g. low-plateau BG) — only the raw
  engine curves (`curves.iob.last()` etc.) are tested. **When reviewing future EGP/soft-floor
  changes, always trace whether an engine-level baseline/cap change needs a matching update in
  `PkpdSoftFloorPathMin`'s own constants/logic, and ask for an end-to-end test through
  `fromCurves()`, not just engine-level curve assertions.**

## Guard-style safety patterns seen here (good reference for future PKPD guards)
- "Cap anchor at `min(fixedCeiling, max(currentValue, floor))`" is a good, algebraically provably
  safe pattern for "never predict above/below the measured value while still correcting a known
  numeric-floor artefact" — verified this always resolves to `≤ currentBG` (with equality at
  degenerate cases), and is a no-op once curve values are already clamped to the numeric floor
  band (so the "baseline > currentBG when currentBG < floor" edge case is inert, not a bug).
- "Suspend outright when `delta <= threshold`" (Guard B style) should mirror an existing constant
  elsewhere (`ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL = -3.0`) via a KDoc cross-reference
  rather than duplicating the magic number blind — confirmed this resolves fine as a
  fully-qualified KDoc link across packages within the same module (`plugins:aps`), no backticks
  needed since the const is `public` in an `object`.
- Note: such delta-based guards use `delta <= threshold` (false for NaN) — NaN deltas silently
  fail to trigger the "suspend" (conservative) branch. This mirrors pre-existing lack of NaN
  guards on `currentBG`/`delta` throughout `AdvancedPredictionEngine`, so it's not a new
  regression, but worth flagging as a suggestion each time (fail-closed on NaN would be more
  conservative for a hypo-protection guard).

## Test conventions in this package
- `plugins/aps/src/test/kotlin/.../pkpd/PkpdSoftFloorPathMinTest.kt` and
  `AdvancedPredictionEngineTest.kt` use JUnit5 (`org.junit.jupiter.api.Test`) + MockK + Truth
  (`assertThat`). Some legacy tests are `@Disabled("Dormant JUnit4 test: ... needs triage")` —
  pre-existing, not caused by recent changes; don't flag these as new failures.
- Command that works for targeted PKPD test runs:
  `./gradlew :plugins:aps:testFullDebugUnitTest --no-daemon --tests "app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorPathMinTest" --tests "app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionEngineTest" > log 2>&1`
  then check XML in `plugins/aps/build/test-results/testFullDebugUnitTest/TEST-*.xml` for
  `tests="N" failures="0" errors="0"` (redirect, don't pipe — see CLAUDE.md gradle exit-code
  caveat).
