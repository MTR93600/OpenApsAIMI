# Garmin on iPhone

Goal: the Garmin watch face, data field and widget that work with AAPS on Android work the same way
when the watch is connected to an iPhone. The watch apps are not changed.

## How the watch talks to AAPS on Android

Two channels, both in `:plugins:sync` `androidMain/.../garmin`:

1. **The watch asks, the phone answers (main channel).** The watch app calls
   `http://127.0.0.1:<port>/get` (and `/carbs`, `/bolus`, `/temptarget`, `/mode`, `/connect`,
   `/sgv.json`) with `makeWebRequest`. Garmin Connect on the phone passes the request to the phone
   itself, where `HttpServer` answers. Heart rate and steps come in on the same requests.
2. **The phone pushes (only at connect and on a key change).** `GarminMessenger` sends a `glucose`
   message through the ConnectIQ Android SDK to the app ids in `GarminPlugin.glucoseAppIds`. It
   carries the AAPS key, so the watch app knows which key to send back.

## What iOS allows

- Garmin Connect on iPhone passes `127.0.0.1` requests to the phone too: Spike, an iPhone CGM app,
  fed Garmin watch faces from a local HTTP server (port 1979). Garmin Connect must be running, at
  least in the background.
- A request only gets an answer while AAPS is running. iOS suspends an app in the background; AAPS
  is woken by the CGM about every 5 min (held connect, see `OemDeviceProfile.holdsPendingConnect`),
  so a pull that falls between two wakes fails.
- The push channel exists on iPhone: Garmin publishes the ConnectIQ companion SDK as a Swift
  package, `github.com/garmin/connectiq-companion-app-sdk-ios` (Trio uses 1.8.0, see its
  `GarminManager.swift`). Device selection goes through a URL scheme round trip with Garmin Connect.

So: the pull channel first (same watch apps, no change on the watch), then the push on every new
glucose value, sent while the CGM has AAPS awake, so the watch stays up to date when the pull fails.

## Phases

### 1. Shared code (no change of behavior on Android)

- [x] `DeltaVarEncodedList` to `commonMain` (the glucose encoding the watch reads). Same text as
      before: `DeltaVarEncodedListCommonTest` checks the value `GarminPluginTest` checks on Android.
- [x] `LoopHub` interface and `LoopHubImpl` to `commonMain`, with `kotlin.time.Instant` and a
      `nowMs` clock instead of `java.time`. The unused RxJava `CompositeDisposable` was removed.
- [x] The request rules of `GarminPlugin` (`/get`, `/carbs`, `/bolus`, `/temptarget`, `/mode`,
      `/connect`, `/sgv.json`, the bolus repeat block, heart rate and steps) are in `GarminRequests`
      (`commonMain`), with JSON from kotlinx.serialization instead of Gson. `GarminPlugin` on Android
      keeps the HTTP server, the ConnectIQ messenger and the key check, and calls it. The 47
      `GarminPluginTest` tests compare the exact JSON and pass unchanged (only the two tests of the
      glucose wait were rewritten). `GarminRequestsCommonTest` runs on the iOS simulator;
      `GarminOneDecimalTest` checks the TBR text against `String.format("%.1f")` for every rate.
- [x] The `glucose` push message: `GarminRequests.getGlucoseMessage`.
- [x] Watch faces that send only the day total (`steps=`): the steps since the last total are
      spread over the real time between the two totals, one row per 5 minutes, and the newest row
      gets the 10 to 180 minute windows. Before, the whole gap went into `steps5min` of one row with
      the other windows at 0, so the loop saw a burst of walking after every gap and never saw a
      long walk. On the iPhone the gaps are longer (suspension), so it mattered more there.

### 2. iPhone, pull channel

- [x] A small HTTP server on `127.0.0.1` in `iosMain` (POSIX sockets), same port preference:
      `IosGarminHttpServer`. The request text and the answer text are `GarminHttp` (`commonMain`),
      written after the Android `HttpServer`. `IosGarminHttpServerTest` runs it on a real socket on
      the simulator.
- [x] An iOS Garmin plugin in the iOS graph, with the same preferences, answering through the
      shared rules: `GarminPlugin` in `iosMain`. Same class name as on Android on purpose, so the
      imported `ConfigBuilder_Enabled_SYNC_GarminPlugin` turns it on. The key check and the path table
      are shared (`GarminRequests.keyAccepted`, `GarminRequests.answer`). A wrong key is answered
      401, and, when Connect IQ push is on, the phone also pushes the current key the way Android
      does (phase 3).
