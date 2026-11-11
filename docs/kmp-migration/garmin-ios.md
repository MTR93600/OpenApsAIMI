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

### 2. iPhone, pull channel

- [x] A small HTTP server on `127.0.0.1` in `iosMain` (POSIX sockets), same port preference:
      `IosGarminHttpServer`. The request text and the answer text are `GarminHttp` (`commonMain`),
      written after the Android `HttpServer`. `IosGarminHttpServerTest` runs it on a real socket on
      the simulator.
- [x] An iOS Garmin plugin in the iOS graph, with the same preferences, answering through the
      shared rules: `GarminPlugin` in `iosMain`. Same class name as on Android on purpose, so the
      imported `ConfigBuilder_Enabled_SYNC_GarminPlugin` turns it on. The key check and the path table
      are shared (`GarminRequests.keyAccepted`, `GarminRequests.answer`). Not yet: sending the key
      to the watch after a wrong key (needs phase 3).
- [ ] Check on the device with the watch: `/get` answered while AAPS is in front, then in the
      background.

### 3. iPhone, push channel

- [ ] Add the ConnectIQ Swift package to `ios/app`, a URL scheme for AAPS and
      `LSApplicationQueriesSchemes` for Garmin Connect.
- [ ] A Swift bridge behind a Kotlin interface: pick the watch, send a message to the app ids.
- [ ] Push the shared `glucose` message on every new glucose value, and at connect.

### 4. Device test

- [ ] Watch face and data field updated every 5 min with the screen locked for an hour.
- [ ] Carbs, bolus and temp target from the watch, as on Android.
