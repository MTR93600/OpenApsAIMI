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
- [x] **P2b. Basal drift correction and pod time zone.** Done in P4 below.
- [x] **P3 (phase 5). Screens.** Done 2026-11-14 (not committed, not on a device, xcodebuild not run
      by the builder). Compose Multiplatform in `dashctl` commonMain (`ui/`), the way the Medtrum patch
      wizard is in `:pump:medtrum`:
      - `DashOverviewContent` (`ComposablePluginContent`, the plugin entry opens it through
        `PluginDescription.composeContent`, as Medtrum): the rows of Android `DashOverviewViewModel`
        (`dashOverviewRows`: address, id, LOT, sequence, firmware, expiry and hard end with the #5365
        colours, pod status and its level, last connection, last bolus (an open one "uncertain", red),
        base basal, temp basal, reservoir, total delivered, alerts, fault), the buttons with the
        Android rules (`dashOverviewButtons`: refresh, silence alerts, resume delivery, activate,
        deactivate, test beep, discard), the communication banner and queue status of the shared
        `PumpCommunicationStatus`, the discard confirmation and the error window of a failed command.
        While no command runs, the banner shows the iPhone caution.
      - The wizard: `DashPodWizardModel` (state, commonMain, the counterpart of
        `OmnipodWizardViewModel` + `DashOmnipodWizardViewModel`) and `DashPodWizardScreen` (the
        counterpart of `OmnipodWizardScreen` and its steps). Same pages and order
        (`dashActivationSteps`: profile gate, fill, insulin, part 1, site, attach, part 2, activated;
        deactivation: start with confirmation, deactivate, deactivated or discarded). SHORT from
        PRIME_COMPLETED on (`dashActivationType`), so an activation stopped by a crash goes on from the
        stored progress. One action at a time; Next only after a success; Retry and Cancel with the
        driver's reason after a failure; a failed deactivation offers the discard.
      - The pod side of the wizard is `IosDashWizardPod` (iosMain): part 1 and part 2 straight to
        `DashPluginRules` (as Android calls the manager), the deactivation through the queue
        (`CommandDeactivatePod`, as Android), the discard. The app side is `DashWizardAppHost`
        (profile gate, insulin switch, site location, the Android calls).
      - Strings: `dashctl` now has its own table (`src/androidMain/res/values/strings.xml`, generated
        to `DashStrings` by `generateDashStrings`). It is not in `StringOwnerModules` (every shell would
        then have to link dashctl); `IosDashDriverHost` registers the owner at run time
        (`registerDashStrings`). The P1/P2 texts moved there from `:core:interfaces` (same names); the
        config builder description is now the Android one, with the Android icon (`IcPluginOmnipod`),
        and the caution text (rewritten: a pod can be activated now, but it is not tested on a real
        pod) is the banner of the pump screen.
      Deliberate differences from Android: ~~no pod history, no "Set time", no connection quality
      row, no time on pod row, no PDM fault text~~ (all added in P4), no "Bluetooth bonding" switch
      (iOS decides bonding, the app cannot); after the profile gate the wizard waits (at most 1 min) until the queue has set the profile, as
      `MedtrumPatchWizardModel`, so part 2 finds a basal program. Tests: `DashWizardStepTest`,
      `DashPodWizardModelTest`, `DashOverviewTest` (commonTest, JVM and iOS), `DashPodEndToEndTest`
      (iosTest: no pod and the "4241" temp basal, the activation through the wizard model on the
      simulated pod, then a bolus, a temp basal, a cancel and a profile through the pump calls of the
      queue commands, each once in `PumpSync`, then the deactivation through the wizard and the queue
      and the zero temp basal), `IosOmnipodDashPluginGraphTest` (description, icon, pump screen,
      texts resolve).
      Review fixes (2026-11-14): the wizard work runs on `IosDashDriverHost.wizardScope` (process
      lifetime; the model state on the main thread, the pod work on the IO dispatcher since the parity
      audit below, as Android subscribes it on `aapsSchedulers.io`), so leaving the screen cannot stop a part or
      skip the PumpSync records after it; the wizard gets the toolbar (`setToolbarConfig`, no back
      arrow while it runs, as Android) and the pump screen sets its toolbar again after it; Finish on
      "Pod Activated" closes only after the insulin switch and the site save are done; the profile
      gate Activate runs once at a time; the iPhone does not lock during the wizard
      (`KeepScreenOnEffect` of `:core:ui`, shared with Medtrum since 2026-11-18: `idleTimerDisabled`, the value from before put back); the settings screen
      (`dashSettingsScreen`: beeps, alerts, notification sounds; the keys are now preference keys with
      the same keys, defaults, limits and switches as Android). Tests: `DashSettingsTest`,
      `ScreenAwakeTest` (now in `:core:ui`), three more in `DashPodWizardModelTest`, the settings check in
      `IosOmnipodDashPluginGraphTest`.
      The e2e test on the real `CommandQueueImplementation` is written (P4, item 6, below). Still open:
      a real pod.
