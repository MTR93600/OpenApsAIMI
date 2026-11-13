# Omnipod Dash on iPhone

Goal: an Omnipod Dash pod runs the loop on an iPhone the way it does on Android. Android keeps its own
driver (`:pump:omnipod:dash` + `:pump:omnipod:common`), unchanged: it is upstream code that many people
use. The iPhone uses `:pump:omnipod:dashctl`, and parity tests keep the two sending the same bytes and
taking the same dosing decisions.

Reference for the iPhone side: OmniBLE (`github.com/loopandlearn/OmniBLE`), the Dash driver of Trio
and Loop.

## Where it stands (2026-11-13)

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
- [x] Stored in the Keychain with the key (codec v4 today, still reads v1 to v3), so an open command survives a
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
- [x] Step 3 (done and tested off line, not on a pod): Android `pair`, `session` and `endecrypt`
      ported to `dashctl/comm` (LTKExchanger, SessionEstablisher with the full EAP message, Session
      with the `S0.0=` envelope and the ACK, EnDecrypt on MessagePacket). `IosDashPump` runs on them
      with Android's two counters (packet sequence per session, seeded from the pair result; 4-bit
      command sequence, stored, +1 on send and +1 on answer), the fixed nonce, the pod's pulse rate
      (a bolus is refused until it is known) and the pod id. The old classes without MessagePacket
      are deleted. All pinned parity tests pass with their original expected values, except
      `pairingSeedsTheSequenceCounterFromThePairResult`, whose premise was wrong (it checks the
      stored command counter, which pairing must not touch).
- [x] Step 4 (same): each answer is read and acknowledged, a status goes into
      `DashPodStateManager.updateFromStatus`, then the open command is decided. A bolus is reported
      delivered only on SUCCESS. A late decision (found by a later status read) reaches
      `onCommandDecided`, so a bolus the pod took after a lost answer is still recorded. Deliberate
      difference from Android: before a delivery command whose 4-bit sequence equals the last one the
      pod reports, a status is read first (Android can take an old command for the new one there).