- [ ] Check on the device with the watch: `/get` answered while AAPS is in front, then in the
      background.

### 3. iPhone, push channel

- [x] Connect IQ iOS SDK 1.8.0 (`github.com/garmin/connectiq-companion-app-sdk-ios`) on both app
      targets. `SwiftConnectIqCompanion` calls
      `ConnectIQ.sharedInstance().initialize(withUrlScheme:uiOverrideDelegate:)`. Info.plist has
      `CFBundleURLTypes` (`aaps-ciq` / `aaps2-ciq`, build setting `AAPS_CIQ_URL_SCHEME`) and
      `LSApplicationQueriesSchemes` = `gcm-ciq`. SwiftUI `onOpenURL` passes the callback to
      `parseDeviceSelectionResponseFromURL`. The SDK does not keep the chosen watches: they are
      stored under `garmin_connectiq_devices` (this phone only) and registered again at the next
      start. Garmin settings has **Select Garmin devices**, which calls `showConnectIQDeviceSelection`.
- [x] Kotlin decides; Swift only carries bytes. `ConnectIqCompanion` is the interface, installed
      from `AapsApp` the same way the Nightscout socket and the meal-advisor camera are. The watch
      app ids are `GarminWatchApps`, the same six as Android `GarminPlugin.glucoseAppIds`, in the
      same order. Device status and app-installed status are tracked. The message is
      `GarminRequests.getGlucoseMessage` (same keys and types Android serializes). The iPhone SDK
      encodes the dictionary itself; `GarminCiqBytes` is the Android encoding, and
      `GarminCiqBytesParityTest` checks the two are the same bytes for the same map.
- [x] Push that message on every new glucose value, on every `EventAPSCalculationFinished`, when a
      watch becomes reachable, when the request key changes, and when a watch sends the wrong HTTP
      key (that last one is what Android's messenger does, so the watch learns the key). Failures
      (`IQSendMessageResult`) wait: 2s, 4s, 8s … up to 60s, one line in the log, and a newer value
      replaces the one in flight instead of queueing. An app the watch does not have waits 30
      minutes. The preference **Connect IQ push (iOS)** (`garmin_connectiq_push_ios`) defaults to
      on. Android registers the key and never reads it, and it is not exported, so Android's
      messenger and its exports stay as they were. The local HTTP server is unchanged and stays up.
- [x] Incoming Connect IQ messages are logged and not acted on. Android's `GarminMessenger`
      callback is empty (`{ _, _ -> }`): heart rate, steps, carbs, bolus, temp target, mode and
      pump connect still arrive as HTTP, and the iPhone server still answers those.
- [x] On each Bluetooth wake (state restoration, connect, or a notification — the held-connect
      case), `GarminPlugin` makes sure the HTTP server is listening and, when that server is
      enabled, takes an 8-second `beginBackgroundTask` so a pull that arrives with the wake can
      be served. A burst of radio callbacks takes one task, not one per packet.

### Manual test (a real iPhone and watch)

The simulator cannot open Garmin Connect or talk to a watch. On a phone that already shows the
watch face while AAPS is in front:

1. **Pairing.** Garmin settings → Select Garmin devices. Garmin Connect opens, the watch is chosen,
   AAPS comes back. Force-quit AAPS, open it again: the watch is still selected (no second picker).
   A new glucose in AAPS shows on the watch within a few seconds, with the phone unlocked.
2. **Locked screen, overnight.** Lock the phone. In the morning the watch face should still have
   moved every few minutes, not sit on Connect IQ error -300. The local pull may still time out
   between CGM wakes; the push during each wake is what should keep the face current.
3. **Garmin Connect killed.** Swipe Garmin Connect away. The next push should fail once in the log
   (`FAILURE_DEVICE_NOT_AVAILABLE` or similar) and not keep retrying in a tight loop. Open Garmin
   Connect again and confirm the following glucose arrives.
4. **Bluetooth off, then on.** Turn Bluetooth off: no storm of errors, the face goes stale. Turn
   it on: the next CGM wake (or bringing AAPS to the front) pushes again.
5. **Switch off.** Turn off "Connect IQ push (iOS)". New glucose does not push (log stays quiet).
   With AAPS in front, the HTTP pull still answers, as in phase 2.

### 4. Device test

- [ ] Watch face and data field updated every 5 min with the screen locked for an hour.
- [ ] Carbs, bolus and temp target from the watch, as on Android.