- [x] **P4. The parity gaps P1-P3 left.** Done 2026-11-14 (not committed, not on a device, xcodebuild
      not run by the builder).
      - Basal drift correction (this fork): `DashBasalDrift` (commonMain) is Android
        `integrateExpectedDelivery` and `calculateBolusPulseIncrease` as they are; the state keeps
        `bolusPulsesDelivered`, `basalExpected`, `lastBasalCorrectionTime` and updates them at each
        status in the order of Android `updatePodState` (`DashPodStateManager.statusApplied`), with
        the transient `basalCorrectionInProgress`. `needsBasalCorrection(now)` has the same rules and
        limits (-0.025 U, reset at +-0.1 U, 2 min cooldown, zero temp basal unless a bolus < 5 min).
        `CommandDeliverBasalCorrection` is queued after a confirmed temp basal (unless one waits) or
        cancel (`DashPluginRules.onOutcome`) and runs `IosDashPump.deliverBasalCorrection`: one silent
        0.05 U bolus through the open command rule, marked `basalCorrection` on the active command and
        the last bolus, never written to `PumpSync` (send, refusal, stop and fault), as Android.
      - Pod time zone: `timeZoneId`, `timeZoneOffsetMs`, `timeZoneUpdatedMs` stored when the pod
        answers a basal program with a status (activation part 2, profile, time change, resume), as
        Android `updateTimeZone`; `sameTimeZone`, `podTimeMs`, `timeDriftMs`; the
        OMNIPOD_TIME_OUT_OF_SYNC notice in `updatePodWarnings`. Time zone and DST changes are still
        ignored, as Android (`timezoneOrDSTChanged`); "Set time" is the user's way to fix it.
      - PDM reference: `AlarmType` is now a full copy of the Android enum (names, values, `code`,
        `pdmFaultCategory`, `PdmFaultCategory`); `AlarmStatusResponse.pdmRef` as Android; stored with
        the first alarm (`pdmRef`); in the fault notice and announcement
        (`omnipod_dash_ios_pod_fault_with_ref`) and in the "PDM Notification" row. Android
        `AlarmStatusResponseTest` vectors unchanged (`AlarmStatusResponsePdmRefTest`).
      - `verifyPumpState` inside the bolus wait: not done, on purpose. Its second branch stops a temp
        basal on the pod, which needs the command lock the bolus call holds for the whole wait. The
        reason is in the KDoc of `DashPluginRules`. The fault part already runs at once.
      - Screens: connection quality row (counters as Android: one success per queue `connect` that
        ends with a session, or that finds one already up (`AlreadyConnected`); a new session the held
        connection makes by itself inside the same connect, or the reconnect inside the bolus wait, is
        not counted; one failure per `stopConnecting`. The counts are written with the command lock
        held, at the next session or command, never from the queue thread: `DashPodStateManager` is
        not thread-safe and a session set up writes the EAP SQN at the same time. The basal correction
        check and cooldown are also done with the lock held, for the same reason), time on pod row (the zone shown by its
        id, Android shows a short display name), "Set time" button (`CommandHandleTimeChange(true)`),
        PDM notification row, "Pod History" (`DashPodHistory` + `DashPodHistoryScreen`, kept in the
        user defaults by `IosDashHistoryStore`: one record per delivery command when sent, decided by
        the pod, what an early-ended bolus gave, and the activation parts and discard; status reads
        and SET_TIME are not kept, at most 5 days and 2000 records; no filter chips).
      - Codec: the new fields are a step P4 part at the end of version 4. Test phones on the P3 build
        (aa241bd32e) had stored version 4 without it, so a version 4 state that ends right after the
        reservoir reading reads with every P4 field empty (the pod key is kept); a P4 part cut short
        still fails. The v3 pin is unchanged; the v4 pin has the new part, and the P3 bytes are pinned
        too.
      Tests: `DashBasalDriftTest` (Android `IntegrateExpectedDeliveryTest` and
      `CalculateBolusPulseIncreaseTest`), `DashPodStateDriftTest` (Android `NeedsBasalCorrectionTest`,
      `UpdatePodStateBolusTrackingTest`, time zone, PDM reference, counters),
      `AlarmStatusResponsePdmRefTest`, `DashPodHistoryTest`, codec tests, `DashOverviewTest`
      (+5), `IosDashPluginPumpTest` (the two pin tests replaced by 10 real ones),
      `DashPumpSyncRecorderTest` (+1). JVM 626, iOS simulator 845 (1 skipped), ios/shell 114, 0
      failures.
      **The end-to-end test on the real `CommandQueueImplementation`** (item 6 of P4): written
      2026-11-14 (not committed, not on a device). `DashCommandQueueEndToEndTest` in `:ios:shell`
      runs a pod's whole life through the real app: the plugin list started as `IosAppStartup` starts
      it, Dash selected with `confirmPumpPluginSwitch`, a profile switch set by the queue with no pod,
      the "no pod" temp basal, activation part 1 and 2 as the wizard calls them, then through the real
      queue and its queue thread an SMB, a bolus, a temp basal, its cancel, a profile change and the
      deactivation. The records are read back from a real database (`PumpSyncImplementation`): one per
      command, the time the command was made as pump id, Dash and the pod serial; the no-pod temp
      basal after the deactivation. The queue also disconnects after its 5 s wait and connects again
      for the SMB. About 13 s real time.
      How it is built: the simulated pod is a module of its own, `:pump:omnipod:dashctl:emulator`
      (`FakeDashPod`, `FakeDashPodCentral`, `HeldPodCentral`, `ActivatingPodModel`, the new
      `RunningPodModel` and `SimulatedDashPod`, the `parity*` crypto with its JVM and iOS actuals;
      public now). dashctl's tests use it too; `DashPumpOnFakePod` keeps its knobs, delegated to
      `RunningPodModel`. Not linked into the framework (`checkMigratedModules` leaves it out). The
      host takes `IosDashPlatform` (radio, pod state store, history store, timer of the driver
      waits); the app binds `CoreBluetoothDashPlatform` (CoreBluetooth, Keychain, user defaults,
      `delay`: what the host built itself before, unchanged). The test graph `DashQueueTestGraph`
      excludes it and `IosPlatformBindings` (only for `UNUserNotificationCenter`, which stops a test
      binary; its other bindings are given by the same functions) and gives its own. The plugin list
      of the test has no other radio: Nightscout is the BG source (the ONE+ source opens CoreBluetooth
      with state restoration, which a test binary may not do) and Medtrum is left out.
      Still open: a real pod.

