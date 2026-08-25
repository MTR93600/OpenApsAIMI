# AAPS + AIMI **master** on iOS — platform blocker analysis

> **Note de consolidation.** Cette annexe reste une analyse de contraintes utile. Les formulations
> « Critical Alerts impossible » et « middleware Trio sans fork » sont toutefois trop catégoriques.
> L'entitlement est spécial et non garanti ; Trio nécessite une couture native `DosingEngine` pour
> AIMI. La synthèse corrigée se trouve dans
> [`annex-7-ios-runtime-trio-integration.md`](annex-7-ios-runtime-trio-integration.md).

Written 2026-08-25. Read-only pass over the repo (`kmp`, `dev_OAPSAIMI`), plus research on the
Swift AID ecosystem (Loop, Trio, iAPS, LoopKit).

The existing note `_docs/KMP_IOS_FEASIBILITY.md` scoped itself to a **follower** and named
background execution as the deepest blocker. This report tests the **master** ambition: a full
AAPS+AIMI that drives a pump on an iPhone.

---

## 0. Bottom line first

The feasibility note's central warning is **wrong in its premise, and right in its conclusion for
the wrong reason.**

- Wrong premise: it says "iOS has no equivalent to WorkManager, `BGTaskScheduler` may delay for
  hours, so a 5-minute loop cannot work; the real answer is push (APNs)". That is not how the
  Swift AID ecosystem solves it, and push is *not* the answer. **Loop, Trio and iAPS have run a
  5-minute closed loop on iPhone for eight years.** They do it with `bluetooth-central` +
  CoreBluetooth state restoration: the CGM (or pump) is a **heartbeat** that wakes the app on every
  BLE notification. `BGTaskScheduler` is used by Loop for exactly one thing — historical log export.
  Background execution is **solved, with constraints**, and the constraints are known and documented.
- Right conclusion, different reason: the project is still enormous, and the two things that
  actually gate it are **(a) the device driver layer** — three AAPS pumps use Bluetooth *Classic*
  and are permanently impossible on iOS — and **(b) distribution**, which forces every single user
  onto a $99/year Apple Developer account.

And the finding that reframes the whole question:

> On the `kmp` branch, `plugins/aps/src/commonMain` already contains `DetermineBasalSMB.kt`,
> `DetermineBasalAMA.kt` and `DetermineBasalAutoISF.kt`, and the module declares `iosArm64`.
> **The AAPS dosing algorithm already compiles for iOS today.** `DetermineBasalAIMI2.kt` is the
> same shape of file (18,885 lines, imports `android.content.Context`, `android.os.Environment`,
> `org.json`, `javax.inject` — all mechanically removable).

That makes a third option real, and it is the one this report recommends. See section 7.

---

## 1. Background execution

### 1.1 What iOS actually gives you

| Mechanism | Real-world behaviour | Use for a loop? |
|---|---|---|
| `BGAppRefreshTask` (`BGTaskScheduler`) | You *request* an earliest-begin-date. iOS decides. Budget is driven by how often the user opens the app; in practice minutes-to-hours, and it stops entirely in Low Power Mode. No guarantee, ever. | **No.** Cannot carry a 5-minute cycle. |
| `BGProcessingTask` | Runs when device is idle and usually charging. Minutes-to-hours-to-overnight. | Only for maintenance (log export, model retraining). |
| `beginBackgroundTask` (`UIApplication`) | ~30 s of extra runtime when the app is about to be suspended. | Yes — this is how you buy time for one loop cycle. |
| `bluetooth-central` background mode | The app is **woken by the system** on connect, disconnect, and on every characteristic notify/indicate from a subscribed characteristic. ~10 s of CPU per wake (extendable with the above). Info.plist only — **no entitlement**, so it works on a free provisioning profile. | **Yes. This is the mechanism.** |
| CoreBluetooth state restoration (`CBCentralManagerOptionRestoreIdentifierKey` + `centralManager(_:willRestoreState:)`) | If iOS terminates the app (memory pressure, reboot after unlock), it **relaunches it into the background** when the restored peripheral event fires. | **Yes. This is what makes it survive termination.** |
| Background BLE *scanning* | Legal but degraded: `CBCentralManagerScanOptionAllowDuplicatesKey` is ignored, you **must** pass an explicit service-UUID list, and the scan interval is stretched. | Usable as a fallback, not as the primary path. |
| Silent APNs (`content-available`) | Rate-limited and coalesced by iOS; delivery is best-effort and throttled hard. Requires a server and a paid account. | **No** for the loop tick. Yes for remote commands (Trio ships `aps-environment` and does exactly this). |
| `audio` background mode (silent-audio keep-alive) | Loop and Trio both declare `audio` — for *alarm playback*, not as a keep-alive hack. A silent-audio keep-alive is a guidelines violation and irrelevant here (no App Review, but also unnecessary). | Not needed. |
| HealthKit `background-delivery` | Trio declares it. Wakes the app on new HealthKit samples. | Secondary heartbeat only. |

