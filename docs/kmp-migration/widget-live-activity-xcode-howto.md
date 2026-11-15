# Live Activity: the steps in Xcode

The Swift files for the Live Activity are in the repository. `project.pbxproj` already compiles the
app side into AAPS and AAPS2 (`LiveActivityBridge.swift` and `AapsActivityAttributes.swift`), so
**the app builds and runs without these steps**; the Live Activity then simply never shows, because
there is no extension to draw it. The extension targets are added by hand, below.

Background: `widget-live-activity.md`, section "Step 2 as built (W2)".

## The files

| File | Target membership |
|---|---|
| `ios/app/Shared/LiveActivity/AapsActivityAttributes.swift` | AAPS, AAPS2, AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/Shared/LiveActivityBridge.swift` | AAPS, AAPS2 |
| `ios/app/LiveActivity/AapsLiveActivityBundle.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/AapsLiveActivityWidget.swift` | AapsLiveActivity, AapsLiveActivity2 |
| `ios/app/LiveActivity/Info.plist` | none (it is the `INFOPLIST_FILE` of both extensions, never a resource) |

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
   `ios/app/LiveActivity/AapsLiveActivityBundle.swift` and `AapsLiveActivityWidget.swift`. Uncheck
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
| Code Signing Entitlements | empty (the extension needs none; do not use `$(AAPS_ENTITLEMENTS)`) |
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
- Add the two Swift files of `ios/app/LiveActivity/` to AapsLiveActivity2 too (File Inspector >
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
  No capability is needed (no App Group, no push: updates are local).
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
