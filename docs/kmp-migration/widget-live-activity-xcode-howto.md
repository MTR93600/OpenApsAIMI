# Live Activity and iOS widgets: the steps in Xcode

The Swift files for the Live Activity are in the repository. `project.pbxproj` already compiles the
app side into AAPS and AAPS2 (`LiveActivityBridge.swift` and `AapsActivityAttributes.swift`), so
**the app builds and runs without these steps**; the Live Activity then simply never shows, because
there is no extension to draw it. The extension targets are added by hand, below.

Background: `widget-live-activity.md`, sections "Step 2 as built (W2)" and "Step 3 as built (W3)".
The home and lock screen widgets (W3) live in the **same** extension as the Live Activity, as a second
widget of the bundle. Their extra steps are in section 8 (App Group) and in the file table.

## The files

| File | Target membership |
|---|---|
| `ios/app/Shared/LiveActivity/AapsActivityAttributes.swift` | AAPS, AAPS2, AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/Shared/LiveActivityBridge.swift` | AAPS, AAPS2 |
| `ios/app/LiveActivity/AapsLiveActivityBundle.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/AapsLiveActivityWidget.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/AapsHomeWidget.swift` (W3) | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/Info.plist` | none (it is the `INFOPLIST_FILE` of both extensions, never a resource) |
| `ios/app/LiveActivity/LiveActivity.entitlements` (W3) | none (it is the `CODE_SIGN_ENTITLEMENTS` of both extensions, never a resource) |

The app side of W3 (`SwiftHomeWidgetCompanion` in `LiveActivityBridge.swift`, `AapsWidgetStore` in
`AapsActivityAttributes.swift`, the install line in `AapsApp.swift`, the `AapsAppGroupId` key in
`Shared/Info.plist`) is in files the app targets already compile, so the app builds without any
step here. Without the App Group the app logs once ("No App Group container", category
`HomeWidget`) and writes nothing.

`ios/app/Shared/Info.plist` already has `NSSupportsLiveActivities = true`, and `AapsApp.swift`
already has the install call. Both need nothing more.

## 1. Add the extension for AAPS

1. Open `ios/app/AAPS.xcodeproj`.
2. File > New > Target… > iOS > **Widget Extension**. Next.
3. Product Name: `AapsLiveActivity`. Team: yours. Uncheck **Include Configuration App Intent**.
   Check **Include Live Activity** (or not, the generated files are deleted below). Project: AAPS.
   **Embed in Application: AAPS**. Finish. If Xcode asks to activate the scheme, say no (keep the
   AAPS scheme).
4. Xcode created a folder `AapsLiveActivity` with template Swift files, an `Assets.xcassets` and an
   `Info.plist`. Delete that folder: select it, Delete, **Move to Trash**.
5. Add our files: right click the project > Add Files to "AAPS"… > select
   `ios/app/LiveActivity/AapsLiveActivityBundle.swift`, `AapsLiveActivityWidget.swift` and
   `AapsHomeWidget.swift`. Uncheck
   "Copy items if needed". Targets: only **AapsLiveActivity** (and AapsLiveActivity2 later).
   Add `ios/app/LiveActivity/Info.plist` the same way with **no** target checked.

## 2. Build settings of AapsLiveActivity

Select the target AapsLiveActivity > Build Settings > All, both configurations:

| Setting | Value |
|---|---|
| Info.plist File (`INFOPLIST_FILE`) | `LiveActivity/Info.plist` |
| Generate Info.plist File | Yes |
| Product Bundle Identifier | `$(AAPS_BUNDLE_ID).LiveActivity` |
| iOS Deployment Target | 18.5 (the same as the app; `.supplementalActivityFamilies` needs iOS 18) |
| Swift Language Version | Swift 5 (the same as the app) |
| Targeted Device Family | iPhone |
| Code Signing Style | Automatic |
| Code Signing Entitlements | `LiveActivity/LiveActivity.entitlements` (only the App Group; do not use `$(AAPS_ENTITLEMENTS)`). Leave it empty until section 8 is done if you want the Live Activity first. **A free Personal Team leaves it empty** (see section 8, step 3). |
| Bundle Display Name (`INFOPLIST_KEY_CFBundleDisplayName`) | `AAPS` |

Then Project AAPS > Info > Configurations: set **Signing** (`Signing.xcconfig`) as the base
configuration of AapsLiveActivity for Debug and Release. That gives the extension your
`DEVELOPMENT_TEAM` from `Signing.local.xcconfig`, `AAPS_BUNDLE_ID`, and the version numbers from
`Version.xcconfig`. The extension must have the same `MARKETING_VERSION` and
`CURRENT_PROJECT_VERSION` as the app, or the install is refused or warns. If Xcode wrote its own
values for these two into the target, delete them there so the xcconfig wins.

Do **not** copy the app's `FRAMEWORK_SEARCH_PATHS`, `OTHER_LDFLAGS` or the gradle build phase. The
extension does not use the Kotlin framework (`AapsShared`) and must not link it: an extension has a
small memory limit, and the Kotlin code would not even run there.

## 3. Repeat for AAPS2

The same as steps 1 and 2 with:

- Product Name `AapsLiveActivity2`, **Embed in Application: AAPS2**.
- Product Bundle Identifier `$(AAPS_BUNDLE_ID)2.LiveActivity` (an extension id must start with the
  id of its app, and AAPS2 is `$(AAPS_BUNDLE_ID)2`).