Evidence — `LoopKit/Loop/Loop/Info.plist` declares
`UIBackgroundModes = [bluetooth-central, processing, remote-notification, audio]` and exactly one
`BGTaskSchedulerPermittedIdentifiers` entry:
`com.loopkit.background-task.critical-event-log.historical-export`.
Trio (`Trio/Resources/Info.plist`) declares
`[bluetooth-central, bluetooth-peripheral, fetch, processing, remote-notification, audio]` and one
BGTask id, `...background-task.critical-event-log`.
`CGMBLEKit/BluetoothManager.swift` creates its central with
`CBCentralManager(delegate:queue:options: [CBCentralManagerOptionRestoreIdentifierKey: "com.loudnate.CGMBLEKit"])`
and implements `centralManager(_:willRestoreState:)`.

### 1.2 The existence proof, and its price

TrioDocs states it plainly:

> "If your CGM does not supply a heartbeat, the app will stop automatically running when it is not
> open."
> "While using Nightscout as a CGM ... it should be avoided if possible because it will not keep
> Trio running in the background like other CGM options. You will have to open Trio manually to
> make it run loop cycles."
> (Omnipod 5 driver) "does not provide a heartbeat at this time."

So the iOS loop is not time-driven, it is **event-driven off a BLE device you own the connection
to**. That is actually a *better* fit for AAPS than it sounds: AAPS master already triggers its
cycle on new BG arriving, not on a wall clock. But it hands you three hard constraints Android
does not have:

1. **No connected BLE heartbeat → no loop.** Any CGM source that arrives over HTTP (Nightscout,
   Dexcom Share, vendor-app-only G7 without a direct connection) cannot drive the loop in
   background. AAPS's `xDrip broadcast` / `BYODA` / Nightscout-as-source paths all die here.
2. **~10–30 s per wake.** Every cycle must finish inside that. `DetermineBasalAIMI2` is 18,885
   lines of Kotlin — almost certainly fine (it is arithmetic, not IO), but AIMI's ML *training*
   (`NeuralModelTrainer`, `AutodriveNeuralTrainer`, `BasalMlTrainerWorker`, `OrefPersonalMlTrainer`
   — currently 9 files behind `androidx.work`) absolutely must move off the wake path onto
   `BGProcessingTask`.
3. **You must own the CGM connection.** If the vendor app (Dexcom, Libre) holds the transmitter,
   your app cannot also connect. This is a live source of Loop bug reports.

### 1.3 Verdict

**POSSIBLE WITH CONSTRAINTS.**

- iOS replacement for WorkManager's periodic loop tick: `bluetooth-central` + state restoration,
  driven by the CGM/pump connection. Fallback and maintenance work: `BGProcessingTask`.
- iOS replacement for `AlarmManager.setExactAndAllowWhileIdle`: `UNUserNotificationCenter` with a
  calendar/interval trigger. Local notifications fire on time; **but they do not run your code**,
  they only show UI. AAPS reminders (`ReminderSchedulerImpl`, `TimerReminderReceiver`) port; AAPS
  logic that piggybacks on an alarm to *execute* does not.
- iOS replacement for the foreground service + persistent notification (`DummyService`): nothing.
  There is no "keep me alive" primitive. A Live Activity (Trio ships `LiveActivity/`) gives you the
  lock-screen presence but **not** the runtime.
- **Effort: 8–14 person-weeks.** The BLE/restoration plumbing itself is ~3 pw. The rest is the
  architectural inversion — making the whole loop cycle re-entrant, resumable, and short enough to
  fit in a wake, and splitting AIMI's training off the critical path.

---

## 2. BLE pumps and CGMs

### 2.1 The one hard, permanent blocker: Bluetooth Classic

Three AAPS pump drivers speak Bluetooth **Classic RFCOMM/SPP**, not BLE:

| Module | Evidence |
|---|---|
| `pump/danar` (DanaR, DanaRv2) | `services/RealRfcommTransport.kt`, `core/interfaces/.../pump/rfcomm/RfcommTransport.kt` |
| `pump/insight` (Accu-Chek Insight) | `utils/ConnectionEstablisher.kt`, `connection_service/InsightConnectionService.kt` — `BluetoothSocket` |
| `pump/combov2/comboctl` (Accu-Chek Combo) | `androidMain/.../AndroidBluetoothInterface.kt`, `AndroidBluetoothDevice.kt` — RFCOMM |

iOS gives third-party apps **no** RFCOMM/SPP access. `CoreBluetooth` is BLE-only; Classic serial
goes through `ExternalAccessory`, which requires the accessory to carry an Apple **MFi**
authentication coprocessor and the developer to be an MFi licensee. Roche and Sooil have not done
that for these pumps and never will. This is not a porting cost — it is a wall.

Corroboration: Trio supports **Dana-i and DanaRS-v3** (both BLE) and **not** DanaR, Combo or
Insight. That gap is exactly the Classic/BLE line.

### 2.2 Per-device verdict table

