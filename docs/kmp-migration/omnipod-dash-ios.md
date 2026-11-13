# Omnipod Dash on iPhone

Goal: an Omnipod Dash pod runs the loop on an iPhone the way it does on Android. Android keeps its own
driver (`:pump:omnipod:dash` + `:pump:omnipod:common`), unchanged: it is upstream code that many people
use. The iPhone uses `:pump:omnipod:dashctl`, and parity tests keep the two sending the same bytes and
taking the same dosing decisions.

Reference for the iPhone side: OmniBLE (`github.com/loopandlearn/OmniBLE`), the Dash driver of Trio
and Loop.

## Where it stands (2026-11-12)

`dashctl` (Kotlin Multiplatform, D1–D4, October) holds:

- the pod protocol in commonMain: the 15 pod commands (the same list as Android), the responses, the
  pairing (X25519, SPS1/SPS2), the session (EAP-AKA / Milenage), the message encryption (AES-CCM);
- the crypto seams with bit-exact vectors against Android, and their iOS side (CommonCrypto,
  CryptoKit through cryptography-kotlin);
- the pod key store in the iOS Keychain (`AfterFirstUnlockThisDeviceOnly`);
- `IosDashPump`, a core that pairs, opens a session, and sends a bolus or a temp basal.

Nothing of it is wired: `ios/shell` does not depend on `dashctl`, no plugin is in the iOS graph.

## What is missing, and the order

### 1. Unconfirmed commands (safety, first)

When a command that changes delivery goes out and no answer comes back, the driver does not know
whether the pod did it. Android (`OmnipodDashPodStateManager.activeCommand`) and OmniBLE
(`unacknowledgedCommand`) block every new command until a pod status says what happened. Without it a
retried bolus can be delivered twice, or insulin can be counted that never went in.

- [x] Pod status in the state: pod and delivery status, pulses delivered, reservoir, time since
      activation, alerts, and the sequence number of the last programming command the pod took.
- [x] The active command: created before the bytes go, refused while one is open, marked sent before
      the write, resolved from the next status (same sequence: done; another: refused by the pod;
      never sent: failed; no status yet: still open). The same decisions as Android
      (`getCommandConfirmationFromState`). Android orders the events with `elapsedRealtime`, which
      starts again at 0 after a reboot while the command is stored; here a stored count of status
      answers does that job.
- [x] Stored in the Keychain with the key (codec v2, still reads v1), so an open command survives a
      crash or a restart of the app.
- [x] `IosDashPump` refuses a delivery command while one is open, stops included as on Android
      (a stop would become the last programming command and hide what happened to the open one),
      and no longer reports a bolus as delivered just because the bytes were written.

Tests: `DashActiveCommandTest` (commonTest, JVM and iOS: the rule, the manager, the stored form,
version 1 still read) and `IosDashPumpAndroidParityTest.aSecondBolusIsRefusedWhileTheFirstIsNotConfirmed`.

Until phase 2 reads the answers, a command stays open after its write, so every later delivery
command is refused: fail closed.

### 2. The link to the pod, then the manager

dashctl had no layer between the commands and Bluetooth: no `MessagePacket`, no splitting of a
message into BLE packets, no RTS/CTS flow. `IosDashMessageTransport` wrote each message raw, so a real
pod would get nothing it understands. The 10 byte differences pinned in `IosDashPumpAndroidParityTest`
(envelope `S0.0=`, flag byte, the two sequence counters, nonce, pulse rate, pod id) are mostly in that
missing layer. So the layer is ported first, as is, and the rest is rebuilt on it.

- [x] Step 1: port Android `comm/message`, `comm/packet`, `comm/command` and the io interfaces to
      `dashctl/comm` (commonMain), with `MessageIO` (RTS/CTS, NACK, SUCCESS) and a common CRC32. The
      Android tests came along byte for byte; 39 tests, green on the JVM and on the iOS simulator. Two
      adversarial reviews found no difference with Android.
      Android bugs found and ported as they are (the bytes must stay the same): `MessagePacket.parse`
      reads the message type from the first flag byte, size bytes are read signed in
      `MessagePacket.parse` and `BlePacket.parse`, `StringLengthPrefixEncoding.parseKeys` reads the
      length as `(b0 shl 1) or b1`. None of them changes real traffic; each is commented in the code.
- [x] Step 2: `DashBleLink` (commonMain) implements the two characteristics (CMD and DATA) on the
      events of `BleCentral`, and prepares a connected pod in Android's order (services,
      characteristics, HELLO, CMD indications, then DATA indications: one at a time, because on iOS
      the completion does not say which characteristic it is for). `BleSession` does not fit Dash: it
      has one notify and one write characteristic and drops the uuid of a value. On iOS,
      `QueuedBleCentral` runs each call on the radio queue of `CoreBluetoothCentral`, which is not
      safe to call from another thread. A write confirmation on iOS carries no bytes, so confirmations
      are paired with writes by their order (CoreBluetooth answers writes in order). Tested against a
      simulated pod that plays the pod side of `MessageIO`, at the level of BLE events: 108 tests on
      the JVM, 112 on the iOS simulator, after two adversarial reviews (7 findings, all fixed).
      Open for phase 3: who owns the `CoreBluetoothCentral` for Dash and its restore identifier.
- [ ] Step 3: pairing, session and commands rebuilt on `MessageIO`, with Android's two counters
      (packet sequence per session, seeded from the pair result; 4-bit command sequence, stored, the
      one the pod reports back and the active command follows), the fixed nonce, the pod's pulse
      rate and its id. The pinned parity tests then pass and lose their `@Ignore`.
- [ ] Step 4: reading the answer of each command and the status, ACK included, into
      `DashPodStateManager.updateFromStatus`, then `resolveActiveCommand`.
- [ ] Activation in two parts (unique id, alerts, prime, basal program, cannula), deactivation,
      suspend and resume, alerts and their silencing, beeps.
- [ ] Time and time zone: the basal program runs on pod time.
- [ ] Pod history, used to record what was confirmed in `PumpSync`.

### 3. Bluetooth on the iPhone

- [ ] Scan for pairable pods (Dash service UUID), then a connection held open, as OmniBLE does: a
      `connect` without timeout, made again after every drop, and CoreBluetooth state restoration so
      iOS starts the app again for the pod. The session is set up once per connection. This is the
      held connect already used for the Dexcom ONE+ (`holdsPendingConnect`).

### 4. The iOS plugin

- [ ] A `Pump` plugin in the iOS graph, the shape of `IosMedtrumPlugin` (driver host, state, actions),
      selectable like Medtrum.

### 5. Screens

- [ ] Activation and deactivation wizard, overview, in Compose Multiplatform.

### 6. On a real pod

There is no Dash emulator. Phases 1 to 5 are tested off line (JVM and iOS simulator, Android vectors,
OmniBLE test vectors); phase 6 uses real pods, one per activation.
