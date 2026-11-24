# Memory Index — ui-to-common

## Module migration state

- [AIMI Control Center move](project_aimi-control-center-move.md) — 1019-line screen moved byte-identical; why the other AIMI screens need an R.string conversion first.
- [Hormonitor viewer move](project_hormonitor-viewer-move.md) — 312-line screen moved with 8 mutation-proven tests; the date-formatter lift that avoided an expect/actual, and the swap-then-move order.
- [Overview three variants covered](project_overview-three-variants-covered.md) — 10 mutation-proven tests pin what differs between the stacked/split/tablet layouts; what is still only rendered, and the unused `manageViewModel` parameter.

## Reference

- [Lessons from finished moves](reference_lessons-from-finished-moves.md) — duplicate string names belong to the module, Math operand order, prove the net twice, real resources in asserts, when to batch mutations.
- [What crosses to CMP unchanged](reference_cmp-what-crosses-unchanged.md) — extended icons, LocalUriHandler, rememberSaveable, Canvas all move with zero edits; grep for the real blockers instead.
- [A move breaks two test harnesses](reference_move-breaks-two-test-harnesses.md) — every commonMain screen move reds Robolectric string lookups and mocked-ResourceHelper tests; both fixes (only five owners resolve through `stubTextRefResolution`), and how to write a test that is immune.
- [metroViewModel is NOT a blocker](reference_metroviewmodel-is-not-a-blocker.md) — the expect/actual has working iOS and JVM actuals; only the old inline `LocalContext` cast is Android only.
- [No plural API in commonMain](reference_no-plural-api-in-commonmain.md) — `rh.gq` has no shared counterpart; lift the call behind a small interface as `DurationText` does, never flatten plurals into format strings.
- [:plugins:aps commonMain has no Vico](reference_aps-commonmain-has-no-vico.md) — :core:graph is androidMain-only there, so any charting file is pinned; proven by compile probe, invisible to an import grep.
- [:plugins:aps screen test harness](reference_aps-screen-test-harness.md) — the LocalPreferences-outside-the-screen trap, and JsonBackedPreferences as a free default-valued Preferences.
- [:plugins:aps host tests red at HEAD](reference_aps-androidhosttest-flaky-baseline.md) — six order-dependent classes, 92 failures at baseline; the briefs name only five.
- [:ui screen test fixture](reference_ui-screen-test-fixture.md) — the shared Robolectric host for commonMain screens; the three locals a screen needs, and the null-in-a-TextField and write-then-reread traps.
- [Three-stage probe move](reference_probe-move-three-stage.md) — how to size a blocked move honestly: raw file, raw cluster, then cluster + mechanical fixes; the last one is the only informative compile.