| AAPS device | Transport | Swift equivalent already exists? | Verdict | Effort if you port it yourself |
|---|---|---|---|---|
| Omnipod DASH (`pump/omnipod`, 304 kt) | BLE | **Yes** — `OmnipodKit` / `OmniBLE` (Loop, Trio) | POSSIBLE | 14–20 pw (or ~4 pw to bridge OmniBLE) |
| Omnipod Eros | 433 MHz via **RileyLink** (RileyLink itself is BLE) | **Yes** — `OmniKit` + `RileyLinkKit` | POSSIBLE — RileyLink is a BLE bridge, so CoreBluetooth is enough. **Not** an iOS blocker. Hardware is being discontinued (Canada EOL 30 Jun 2026), so it is a shrinking target. | 12–18 pw |
| Dana-i / DanaRS v3 (`pump/danars`, 108 kt) | BLE | **Yes** — `DanaKit` (Trio) | POSSIBLE | 8–12 pw (or ~3 pw to bridge DanaKit) |
| DanaR / DanaRv2 (`pump/danar`, 53 kt) | **Classic RFCOMM** | No | **IMPOSSIBLE TODAY** | — |
| Accu-Chek Combo (`pump/combov2`, 84 kt) | **Classic RFCOMM** | No | **IMPOSSIBLE TODAY** | — |
| Accu-Chek Insight (`pump/insight`, 295 kt) | **Classic RFCOMM** | No | **IMPOSSIBLE TODAY** | — |
| Medtrum Nano/300U (`pump/medtrum`, 114 kt) | BLE | **Yes** — `MedtrumKit` (Trio) | POSSIBLE | 8–12 pw |
| Medtronic 5xx/7xx (`pump/medtronic` + `rileylink`, 164 kt) | 916/868 MHz via RileyLink (BLE bridge) | **Yes** — `MinimedKit` + `RileyLinkKit` | POSSIBLE | 12–18 pw |
| EOPatch (`pump/eopatch`, 265 kt) | BLE (RxAndroidBle) | No | POSSIBLE WITH CONSTRAINTS — must also replace RxAndroidBle | 14–20 pw |
| Diaconn G8 (`pump/diaconn`, 269 kt) | BLE | No | POSSIBLE | 14–20 pw |
| Equil (`pump/equil`, 182 kt) | BLE | No | POSSIBLE | 12–16 pw |
| Apex (`pump/apex`) | BLE | No | POSSIBLE | 10–14 pw |
| Dexcom G6 / ONE | BLE | **Yes** — `CGMBLEKit` | POSSIBLE | 6–10 pw |
| Dexcom G7 / ONE+ (`plugins/dexcom_oneplus`, 70 kt on `dev_OAPSAIMI`) | BLE | **Yes** — `G7SensorKit` | POSSIBLE — and this is the AIMI-branch native driver you already own. Its Control-characteristic INDICATE-not-NOTIFY finding maps 1:1 onto `CBCharacteristicProperties.indicate`; CoreBluetooth handles both under one `setNotifyValue(true)`. | 6–9 pw |
| Libre 2/3 (`plugins/libre3`, 107 kt) | BLE + **NFC** for activation | **Yes** — `LibreTransmitter`; iOS `CoreNFC` covers activation (Trio declares `nfc.readersession.formats`) | POSSIBLE | 10–14 pw |
| Eversense (`plugins/eversense`, 156 kt) | app-to-app / BLE | Partial — Trio lists Eversense E3/365 | POSSIBLE WITH CONSTRAINTS | 10–14 pw |

### 2.3 Two constraints that bite even on the BLE ones

- **Pairing/bonding.** Android lets you drive `createBond()`, read the bond state and, for some
  drivers, feed a PIN. iOS has no bonding API at all: pairing is triggered implicitly by touching an
  encrypted characteristic and the OS owns the UI. Any driver whose handshake assumes it can
  observe or control bonding (Dana, Medtrum, Diaconn) needs its state machine reworked. `DanaKit`
  and `MedtrumKit` prove it is solvable, not that it is free.
- **Legality is unchanged by the port.** These protocols are reverse-engineered and unlicensed on
  both platforms. iOS adds no new legal barrier for BLE — it adds a *distribution* barrier
  (section 5).

### 2.4 Verdict

**POSSIBLE WITH CONSTRAINTS**, with a permanent feature loss of DanaR / Combo / Insight.

- **Effort if you port AAPS's own drivers:** 8–20 pw *per device*. For a minimum usable master
  (one CGM + one pump) that is **14–30 pw**; for parity with AAPS's supported list, 150+ pw.
- **Effort if you bridge the Swift LoopKit drivers instead:** ~10–16 pw total for a
  `PumpSync`/`PumpInterface` ↔ `LoopKit.PumpManager` adapter, then ~2–4 pw per device. This is by
  far the better trade and it is the same argument that drives section 7.

---

## 3. ML runtime

### 3.1 What AIMI actually uses (measured on `dev_OAPSAIMI`)

The AIMI package is **442 files / 102,099 lines** under
`plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/`. Only **two** files touch a native
ML runtime:

