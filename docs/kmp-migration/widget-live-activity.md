# One widget state for Android widgets, the iOS Live Activity and iOS widgets

Agreed with the owner on 2026-11-14. Start after the Dash on iOS work (steps S1 to S4) is
committed, so that two gradle builds never run at the same time.

## Why

Android already has home screen widgets in `:ui` (`ui/src/androidMain/.../widget/`): the full
widget, the compact one, the glucose circle, the graph and the small widget. Their content comes
from `WidgetStateLoader`, which builds `WidgetRenderState`. That loader already holds everything a
Live Activity shows, and more: BG, arrow, delta, age (struck out when old), IOB, COB, carbs required,
temp target, profile, running mode, sensitivity, ISF, temp basal and the spoken text.

The rule is almost platform free. Only colours (`@ColorInt`) and icons (`@DrawableRes`) are Android,
and those do not belong in a domain model anyway. So we move the rule, not write a second one.

## Steps

1. **Common widget state.** Move the logic of `WidgetStateLoader` to commonMain as a neutral state:
   a colour class (low, in range, high, stale, and the chip states) and an icon kind instead of
   Android resources. Android maps them back to the same colours and drawables. No visible change
   on Android; tests prove the Android `WidgetRenderState` is the same as before.
2. **iOS Live Activity** (lock screen, Dynamic Island, Apple Watch Smart Stack), fed by that state.
   - Kotlin: an iOS-only plugin next to Garmin (`plugins/sync/src/iosMain/.../liveactivity/`), with
     an on/off key (default off) and keys for the chart, IOB/COB and loop rows. Triggers: new GV and
     `EventAPSCalculationFinished`, as the iOS Garmin plugin. At most one update every 30 s, always
     on a new BG.
   - Swift: a bridge like `ConnectIqBridge` (Kotlin decides, Swift moves JSON), an
     `ActivityAttributes` struct shared by the app and the extension, and a Widget Extension target.
   - Local updates only (no APNs), `staleDate` = BG time + 6 min (+3 min for a 1-min CGM), recreate
     after 60 min only while the app is active, end unknown activities, a restart App Shortcut.
   - `NSSupportsLiveActivities = true`. No App Group needed.
   - Reference: Trio `LiveActivity/` and `LiveActivityManager.swift` (MIT). Write our own; do not
     copy files.
3. **iOS home and lock screen widgets** (WidgetKit) with the same content as the Android widgets.
   These need an App Group (the widget reads the last state itself).
4. **Both sides get the improvements**: a small chart, clear stale rules.
5. **Option: Android 16 Live Updates** (promoted ongoing notification on the lock screen), fed by
   the same state.

## Step 1 as built (W1)

The rule now lives in `:ui` commonMain, package `app.aaps.ui.widget.state`. No new module
dependency was needed: `:ui` commonMain already had every input.

- `WidgetState` is the neutral state. Texts are finished and translated. Colours are classes
  (`WidgetBgRange`, `WidgetBgColorClass`, `WidgetTargetChip` with the temp target reason, the
  `RM.Mode`), icons are kinds (`WidgetTargetIcon`, `WidgetSensitivityIcon`, `WidgetTbrState`,
  `WidgetLoopIcon`, `IsfStep`, `TrendArrow`). For the Live Activity it also carries raw numbers:
  BG in mg/dL, units, BG time, delta in mg/dL, IOB, COB, carbs required, last loop time (null on a
  client) and up to 36 chart points over 3 h (only when asked, `build(withChart = true)`).
- `WidgetStateRules` holds the decisions as plain functions. `WidgetStateBuilder` (`@Inject`)
  gathers the inputs and calls them. It is the old `WidgetStateLoader` body with two small
  differences, both only when something changes during one build: the age comes from the one
  `lastBg()` read that is shown (before, `isActualBg()` read again and a new reading landing in
  between could leave an old value not struck through), and the profile is read once, not three
  times.
- Android: `WidgetStateLoader` now adds only the widget background and calls
  `WidgetState.toRenderState()` (`WidgetRenderStateMapper.kt`), which maps the classes back to the
  same `@ColorInt` and `@DrawableRes` values. `WidgetRenderState` and the Glance widgets did not
  change. `BgGraphStateLoader` uses the same BG range rule.
- Stale rule: one shared rule, `WidgetStateRules.isStale(bgTimestamp, now)`, built on
  `Constants.OLD_BG_MINUTES` (9 min, the same edge as `LastBgData.isActualBg()`). `isStale` is that
  rule at build time only. A reader that shows the state later must ask again with the time of
  showing: `isStaleAt(now)`, `bgColorClass(now)` (`STALE` for an old reading or none, whatever the
  range) and `staleAt` (BG time + 9 min). Android keeps its old look on purpose (range colour,
  struck through). iOS paints `STALE` and must not write its own copy of the 9 minutes; the bridge
  sends `staleAt` with the state.
- Open for the owner: the plan above sets the Live Activity `staleDate` to BG time + 6 min. That is
  a third threshold next to the 9 min colour rule. Either use `staleAt` for `staleDate` so both
  agree, or keep 6 min on purpose (an earlier system mark is not less safe) and write down why.
- Frozen texts: `timeAgoText` and `cobText` are made at build time. The Live Activity shows the age
  from `bgTimestamp` (SwiftUI `Text(date, style: .relative)`) and the carbs from `cob`,
  `carbsRequired` and `carbsRequiredGrams`, never these texts. The carbs line of `cobText` is still
  joined with `"\n" +` as before; move it to a format string resource when the Android look may
  change (the parity test would see the new key).
- Chart points are real readings only: values made up to fill a gap, and values that are not
  finite or not above 0, are left out.
- Tests: `WidgetStateRulesTest` (commonTest, runs on JVM, Android host and the iOS simulator) and
  `WidgetRenderStateParityTest` (Android host). The parity test runs a frozen copy of the old
  loader (`LegacyWidgetStateLoader`, test only) next to the new one for many inputs (both units,
  stale, no reading, no profile, every running mode, every temp target reason, every arrow, every
  sensitivity icon, client builds) and asks for an equal `WidgetRenderState`. When the widget look
  is changed on purpose later, delete the frozen copy and the parity test together.

Not done in W1: nothing on iOS uses the state yet. The iOS graph does not have to provide the
builder's inputs until step 2 asks for it.

## By hand in Xcode

Add the Widget Extension target to both apps (AAPS and AAPS2), signing and profiles for each
extension id (a Personal Team has a small app id limit), target membership of the attributes file,
the embed phase, and later the App Group for step 3.

## Risks

- The app must run to update the activity. After a force quit or with Bluetooth off it freezes; it
  must then show as stale, never as a fresh BG.
- iOS ends an activity after 8 h; only the app in the foreground can start a new one.
- Keep the state small (about 4 KB). Throttle 1-min CGMs.
- No second keep-awake reason for App Store review.
