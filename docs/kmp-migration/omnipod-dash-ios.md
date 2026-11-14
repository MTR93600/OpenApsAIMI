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

- [x] Code and fakes (2026-11-13, not on a device): scan for pairable pods (`DashPodScanner`, the
      Android `PodScanner` rules: Dash service filter, 5 s, 9 service UUIDs, pod id ffff fffe, exactly
      one), then a connection held open as OmniBLE does (`DashHeldConnection`): a `connect` without
      timeout, made again after every drop while the connection is wanted (from `IosDashPump.connect`
      to `disconnect` / `stopConnecting`), a new link and session on each connection, set up again
      after `Busy`, and CoreBluetooth state restoration with its own identifier
      (`BleRestoreIdentifier.OMNIPOD_DASH`): the restored pod is connected again, other peripherals
      released. Bluetooth off marks the link lost. After a failed set up the next connect waits 10 s.
      The activation scans and pairs inside the activation (Android `pairNewPod`); every operation
      waits for the connection first (Android `observeConnectToPod`). A deactivated pod is forgotten.
      `IosDashBleHost` (iosMain) is the thin CoreBluetooth glue. Tests: `DashPodScannerTest`,
      `DashHeldConnectionTest` (JVM and iOS), `IosDashPumpHeldConnectionTest`.
      After the review (2026-11-14), as Android: `isConnected()` is `!isPodRunning || session`; the
      pod state is checked before the connect (no BLE contact for a command the state refuses); a
      failed part 1 before the pairing stops the held connect (Android `disconnect(false)`, so the
      next scan finds the pod again); the stored pod address is never replaced by another one
      (Android setter throws); the bolus wait connects again before each status read and each stop
      (`awaitConnectionHoldingLock`, with the lock the wait holds), so a stop after a drop reaches the
      pod; the connector is built last in `IosDashPump`, after every other field.
- [ ] On a device: a recorded pod advertisement, the `didConnect` answer to a connect on a restored
      peripheral that is still connected, background wake by the pod, the xcodebuild of the app.
- [ ] Android's connection counters (`incrementSuccessfulConnectionAttemptsAfterRetries`,
      `incrementFailedConnectionsAfterRetries`, `successfulConnections`): statistics for the Dash
      overview only, no dosing effect. Not kept; add them with the overview screen (phase 5), in the
      stored state.

### 4. The iOS plugin

The plugin turns `IosDashPump` into a pump the iPhone can choose and the queue can drive. Android
(`OmnipodDashPumpPlugin`) is the reference for every rule. Three steps, each green on the JVM and the
iOS simulator before the next one.

Decisions for the whole phase (taken with the owner, do not ask again): parity with Android first;
fail closed; beeps and alert settings read the same preference keys with the same defaults as
Android; `PumpSync` records at the same moments with the same pump type and serial as Android; a late
correction updates the record that exists, never adds a second one; the iPhone is a full master
phone, never AAPSClient. Android keeps `:pump:omnipod:dash` unchanged.