| File | Runtime | What it does |
|---|---|---|
| `AimiModelHandler.kt` | `org.tensorflow:tensorflow-lite:2.4.0` (`Interpreter`) | Loads a **user-supplied** `modelUAM.tflite` from `Documents/AAPS/ml/`. **Inference only.** Also pulls in Guava `CacheBuilder` and `java.security.MessageDigest`. |
| `advisor/oref/OrefOnnxScorer.kt` | `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` | Scores three bundled LightGBM models: `assets/oref/{bg_change,hyper,hypo}_lgbm.onnx`. **Inference only.** |

Assets: `app/src/main/assets/{model,modelUAM}.tflite`, `plugins/aps/src/main/assets/oref/*.onnx`.

**Everything else is hand-written Kotlin.** `aimiNeuralNetwork.kt` (dense net + backprop, imports
only `java.io.File`, `kotlin.math`, `kotlin.random`, `org.json`), `ml/NeuralModelTrainer.kt`,
`ml/AimiSmbTrainer.kt`, `ml/TrainingCircuitBreaker.kt`, `learning/BasalNeuralLearner.kt`,
`autodrive/learning/{AutodriveNeuralTrainer,OnlineLearner,MechanismAttentionGate}.kt`,
`OrefPersonalMlTrainer.kt`. **On-device training exists and is 100 % pure Kotlin.**

That is the single luckiest fact in this whole report. If AIMI trained through TFLite's on-device
training API, every conversion path (Core ML, ONNX export) would be dead. It does not.

### 3.2 iOS options

| Option | Works on Kotlin/Native? | Notes | Effort |
|---|---|---|---|
| **Pure-Kotlin NN, as-is** | **Yes, free.** `kotlin.math`, `kotlin.random`, arrays — all Native. | Only cost is replacing `java.io.File` (→ okio or `expect/actual`) and `org.json` (→ kotlinx.serialization; 67 AIMI files use org.json). Covers *all* training and most inference. | 3–5 pw (part of the general org.json/File sweep) |
| **TFLite / LiteRT via cinterop** | Yes. LiteRT ships `TensorFlowLiteC.framework` (a plain C API) for iOS; `cinterop` binds C headers directly. `moko-tensorflow` is an existing KMP precedent. | Model is user-supplied at runtime, so it must stay a runtime loader, not a build-time conversion. Pin the same TFLite version on both sides or the two platforms diverge. | 3–5 pw |
| **ONNX Runtime via cinterop** | Yes. `onnxruntime-c` / `onnxruntime-objc` ship an iOS XCFramework with a C API. | Three small LightGBM models; straightforward. | 2–4 pw |
| **Core ML conversion** | No — and don't. | Would need `coremltools` at build time, ships a *frozen* model, and cannot load the user's own `.tflite`. Kills the "drop your model in Documents" workflow. Also **cannot train**. | (rejected) |
| **Drop TFLite+ONNX on iOS** | Yes | Both call sites are behind toggles already (`AimiUamHandler` has a `lastLoadOk` health flag; the ONNX advisor is gated by `OrefPersonalSignalGate`). A no-op iOS `actual` is a legitimate v1. | 0.5 pw |

### 3.3 Verdict

**POSSIBLE.** ML is *not* a blocker — it is the smallest section of this report. Two files,
two `expect/actual` seams, both with a valid degrade-to-nothing fallback. On-device training being
pure Kotlin means it moves for free.

- **Effort: 5–9 pw** for full parity (both runtimes via cinterop), or **3–5 pw** for a v1 that
  ships pure-Kotlin learning only.
- One real iOS-specific chore: the model file lives in `Environment.getExternalStorageDirectory()`.
  On iOS that becomes the app's `Documents/` with `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`
  so it shows in the Files app. Trio already does this. Same fix as section 4.

---

## 4. Storage and persistence

| Surface | State | iOS answer | Verdict | Effort |
|---|---|---|---|---|
| **Room, 46 DAOs** | Best case in the repo: **0 RxJava return types, 22 `suspend`, already on `BundledSQLiteDriver`**. Room KMP (2.7+, 2.8.x current) supports `iosArm64` with `BundledSQLiteDriver`; entities/DAOs/`@Database` live in `commonMain`. | Room KMP. Only the database *builder* needs `expect/actual` (path resolution: `NSDocumentDirectory` vs `context.getDatabasePath`). | POSSIBLE | 6–10 pw incl. migration re-verification |
| Room caveats | Room KMP is still moving; some `@RawQuery`/converter/`@Transaction` shapes behave differently on Native, and there is no `SupportSQLiteDatabase` escape hatch. Also **kotlin-reflect is unavailable on Native** — 9 files use it. | Audit + rewrite the reflective bits | POSSIBLE WITH CONSTRAINTS | included above |
| **SAF / `DocumentFile`** (13 files: `FileStorage`, `FileListProviderImpl`, `MaintenanceImpl`, `ImportExportPrefsImpl`, `EncryptedPrefsFormat`, `ZipWatchfaceFormat`, …) | Android-only, no analogue whatsoever. iOS has no user-granted tree access. | App-sandbox `Documents/` + `UIFileSharingEnabled` + Files-app integration; `UIDocumentPickerViewController` for one-shot import/export. This is what Trio does (`UIFileSharingEnabled` is in its Info.plist). | POSSIBLE WITH CONSTRAINTS — **behaviour changes**: no "pick a folder once and keep writing to it". Automatic scheduled export to a user-chosen location is **lost**. | 5–8 pw |
| **Preferences / `SharedPreferences`** | `:core:keys` is already multiplatform (46 files, 0 Android, 0 `java.*` since Wave 3). | `androidx.datastore` is multiplatform (Native supported) over okio. Or `NSUserDefaults` behind `expect/actual`. | POSSIBLE | 3–5 pw |
| **Export / import (AAPS preferences file)** | `ImportExportPrefsImpl` + `EncryptedPrefsFormat` + `PrefsFormat`; crypto via `javax.crypto` / spongycastle / tink-android (~20 files). | `cryptography-kotlin` (multiplatform, CryptoKit-backed on Apple) or `expect/actual` over CommonCrypto. **The file format must stay byte-identical** or an Android→iOS migration is impossible. | POSSIBLE | 4–7 pw |
| **Logs / JSONL** (AIMI writes heavily: 31 `java.io.File` imports, `AimiStorageHelper`, `AutodriveDataLake`) | `java.io.File` everywhere. | okio `FileSystem.SYSTEM`, one sweep. | POSSIBLE | 3–5 pw (folded into the AIMI port) |
| **Health Connect** (AIMI: 41 imports across `androidx.health.connect.*`) | Android-only. | **HealthKit** — richer, and Trio/Loop already read/write it. Real work, not a port. | POSSIBLE | 4–6 pw |