- [x] **Parity audit fixes (2026-11-14, second round).** Five auditors compared the iOS plugin with
      Android; the fixer checked each finding against both sides. Not committed, not on a device,
      xcodebuild not run.
      - **Unreadable Keychain is "state unknown", not "no pod"** (blocker, dosing). iOS can start the
        app for a BLE event before the first unlock after a reboot; the Keychain read then fails
        (`errSecInteractionNotAllowed`). Before, that looked exactly like "no pod": the status checker
        wrote the 80 h zero PUMP_SUSPEND temp basal while the pod gave its basal (insulin on board too
        low), and a new activation or a discard deleted the key of the pod on the body. Now:
        `DashPodStateStore.read()` gives `Loaded`, `Empty` (only `errSecItemNotFound`) or `Unreadable`
        (any other status, no data, bytes that do not decode; logged, the item is not touched);
        `DashPodStateManager.isStateKnown` is false for `Unreadable` and every write is refused
        (`DashPodStateUnknownException`), so nothing can replace the stored item; `IosDashPump` refuses
        every pod command, the activation, the status read and the discard with nothing sent
        (`refusedStateUnknown`), reading the store again first; `DashPluginRules.statusCheck` does
        nothing then (no "no pod" temp basal, no "no active pod" notice), posts an urgent PUMP_ERROR
        notice (`omnipod_dash_ios_pod_state_unreadable`) and logs; the read is tried again by every
        command, every status check (1 min) and when iOS posts
        `UIApplicationProtectedDataDidBecomeAvailable` (observer in `IosOmnipodDashPlugin.onStart`).
        A save no longer deletes first: `SecItemUpdate`, then `SecItemAdd` only for "not found", both
        results checked and logged. A discard that is refused shows its reason (wizard and pump
        screen). Tests: `DashPodStateUnknownTest` (commonTest), `DashKeychainCodecTest` (+4, the status
        table), `DashPodStateUnknownPluginTest` (iosTest, 4: no temp basal, every command refused, no
        activation or discard writes, back to normal after the unlock). Left: the pump screen still
        shows "No active pod" and the Activate button while the state is unknown (the activation is
        refused with the reason when pressed); a write of the state that some path makes in memory
        before its refused save stays in memory until the next successful read replaces it.
      - **Wizard pod work off the main thread** (parity). `IosDashWizardPod` runs part 1, part 2,
        the deactivation and the discard on the IO dispatcher (`podContext`), as Android
        `subscribeOn(aapsSchedulers.io)` and `rxSingle(Dispatchers.IO)`; the model and its screen
        state stay on the main thread. Before, the 5 s scan, the connect (up to 30 s), the pairing and
        every activation command blocked the main thread. Test: `DashWizardPodThreadTest`.
      - **Lost bolus answer** (dosing). A bolus whose answer is lost is now decided by a status read
        with or without a stop (`decideOpenBolus`), as Android's wait reads the status; when the pod
        took it, it is followed to its end; when no status can be read, the result is Android's
        success with the asked amount (it is already in `PumpSync` from the send), so the queue saves
        the carbs of a meal bolus and the user is not told to bolus again; the command stays open, so
        nothing more is sent until a status decides it. Before, the result was a failure: the carbs
        were dropped while the insulin was counted. Tests: 3 new and 1 changed in
        `IosDashPumpBolusTest`; the discard test now opens its bolus with a lost link.
      - **Stop during the connect of a bolus** (parity). `bolusDeliveryInProgress` is set before the
        connect, in `deliverTreatment` and `deliverBasalCorrection`, as Android; a stop pressed while
        the link comes back is kept and no bolus is sent. Test in `IosDashPumpHeldConnectionTest`.
      - **Deactivation with an open command** (parity, with a safety part kept). Android sends it
        anyway (`checkNoActiveCommand = false`). Here a status is read first in the same call; it
        decides the open command (so its outcome is counted), then the deactivation goes. When no
        status can be read, it stays refused (the pod would not get it either). Tests: 2 in
        `IosDashPumpDeactivationTest` (one replaces "an open command blocks the deactivation").
      - **Connect again after the prime and cannula waits** (parity, Android `observeConnectToPod`).
        `DashActivationPod.connectAfterWait`, which the pump does with the lock held
        (`awaitConnectionHoldingLock`). Tests: 2 in `IosDashPumpHeldConnectionTest`.
      - **Late "Set time" program asks for no profile change** (parity). The program of a time change
        its call left open is kept in memory (`DashPluginRules.openTimeChangeProgram`) and its late
        SUCCESS sends no `EventProfileChangeRequested`, as Android SET_TIME. After an app restart the
        memory is gone and a late SUCCESS asks for the profile once (the queue finds it set). Test in
        `IosDashPluginPumpTest`. (Two auditors found the same thing.)
      - **Basal program failure text** (minor). A program that stays open gives Android's
        `setting_basal_profile_might_have_failed` text (`omnipod_dash_ios_basal_profile_might_have_failed`).
        Android's other text ("Delivery suspended") is never shown by Android: its flag is read when
        the chain is built, before the suspend runs, so it is always false. Test in
        `IosDashPluginPumpTest`.
      - **Overview discard on the process scope** (minor). `IosDashDriverHost.discardPodFromScreen`
        runs it on `wizardScope`; closing the screen no longer drops it.
      - **Reservoir colour on insulin units** (minor). `DashOverviewFormat.fromPump` (iOS:
        `ConcentrationHelper.fromPump`), as Android `buildReservoir`. Test in `DashOverviewTest`.
      - **Pod history lock** (minor). `DashPodHistory` holds an `AapsLock` around each change and its
        save. Test in `DashPodHistoryTest` (4 threads at once).

