---
name: hormonitor-viewer-move
description: HormonitorViewerScreen is in :plugins:aps commonMain with 8 mutation-proven tests; the date-formatter lift pattern that avoided an expect/actual, and why the other two advisor screens stayed
metadata:
  type: project
---

`HormonitorViewerScreen.kt` (312 lines) now lives in
`plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/hormonitor/viewer/`. Its whole
supporting cluster (`HormonitorReader`, `HormonitorViewerModels`, `HormonitorViewerText`,
`HormonitorLabels`) was already in commonMain, so only the view layer moved.

**The one platform line, and how it was lifted.** The file had
`private val viewerDateFormatter: AimiDateFormatter = AndroidDateFormatter()` at file scope.
`AimiDateFormatter` was already the commonMain interface, so no new port was needed - the screen now
takes `dateFormatter: AimiDateFormatter` as a parameter and `OpenAPSAIMIPlugin` passes
`AndroidDateFormatter()`. **No `expect`/`actual` was added on purpose**: an iOS target that forgets
to supply a formatter fails to compile, where a defaulted `actual` would have rendered wrong dates
silently. The two file-private wrappers `shortDay`/`timeSpan` were deleted; the screen calls the
shared `shortDayText`/`timeSpanText` directly.

**The string swap was a lookup swap only.** 40 `R.string.aimi_hormonitor_*` became
`ApsStrings.aimi_hormonitor_*` (the generator already emitted all 41 names), plus
`app.aaps.core.ui.R.string.back` to `CoreUiStrings.back`. Anchor the substitution on
`stringResource(R.string.aimi_hormonitor_` - a bare `R.string.` also matches the `core:ui` one.

**Order that made the lift honest.** Tests written and mutation-proved first, then the lift and the
string swap **in place in androidMain** and the tests re-run green (that is what proves the lift
changed nothing), and only then `git mv`. Every assertion survived unedited; the only test edit was
one argument at the single construction site.

**Coverage left behind.** 8 Robolectric tests in
`androidHostTest/.../hormonitor/viewer/HormonitorViewerScreenTest.kt`, each proved to red on its own
mutation. They stay in androidHostTest (Robolectric + Mockito + Android `R`). Fixture trap: the
day-summary stale flag is read from the JSON key `source_stale_flag`, not `source_stale`.

Not covered, and said so rather than implied: the null branch of `StatRow` (its "—" would collide
with the eight other empty stat rows, so no unique assertion exists), the six-item cap in
`DistributionBlock`, and the `TirBar` all-zero early return.

Related: [[aps-commonmain-has-no-vico]], [[aps-screen-test-harness]], [[aimi-control-center-move]]