- Add the three Swift files of `ios/app/LiveActivity/` to AapsLiveActivity2 too (File Inspector >
  Target Membership).

## 4. Membership of the shared files

- `ios/app/Shared/LiveActivity/AapsActivityAttributes.swift`: already in AAPS and AAPS2. Select it
  > File Inspector > Target Membership, and also check AapsLiveActivity and AapsLiveActivity2.
- `ios/app/Shared/LiveActivityBridge.swift`: already in AAPS and AAPS2, and only there. It imports
  `AapsShared`, which the extensions do not have.

## 5. Embedding

Select AAPS > Build Phases > **Embed Foundation Extensions** (Xcode created it in step 1). It must
contain `AapsLiveActivity.appex` and nothing else. AAPS2's must contain only
`AapsLiveActivity2.appex`. Each app target also lists its extension under Target Dependencies.

## 6. Signing

- A paid team: Automatic signing creates the two app ids `$(AAPS_BUNDLE_ID).LiveActivity` and `$(AAPS_BUNDLE_ID)2.LiveActivity`.
  No push capability is needed (updates are local). The Live Activity needs no App Group; the home
  widgets do (section 8).
- A free Personal Team: each extension is one more app id against the small weekly limit (10 app
  ids per 7 days). With your own `AAPS_BUNDLE_ID` in `Signing.local.xcconfig` the extension ids
  follow it.
- The simulator needs no team.

## 7. Check

1. Build and run AAPS on a device or the simulator (iOS 18.5 or later).
2. In AAPS: Config Builder > Sync > Live Activity > settings, switch on **Show Live Activity**.
3. The activity appears on the lock screen and in the Dynamic Island while the app is in the
   foreground. If iOS has Live Activities off for AAPS (Settings > AAPS > Live Activities), nothing
   appears and the log says so (category `LiveActivity`).
4. Wait 9 minutes without a new reading (or stop the CGM): the value must turn struck out and grey,
   without an arrow and without a delta. The age keeps counting.
5. Switch it off: the activity ends at once.
6. Shortcuts app: the action **Restart Live Activity** exists for AAPS.
7. On an Apple Watch paired with the phone: the activity shows in the Smart Stack in the small layout.

## 8. App Group for the home and lock screen widgets (W3)

The widgets are a separate process. They read the last state from a file that the app writes into an
App Group container. One group id serves AAPS, AAPS2 and both extensions: the build setting
`AAPS_APP_GROUP_ID` in `Signing.xcconfig`, `group.$(AAPS_BUNDLE_ID)` (so a Personal Team with its own
`AAPS_BUNDLE_ID` gets its own group). The file name holds the bundle id of the app
(`widget-state.<app id>.json`), so the widget of AAPS2 never shows the data of AAPS. Nothing is
hardcoded in Swift: both Info.plist files read the id from the build setting (`AapsAppGroupId`).

1. Add the App Group entitlement to the **app** entitlements files. They are shared by AAPS and
   AAPS2, so this is done once per file, not per target. Add to `Device.entitlements` and
   `Simulator.entitlements`, **not** to `Personal.entitlements` (see step 3):

   ```xml
   <key>com.apple.security.application-groups</key>
   <array>
       <string>$(AAPS_APP_GROUP_ID)</string>
   </array>
   ```

   Do not tick "App Groups" in the Signing & Capabilities tab as well: Xcode would write a second
   entitlements file and point the target at it (the same warning as for HealthKit in
   `Device.entitlements`). With Automatic signing, Xcode registers the group and adds it to the app ids
   when it signs. If it does not (an older Xcode), open Signing & Capabilities, where it then offers
   to fix the profile, or register `group.<your AAPS_BUNDLE_ID>` in the developer portal by hand.
2. The **extensions**: set Code Signing Entitlements of AapsLiveActivity and AapsLiveActivity2 to
   `LiveActivity/LiveActivity.entitlements` (section 2). It holds only the same App Group.
3. A free Personal Team (`AAPS_ENTITLEMENTS = Personal.entitlements`): skip steps 1 and 2. As far as
   we know, Apple does not offer App Groups to a free account (not tested here), and an App Group
   entitlement would then make the app or the extension fail to sign. So keep the App Group out of
   `Personal.entitlements`, and leave Code Signing Entitlements of both extensions **empty**. The app
   and the Live Activity still work; the home and lock screen widgets show "---" with a warning sign,
   and the app logs "No App Group container" once. If you later move to a paid team, do steps 1 and
   2 then.
4. Check:
   1. Build and run AAPS. The log (category `HomeWidget`) must **not** say "No App Group container".
   2. Long press the home screen > Edit > Add Widget > AAPS. Add the small, medium and large widget,
      and on the lock screen the round, the rectangular and the inline one.
   3. They show the same glucose, arrow, delta, IOB and COB as the app. The medium and large ones also
      show the running mode, profile, target, sensitivity/ISF and temp basal chips, the large one the
      3 h chart.
   4. Stop the CGM (or wait 9 minutes without a reading): the value turns struck out and grey, with a
      warning sign, no arrow and no delta, even without opening the app. The age keeps counting.
   5. Force quit AAPS: the widgets keep the last value and turn stale at the same 9 minute edge.
   6. With AAPS2 installed too: its widgets show AAPS2's data only.