### 4b. Dose accounting taken from OmnipodKit (Trio), 2026-11-14

A comparison with the Swift driver of Trio (`loopandlearn/OmnipodKit`) found places where Android
counts insulin wrong or loses what the user did. The owner approved these deliberate differences from
Android. Each one has a KDoc line that says why and tests. Android `:pump:omnipod:dash`, the Android
test vectors and the pinned parity bytes do not change.

- [x] **Assume more insulin when an outcome is not known** (OmnipodKit
      `PodState.resolveAnyPendingCommandWithUncertainty`). `dashCountAsTakenWhileUnknown`
      (`DashActiveCommand.kt`), `DashPodStateManager.countAsTakenWhileUnknown`, used by
      `DashPumpSyncRecorder`. A temp basal below the scheduled rate (a zero one always) and a suspend
      are no longer written to `PumpSync` when they are sent, nor when the pod is discarded with them
      open; their SUCCESS writes them at the command time with the same pump id. A bolus and a temp
      basal not below the schedule stay written at send, as Android. A cancel of a low temp basal left
      open when the pod is discarded writes the stop at its command time; a cancel of a high one writes
      nothing (the high rate stays counted). A resume or a basal program left open when the pod is
      discarded writes the stop at its command time too (OmnipodKit `.resume`: the basal may run
      again), unless a temp basal not below the schedule still runs on the pod. With no basal program
      known, a low temp basal is counted as Android. Tests: `DashDoseAccountingTest`, `DashPumpSyncRecorderTest`, `IosDashPluginPumpTest`.
- [x] **A lost answer decided by the delivery status** (OmnipodKit `checkCommandAgainstStatus`).
      `DashCommandConfirmation.of` / `deliveryShowsTaken`: before DENIED on a sequence that does not
      match, a sent bolus is taken when the status shows a bolus running (and no followed bolus still
      runs), a temp basal when a temp basal runs, a resume when the basal runs. Stops, suspends and
      basal programs keep the sequence check only. Tests: `DashDoseAccountingTest`,
      `IosDashPumpBolusTest`; `DashAlertAndResetStateTest` (a refused resume) now reports a suspended
      pod, which is what a refused resume leaves.
