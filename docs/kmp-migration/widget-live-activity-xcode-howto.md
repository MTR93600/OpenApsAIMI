# Live Activity and iOS widgets: setup

The project already contains everything: the widget extensions `AapsLiveActivity` (embedded in AAPS)
and `AapsLiveActivity2` (embedded in AAPS2), their files, their build settings, and the App Group.
**There is nothing to add by hand in Xcode.** Pick the AAPS scheme, your iPhone, and press Cmd+R.

Background: `widget-live-activity.md`, sections "Step 2 as built (W2)" and "Step 3 as built (W3)".
The Live Activity and the home and lock screen widgets live in the same extension.

## What is in the project

| File | Target membership |
|---|---|
| `ios/app/Shared/LiveActivity/AapsActivityAttributes.swift` | AAPS, AAPS2, AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/Shared/LiveActivityBridge.swift` | AAPS, AAPS2 (it imports `AapsShared`, which the extensions do not have) |
| `ios/app/LiveActivity/AapsLiveActivityBundle.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/AapsLiveActivityWidget.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/AapsHomeWidget.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/Info.plist` | none (`INFOPLIST_FILE` of both extensions) |
| `ios/app/LiveActivity/LiveActivity.entitlements` | none (`CODE_SIGN_ENTITLEMENTS` of both extensions, through `AAPS_WIDGET_ENTITLEMENTS`) |

Build settings of the extensions:

- Bundle id `$(AAPS_BUNDLE_ID).LiveActivity` and `$(AAPS_BUNDLE_ID)2.LiveActivity`. An extension id must
  start with the id of its app.
- Base configuration `Signing.xcconfig`, so the team, the bundle id and the version numbers are the
  same as the app's. iOS refuses an extension whose version differs from its app.
- The extensions do **not** link the Kotlin framework (`AapsShared`). An extension has a small memory
  limit; it only reads the state the app hands over.
- Each app has an "Embed Foundation Extensions" phase and a dependency on its extension.

## Signing

The App Group (`AAPS_APP_GROUP_ID = group.$(AAPS_BUNDLE_ID)` in `Signing.xcconfig`) is in
`Device.entitlements`, `Simulator.entitlements` and `LiveActivity/LiveActivity.entitlements`. One id
serves AAPS, AAPS2 and both extensions; the file name in the group holds the app's bundle id, so
AAPS2's widgets never show AAPS's data.

- **Paid Apple Developer account.** `Signing.local.xcconfig` needs your `DEVELOPMENT_TEAM`, and
  `AAPS_ENTITLEMENTS = Device.entitlements` (the default) or no `AAPS_ENTITLEMENTS` line at all.
  Automatic signing registers the extension ids and the App Group the first time you build for a
  device. If Xcode says it cannot create the profile, open the target AapsLiveActivity >
  Signing & Capabilities once: it offers to fix it. Do not tick "App Groups" there; the entitlements
  files already have it, and Xcode would write a second file.
- **Free Personal Team.** Apple gives no App Group to a free account (not tested here). Put both lines
  in `Signing.local.xcconfig`:

  ```
  AAPS_ENTITLEMENTS = Personal.entitlements
  AAPS_WIDGET_ENTITLEMENTS =
  ```

  The app and the Live Activity work. The home screen widgets show "---" with a warning sign, and the
  app logs "No App Group container" once (category `HomeWidget`). Each extension also uses one more
  app id of the 10 per 7 days a free account may register.
- **Simulator.** No team needed.

## Check on the iPhone

Live Activity:

1. Build and run AAPS (iOS 18.5 or later).
2. Config Builder > Sync > Live Activity > settings: switch on **Show Live Activity**.
3. The activity appears on the lock screen and in the Dynamic Island. If iOS has Live Activities off
   for AAPS (Settings > AAPS > Live Activities), nothing appears and the log says so (category
   `LiveActivity`).
4. Wait 9 minutes without a new reading (or stop the CGM): the glucose turns struck out and grey,
   without arrow and delta; IOB and COB turn grey and struck out too. The age keeps counting.
5. Switch it off: the activity ends at once.
6. Shortcuts app: the action **Restart Live Activity** exists for AAPS.
7. Apple Watch paired with the phone: the activity shows in the Smart Stack.

Home and lock screen widgets:

1. The log (category `HomeWidget`) must **not** say "No App Group container".
2. Long press the home screen > Edit > Add Widget > AAPS. Add the small, medium and large widget, and
   on the lock screen the round, the rectangular and the inline one.
3. They show the same glucose, arrow, delta, IOB and COB as the app. The medium and large ones also
   show mode, profile, target, sensitivity/ISF and temp basal; the large one the 3 h chart.
4. Stop the CGM (or wait 9 minutes): the value turns struck out and grey with a warning sign, even
   without opening the app.
5. Force quit AAPS: the widgets keep the last value and turn stale at the same 9 minute edge.
6. With AAPS2 installed too: its widgets show AAPS2's data only.

Known limits: iOS ends a Live Activity after 8 hours (open the app to start it again), and iOS may
limit how often home widgets refresh in the background; they then turn stale on time.