- [x] Step 5 (same, 2026-11-13): one link per BLE connection. `DashPodConnection` (commonMain) owns
      the link across connections: for each one a new `DashBleLink` and `MessageIO`, `prepare()`, the
      link given to the pump (`attachLink`), then the session from outer counter 1 when the pod is
      paired (Android `SharedBleManager.connect`). Events go to the current link only. `IosDashPump`
      and its `DashPodStateManager` live on: the 4-bit sequence, the EAP SQN and an open command stay,
      and an open command from before a drop still blocks new delivery commands until a status
      decides it. A command on a lost, closed or replaced link is refused and sends nothing. A failed
      link set up or session cancels the BLE connection (`BleCentral.cancel`, Android
      `disconnect(false)`); closing an old link never cancels a newer one. A replaced or closed link
      is retired (`DashBleLink.retire`): it writes nothing more, as Android sets `msgIO` to null.
      One at a time, as Android's busy flag: `connect`, every command, `pair` and `establishSession`
      hold the `commandMutex` of the pump. `connect` does not wait for it (`tryLockLink`) and returns
      `Busy`, as Android `BusyException`, so a connect from inside a command is refused, never a
      dead lock. `IosDashPump.runActivation` is Android `activationRunning`: while it runs, `isBusy()`
      is true and `disconnect()` is ignored (#5219), so the queue cannot break a pairing.
      Open: `DashPodConnection.connect` runs on a peripheral that is already connected; making the
      BLE connection (held connect, restore identifier) is phase 3. `IosDashPump.connect(reason)` does
      nothing until then, so after a drop every command is refused (fail closed) until the central
      owner connects again. When the manager is ported, connect before each operation as Android
      `observeConnectToPod`, outside the command lock, one after the other (also in the 2 s bolus
      wait loop). Android's `connectionAttempts` / `successfulConnections` counters (UI only)
      are not kept.
- [x] Step 6 (same, 2026-11-13): the activation fields in the stored state. `DashPodState` keeps
      what Android `updateFromVersionResponse` and `updateFromSetUniqueIdResponse` store: prime pulse
      rate, the two prime volumes, pod life in hours, firmware and BLE versions, lot, pod sequence
      number, the pod status of the answer (`reportedPodStatus`, Android `podStatus`; `podStatus`
      stays the full last status), and `lastUpdatedSystemMs` (Android `lastUpdatedSystem`). The first
      default status sets `activationTime` (an alarm status does not, as Android).
      `DashPodStateManager.expiryMs` is Android `expiry` (pod life - 8 h from the activation).
      `IosDashPump` now routes a Version answer to `updateFromVersionResponse` and a default status to
      `updateFromDefaultStatus`. The codec writes version 4 (v3 + the activation part); v1, v2 and
      the v3 bytes of 2026-11-13 are still read (pinned by hand), with no activation fields: nothing is
      guessed from the stored status. Truncated bytes, a trailing byte or an unknown activation flag
      fail closed. Tests: `DashPodStateCodecVersionsTest` and `DashPodActivationFieldsTest`
      (commonTest, 10 + 10). Not stored, as decided: time zone fields (Android ignores a time zone or
      DST change too), the alert settings (they come with the alerts, phase 2 step 7 of the next run).
- [x] Step 7 (same, 2026-11-13): a temp basal like Android. `setTempBasalAbsolute` cancels a
      running temp basal first (`observeNoActiveTempBasal`), decided on the last status known; with
      no status yet the cancel is sent, as Android. Cancel and new temp basal each have their own
      active command and outcome. The new one goes after a confirmed or a denied cancel, and is not
      sent when the cancel is not confirmed (it stays open, every delivery command is refused until a
      status) or never left the phone. Tests: `IosDashPumpTempBasalTest` (iosTest, 9: order and
      Android bytes, no status, lost answer to either command, NAK, send failure, denied cancel, an
      open command before). Deliberate difference: a temp basal or cancel sent but not confirmed is
      a failure (fail closed); Android reports success with a notification.
      Each active command keeps the time it was made (`createdAtMs`, Android `createdAt`, codec
      version 4), so an outcome decided late is still recorded at the send time.
      Open: `PumpSync` recording at send time (Android `pumpSyncTempBasal`) and the stored
      `tempBasal` of Android (`tempBasalActive`, the "nothing to cancel" shortcut of
      `cancelTempBasal`) are plugin work (phase 4); `observePodRunning` comes with the activation.
- [x] Step 8 (same, 2026-11-13): a bolus followed to its end. After a SUCCESS bolus,
      `DashBolusWait` (commonMain) waits as Android `waitForBolusDeliveryToComplete` (estimate, then
      up to 5 status reads every 2 s or after the time the rest should take) and shows the progress
      in `BolusProgressData`. A stop (`stopBolusDelivering`) only sets a flag; the wait sends the stop
      of the bolus. The last bolus is stored (codec version 4), so a crash during the wait keeps what
      is needed to count it; delivered = asked - left, as `LastBolus.markComplete`, and a confirmed
      stop gives `endedBolus` to `onCommandDecided`. Tests: `DashBolusWaitTest`, `DashLastBolusTest`,
      `IosDashPumpBolusTest`. Deliberate differences: no wait after a bolus that is not SUCCESS
      (except a stop asked during the send: a status is read first, as Android, and the stop is sent
      when the pod took the bolus), an ended bolus does not change, units left out of range count
      the whole bolus, a bolus the pod stopped itself is ended and counted. Review fixes: closing a
      command and changing the last bolus are one write (`resolveActiveCommand(nowMs)`), so a crash
      between them cannot lose the bolus.
      Open: reconnect inside the wait (phase 3), a stop whose answer is lost counts the whole bolus
      as Android (live or after a crash; never less than went in, pinned), `PumpSync` (phase 4). The
      reservoir check and the correction of a bolus a fault stopped came with step 12.
- [x] Step 9 (same, 2026-11-13): the basal program. `setNewBasalProfile` as Android: nothing
      before the activation is completed, a suspend first unless the last status shows delivery
      suspended (own active command, `SUSPEND_DELIVERY`), then `ProgramBasalCommand` built from
      `mapProfileToBasalProgram` (the Android mapper) with its own active command that carries the
      program. A confirmed program becomes the running one in the same write that closes the command,
      stored (codec version 4). `isThisProfileSet` as Android. Tests: `ProfileToBasalProgramTest`
      (Android mapper tests and an Android byte vector), `DashBasalProgramStateTest`,
      `IosDashPumpBasalProgramTest`. Deliberate differences: a refused program is a failure (Android
      reports success while the pod, just suspended, has no basal); a profile that cannot be mapped is
      a failure instead of an exception; a confirmed suspend (or stop of all delivery) ends a bolus
      still running, with what its answer left, and gives it as `endedBolus` (Android leaves it open,
      so the next status counts all of it). After review, `isSuspended()` reads the last status, as
      Android (it was always false). Open: `observePodRunning` (activation), `PumpSync` and
      `EventProfileChangeRequested` on a late success (phase 4), `baseBasalRate` and `deliveredBasal`
      from the program, time zone and DST changes ignored as Android.
- [x] Step 10 (same, 2026-11-13): the activation in two parts. `DashActivation` (commonMain) runs
      Android `activatePodPart1` and `activatePodPart2` in Android order with the Android values
      (version, unique id, low reservoir and lump of coal alerts, prime and its check, basal program,
      expiration alerts, cannula and its check), storing the `ActivationProgress` after each step,
      so a call after a crash or an error resumes there. `IosDashPump.activatePodPart1/2` run it as
      one activation; part 1 pairs a new pod on the link of the connection first (Android
      `pairNewPod`). `recoverActivationFromPodStatus` as Android (on `reportedPodStatus`), called by
      `getPumpStatus` before the activation is completed (which, as Android, reads only when the
      user asks, and never before SET_UNIQUE_ID), and only on a status that read stored (not after
      a NAK). Delivery commands are refused until the pod is activated and runs
      (`observePodRunning`); the stop of all delivery is not held back. Tests:
      `DashActivationTest` (JVM and iOS, including a crash after every write of a full activation),
      `IosDashPumpActivationTest` (Android bytes in Android order from `LinkReady`, crashes around the
      prime and the cannula). Deliberate differences: the prime and the cannula bolus are marked in
      the stored state before they go, and a later call reads a status before sending them again
      (only a real status decides, a NAK stops it), so neither goes twice (Android sends them again); the program the pod took is the running program
      from PROGRAMMED_BASAL on. Open: connect before each step (phase 3), alert settings stored
      (next step), history and `PumpSync` after part 2 (phase 4), the reservoir check of a bolus.
- [x] Step 11 (same, 2026-11-13): deactivation, suspend and resume, alerts, beeps.
      `deactivatePod` sends `DeactivateCommand` (also to a pod in alarm or half activated, as
      Android) and, when the pod took it, clears the whole pod state as Android `reset()` and closes
      the link; `discardPod` clears it without asking the pod, after giving a command still open to
      `onCommandDecided` (NOT_CONFIRMED with `podDiscarded`, to be counted as Android counts it). `suspendDelivery` is the Android
      `SuspendDeliveryCommand` (it sent a stop of all delivery before) and sets
      `suspendAlertsEnabled`; `resumeDelivery` sends the basal program to a suspended pod.
      `silenceAlerts`, `programAlerts`, `disableSuspendAlerts`, `updateAlertConfiguration` (the four
      Android alerts, the user settings stored and compared) and `playTestBeep`. Each goes through the
      active command, so a lost answer blocks every delivery command until a status. Beeps are a
      parameter (`DashBeepSettings`), all off by default, and fill the same fields as Android. Tests:
      `IosDashPumpSuspendResumeTest`, `IosDashPumpAlertsTest`, `IosDashPumpDeactivationTest`,
      `IosDashPumpBeepsTest`, `DashAlertsTest`, `DashAlertAndResetStateTest`. Deliberate differences:
      a refused deactivation forgets the pod only when the pod reports it delivers nothing (ALARM or
      DEACTIVATED), so a running pod is never forgotten next to a new one; a confirmed resume becomes
      the running program; a confirmed deactivation ends a running bolus with what was left; alert
      settings are stored only when the pod confirmed them. Open (phase 4): the queue commands, the
      user settings for beeps and alerts, `disableSuspendAlerts` after a confirmed resume or basal
      program, `PumpSync` and history.
- [x] Step 12 (same, 2026-11-13): the status. `getPumpStatus` reads the status, decides an open
      command, runs `checkPodKaput` and takes the flows from the state (`lastDataTime`, last bolus,
      `reservoirLevel`), as Android. A faulted pod answers with an alarm status: it is stored as a
      status and its alarm type is kept (the first one, as Android). A pod in ALARM or DEACTIVATED ends
      the last bolus with what it had left and gives it to `onCommandDecided` as
      `DashBolusEndedByFault` (asked - left, at the time of the bolus command), once, also when the
      fault is first seen after a crash. `onCommandDecided` now takes the sealed `DashPumpOutcome`.
      A bolus larger than the reservoir is refused (Android). The temp basal the pod confirmed is
      stored (`DashTempBasal`, Android `tempBasal`), with `tempBasalActive()` and the "nothing to
      cancel" shortcut of `cancelTempBasal` (its `PumpSync` half is the `expectedTempBasalRunning`
      parameter, true until the plugin wires it). Codec version 4 extended in place. Tests:
      `DashPodFaultAndTempBasalStateTest`, `IosDashPumpStatusTest`, codec tests. Deliberate difference:
      a bolus decided SUCCESS by the status of a kaput pod takes the pulses left from it (Android
      records 0 U for a bolus the pod partly gave). Review fixes: the reservoir keeps the last reading
      below 1023 (`DashPodState.pulsesRemaining`, stored, Android `pulsesRemaining`), so a later 1023
      or an alarm status no longer shows 75 U and lets through a bolus Android refuses; the receiver
      contract of `DashBolusEndedByFault` and `endedBolus` says one record is corrected (key: the
      command time of the SUCCESS outcome), never a second bolus. Open (phase 4): the PUMP_SUSPEND temp basal for a
      kaput pod, the fault notification (`alarmSynced`), `pdmRef`, `verifyPumpState`, the history,
      `baseBasalRate`.
- Still to do before a pod: call `getPumpStatus` from the queue/plugin, beeps and alert settings
  from the user settings.

- [x] Deactivation, suspend and resume, alerts and their silencing, beeps (step 11 above).
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