- [x] **No new temp basal after a refused cancel** (OmnipodKit `enactTempBasal`).
      `IosDashPump.cancelRunningTempBasalFirst(newTempBasalFollows = true)` returns a failure on DENIED.
      The stop of a temp basal not expected (`cancelTempBasalNotExpected`) still goes on as Android.
      Tests: 2 in `IosDashPumpTempBasalTest` replace the two Android-order tests.
- [x] **The zero temp basal of a fault at the fault time** (OmnipodKit `setDeliveryStoppedAt` with
      `faultEventTimeSinceActivation`). `DashPodState.faultTimeMs`, from the first alarm status (alarm
      minutes on the clock of that same status; 0, 0xffff or later than the pod clock is not valid).
      `DashPluginRules.checkPodKaput` starts the PUMP_SUSPEND temp basal there, else now; never at or
      before the start of the temp basal running in AAPS (`PumpSync` would not end it). Tests:
      `DashDoseAccountingTest`, `IosDashPluginPumpTest`. The emulator alarm status has an alarm time
      (`RunningPodModel.faultAlarmMinutes`, 0 by default, so its bytes are as before).
- [x] **A bolus the pod reports that the app does not track** (OmnipodKit `updateDeliveryStatus`).
      `DashPodStateManager.updateFromDefaultStatus` makes it the last bolus of the units left, with a
      stored mark (`DashPodState.untrackedBolusToRecordAt`); `IosDashPump` gives it as
      `DashUntrackedBolus`; the recorder writes one bolus with the status time as the pump id and only
      then clears the mark (`DashUntrackedBolus.onWritten`, `DashPodStateManager.untrackedBolusWritten`).
      A write that throws keeps the mark and the next status writes it again; the same pump id makes a
      second write update the same record. A retry after a fault ended the bolus writes what went in. Only on a running pod
      whose activation is completed, with pulses left, while no bolus command is open and no followed
      bolus still runs. Known edge: a bolus running across an upgrade from codec version 3 (no stored
      last bolus) is counted again at the units left (more insulin, never less). Tests:
      `DashDoseAccountingTest`, `IosDashPumpBolusTest`, `DashPumpSyncRecorderTest`, `IosDashPluginPumpTest`.
- [x] **Codec**: one part added at the end of version 4, written only when one of its two fields is
      set, so a state without them has the same bytes as before and every older version 4 and version
      3 state still decodes (the pinned tests are unchanged). Tests in `DashDoseAccountingTest`.
- [x] **An answer must belong to the command** (OmnipodKit `BleMessageTransport.readAndAckResponse`,
      `MessageError.invalidSequence`). `Session.readAndAckResponse` ACKs the answer as before, then
      checks its 4-bit message sequence (command sequence plus one, mod 16) and its unique id (the one
      of the command; not checked for a command to `ffffffff`, the GetVersion and SetUniqueId of the
      activation). A mismatch throws `PodAnswerMismatchException`; `IosDashPump.sendCommand` treats it
      as no answer: the link is closed, the answer does not move the sequence and decides nothing, the
      command stays open until a status. The length and the CRC stay unchecked (Android TODOs; OmnipodKit
      says the Dash CRC is not understood). The emulator answers as before by default and has two knobs
      (`FakeDashPod.answerSequenceFor`, `answerUniqueIdFor`). Tests: `DashSessionEndToEndTest` (4),
      `IosDashPumpCommandTest` (2).
- [x] **A bolus and a temp basal decided on a status of this session** (OmnipodKit
      `PodCommsSession.bolus`, `OmniPumpManager.enactTempBasal`, both against 0x31 faults).
      `IosDashPump.refusedByDeliveryStatus`, called with the command lock before a bolus (after the
      "stop asked" check) and before the cancel of a temp basal. A status is read first when the stored
      one is missing, has an unknown delivery status, was stored before the current session
      (`statusResponseCount` at session set up, in memory, so no codec change) or shows a conflict
      (suspended; for a bolus a bolus running). Then nothing is sent when no status of this session
      could be read, the pod is suspended, or (bolus) a bolus runs. An open command not confirmed is
      still refused first, as before. The cancel of a temp basal is now decided on that fresh status
      (Android cancels blind when no status is known). Tests: `IosDashPumpBolusTest` (5),
      `IosDashPumpTempBasalTest` (6, two replace the Android "cancel first when no status is known").
- [x] **No programming command while a command is open, on every path** (OmnipodKit `send` adds no
      beep block while it resolves an unacknowledged command). The delivery path already refused it
      (`createActiveCommand`); `IosDashPump.sendCommand` now refuses any command other than a status
      read while an active command is open, the activation included. Tests: `IosDashPumpActivationTest`
      (an open stop blocks part 2 while no status decides it; since 4d part 2 reads a status first),
      `IosDashPumpCommandTest` (pin: no PROGRAM_BEEPS while a bolus is
      open; this one passed before too).