- [x] **P1. The plugin, selectable in the config builder.** Done 2026-11-14 (not committed, not on a
      device, xcodebuild not run by the builder). In `dashctl` iosMain
      (`ios/plugin`), the shape of `IosMedtrumPlugin`:
      - `IosOmnipodDashPlugin`: `PumpPluginBase`, `@ContributesIntoMap` with key 1080 (the Android
        Dash key), so it is offered in the pump list like Medtrum. Name "Omnipod Dash" (brand, not
        translated). `Pump` is delegated to `IosDashPluginPump`.
      - `IosDashDriverHost`: owns the Keychain state store, `DashPodStateManager`, `IosDashBleHost`,
        `IosDashPump` and the `PumpSync` recorder, and their lifecycle. Building it does not open the
        radio (the central opens in `openRadio`, called by `onStart`), so building the graph never
        asks for the Bluetooth permission.
      - `IosDashPluginPump`: the `Pump` answers that Android gives from the plugin and not from the
        manager: `PumpDescription().fillFor(OMNIPOD_DASH)`, `isInitialized` = `isPodRunning`,
        `isConfigured` true (Android default), `serialNumber` with the Android "4241" fallback,
        `baseBasalRate` and `deliveredBasal` from the confirmed program, extended bolus, percent temp
        basal and TDD refused, `timezoneOrDSTChanged` logs only. Everything else is the driver.
      - `DashPumpSyncRecorder`: every outcome of `onCommandDecided` to `PumpSync` (see the table in
        the code): a confirmed bolus at the asked amount, a confirmed temp basal, the stop of a temp
        basal, the PUMP_SUSPEND temp basal of a confirmed suspend and its stop by a confirmed basal
        program or resume, the correction of a bolus that was stopped or ended by a fault (same
        record), and the commands left open when a pod is discarded (bolus at the asked amount, temp
        basal as started).
      - Beeps from the Android keys (`AAPS.Omnipod.bolus_beeps_enabled`, ...) and defaults;
        `expectedTempBasalRunning` from `PumpSync.expectedPumpState()`.
      - Registered in `IosProbeGraph` (graph test `IosOmnipodDashPluginGraphTest`), and through the
        contribution in `IosAppGraph`.
      Deliberate differences from Android, each with a test (the first one was removed in P2, on the
      owner's order: records are now written when sent, as Android): ~~records are written on the
      confirmed outcome, not when sent~~; the pump id is the command time (no pod history on iOS); after
      an app restart an open bolus or temp basal decided late is written as NORMAL (amount and time
      right); a correction with no record known writes nothing (counted in full, the over-count side);
      a bolus with carbs or no insulin is a failure (Android throws); a stop is never written for a
      discarded pod. `IosDashBleHost` no longer opens the central in `connectorFactory`, only in
      `open()`. Tests: `DashPumpSyncRecorderTest` (12), `IosDashPluginPumpTest` (15, fake pod),
      `DashBeepKeyTest` (3, JVM and iOS), `ProfileToBasalProgramTest` +1 (`hourlyRates`, Android
      port), `IosOmnipodDashPluginGraphTest` (6: probe graph, app graph plugin map key 1080 next to
      Medtrum 1120, not initialized with no pod). 503 tests on the JVM, 668 on the iOS simulator
      (1 skipped, the known pin), ios/shell 109, 0 failures.

      After review (same day), five findings, all fixed with a test that was red first:
      - The plugin is `OmnipodDash`, as Android. Without it the shared setup wizard
        (`SWDefinition.screenPump`, not skippable) kept the user on its pump screen with Dash and no
        pod, and showed "Read status".
      - The no-pod temp basal of the Android status checker (`createFakeTBRWhenNoActivePod`) is
        ported now, not in P2, because the plugin can be chosen now: every minute, with no running
        pod, a 0 U/h PUMP_SUSPEND temp basal of 80 h with serial "4241", unless a zero one runs
        (`writeNoPodTempBasal`, `DashNoPodTempBasalTest`). The rest of the status checker stays in P2.
      - The config builder shows a caution (`omnipod_dash_ios_caution`): a pod cannot be activated
        from the iPhone yet and the loop does not run. It lives in `:core:interfaces` strings until
        dashctl has its own string table: making dashctl a string owner now would register it in
        every shell, so the Android app and desktop would need dashctl. P3 moves it and changes the
        text once a pod can be activated.
      - A refused bolus or temp basal call no longer gives its type to the earlier command that is
        still open (`IosDashPluginPump.commandMadeSince`, and the recorder never replaces a type it
        keeps).
      - A late bolus older than the `PumpSync` registration of the pump (`confirmActivePump` takes
        one minute back after `connectNewPump`) was dropped, and insulin that went in was not counted.
        It is now written again at the time it was decided, same pump id (counted later than it went
        in: more insulin on board, never less), and its correction goes to that time. Temp basals
        need nothing: `PumpSync` starts them at the registration itself. P3 must still write the
        registering therapy events at activation, as Android.
      Now 675 tests on the iOS simulator (1 skipped), 503 on the JVM, ios/shell 111, 0 failures.
- [x] **P2. What the plugin records, queue commands, alerts and notices.** Done 2026-11-14 (not
      committed, not on a device, xcodebuild not run by the builder).
      - `PumpSync` as Android, at the same moments with the same calls (owner order: P1's "write on
        the confirmed outcome" is removed). `IosDashPump.onCommandSent` (new, Android
        `isCommandSent()`: after `CommandSendSuccess` or `CommandSendErrorConfirming`, before the answer
        is read) gives the open command to `DashPumpSyncRecorder.onCommandSent`, which writes the bolus
        at the asked amount with its type (`pumpSyncBolusStart`), the temp basal with its type
        (`pumpSyncTempBasal`) and the PUMP_SUSPEND temp basal of a suspend. The confirmation
        (`onCommandDecided`, at once or late) then: a refused bolus is set to 0 U on the same record
        (type kept), a refused temp basal or suspend is invalidated, a confirmed stop of the temp
        basal, basal program or resume ends the temp basal at the command time, a stopped bolus or one
        a fault ended is corrected on the same record. A late SUCCESS adds nothing. Pump id = command
        time, pump type and serial as Android. The P1 rewrite of a late bolus at its decision time
        (registration window) is gone: with the record written at send it is not needed.
      - One deliberate difference, with tests: a SUCCESS (or a pod discarded while open) of a bolus,
        temp basal or suspend whose send record was not written in this run (the write threw, or the
        app stopped between the write and the record) writes it then, same call, time and pump id.
        Android writes nothing when the write throws and clears the command as a send failure, so a
        bolus the pod may have taken is never counted. After a restart where the record does exist,
        `PumpSync` finds it by its pump id and updates it with the same values: still one record.
      - `DashPluginRules` (iosMain, `ios/plugin`), the rest of Android `OmnipodDashPumpPlugin`:
        the queue commands in `dashctl/queue/command` (same list and status texts as Android;
        `CommandDisableSuspendAlerts` has an all caps literal) mapped as Android
        (`IosDashPluginPump.executeCustomCommand`; `CommandSuspendDelivery` and the basal correction
        fail as unsupported); `CommandDisableSuspendAlerts` queued after a confirmed resume or basal
        program, with the notices Android dismisses; `EventProfileChangeRequested` after a basal
        program confirmed while the queue does not run BASAL_PROFILE (not inside a time change, Android
        SET_TIME); `verifyPumpState` and the plugin part of `checkPodKaput` (PUMP_SUSPEND temp basal,
        fault notice with sound unless a deactivation is queued, announcement, `alarmSynced`) after
        every command and status read; the notices of a command not confirmed (uncertain SMB, bolus
        alarm, TBR, cancel, suspend, resume) with the four sound settings; the status checker (status
        read for an open command with an idle queue, "no active pod" / "suspended" every 15 min, the
        no-pod temp basal); the alert settings (`DashAlertBooleanKey`, `DashAlertIntKey`, Android keys
        and defaults, kept in the Android limits) watched in `onStart`; what the wizard does around the
        two activation parts (`activatePodPart1`, `activatePodPart2`: settings in, then the end of the
        "4241" temp basal, `connectNewPump`, CANNULA_CHANGE and INSULIN_CHANGE with the pod serial, a
        status read) and `discardPod`, ready for the P3 screens.
      - Texts (notices, alarm title) are English strings in `:core:interfaces`, next to the caution of
        P1, until dashctl has its own string table (P3).
      Tests: `DashPumpSyncRecorderTest` (20), `IosDashPluginPumpTest` (36, fake pod, rules wired),
      `DashPluginActivationTest` (2, fake pod being activated), `DashAlertKeyTest` (4, JVM and iOS).
      Fixes after the adversarial review (2026-11-14, each with a test that failed first):
      - a decided deactivation runs `checkPodKaput` before the driver clears the state, so the
        PUMP_SUSPEND temp basal is written with the pod serial at once, as Android;
      - a bolus ended by a fault (`DashBolusEndedByFault`) runs `checkPodKaput` at once, also inside
        the bolus wait, as Android does at each status read of the wait;
      - the time change gives the "suspend not confirmed" notice (`DashPluginRules.afterBasalProgram`);
      - the stop of an unexpected temp basal in `verifyPumpState` gives the "cancelling the temp basal
        might have failed" notice, as Android `observeNoActiveTempBasal`;
      - `getPumpStatus` with a reason other than the user's, before the activation is completed, runs
        no check at all, as Android (no false "TBR out of sync" alarm on a half activated pod);
      - a bolus correction with no command time (codec version 3) writes nothing: the old fallback to
        the last bolus written could change the record of another bolus.
      Tests now: `DashPumpSyncRecorderTest` (20), `IosDashPluginPumpTest` (43).
      Not ported (pinned by tests, KDoc in `DashPluginRules`): `verifyPumpState` inside the bolus wait
      (it may stop a temp basal on the pod, not possible while the bolus call holds the lock); the PDM
      reference in the fault text (no `pdmRef` in the iOS state). Moved to P2b below: basal drift
      correction and the time zone notice.
- [ ] **P2b. Basal drift correction and pod time zone.** Both need new stored fields and a new codec
      version. Port Android `needsBasalCorrection`, `basalExpected`, `bolusPulsesDelivered`,
      `integrateExpectedDelivery`, `lastBasalCorrectionTime` and `deliverBasalCorrection`
      (`CommandDeliverBasalCorrection`, queued after a confirmed temp basal or cancel), and the pod
      time zone offset with the OMNIPOD_TIME_OUT_OF_SYNC notice of `updatePodWarnings`. Until then the
      iOS loop does not make up basal drift (Android does, in 0.05 U pulses) and gives no notice when
      the pod time zone differs from the phone. The pin tests `a confirmed temp basal or cancel queues
      no basal correction because it is not ported yet` and `a running pod gets no time zone notice
      because the pod time zone is not stored yet` must change with the port.
- [ ] **P3 (phase 5). Screens.** Activation and deactivation wizards in Compose Multiplatform, with
      what Android does after part 2 (`connectNewPump`, stop of the fake PUMP_SUSPEND temp basal,
      CANNULA_CHANGE and INSULIN_CHANGE therapy events, status read) and after a discard; the
      overview; the settings screen (beeps, alerts, notifications) with English strings in a string
      table of the module, and the config builder caution moved there from `:core:interfaces` with a
      text that fits once a pod can be activated.

### 5. Screens

- [ ] Activation and deactivation wizard, overview, in Compose Multiplatform.

### 6. On a real pod

There is no Dash emulator. Phases 1 to 5 are tested off line (JVM and iOS simulator, Android vectors,
OmniBLE test vectors); phase 6 uses real pods, one per activation.
