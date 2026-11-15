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
   - Local updates only (no APNs), `staleDate` = `WidgetState.staleAt` (BG time + 9 min, the same
     rule as the rest of the app; one threshold only), recreate after 60 min only while the app is
     active, end unknown activities, a restart App Shortcut.
   - `NSSupportsLiveActivities = true`. No App Group needed.
   - Reference: Trio `LiveActivity/` and `LiveActivityManager.swift` (MIT). Write our own; do not
     copy files.
3. **iOS home and lock screen widgets** (WidgetKit) with the same content as the Android widgets.
   These need an App Group (the widget reads the last state itself). Built, see "Step 3 as built (W3)".
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
- Decided in W2: the Live Activity `staleDate` is `staleAt` (9 min), not BG time + 6 min. One
  threshold everywhere, so the system stale mark and the grey colour can never disagree.
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

## Step 2 as built (W2)

Kotlin decides, Swift draws. No new module dependency.

- `core:interfaces` has `WidgetStateExport` (`app.aaps.core.interfaces.widget`): "build the widget
  state as JSON". `:ui` implements it for iOS only (`IosWidgetStateExport` in iosMain, so the Android
  graph does not change) with `WidgetStateBuilder` and `WidgetStateJson`. The plugin in
  `:plugins:sync` sees only the interface, so `:plugins:sync` still does not depend on `:ui`.
- `WidgetStateJson` (`:ui` commonMain) is the contract with the Swift struct
  `AapsActivityAttributes.ContentState`; the keys are listed in its KDoc. It sends raw numbers
  (`bgMgdl`, `bgTime`, `staleAt`, `deltaMgdl`, `iob`, `cob`, `carbsReqGrams`, `lastLoopTime`) and
  texts made from them with format string resources (`value_with_unit`, `format_insulin_units`,
  `format_carbs`, `widget_carbs_required`). It never sends `timeAgoText` or `cobText`. A number that
  is not a real number (NaN, infinity) goes as null with the "no value" text, because it is not valid
  JSON and Swift would drop the whole update; a NaN reading goes as no reading (stale). The age is drawn by SwiftUI from
  `bgTime` (`Text(date, style: .relative)`), so it keeps counting while the app is frozen.
- `LiveActivityPlugin` (`:plugins:sync` iosMain, `liveactivity/`, map key 380, enforced on) with the
  keys `LiveActivityEnabled` (off by default, the one switch), `LiveActivityShowChart`,
  `LiveActivityShowIobCob`, `LiveActivityShowLoop`. Not exported, not in the Android schema.
  Triggers: new GV and `EventAPSCalculationFinished`.
- `LiveActivityPublisher` has the rules: a new reading (or a reading that disappeared) goes out at
  once; anything else waits until 30 s after the last send and then goes out once, with a state
  built fresh at that time (so the loop result a few seconds after a reading is not lost); switching
  on, a show flag change and a refresh asked by Swift go out at once; switching off ends every
  activity. `LiveActivityThrottle` is the pure 30 s rule.
- `LiveActivityCompanion` + `installLiveActivityCompanion` are the bridge, the same pattern as
  Connect IQ. `ios/app/Shared/LiveActivityBridge.swift` implements it: one activity only, a leftover
  from an earlier process is adopted once and any other is ended, start only while the app is active
  (in the background the newest state is kept and the activity starts on `didBecomeActive`),
  recreate after 60 min only while active, `staleDate` = `staleAt`, `end()` ends all. On
  `didBecomeActive` and from the "Restart Live Activity" App Shortcut it asks Kotlin for a fresh
  state. A generation counter raised by `end()` stops a start that was waiting on `activity.end`,
  so a switch-off during a recreation is never undone.
- The extension (`ios/app/LiveActivity/`) draws the lock screen, the Dynamic Island (expanded,
  compact, minimal) and `.small` (Apple Watch Smart Stack). It judges the age itself:
  `context.isStale || state.isStale(at: now)`, the same edge as Kotlin `isStaleAt`. Stale means
  struck out, grey, no arrow, no delta; the age is still shown. Colours come from the range class.
- `BgGraphStateLoader` now strikes through from the one `lastBg()` read too, with a clock read taken
  next to it (as `isActualBg()` did), not the `now` from the start of the load.
- **Two changes from the plan above, to be confirmed by the owner:**
  1. "One update per 30 s" is not a pure drop: an update inside the 30 s waits and goes out once at
     the end of the window. A drop would lose the loop result that comes a few seconds after each
     reading, so IOB on the lock screen would stay 5 minutes behind. Cost: at most two updates per
     reading.
  2. "Recreate on `didBecomeActive`" recreates only when the activity is no longer running or is
     60 min old or more. Otherwise it keeps the activity and asks Kotlin for a fresh state, so the
     activity does not flash each time the app opens. Its content is still replaced at once.
- Tests: `WidgetStateJsonTest` (commonTest), `LiveActivityPublisherTest` and `LiveActivityThrottleTest`
  (iosTest, simulator), three `BgGraphStateLoader` cases in `WidgetRenderStateParityTest`.
- Swift-only English texts: the App Shortcut title and phrase. App Shortcuts are read by iOS at
  build time, so they cannot come from the Kotlin strings. Everything the activity shows comes from
  Kotlin.
- The Xcode steps are in `widget-live-activity-xcode-howto.md`. Until they are done, the app does
  not build: `AapsApp.swift` already names `SwiftLiveActivityCompanion`.