- Test fixture changes for these: `DashPumpOnFakePod.pairAndConnect` now gives an activated pod a
  running basal and the status it reports, as read in this session, with no message
  (`basalOfThisSession = true`, `giveStatusOfThisSession`); tests of "no status known" pass false.
  The emulator `ActivatingPodModel` reports `BASAL_ACTIVE` once it took the basal program (a real pod
  runs its basal then; before, it reported the capture SUSPENDED all along). A test that reconnects
  before a bolus now sees the extra status read (wire numbers updated).
- To check on a real pod (section 6): that the answers to ordinary commands carry the unique id of
  the command (the Android capture in `EnDecryptTest` does), and that every answer carries the command
  sequence plus one (OmnipodKit relies on it for Dash).

### 4c. Pod alerts on the phone, from OmnipodKit (Trio), 2026-11-15

Android shows the active pod alerts only as a row on the pump screen, and can only silence all of
them at once. OmnipodKit posts each new alert on the phone (`alertsChanged` -> `issueAlert`), silences
one alert at a time (`acknowledgeAlert`), and keeps an acknowledgement the pod has not taken yet so it
is sent again (`alertsWithPendingAcknowledgment`, `silenceAcknowledgedAlerts`). Deliberate differences
from Android, each with a KDoc line (the alert is not seen on the phone, or a silence the user asked
for is a user action lost):

- [x] **A notice for the active alerts** (`DashPluginRules.updatePodAlerts`). After every command and
      status read (`afterCommand`) and at each run of the status checker (`statusCheck`, nothing sent
      from there): the alerts of the last status of a pod that is activated and runs, minus the ones the
      user silenced, in one notice `OMNIPOD_POD_ALERTS` (the id, level and "no sound" of Android Eros;
      the pod beeps itself), with Android's alert names (`dashAlertText`, ex `translatedActiveAlert`)
      and one "Silence <alert>" button each. Posted again only when that list changes, dismissed when
      it is empty or the pod is gone or in alarm. Notices are in memory, so after a restart the alerts
      not silenced are shown again. Low reservoir, user expiration reminder, expiration, imminent
      expiration, suspend ended and shutdown imminent (AUTO_OFF) have their Android names; any other
      slot (MULTI_COMMAND, slot 1, what OmnipodKit calls `notUsed` / `unexpectedAlert`) is "Unknown
      Alert". There is no "lump of coal" slot on Dash in Android or here; it would show as that.
- [x] **Silence one alert** (`IosDashPump.acknowledgeAlert`, queue command `CommandAcknowledgeAlert`,
      from the notice button or a new "Silence <alert>" button on the pump screen,
      `dashAlertSilenceButtons`). One `SilenceAlertsCommand` with that slot only, through the active
      command (SILENCE_ALERTS). "Silence Alerts" (all, Android) stays as it was. As OmnipodKit,
      "suspend ended" is not silenced alone while delivery is suspended (no button, refused): it beeps
      until the resume.
- [x] **The acknowledgement is stored** (`DashPodState.acknowledgedAlerts`), before the wait for the
      connection. SUCCESS or DENIED removes it (DENIED: the notice shows again, fail closed); a send
      failure or a lost answer keeps it. It is dropped once the pod no longer reports the alert (also
      after "Silence Alerts"), and with the pod state on reset. A stored one is not shown again, also
      after a restart, and is queued again after a status read (one per read, as OmnipodKit) when the
      last status is at most 2 minutes old, no command is open and none waits in the queue, so a pod
      out of range does not fill the queue. Not after the single silence itself, so a failing one is
      not sent in a row.
- [x] **A tap on the notice** marks the notice as gone (the shared UI closes the whole card without
      telling the plugin), so a silence the pod refused, or one not sent, posts the notice again at the
      next read. The tap also stores the silence before the queue: the queue refuses a second
      `CommandAcknowledgeAlert` while a silence sent again waits, and the tap would be lost. The
      driver forgets a stored silence it refuses (alert not active, suspend ended while suspended).
- [x] **Codec**: bit2 of the dose accounting flags (end of version 4) and one alert byte after the
      other fields, written only when the set is not empty, so older states have the same bytes and
      still decode (pinned tests unchanged).
- Tests: `DashPodAlertNoticeTest` (11, simulated pod: notice and button, one slot silenced and the
  notice keeps the other, a silence lost on the link not shown after a restart and sent at the next
  read, the 2 minute rule, alert cleared, silence all, status checker, suspend ended, pod in alarm, a refused silence after a
  tap posts the notice again, a tap while a silence waits in the queue is kept),
  `IosDashPumpAlertsTest` (6 more: bytes of the single silence, send failure kept, lost link kept,
  DENIED forgotten, alert not active, suspend ended while suspended), `DashOverviewTest` (4 more),
  `DashDoseAccountingTest` (2 more, codec).