**Section verdict: POSSIBLE WITH CONSTRAINTS. Effort 25–41 pw.** Nothing here is a wall; SAF is the
only genuine feature regression.

---

## 5. Distribution — the real gate

An insulin-dosing app cannot go on the App Store. LoopDocs is explicit: *"The Loop app will not be
available in the Apple App store because that would be distribution of a medical device."*
Every route below is a workaround for that fact.

| Route | Cost | Reach | Duration | App Review? | Verdict |
|---|---|---|---|---|---|
| **Free personal team + Xcode** | $0, but **needs a Mac** | 1 person (the author) | **7 days**, then re-sign from Xcode | No | Author-only. And it loses App Groups, push, iCloud and "a limited set of background modes". `bluetooth-central` is Info.plist-only and *does* survive; App Groups (widgets, watch app) does **not**. |
| **$99 Apple Developer Program + ad-hoc** | $99/yr | 100 devices/yr on **your** account | 1 year | No | Works, but you must collect every user's UDID and re-issue — unmanageable at community scale, and arguably makes you the distributor of a medical device. |
| **$99 + TestFlight, user as their own internal tester** ← **this is the real one** | $99/yr **per user** | The user's own devices | **90-day builds**, auto-rebuilt | **No** — internal testers (up to 100 team members) get builds in minutes with **no Beta App Review** | **The proven route.** This is exactly what Loop/Trio/iAPS do. |
| TestFlight **external** testing | $99/yr | 10,000 | 90 days | **Yes — Beta App Review on the first build of each version** | Would be refused. Nobody in this ecosystem attempts it. |
| **AltStore PAL / SideStore + DMA** | Marketplace operator obligations; Apple **notarizes** every Alternative Distribution Packet | **EU only** | — | Notarization, which Apple can refuse | Not viable for a dosing app: you would be publicly distributing an unregulated medical device in the exact jurisdiction (EU MDR) where that is most clearly illegal. |
| **MDM** | Enterprise/education programme | Managed devices only | — | No | Not applicable to individuals. |

### 5.1 How the Loop community actually does it

The **browser build** (LoopDocs `browser/bb-overview`, mirrored by `Trio/fastlane/testflight.md`
and `iAPS/fastlane/testflight.md`):

1. The user **forks** the repo on GitHub (free account).
2. They create their **own** paid Apple Developer account ($99/yr) and an App Store Connect API key.
3. They save 4 secrets into their fork: `TEAMID`, `FASTLANE_ISSUER_ID`, `FASTLANE_KEY_ID`,
   `FASTLANE_KEY`.
4. **GitHub Actions + fastlane** build and upload to **their own** TestFlight. ~30 min per build,
   free CI minutes on a public repo.
5. They install via the TestFlight app. **No Mac needed at any point.**
6. Rebuild: automatic weekly workflow; since May 2025 a manual re-trigger is needed roughly every
   60 days. Builds expire at **90 days**.
7. First-time setup: LoopDocs budgets **2–4 hours, spread over several days**.

Support and hand-holding live outside the code repo: **Loop and Learn** (loopnlearn.org) publishes
the step-by-step guides and helper scripts; **LoopFollow** is the separate remote-monitoring app
distributed the same way; the Loop/Trio Facebook and Discord groups do the triage.

### 5.2 What this means for AAPS-AIMI on iOS

- Every user pays **$99/year to Apple**, forever, and re-triggers a build every ~60–90 days.
  AAPS today is a free APK you sideload once. This is a **hard filter** on adoption, and it will
  cut the audience by an order of magnitude versus Android.
- You must build and maintain the whole fastlane/GitHub-Actions harness plus the documentation —
  LoopDocs treats it as a first-class product, and so must you. Budget **6–10 pw** for the pipeline
  and docs, plus permanent support load.