## Step 3 as built (W3)

iOS home and lock screen widgets (WidgetKit), the same content as the Android widgets, from the same
state. Kotlin decides, Swift saves, the extension draws. No new module dependency.

- `WidgetStateJson.encode(state, rh, withDetails = true)` adds the chips of the Android widget as
  names and texts: `targetText`, `targetChip`, `ttReason`, `targetIcon`, `profileText`,
  `profileModified`, `loopIcon`, `sensIcon`, `sensText`, `isfFromText`, `isfToText`, `isfStep`,
  `isfStepText`, `tbr`, `tbrText`, `iobActive`, `cobActive`. Without `withDetails` (the Live
  Activity) the JSON is exactly as in W2. `WidgetStateExport.export` got the same flag (default off).
  The base keys and the stale rule are shared, so the widget and the Live Activity judge the age alike.
- `HomeWidgetPlugin` (`:plugins:sync` iosMain, `homewidget/`, map key 381): always on, not in the
  plugin list, no settings, like the Android widgets (the user chooses by adding a widget). Triggers:
  new GV and `EventAPSCalculationFinished`. `HomeWidgetPublisher` uses the same 30 s rule as the Live
  Activity (`LiveActivityThrottle`): a new reading at once, anything else once at the end of the window,
  built fresh. On top of that a state with the same text as the last save is not saved again, because
  iOS gives an app only a small number of background widget reloads per day. For the same reason
  saving and reloading are separate: every state is written, but WidgetKit is asked to reload only on
  the first save and on a new reading, at most once in 3.5 min (`MIN_RELOAD_INTERVAL_MS`). A loop
  result alone is only written. 3.5 min plus a 5 min CGM interval stays below the 9 min stale edge.
  The file also carries the three show flags of `LiveActivityPayload` (all true), because the widget
  decodes it with `ContentState`, which requires them.
- `HomeWidgetCompanion` + `installHomeWidgetCompanion`: the bridge, the same pattern as Connect IQ.
  `SwiftHomeWidgetCompanion` (in `ios/app/Shared/LiveActivityBridge.swift`, already compiled by both
  apps) writes the JSON with `AapsWidgetStore` and, when Kotlin asks for it, calls
  `WidgetCenter.reloadTimelines`.
- `AapsWidgetStore` (in `AapsActivityAttributes.swift`, compiled by the apps and the extension) is the
  one place that knows the file: App Group id from the build setting `AAPS_APP_GROUP_ID` through the
  Info.plist key `AapsAppGroupId` (no hardcoded id), file `widget-state.<app bundle id>.json` so AAPS
  and AAPS2 never read each other's data even with one shared group, written atomically with file
  protection "until first unlock". No container (setting missing, entitlement not added yet) is a
  logged no-op, never a crash.
- The extension (`ios/app/LiveActivity/AapsHomeWidget.swift`, a second widget in the Live Activity
  bundle): small (glucose, delta, age, IOB, COB), medium (plus the chips: running mode, profile,
  target, IOB, COB, sensitivity/ISF, temp basal), large (plus the 3 h chart), and the lock screen
  circular, rectangular and inline families.
- Stale: the timeline has an entry now and a second one at `staleAt`, so the widget turns stale on time
  even when nobody reloads it, and asks WidgetKit to read the file again after 15 min. Each entry judges
  the age at its own date with `ContentState.isStale(at:)` (the Kotlin `isStaleAt` edge, 9 min). Stale
  means struck out, grey, a warning sign, no arrow, no delta; the age keeps counting
  (`Text(date, style: .relative)`). The sign and the strike also show on the lock screen, where iOS
  draws without colours. IOB, COB and every chip come from the same old file, so when stale they are
  struck out and grey too, and "carbs required" is hidden. No file, a file that cannot be read, or another JSON version shows "---"
  with the warning sign, never numbers.
- Swift-only English texts: the widget gallery name and description (read by iOS at install time,
  like the App Shortcut texts). Everything the widget shows comes from Kotlin.
- Tests: `WidgetStateJsonTest` (5 new: no detail keys without the flag, every detail key and value,
  nulls, base keys unchanged, size) and `HomeWidgetPublisherTest` (iosTest, 13: timing, the three
  flags Swift requires, and the reload rule).
- Known limit: in the background iOS refuses most widget reloads (a budget of a few dozen per day).
  Even with the reload rule a 5 min CGM asks for up to 288 a day. The
  widget then shows the last saved value and greys it out after 9 min, so it can look stale while the
  app has a fresh value. That is the safe side; the Live Activity is the fresh view.
- A free Personal Team gets no App Group (HOWTO section 8, step 3): the widgets then show "---".
- The Xcode steps (App Group entitlement for both apps and the extensions, target membership of
  `AapsHomeWidget.swift`) are in `widget-live-activity-xcode-howto.md`, sections "The files" and 8.

## By hand in Xcode

Add the Widget Extension target to both apps (AAPS and AAPS2), signing and profiles for each
extension id (a Personal Team has a small app id limit), target membership of the attributes file,
the embed phase, and the App Group for step 3 (section 8 of the HOWTO).

## Risks

- The app must run to update the activity. After a force quit or with Bluetooth off it freezes; it
  must then show as stale, never as a fresh BG.
- iOS ends an activity after 8 h; only the app in the foreground can start a new one.
- Keep the state small (about 4 KB). Throttle 1-min CGMs.
- No second keep-awake reason for App Store review.