- New English strings: `omnipod_dash_ios_pod_alerts`, `omnipod_dash_ios_silence_alert`.
- To check on a real pod (section 6): that a silence with one slot stops only that alert's beeps.

### 4d. BLE robustness and activation part 2, from OmnipodKit (Trio), 2026-11-15

Item 9 of the OmnipodKit comparison. Deliberate differences from Android (Android :pump:omnipod:dash
does not change), each with a KDoc line. The rules are in commonMain (`DashHeldConnection`), with tests
on the fake central `HeldPodCentral`; the CoreBluetooth side only gives a timer and turns the two
features on (`IosDashBleHost`), plus the peripheral state for the watchdog
(`CoreBluetoothCentral.isPeripheralConnected`).

- [x] **Connect watchdog** (OmnipodKit `BluetoothManager.armConnectWatchdog`,
      `eagerConnectWatchdogSeconds` 2 s, `eagerConnectTeardownSeconds` 0.2 s,
      `eagerConnectBudgetSeconds` 40 s). A user action lost otherwise: OmnipodKit found connects that
      stay wedged with no `didConnect` (InPlay pods, iPhone 16 class), and a command or bolus then waits
      until the queue gives up. While the connection is wanted, a connect with no link 2 s after it was
      asked (after its start delay, when it has one) is cancelled, and at once a new one is asked with a
      start delay of 1 s. For at most 40 s from the first connect; the budget starts again after a link
      came up, a new `hold` or a restore. After the budget the connect stays held with no limit (the
      OmniBLE rule, so iOS still wakes the app when the pod comes). The timer is
      `CoreBluetoothCentral.postAfter` (the radio queue, as OmnipodKit `managerQueue.asyncAfter`), which
      iOS does not run while the app is suspended. Three choices made after the review of 2026-11-15:
      - **No pause without a connect.** OmnipodKit waits 0.2 s on a timer between the cancel and the
        new connect. iOS does not run that timer in a suspended app, so an app suspended in the pause
        had no connect open and the pod coming back did not wake it. Here the pause is the start delay
        of the new connect, counted by iOS (CoreBluetooth takes whole seconds only, so 1 s). A cycle is
        3 s, about 13 cycles in 40 s.
      - **A link that is already up is not cut.** Before the cancel the watchdog asks the peripheral
        state, as OmnipodKit (`guard target.state != .connected`): a link that came up just before the
        timer, whose event still waits on the radio queue, would otherwise be cut, its set up would
        fail and the next connect would wait 10 s.
      - **No fault scan while the watchdog runs**, as OmnipodKit `eagerConnect` ("a concurrent scan
        starves connection completion on iOS"). After the budget the light C00A scan runs beside the
        held connect, as OmnipodKit keeps it beside its delayed connect (`issueDelayedConnectProbe`).
      Not taken from OmnipodKit: its gate to InPlay pods on some iPhones (it reads the peripheral name
      and the phone model); here the watchdog runs for every pod, and a false trip costs one cancel and
      1 s. Tests: `DashConnectWatchdogTest` (12), `DashFaultWatchTest` "the fault scan stops while the
      connect watchdog runs and comes back after its budget".
- [x] **Fault watch scan** (OmnipodKit `startScanning` with `alarmServiceUUIDs` = [C00A],
      `didDiscover` gated on its own pod, `surfacePodConditionAndQuiet`, `omnipodDidDetectAlert`).
      Insulin counted wrong otherwise: a pod that faults stops all delivery, and the app learned it only
      at the next status read. While the paired pod is known and not connected (Bluetooth on, no
      pairing scan), a scan runs filtered on C00A ONLY with no duplicates (the 2nd service UUID is C001
      in normal use, C005 with an alert set, C00A in fault; with C005 in the filter iOS reports the
      change to C00A about 7 minutes late, OmnipodKit measured). An advertisement of THIS pod (the
      filter is not pod specific) with C00A calls the fault listener on a worker, never on the radio
      queue (OmnipodKit issue 126), and the scan stops for this pod. The report is used up only when a
      link to the pod came up after it (the read, or any command, reached the pod), or when a connection
      was asked after it and stopped with no link (the read never reached the pod); then the scan runs
      again. So a report the plugin could not use (no activated pod yet) or a status read that failed
      does not hide the fault until the next regular read; a pod still in fault is reported again at
      most once for each connection tried. `DashPluginRules.onPodFaultAdvertised`
      then queues a status read ("Pod fault seen"); the usual `checkPodKaput` rules do the rest. Not
      taken: the decoding of the status word in the manufacturer data (with a C00A only filter a
      change there never reaches the app), and alert (C005) detection, which OmnipodKit also dropped.
      Tests: `DashFaultWatchTest` (12), `IosDashPluginPumpTest` (2), `DashPodFaultWatchPluginTest` (1).
- [x] **Activation part 2 decides an open command first** (owner decision; insulin counted wrong and a
      user action lost). With a command open and NOT_CONFIRMED, part 2 reads a status first, with the
      command lock, as `deactivatePod` does (`IosDashPump.decideOpenCommandBeforePart2`). When the status
      decides the command, its outcome goes to `onCommandDecided` (decided late) and part 2 goes on;
      when no status can be read, the command stays open and part 2 is refused as before (only that
      status read is sent). Tests: `IosDashPumpActivationTest` "part 2 reads a status first and goes on
      when it decides the open command" (fails before the change) and "no activation command goes while
      a command is open and no status decides it" (the old test, now with an answer that cannot be read).
- New English string: `omnipod_dash_ios_pod_fault_seen`. No codec change.
- To check on a real phone (section 6):
  - the fault scan: an induced occlusion (or a pod at its end of life) wakes a suspended app within
    about a minute and a status read follows; the pod advertises C00A in fault (OmnipodKit captured it
    on its pods, not on ours); the scan keeps running in the background and after an iOS relaunch
    (state restoration keeps it, and the app starts it again); a nearby pod of someone else in fault
    does nothing;
  - the watchdog: a pod in range still connects first time (no false trips in the log), also with the
    fault scan running before the connect (the scan stops before the connect; check that the connect
    time in the log is the same as with no scan); with the pod out of range the log shows about 13
    cancel cycles in 40 s and then the connect stays held, and the app is still woken when the pod
    comes back in range, also when it was suspended in the middle of the cycles;
  - that a connect with a 1 s start delay, asked right after the cancel, really tears down a wedged
    connect (OmnipodKit tested a 0.2 s pause with no connect open, not this);
  - whether a cancel of a pending connect gives a `didDisconnect` or `didFailToConnect`. If it does, the
    code asks a connect with no delay at once (no harm, but the pause is then shorter);
  - the "not cut" check: the log line "is connected, its event is on the way" should be rare;
  - after a fault report and a status read that failed (pod out of range), the C00A scan runs again
    and a second report comes when the pod is back;
  - battery: the C00A scan with no duplicates should cost nothing in normal use (no match).

### 4e. Parity check against Android and OmnipodKit (Trio), 2026-11-25

A three-way comparison (Android, this iOS driver, OmnipodKit) found six points where the iOS driver
was weaker than OmnipodKit. All six are fixed in `dashctl`, each with a test:

- **A NAK is never a done activation step.** `DashActivation.step` stops on a NAK, so the step is
  sent again on the next try. Before, as on Android, a refused alert command moved the activation on
  and the pod ran without that alert. For the basal program a NAK is followed by a status read:
  BASAL_PROGRAM_SET or later means an earlier copy was taken (its answer was lost).
- **A lost SetUniqueId answer no longer loses the pod.** That answer is the only one with the prime
  volumes, the prime pulse rate and the pod life. When it has none, a status decides. UID_SET means the
  pod took it, and the fixed Dash values are used (`PodConstants.DASH_*`, 80 h), as OmnipodKit always
  does. A later activation status means a progress write was lost: the progress is taken from the pod
  and the activation stops, so it never primes twice. The prime step also falls back to the fixed
  values when the step was stored without them.
- **The prime and cannula "in flight" mark must be on disk** before their bytes go, like the open
  command below.
- **16.15 U bolus.** The Android fix `dec98d6397` (round, do not cut) is ported to the `dashctl` copy
  of `ProgramBolusCommand`.
- **One lock for the pod state.** Every change of `DashPodStateManager` holds one reentrant lock. The
  "Silence" button and the status checker write from other threads than the queue.
- **A failed Keychain write blocks insulin.** `DashPodStateStore.saveChecked` reports it. The open
  command and its "sent" mark must be on disk before any byte goes, or nothing is sent; after any other
  failed write, the next delivery command first tries to save again.
- **Past alert timers are sent turned off** (`DashAlerts.updateAlertConfigurations`), as OmnipodKit
  does. Android sends the negative minutes, which the pod reads as about 45 days.

Still open, to be checked on a real pod first: a new EAP-AKA session on a link iOS kept through a
relaunch (`DashHeldConnection.onRestored`), and a BLE heartbeat to wake the loop as OmnipodKit has.

### 5. Screens

- [x] Activation and deactivation wizard, overview, in Compose Multiplatform (P3 above).
- [x] Settings screen (beeps, alerts, notification sounds), `dashSettingsScreen`.

### 6. On a real pod

There is no Dash emulator. Phases 1 to 5 are tested off line (JVM and iOS simulator, Android vectors,
OmniBLE test vectors); phase 6 uses real pods, one per activation.
