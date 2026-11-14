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