- **Verdict: POSSIBLE WITH CONSTRAINTS.** The route exists and is proven. It is not free, not
  frictionless, and not App Store.

---

## 6. Other Android-only surfaces, and the feature-loss list

Measured on the working tree.

| AAPS surface | Count | iOS equivalent | Verdict |
|---|---|---|---|
| `WorkManager` | **85 `.kt` files** (repo), 9 in AIMI | `BGProcessingTask` for deferrable work; the loop tick moves to the BLE wake (§1). **There is no guaranteed-execution, constraint-aware, retrying job queue on iOS.** You write your own on top of a durable queue + BGProcessingTask + the BLE wake. | POSSIBLE WITH CONSTRAINTS — 10–16 pw |
| Foreground service + persistent notification (`DummyService`, `LocationService`, `InsightAlertService`, `AutomationRuntime`) | 9 files | None. A **Live Activity** gives lock-screen presence but no runtime. | **LOST as a mechanism** (presence can be recreated) |
| Exact alarms (`AlarmManager`, `setExactAndAllowWhileIdle`) — `ReminderSchedulerImpl`, `AlarmNotificationManager`, EOPatch `AlarmRegistry` | 16 files | `UNCalendarNotificationTrigger` / `UNTimeIntervalNotificationTrigger` — fires the *notification* on time but **does not run code**. | POSSIBLE WITH CONSTRAINTS |
| **Full-volume alarms that override Do-Not-Disturb / silent** | `AlarmSoundPlayer`, `AlarmScreenWakeReceiver` | iOS **Critical Alerts** need `com.apple.developer.usernotifications.critical-alerts`, granted case-by-case by Apple on manual request. **Trio's `Trio.entitlements` does not contain it** (only `aps-environment`, healthkit×3, nfc, app-groups). A DIY fork will not get it. | **LOST — and this is a safety regression.** iOS alarms are ordinary notifications; a silenced phone silences them. |
| Widgets (`androidx.glance`) | 8 files | WidgetKit — **Swift/SwiftUI only, zero Kotlin reuse.** Needs App Groups (so: not on a free profile). | **REWRITE** |
| **Wear OS companion** (`wear/` module, `DataLayerListenerService*`, Play Services Wearable) | whole module + 4 sync files | watchOS + WatchConnectivity. Trio ships `Trio Watch App` + Complication — proof it can be done, but it is a **from-scratch Swift rewrite**; none of the AAPS wear code, watchfaces (`ZipWatchfaceFormat`) or Wear tiles survive. | **LOST / full rewrite** |
| **SMS commands** (`smsCommunicator`) | plugin | iOS gives no app SMS send/receive API. **No workaround exists.** | **LOST, permanently** |
| Phone / call-based triggers | automation | Same. | **LOST, permanently** |
| **Garmin** integration | plugin | Garmin Connect IQ has an iOS SDK, but the AAPS side is Android-specific. Rewrite. | REWRITE |
| **Automation triggers** — location, wifi SSID, connected BT device, Tasker/intents, screen state | `plugins/automation` (uses `android.bluetooth`, Play Services location) | Partial: location works via `CoreLocation` (and geofence wakes actually run code). **Wifi SSID: heavily restricted. Nearby-BT-device: only for peripherals you connect to. Tasker/broadcast intents: nothing — iOS Shortcuts/App Intents is a different, weaker model.** | **PARTIAL LOSS** |
| Boot receiver (`AutoStartReceiver`, `BOOT_COMPLETED`) | 2 files | **Nothing.** After a reboot, iOS will not launch your app until the user unlocks the phone once; CoreBluetooth restoration then relaunches it in background. So: **a reboot stops the loop until first unlock.** | **PARTIAL LOSS** |
| Play Services (`LocationService`, Wear data layer) | 8 files | `CoreLocation`, `WatchConnectivity` | REWRITE |
| Dagger / Hilt | ~300 files repo-wide, **83 in AIMI** | Metro, kotlin-inject, or Koin. Documented on the `kmp` branch: **Dagger generates Java even on its KSP backend, so it can never live in a KMP module — not even `androidMain`, where it silently emits Java that never compiles and the build still passes green.** "A module's conversion cost is roughly its Dagger count." | POSSIBLE — but it is the single largest mechanical cost in the repo |
| `org.json` | 227 repo-wide, **67 in AIMI** | kotlinx.serialization `JsonObject` | POSSIBLE |
| `kotlin-reflect` | 9 files | **No reflection on Native.** Must be removed. | POSSIBLE |
| socket.io (NSClient v3) | 1 file | Not a library choice — a server protocol. Must be **kept and abstracted**, per the existing note §3a. | POSSIBLE WITH CONSTRAINTS |

### 6.1 Explicit feature-loss list — what an iOS AAPS would simply NOT have

1. **DanaR / DanaRv2, Accu-Chek Combo, Accu-Chek Insight** — Bluetooth Classic. Permanent.
2. **SMS commands and phone-call triggers** — no iOS API. Permanent.
3. **DND-overriding / full-volume alarms** — Critical Alerts entitlement unobtainable for a DIY
   fork. This is a *safety* loss, not a convenience loss.
