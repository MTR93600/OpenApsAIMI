---
name: aimi-control-center-move
description: AimiControlCenterScreen moved to plugins/aps commonMain byte-identical, with 5 mutation-proven Robolectric tests; why the other AIMI screens cannot follow yet
metadata:
  type: project
---

`AimiControlCenterScreen.kt` (1019 lines) is in
`plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/compose/` and compiles for
iosArm64. The moved file is **byte-identical** to the committed androidMain version - no import
rewrite, no `expect`/`actual`, no build file edit, nothing lifted behind an interface. The
`androidMain/.../openAPSAIMI/compose/` directory is now gone (it held only this file).

**Why it crossed untouched.** Its whole supporting cluster was already in commonMain
(`AimiControlCenterSnapshot`, `AimiControlCenterSupport`, `AimiControlCenterAdvisor`,
`AimiControlCenterRuntimeLoaders`, `AimiBehaviorFamilyRegistry`, `TpoUiSupport`, `AimiStorage`,
`PkpdSmbTailDamping`, `aimiWallClockMs`). Only the view layer was still Android, and it already used
`TextRef` + `ApsStrings` instead of `R.string`. Its sole caller, `OpenAPSAIMIPlugin` in androidMain,
needed no change.

**Why the other AIMI screens are not the same job.** `AimiMealAdvisorScreen`,
`AimiHealthConnectPermissionScreen` and `AimiSosPermissionScreen` are genuinely Android. The rest of
the AIMI screens in androidMain still reference `R.string` / `R.drawable` directly and need a
`TextRef`/`ApsStrings` conversion before any move is even a candidate - that conversion, not the
move, is the work.

**Coverage left behind.** 5 Robolectric tests in
`androidHostTest/.../openAPSAIMI/compose/AimiControlCenterScreenTest.kt`, each proved to fail when
the behaviour it names is broken. They drive only the public `AimiControlCenterScreen` entry point,
so they survived the move unedited. They stay in androidHostTest (Robolectric + Mockito + Android
`R`). See [[aps-screen-test-harness]] for how to build one.

Not covered, and said so rather than implied: the advisor empty/populated branch, the family
slider's `coerceIn(0, maxLevel)`, and the apply-confirm dialog's write-back. Those need a
`Preferences` whose `put` is readable, which `JsonBackedPreferences` is not for Int keys.

Related: [[aps-loop-move]], [[aps-screen-test-harness]], [[aps-androidhosttest-flaky-baseline]]