4. **Wear OS** — entire module, watchfaces and tiles. A watchOS app is a separate Swift project.
5. **Widgets** — Glance code is unusable; WidgetKit rewrite, and unavailable on a free profile.
6. **Automatic looping without a directly-connected BLE CGM** — Nightscout-as-source, xDrip
   broadcast, Dexcom Share, and any vendor-app-only CGM cannot drive a background cycle.
7. **Auto-start after reboot** — the loop stays down until the user unlocks the phone once.
8. **"Pick a folder and keep exporting there" (SAF)** — scheduled export to arbitrary user storage.
9. **Tasker / broadcast-intent automation**, wifi-SSID triggers, most non-location automation.
10. **Garmin**, in its current form.
11. **Free, one-off sideloading** — replaced by $99/yr + a rebuild every 60–90 days, per user.

---

## 7. Verdict

### 7.1 Is "AAPS-AIMI master on iOS via KMP" technically feasible?

**Yes — with a large asterisk, and it is not the asterisk the existing note names.**

Background execution is solved (§1). ML is nearly free (§3). Storage is routine (§4). What is
genuinely hard is the device layer (§2), the DI/`org.json`/reflection sweep across ~300 Dagger
files and 227 `org.json` files (§6), and the fact that a dosing app needs months of field
validation on top of "it compiles".

Honest roll-up for a **first usable AAPS-AIMI master on iPhone**, one experienced developer:

| Workstream | pw |
|---|---|
| Finish the KMP conversion of the remaining ~35 modules (dominated by Dagger, org.json, reflection) | 40–70 |
| Port the AIMI package (442 files / 102 KLOC; 72 android, 41 androidx, 83 dagger, 67 org.json, 119 java.*) | 20–35 |
| Background/lifecycle inversion + wake-driven loop | 8–14 |
| One CGM + one pump driver (or the LoopKit bridge) | 14–30 |
| Storage, Room KMP, export/import, HealthKit | 25–41 |
| ML runtime seams | 5–9 |
| iOS app shell, notifications, alarms, settings, UI gaps | 10–18 |
| Distribution pipeline (fastlane/Actions) + docs + support | 6–10 |
| Safety validation, field trial, regression suite | 15–30 |
| **Total** | **~145–260 pw ≈ 3–5 person-years** |

For calibration: the `kmp` branch represents ~18 documented "waves" of work and has 14 of ~50
modules multiplatform, with `core/ui` at 435 files in `commonMain` and `plugins/aps` compiling
`DetermineBasalSMB` for `iosArm64`. That is real, impressive progress — and it is maybe 15 % of the
number above.

### 7.2 Is it a better use of effort than contributing AIMI's logic to Trio/Loop?

**The case FOR the KMP master:**
- One algorithm, one language, one repo. AIMI's 102 KLOC keeps evolving on Android and the iOS
  build follows automatically. No fork, no re-implementation, no drift. This is the same argument
  the existing note makes for Compose Multiplatform, and it is the strongest one.
- You keep the AAPS *product*: profiles, the plugin architecture, objectives, the constraint
  system, Nightscout sync, the whole safety scaffolding. Trio has its own, different versions.
- Trio's governance is not yours. AIMI is opinionated, fast-moving and experimental; getting
  Harmonia / RBT / EffortActivityBelief / the physiological tree accepted upstream into Trio would
  be a multi-year social project on top of the engineering.
- `core/ui` at 435 files in `commonMain` means the UI genuinely is reusable, which is the part
  people usually underestimate.

**The case AGAINST:**
- 3–5 person-years to arrive where Trio already is, minus Wear, widgets, SMS, three pumps and
  Critical Alerts.
- The device layer and the background/lifecycle layer — the two hardest, most safety-critical
  pieces — are **already written, field-tested by thousands of users, and open source** in
  `CGMBLEKit`, `G7SensorKit`, `LibreTransmitter`, `DanaKit`, `MedtrumKit`, `MinimedKit`,
  `OmnipodKit`, `RileyLinkKit`. Rewriting them in Kotlin creates a second, less-tested
  implementation of the riskiest code in the system. That is a *negative* safety trade.
- Distribution and support infrastructure (fastlane, browser build, LoopDocs, Loop and Learn) also
  already exist and would have to be duplicated.
- The AIMI logic itself — the actual intellectual property — is **the cheapest part to move**
  (20–35 pw) and the only part nobody else has.

**And the fact that decides it:** Trio's algorithm is **oref0 JavaScript running in
JavaScriptCore** (`Trio/Resources/javascript/bundle/{determine-basal,iob,meal,profile,autosens,
autotune-*}.js`). AIMI is 102 KLOC of Kotlin with on-device learning. "Contributing AIMI's logic to
Trio" in the naive sense means **rewriting 102 KLOC of Kotlin into JavaScript**, which is worse than
either option above and would guarantee permanent drift from the Android original.

### 7.3 Recommendation — take the third door

Neither "port all of AAPS to iOS" nor "rewrite AIMI in JS/Swift". Instead:

> **Compile the AIMI decision engine to a Kotlin/Native XCFramework and plug it into Trio as the
> dosing engine, replacing the oref0 JavaScript bundle.**

Why this is the right shape:

- **It is already half-done.** `plugins/aps/src/commonMain` on the `kmp` branch compiles
  `DetermineBasalSMB` / `AMA` / `AutoISF` for `iosArm64` today. `DetermineBasalAIMI2.kt` is the
  same kind of file. Trio *already* treats determine-basal as a swappable black box behind a
  JSON-in / JSON-out boundary — it has to, because it calls into JavaScriptCore. Swapping
  JavaScriptCore for a Kotlin framework is an interface substitution, not a re-architecture.
  Trio even ships a `middleware/` hook at that exact seam.
- **You inherit the two hardest layers for free**: every LoopKit device driver, and eight years of
  hard-won background-execution behaviour, including the heartbeat semantics per CGM.
- **You inherit distribution for free**: Trio's fastlane/GitHub-Actions browser build and its
  documentation and support community.
- **One source of truth survives.** The same `commonMain` Kotlin feeds Android AAPS-AIMI and iOS
  Trio-AIMI. That is the maintenance argument that motivated KMP in the first place — realised at
  a fraction of the cost.
- **Effort: roughly 25–45 pw** — the AIMI KMP port (20–35 pw, which you need for *any* of these
  options) plus the JSON boundary adapter and Swift shim (5–10 pw). Compare 145–260 pw.

Sequence it:
1. **Prove the seam.** Get `DetermineBasalAIMI2` + the tree into a `commonMain` module with no
   Dagger, no `org.json`, no `java.io.File`; build an XCFramework; call it from a Swift test harness
   with a captured real `determine-basal` input JSON and diff the output against the Android run.
   That is a **4–6 week** spike and it de-risks everything else.
2. Move the learning/persistence layer (okio + Room KMP or plain files) so on-device training works
   on Native.
3. Build the Trio integration behind a toggle, run it in open-loop/shadow mode against Trio's own
   oref output for weeks before it dispenses anything.
4. Keep the full KMP-AAPS-on-iOS ambition as a **possible later consequence** of steps 1–3, not as
   the goal. If the shared-Kotlin core proves itself inside Trio, growing it outward becomes a
   choice you can make with evidence instead of a 3–5-year bet you have to make now.

The one thing to *stop* doing: treating `BGTaskScheduler` and APNs as the background answer. They
are not. Whichever door you take, the loop tick is a BLE wake, and every design decision downstream
depends on getting that right.

---

## Sources

- [LoopKit/Loop `Info.plist`](https://raw.githubusercontent.com/LoopKit/Loop/dev/Loop/Info.plist)
- [LoopKit/CGMBLEKit `BluetoothManager.swift`](https://raw.githubusercontent.com/LoopKit/CGMBLEKit/dev/CGMBLEKit/BluetoothManager.swift)
- [nightscout/Trio repository](https://github.com/nightscout/Trio) — `Trio/Resources/Info.plist`, `Trio.entitlements`, `Trio/Resources/javascript/bundle/`, and the `CGMBLEKit`, `G7SensorKit`, `LibreTransmitter`, `DanaKit`, `MedtrumKit`, `MinimedKit`, `OmnipodKit`, `RileyLinkKit`, `Trio Watch App` submodules
- [TrioDocs — CGM](https://triodocs.org/install/build/requirements/devices/cgm/) and [Pumps](https://triodocs.org/install/build/requirements/devices/pump/) (heartbeat statements)
- [LoopDocs — Browser Build overview](https://loopkit.github.io/loopdocs/browser/bb-overview/)
- [LoopWorkspace `fastlane/testflight.md`](https://github.com/LoopKit/LoopWorkspace/blob/main/fastlane/testflight.md)
- [LoopDocs — RileyLink](https://loopkit.github.io/loopdocs/build/rileylink/) and [Omnipod FAQs](https://loopkit.github.io/loopdocs/faqs/omnipod-faqs/)
- [LoopDocs — Loop FAQs](https://loopkit.github.io/loopdocs/faqs/loop-faqs/) (App Store / medical device)
- [Apple — Core Bluetooth background processing](https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html)
- [Apple — Critical Alerts entitlement](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.usernotifications.critical-alerts)
- [Apple — TestFlight](https://developer.apple.com/testflight/)
- [Android Developers — Room for KMP](https://developer.android.com/kotlin/multiplatform/room)
- [icerockdev/moko-tensorflow](https://github.com/icerockdev/moko-tensorflow) — TFLite bindings for KMP
- [Kotlin/Native C interop](https://kotlinlang.org/docs/native-c-interop.html)
- [Loop and Learn](https://www.loopnlearn.org/) — community distribution and support
- [AltStore PAL / EU DMA marketplaces](https://techcrunch.com/2026/02/22/move-over-apple-meet-the-alternative-app-stores-available-in-the-eu-and-elsewhere/)
- In-repo: `_docs/KMP_IOS_FEASIBILITY.md`; branches `kmp` and `dev_OAPSAIMI` as cited inline.
