# Omnipod Dash on iPhone: the next run

A run that can go on without the owner: every decision it needs is taken below. It follows
[omnipod-dash-ios.md](omnipod-dash-ios.md); read that first.

## Where it starts (2026-11-13, `1e6037df07`)

- Phase 1 and phase 2 steps 1 to 4 are done and tested off line: the pod link (`dashctl/comm`),
  pairing, session, commands and their answers, the open command until the pod confirms it.
  313 tests on the JVM, 307 on the iOS simulator. (After step 4 of this run: 384 and 410.)
- `dashctl` is compiled into the iOS framework but nothing registers it: no plugin, no Dash in the
  pump list. The iOS app builds and runs as before.
- Not tested on a pod. There is no pod and no iPhone in this run: everything is tested against the
  simulated pod (`FakePodProtocol`, `FakeDashPodCentral`, `DashPumpOnFakePod`).

## What this run does, in order

Each step ends green on both targets before the next one starts. Android is the reference for every
rule: `pump/omnipod/dash/.../OmnipodDashPumpPlugin.kt`, `.../driver/OmnipodDashManagerImpl.kt`,
`pump/omnipod/common/.../bledriver/pod/state/OmnipodDashPodStateManagerImpl.kt`. Put rules in
commonMain (`dashctl`), so the JVM tests run them; only CoreBluetooth glue goes in iosMain.

### A. Finish the command layer (before any pod)

1. [x] **One link per BLE connection.** Done (not committed): `DashPodConnection` (commonMain,
   `comm/connection`) makes a new `DashBleLink` + `MessageIO` for each connection, runs `prepare()`,
   gives the link to the pump (`DashLinkClient.attachLink`) and, for a paired pod, sets up the
   session from 1, as Android `SharedBleManager.connect`. An unpaired pod gets `LinkReady`: pair on
   that link, then `establishSession` (Android `pairNewPod`). A failed set up or session cancels the
   BLE connection (Android `disconnect(false)`). `IosDashPump` no longer takes a `MessageIO`; it lives
   on with its `DashPodStateManager`. After review: `connect` takes the command lock of the pump
   without waiting (`Busy` as Android `BusyException`, never a dead lock from inside a command); a
   replaced or closed link is retired and writes nothing more; `IosDashPump.runActivation` (Android
   `activationRunning`) makes `isBusy()` true and ignores `disconnect()` during a pairing (#5219);
   `pair` and `establishSession` hold the command lock. Tests: `DashPodConnectionTest` (commonTest,
   13) and `IosDashPumpReconnectTest` (iosTest, 7). 326 tests on the JVM, 327 on the iOS simulator
   (1 skipped, the known wrong-premise pin), 0 failures.
   Still open: `IosDashPump.connect(reason)` does nothing; the reconnect after a drop is made by the
   central owner (step 9). Steps 4, 6 and 8 must connect before each operation, as Android
   `observeConnectToPod`, outside the command lock (a connect inside a command returns `Busy`).
2. [x] **Activation fields in the stored state.** Done (not committed): `DashPodState` and the
   manager keep what Android `updateFromSetUniqueIdResponse` and `updateFromVersionResponse` store
   (prime pulse rate, prime volumes, pod life, firmware and BLE versions, lot, pod sequence, the pod
   status as `reportedPodStatus`, `lastUpdatedSystemMs`), `activationTime` from the first default
   status (`updateFromDefaultStatus`; an alarm status does not set it), and `expiryMs` as Android
   `expiry`. `IosDashPump` routes the Version answer and the default status to them. The codec
   writes version 4; v1, v2 and the v3 bytes of 2026-11-13 (pinned by hand before the change) are
   still read, with the new fields empty. Tests: `DashPodStateCodecVersionsTest` (10) and
   `DashPodActivationFieldsTest` (10), commonTest. 346 tests on the JVM, 347 on the iOS simulator
   (1 skipped, the known pin), 0 failures.
   For step 6: `recoverActivationFromPodStatus` reads `reportedPodStatus`, not `podStatus`.
3. [x] **Temp basal like Android.** Done (not committed): `IosDashPump.setTempBasalAbsolute` first
   runs `cancelRunningTempBasalFirst` (Android `observeNoActiveTempBasal`): the decision is taken
   on the last status known before anything is sent; no status known means the cancel is sent, as
   Android. The cancel (STOP_TEMP_BASAL) and the new temp basal (TEMP_BASAL) each have their own
   active command and their own `onCommandDecided`. After the cancel: SUCCESS or DENIED, the new one
   goes (Android goes on after a denied cancel); NOT_CONFIRMED, it stays open and nothing more is
   sent; SENDING_FAILURE, the new one is not sent. Bytes: the Android builders with beeps off
   (SILENT, reminders false). Tests: `IosDashPumpTempBasalTest` (iosTest, 9); the fake pod now
   reports `TEMP_BASAL_ACTIVE` while it runs a temp basal. "a bolus is never built with the last programming sequence of the pod" in
   `IosDashPumpCommandTest` (a safety test, not a pinned parity test) now expects sequence 2: its
   first TBR has no status before it, so a cancel goes first. 346 tests on the JVM, 356 on the iOS
   simulator (1 skipped, the known pin), 0 failures.
   Deliberate difference: a temp basal or a cancel that is sent but not confirmed is a failure here
   (fail closed); Android returns success and shows a notification. The command stays open in both.
   Review fix: each active command now keeps the time it was made, `DashActiveCommand.createdAtMs`
   (Android `HistoryRecord.createdAt`, the time Android records a TBR start and stop with), stored by
   the codec (version 4). A late outcome carries it in
   `outcome.command.createdAtMs`; `decidedAtMs` is only when it became known. Tests: 2 in
   `DashActiveCommandTest`, 7 in `DashPodStateCodecVersionsTest` (v5 pin; the v4 pin is now
   decode only), 2 in `IosDashPumpTempBasalTest` (a cancel and a TBR decided an hour late), plus a
   test where the fake pod refuses the TBR after a refused cancel (two DENIED, nothing open): the
   SUCCESS of the "denied cancel then set" test comes from the fake pod, a real pod's answer there
   is not known. After the fix: 355 tests on the JVM, 368 on the iOS simulator (1 skipped, the
   known pin), 0 failures.
   Still open, for the plugin (phase 4): Android records the TBR in `PumpSync` when it is sent
   (`pumpSyncTempBasal` on `isCommandSent`) and invalidates it when denied; here only final
   outcomes are given. Whoever records them must use `command.createdAtMs`, not `decidedAtMs`. Android `podStateManager.tempBasal`
   (set on a confirmed TBR, cleared on a confirmed cancel; `tempBasalActive`, the "nothing to
   cancel" shortcut of `cancelTempBasal`) is kept since step 8; the basal correction is not ported. `observePodRunning` (pod
   activated and running) is not checked before a delivery command: it comes with the activation
   (step 6). `verifyPumpState` and `checkPodKaput` after each command come with steps 8 and phase 4.
4. [x] **Bolus to its end.** Done (not committed): after a SUCCESS bolus, `deliverTreatment` keeps
   the command lock and runs `DashBolusWait` (commonMain, `driver`), the port of
   `waitForBolusDeliveryToComplete`: the estimate (2 s per pulse + 3 s, one tick per second, estimated
   progress except for an SMB), then up to 5 tries (`BOLUS_RETRIES`): "checking delivery status"
   (100 %), a status read or, after a stop, the stop of the bolus; a failed try waits 2 s
   (`BOLUS_RETRY_INTERVAL_MS`); a bolus still running shows asked - left and waits 2 s after a stop or
   the time the rest should take. All tries failed: the asked amount ("updated later"). The timer is
   `IosDashPump(sleep = ...)`, `delay` by default, so tests run on virtual time. `stopBolusDelivering`
   only sets `bolusCanceled` as Android; the wait sends the stop (STOP_BOLUS, own active command,
   beeps off); while a stop is open the next try reads a status instead of a second stop. The last
   bolus (`DashLastBolus`, Android `LastBolus`) is in the stored state (codec version 4), set by a
   SUCCESS bolus, updated by each status (`updateLastBolusFromResponse`), ended by a confirmed stop
   (`DashPodStateManager.recordOutcome`, Android `handleCommandConfirmation`). A confirmed stop gives
   `DashCommandOutcome.endedBolus` (delivered = asked - left, record time = `createdAtMs` of the
   bolus). Progress goes to `BolusProgressData` as `IosMedtrumPump` does. A bolus is refused while
   the last status shows a bolus running (Android). Tests: `DashBolusWaitTest` (commonTest, 9),
   `DashLastBolusTest` (commonTest, 11), codec v6 tests in `DashPodStateCodecVersionsTest` (24 in
   all; the v5 pin is now decode only), `IosDashPumpBolusTest` (iosTest, 8). Tests that count
   messages after a successful bolus now include the status read of the wait (2 more messages, +2
   on the 4-bit sequence). 382 tests on the JVM, 403 on the iOS simulator (1 skipped, the known pin),
   0 failures.
   Review fixes: (a) a bolus not confirmed while the user asked to stop it: a status is read first,
   up to 5 times 2 s apart (`IosDashPump.decideBolusBeforeStop`, Android's first block of
   `waitForBolusDeliveryToComplete`); when the pod took it, the wait follows it and sends the stop,
   and the outcome goes to `onCommandDecided` as not late (the result reports it). Before, the stop
   was dropped and the pod gave the whole bolus. No status read: failure, the bolus stays open.
   (b) `DashPodStateManager.resolveActiveCommand(nowMs)` closes the command and creates or ends the
   last bolus in one write (`recordOutcome` is gone): two writes left a window where a crash lost a
   bolus the pod took, or left a closed stop with a bolus not ended. (c) Pinned: a stop whose answer
   is lost is decided by the next status with 0 left, so the whole bolus is counted, live in the
   wait too, not only after a crash (Android parity, over-count side). Tests: 5 more in
   `IosDashPumpBolusTest`, 2 more in `DashLastBolusTest`. 384 tests on the JVM, 410 on the iOS
   simulator (1 skipped, the known pin), 0 failures.
   Not changed after review: the wait still ends when the coroutine is cancelled. The only one that
   cancels it is the app shutdown (`CommandExecutor`: "cooperative cancel = app shutdown only"),
   which is a crash for the pod state: the stored open command and last bolus cover it, and a
   `NonCancellable` wait would hold a BLE exchange of up to minutes through the shutdown.
   Deliberate differences, each with a test: the wait runs only after SUCCESS (Android also after
   DENIED, where it reports the amount of the bolus before, and after not confirmed; a not confirmed
   bolus with a stop asked is decided by a status first, as Android, see the review fixes); an ended bolus
   no longer changes (Android writes later statuses into it, so a stopped bolus would count all of
   it); units left out of range count the whole bolus; a bolus the pod stopped itself with units left
   is ended and counted (Android throws on `deliveredUnits()!!` and reports 0).
   Still open: the reservoir check of Android (done in step 8); a reconnect inside the
   wait (`observeConnectToPod`, step 9: today a lost link makes the tries fail fast and the asked
   amount is reported); a stop whose answer is lost (live or after a crash) is decided by a status
   that shows 0 left, so the whole bolus is counted (more than went in, never less; Android parity,
   pinned); a bolus the pod ended itself with units left (fault) is only in the result, not in an
   outcome, and a fault seen after a crash is not corrected: done in step 8 (`DashBolusEndedByFault`);
   a stop asked after a not confirmed bolus already returned its failure does nothing (Android is
   still in its wait then); the "Checking delivery status"
   text is a literal until the plugin brings strings (phase 4); `PumpSync` recording (phase 4).
5. [x] **Basal program.** Done (not committed): `mapProfileToBasalProgram` (commonMain, `util`) is the
   Android mapper line by line (same checks, same error texts). `IosDashPump.setNewBasalProfile`:
   before the activation is completed nothing is sent and the result is success, not enacted
   (Android); otherwise `suspendDeliveryIfActive` (skipped when the last status shows SUSPENDED; no
   status known means the suspend is sent, as Android), its own active command (new kind
   `SUSPEND_DELIVERY`, `SuspendDeliveryCommand`, beeps off); SUCCESS or DENIED goes on, NOT_CONFIRMED
   stays open and nothing more is sent, SENDING_FAILURE stops. Then the program (BASAL_PROGRAM,
   `ProgramBasalCommand`, reminder all off, current time read when the command is built), its own
   active command carrying the program. A SUCCESS sets `DashPodState.basalProgram` in the same write
   that closes the command (`resolveActiveCommand`). `isThisProfileSet`: true before the activation
   is completed, false when suspended, else mapped profile == confirmed program; an empty profile
   throws (Android). Codec version 4: the program of the open command and the running program
   (versions 1 to 6 still read, with no program). Tests: `ProfileToBasalProgramTest` (commonTest, 8:
   the Android mapper tests and the Android vector `testProgramBasalCommandDifferentInterval` from a
   mapped profile), `DashBasalProgramStateTest` (commonTest, 7), codec v7 tests in
   `DashPodStateCodecVersionsTest` (31 in all; the v6 pin is now decode only), `IosDashPumpBasalProgramTest`
   (iosTest, 12). The fake pod now has `basalRunning` (BASAL_ACTIVE in its status; off by default, so
   the old tests see the same capture status). 406 tests on the JVM, 444 on the iOS simulator
   (1 skipped, the known pin), 0 failures.
   Deliberate differences, each with a test: a program the pod refused (DENIED) is a failure (Android
   returns success because nothing is left open, while the pod, just suspended, delivers no basal); a
   profile that cannot be mapped is a failure with nothing sent (Android throws; nothing is sent there
   either).
   Review fixes: (a) `IosDashPump.isSuspended()` was always false; it is now
   `DashPodStateManager.isSuspended`, as Android, so after a confirmed suspend and a lost or refused
   program the pump says it is suspended (and agrees with `isThisProfileSet`). (b) Deliberate
   difference from Android (it counts insulin wrong): a confirmed SUSPEND_DELIVERY or STOP_ALL ends a
   last bolus that has not ended, in the same write that closes the command, with what the answer
   left, and gives it as `endedBolus` in the outcome. Android leaves it open, so the next status (0
   left) counts the whole bolus. A bolus that already ended is not changed or given again. Tests: 3
   more in `IosDashPumpBasalProgramTest`, 4 more in `DashLastBolusTest`. 410 tests on the JVM, 451 on
   the iOS simulator (1 skipped, the known pin), 0 failures.
   Still open: `observePodRunning` before the commands (step 6, done); the connect before them (step 9);
   `suspendAlertsEnabled` after the suspend (step 7, done); for the plugin (phase 4): `PumpSync` (the
   PUMP_SUSPEND temp basal when the suspend is sent, its stop at the `createdAtMs` of a confirmed
   program, its invalidation on a refused suspend), `EventProfileChangeRequested` on a late SUCCESS,
   `CommandDisableSuspendAlerts`, `baseBasalRate` and `deliveredBasal` from the program (still 0 and
   null on iOS); `BasalProgram.hourlyRates` is not ported yet. Time zone and DST changes are ignored,
   as Android (known gap: the pod keeps running the program on the old time).

### B. The manager

6. [x] **Activation** part 1 and part 2. Done (not committed): `DashActivation` (commonMain,
   `driver`) is Android `activatePodPart1` / `activatePodPart2` step by step, in Android order and with
   the Android values: GetVersion (unique id ffffffff), SetUniqueId (lot and pod sequence of the
   Version answer, time now), the low reservoir alert when asked, the lump of coal alert, the prime
   (first prime volume x 0.05 U at the prime pulse rate), a wait of 1 s per pulse, a status that must
   show CLUTCH_DRIVE_ENGAGED, then part 2: the basal program, the three expiration alerts (multi
   command flag, same minutes as Android, `Duration.toMinutes` rounding), the cannula bolus, a wait,
   a status that must show RUNNING_ABOVE_MIN_VOLUME. Each step stores its `ActivationProgress` once
   its answer is read; a call skips the steps already stored, so a crash or an error resumes there.
   `IosDashPump.activatePodPart1(trigger)` / `activatePodPart2(program, reminderHours, alarmHours)`
   run it inside one activation (`activationRunning`, command lock, failure as a result). Part 1 pairs
   a pod with no LTK on the link of the connection and sets up the session (Android `pairNewPod`), so
   the caller connects first (`LinkReady`). `DashPodStateManager.recoverActivationFromPodStatus` is the
   Android table on `reportedPodStatus`; `getPumpStatus` calls it after a status read on a pod not
   activated, and, as Android, reads nothing before the activation is completed unless the reason is
   `REQUESTED_BY_USER`, and nothing at all before SET_UNIQUE_ID (Android `getStatus` starts with
   `observeUniqueIdSet`). Note: the Android Dash overview passes `rh.gs(refresh)`, never the literal
   "REQUESTED BY USER", so on Android this recover is close to unreachable; the queue/plugin caller
   (phase 4) must decide which reason it passes. The recover runs only when this read stored a
   status (`statusResponseCount` went up): after a NAK the stored pod status is the one from before. `isBusy()` is also true from the first step to COMPLETED (Android). The
   answers go into the state through one place, `DashPodStateManager.updateFromResponse`.
   `observePodRunning`: every delivery command except the stop of all delivery is refused (nothing
   sent, no active command, SENDING_FAILURE) unless the activation is COMPLETED and the reported pod
   status is running (`isActivatedAndRunning`).
   Deliberate differences, each with tests: (a) the prime and the cannula bolus are marked in the
   stored state before their bytes go (`activationStepInFlight`, codec version 4 extended in place,
   v4 pin updated, v3 pin unchanged); a call that finds a mark reads a status first and lets the pod
   status decide (UID_SET / BASAL_PROGRAM_SET: not taken, sent; ENGAGING_CLUTCH_DRIVE /
   CLUTCH_DRIVE_ENGAGED, PRIMING / running: taken, not sent again; anything else or no status: stop,
   mark kept). Only a status that this read stored decides: an answer with no status (a NAK) leaves
   the status from before the bolus, which says "not taken", so it counts as no status (review fix,
   2026-11-13). Android sends the bolus again, and the cannula bolus goes under the skin.
   `recoverActivationFromPodStatus` clears the mark when it sets a progress. (b) The program the pod
   took becomes the running program in the write that stores PROGRAMMED_BASAL; Android stores the
   program of the last part 2 call at its end, which after a resume with another profile is a
   program the pod does not run.
   Ported as Android (reported): the answer type is not checked (a NAK to a step counts as an
   answer; the status check after the prime or the cannula then stops the activation); a lost answer
   to SetUniqueId sends it again on the next call (a real pod may refuse it; as Android, any answer,
   a NAK too, then stores SET_UNIQUE_ID; a status the user asks for is no way out, it is refused
   before SET_UNIQUE_ID as on Android). The status check after the prime or the cannula bolus takes
   the pod status stored last, also when its own answer was a NAK (Android; nothing is sent twice).
   Test harness: the fake pod's default status now says RUNNING_ABOVE_MIN_VOLUME (the Android
   capture says CLUTCH_DRIVE_ENGAGED, a pod in the middle of its activation), `pairAndConnect` marks
   the pod activated unless `activated = false`, and `FakeDashPod` takes GetVersion and SetUniqueId to
   unique id ffffffff. No pinned expected value changed; three tests that read a status or sent a bolus
   on a pod that was never activated now ask for the status as the user (`REQUESTED_BY_USER`) or mark
   the pod activated first.
   Tests: `DashActivationTest` (commonTest, 19: Android order, every progress stored in order, the
   prime bytes, the expiration alert values with and without the user alerts, `toMinutes`, the
   gates, lost answers, a send that never left, a pod not taking the prime, no status, an unexpected
   status, a pod still priming, the program the pod took, a crash after every single write of a full
   activation, the recover table, the running gate; +2 after review: a NAK to the status read of a
   prime or a cannula bolus in flight sends nothing more and keeps the mark), codec tests in `DashPodStateCodecVersionsTest`
   (4 more), `IosDashPumpActivationTest` (iosTest, 13: full activation bytes in Android order from
   `LinkReady`, busy, part 2 first, a stop before and after the prime and the cannula bytes, delivery
   refused before and during the activation and on a pod in alarm, a bolus after it, the stop of all
   delivery not held back, `getPumpStatus` before the activation; +2 after review: a NAK to a status
   the user asks for keeps the cannula mark, no status read before SET_UNIQUE_ID). Two
   `IosDashPumpCommandTest` SQN tests that read a status on a pod never activated now mark it
   activated first (their asserted values did not change). 422 tests on the JVM, 478 on the iOS
   simulator (1 skipped, the known pin), 0 failures.
   Still open: the connect before each step (`observeConnectToPod`, step 9: the activation needs a
   session today and fails closed without one); for the plugin (phase 4): the low reservoir and
   expiration alert settings stored after each part (done in step 7: `activatePodPart1/2` store them
   when the caller gives them), the history records, the
   `PumpSync` records after part 2 and the status read after it; the reservoir check of a bolus
   (`reservoirLevel` is still a placeholder); deactivation and a reset of the state for the next pod
   (done in step 7).
7. [x] **Deactivation**, **suspend** and **resume**, **alerts** and **silence alerts**, **beeps**.
   Done (not committed). Every one goes through the open command rule as Android
   `executeProgrammingCommand` (own active command, refused while one is open, outcome to
   `onCommandDecided`, success only on SUCCESS), with new kinds at the end of `DashCommandKind`
   (RESUME_DELIVERY, SILENCE_ALERTS, PROGRAM_ALERTS, PROGRAM_BEEPS, DEACTIVATE).
   - `IosDashPump.suspendDelivery()` is now the Android manager `suspendDelivery`
     (`SuspendDeliveryCommand`; before it sent STOP_ALL); `suspendDeliveryIfActive` uses the same
     path. `suspendAlertsEnabled` is set once the answer is read (Android `doOnComplete`), stored.
     A confirmed suspend ends a running bolus with what the answer left (step 5 rule).
   - `resumeDelivery(profile)`: Android `resumeDelivery` (map the profile, "Expected suspended
     delivery" unless the last status shows SUSPENDED, then the basal program, no suspend first).
   - `silenceAlerts()` (the alerts of the last status, all of them; "No active alerts" with no
     status), `programAlerts(list)`, `disableSuspendAlerts()` (clears `suspendAlertsEnabled` on
     SUCCESS, DENIED and NOT_CONFIRMED, as Android; kept after a send failure), `updateAlertConfiguration(DashAlertSettings)` (`sameAlertSettings` and "no running
     pod" send nothing; the four alerts of `DashAlerts.updateAlertConfigurations`, commonMain),
     `playTestBeep()`. The alert settings are stored (`DashExpirationAlertSettings`,
     `DashLowReservoirAlertSettings`), and `activatePodPart1/2` store them after the part when
     given (Android wizard).
   - Beeps: `IosDashPump(beeps = { DashBeepSettings() })`, all off by default, read when each command
     is built; bolus / SMB reminders, temp basal reminders and its stops, basal program `atStart`, the
     suspend and the stop of the bolus take the Android settings. The test beep always beeps.
   - `deactivatePod()`: `DeactivateCommand`, not held back by `observePodRunning` (a pod in alarm or
     half activated is deactivated); on SUCCESS the outcome is given, then `DashPodStateManager.reset()`
     clears everything (Android `PodState()`) and the link is closed (Android removes the bond).
     `discardPod()` is the Android "Discard pod" (reset, nothing sent). A command still open is
     given to `onCommandDecided` first: its real outcome when a status decided it, otherwise
     NOT_CONFIRMED with `podDiscarded = true` (see (f) below).
   Stored format: version 4 extended in place (suspend alerts flag, the two alert settings), v4 pin
   updated, v3 pin unchanged.
   Deliberate differences, each with a test: (a) a refused deactivation (DENIED) clears the state only
   when the pod reports ALARM or DEACTIVATED (`isPodKaput`); Android clears it after any refusal, so a
   pod still running its basal would be forgotten and a new pod would deliver next to it. (b) A
   confirmed resume makes its program the running one (Android keeps the old program: a later profile
   equal to it would count as set while the pod runs another). (c) A confirmed deactivation ends a
   running bolus with what the answer left (as step 5 for a suspend; Android resets and keeps the whole
   bolus). (d) Alert settings are stored only when the pod confirmed the alerts (Android stores them
   also after a refused or not confirmed command, then never sends them again). (e) No pod expiry
   known: the alert configuration fails with nothing sent (Android throws). (f) Discarding the pod
   with a command still not confirmed gives it to `onCommandDecided` with `podDiscarded = true`: Android
   records a bolus and a temp basal when they are sent, so its reset keeps them; here a not confirmed
   bolus is counted only from an outcome, and the reset would lose it from insulin on board. The
   plugin (phase 4) must count such a BOLUS at the asked amount and such a TEMP_BASAL as started. Plus the rule of every
   command here: success only on SUCCESS (Android: success after DENIED and NOT_CONFIRMED too).
   Ported as Android (reported): a negative alert delay in `updateAlertConfiguration` is only logged
   and the negative minutes are sent (Android builds a failure and drops it); silence alerts with an
   empty set of alerts is sent; a deactivation decided SUCCESS only later by a status does not clear
   the state (the user deactivates again or discards); the Android plugin has no suspend of its own
   (`CommandSuspendDelivery` is not handled, the button is hidden): `suspendDelivery()` is the manager
   call for the plugin.
   Tests: `IosDashPumpSuspendResumeTest` (14), `IosDashPumpAlertsTest` (13),
   `IosDashPumpDeactivationTest` (12), `IosDashPumpBeepsTest` (7), 2 more in
   `IosDashPumpActivationTest` (iosTest); `DashAlertsTest` (5), `DashAlertAndResetStateTest` (8), codec
   tests in `DashPodStateCodecVersionsTest` (26 in all, commonTest). The fake pod now reports
   DEACTIVATED after a deactivation, can report active alerts and clears the silenced ones
   (`podDefaultStatus(activeAlerts)`). 439 tests on the JVM, 543 on the iOS simulator (1 skipped, the
   known pin), 0 failures.
   Still open, for the plugin (phase 4): the custom commands of the queue (`CommandSilenceAlerts`,
   `CommandResumeDelivery`, `CommandDeactivatePod`, `CommandUpdateAlertConfiguration`,
   `CommandPlayTestBeep`, `CommandDisableSuspendAlerts`) mapped to these calls; queuing
   `disableSuspendAlerts()` after a confirmed RESUME_DELIVERY or BASAL_PROGRAM outcome (Android does
   it in `handleCommandConfirmation`); the beep and alert user settings; an outcome with `podDiscarded` counted as Android (BOLUS at
   the asked amount, TEMP_BASAL as started); `PumpSync` (the stop of the
   PUMP_SUSPEND temp basal on a confirmed resume) and the history records; dismissing the fault
   notification after a deactivation. The connect before each command (step 9).
8. [x] **Status**. Done (not committed). `getPumpStatus` is Android `getPumpStatus` / `getPodStatus`:
   one status read, the open command decided, `checkPodKaput`, then `syncPumpFlows`.
   - The alarm status: a faulted pod answers the default status request with an `AlarmStatusResponse`
     (Android never asks for it, it comes in place of the default one). It is stored as a status
     (counted, so it decides an open command) and `DashPodStateManager.updateFromAlarmStatus` keeps the
     alarm type of the first one, as Android (`alarmType`, latched). `alarmSynced` is stored for the
     plugin.
   - `checkPodKaput` (`IosDashPump.checkPodKaput`, state part `DashPodStateManager.completeBolusOnPodKaput`):
     a pod in ALARM or DEACTIVATED ends a last bolus that has not ended, with what the last status
     left, in one write, and gives it to `onCommandDecided` as a new outcome, `DashBolusEndedByFault`
     (bolus with delivered = asked - left, `createdAtMs` of the bolus, alarm type). `onCommandDecided`
     now takes `DashPumpOutcome`, a sealed type over `DashCommandOutcome` and `DashBolusEndedByFault`.
     It runs after every `resolveAndHandle` (each status read, before and after each delivery command,
     before a discard), as Android runs it after each status read and each programming command. The
     bolus is stored, so a fault first seen after a crash is corrected by the first status read after
     it. Given once: the bolus is ended before the outcome is given (a crash between the two loses the
     correction and keeps the whole bolus counted, the over-count side, as Android).
   - `syncPumpFlows` (Android): `lastDataTime`, `lastBolusTime` / `lastBolusAmount` from the stored
     last bolus, `reservoirLevel` (0 before the activation is completed, pulses x 0.05 U, 75 U for the
     reading 1023 or no status). With it, Android's reservoir check of a bolus: a bolus larger than
     the reservoir is refused with nothing sent.
   - Android `podStateManager.tempBasal` (step 3 open item): `DashTempBasal` in the stored state, set
     by a confirmed TEMP_BASAL (start = `createdAtMs` of the command), cleared by a confirmed
     STOP_TEMP_BASAL, SUSPEND_DELIVERY, RESUME_DELIVERY (Android), STOP_ALL and DEACTIVATE (the pod
     stops all delivery). `IosDashPump.tempBasalActive()` as Android (not suspended, not run out).
     `cancelTempBasal` has the Android "nothing to cancel" shortcut; the `PumpSync` half of it is a new
     parameter, `IosDashPump(expectedTempBasalRunning = ...)`, true by default, so every cancel is
     sent until the plugin gives the real answer.
   - Android `podStateManager.pulsesRemaining`: `DashPodState.pulsesRemaining`, the last reservoir
     reading below 1023, set by each status (default or alarm) that reads below 1023 and kept by one
     that reads 1023 or more (Android `updatePodState`). `reservoirLevel` and the reservoir check of a
     bolus read it. A state from codec version 3 has none and takes its stored status when it reads
     below 1023 (`DashPodStateManager.pulsesRemaining`).
   Stored format: version 4 extended in place (temp basal, alarm type, alarm synced, reservoir
   reading), v4 pin updated, v3 pin unchanged.
   Deliberate difference (it counts insulin wrong), with tests: a BOLUS decided SUCCESS by the status
   of a kaput pod (answer lost, then the pod faults) takes the pulses left from that status. Android
   creates the last bolus with all of it left and `checkPodKaput` then records 0 U for a bolus the pod
   partly gave. Only for a kaput pod: a bolus decided by a running pod still starts with all of it
   left (Android).
   Small differences, not about dosing: the reservoir check comes after the pulse rate check (Android:
   before; both refuse with nothing sent); `syncPumpFlows` also runs when the
   status read is refused before SET_UNIQUE_ID (Android: its `getStatus` fails inside the `try`, then
   syncs too).
   Tests: `DashPodFaultAndTempBasalStateTest` (commonTest, 18), 4 more codec tests in
   `DashPodStateCodecVersionsTest`, `IosDashPumpStatusTest` (iosTest, 16: a status read updates the
   state and the flows, the reservoir, the reservoir check, a status from `getPumpStatus` decides a lost
   temp basal, a faulted pod reports kaput and takes no delivery command, the bolus correction after a
   fault in the wait, given once, after a crash, for a bolus decided late by the alarm status, after a
   discard, the cancel shortcut). The fake pod can fault (`faultAlarmType`, alarm status built from the
   Android vector) and report its reservoir (`reservoirPulses`). No pinned expected value changed.
   461 tests on the JVM, 581 on the iOS simulator (1 skipped, the known pin), 0 failures.
   Review fixes: (a) the reservoir reading of 1023 after a lower one showed 75 U (the alarm status of
   a faulted pod always reads 1023 here), so the reservoir check let through a bolus Android refuses:
   now the last reading below 1023 is kept and stored, as Android (tests: 3 in `IosDashPumpStatusTest`,
   1 in `DashPodFaultAndTempBasalStateTest`, codec pins). (b) The receiver contract of
   `DashBolusEndedByFault` and `DashCommandOutcome.endedBolus` said "records again": it now says the
   receiver corrects the one record made from the SUCCESS outcome of the bolus (key: the command time,
   `DashLastBolus.createdAtMs` = `DashActiveCommand.createdAtMs`), never adds a second bolus, also when
   the correction comes while `deliverTreatment` runs, and that `bolusDelivered` of the result is not a
   record (no queue records from it; on Android only the pump records). Code unchanged: Android also
   runs `checkPodKaput` in the status reads of the bolus wait (`getPodStatus`). Pinned by `a fault in
   the bolus wait corrects the record of the same bolus`. 462 tests on the JVM, 586 on the iOS
   simulator (1 skipped, the known pin), 0 failures.
   Still open, for the plugin (phase 4): the PUMP_SUSPEND temp basal Android records for a kaput pod
   (`checkPodKaput`, `createFakeTBRWhenNoActivePod`), the fault notification and announcement
   (`alarmSynced`), the Android `pdmRef` (not ported: `AlarmType.pdmFaultCategory` is newer than the
   dashctl port), `verifyPumpState` (needs `PumpSync`), the history, `baseBasalRate` from the program,
   `isInitialized` as Android `isPodRunning`; the basal correction of Android (`needsBasalCorrection`).
   The receiver of `DashBolusEndedByFault` corrects the record of that bolus (made from its SUCCESS
   outcome, key `bolus.createdAtMs`) to the delivered units, the same way as
   `DashCommandOutcome.endedBolus`; never a second record, and nothing is recorded from `bolusDelivered`.

### C. Bluetooth on the iPhone (code and fakes only; the device test comes later)

9. A `CoreBluetoothCentral` for Dash with its own restore identifier, `QueuedBleCentral` around it,
   a scan for pairable pods (Dash service UUID, the pod advertisement as Android `PodScanner`), and
   the held connection of OmniBLE: `connect` without timeout, made again after every drop. Wire
   `IosDashPump.connect(reason)` to it, and retry `DashPodConnection.connect` when it returns `Busy`.
   Pairing goes inside `IosDashPump.runActivation`. Test the
   scan and reconnect rules with a fake central.

Stop after step 9. The plugin (phase 4) and the screens (phase 5) need the owner.

## Decisions already taken (do not stop to ask)

- **Parity first.** Same bytes and same dosing decisions as Android. A deliberate difference needs a
  safety reason, a KDoc line and a test. Android bugs are ported as they are and reported, unless
  they can deliver insulin twice or count it wrong: then fix, and say so (as with the 4-bit
  sequence check).
- **Fail closed.** When the outcome of a delivery command is unknown, nothing new is sent and
  nothing is reported delivered until a status decides.
- **Late outcomes** go to `onCommandDecided`. Who records them in `PumpSync` is the plugin's job
  (phase 4); until then tests are the only consumer.
- **Beeps** stay off until the plugin brings the user settings (phase 4).
- **Time zone and DST**: Android ignores them (`timezoneOrDSTChanged` logs and does nothing). Do the
  same; note it in the plan as a known gap.
- **HELLO** stays in `DashBleLink.prepare()`, as Android `SharedBleConnection.connect`.
- **Restore identifier** for the Dash central: `app.aaps.ble.pump.omnipod.dash`, added to
  `BleRestoreIdentifier` in `core/interfaces` (the one change outside `dashctl` this run may make).
- **Never** change an Android test vector or the expected value of a pinned parity test.

## How the run works

- Multi agent, the same shape as the last runs: for each step, one builder (the only one allowed to
  run gradle), then two adversarial reviewers in parallel that never build (lens 1: Android parity of
  bytes and rules; lens 2: dosing safety: a dose delivered twice, counted wrong, or lost after a
  crash), then one fixer that adds a failing test for each confirmed finding.
- Build and test, with output in a log file, never through a pipe:

  ```
  ./gradlew :pump:omnipod:dashctl:jvmTest :pump:omnipod:dashctl:iosSimulatorArm64Test \
    --offline --no-daemon --continue --console=plain > <scratchpad>/dash.log 2>&1
  ```

  then grep `^e: `, `FAILED`, `BUILD (SUCCESSFUL|FAILED)` and count from the XML results.
  `--offline`: there is no network for gradle on a plane; the caches are warm.
- After step 9: build the whole iOS app once, in its own folder so Xcode is not touched:

  ```
  xcodebuild -project ios/app/AAPS.xcodeproj -scheme AAPS -destination 'generic/platform=iOS' \
    -configuration Debug -derivedDataPath <scratchpad>/dd CODE_SIGNING_ALLOWED=NO build
  ```

- Never run two gradle builds at once. If the same compile error comes twice, stop and write it down.
- Keep this file and `omnipod-dash-ios.md` up to date: tick a step only when it is green on both
  targets, and write what is still open.

## Text to paste to start the run

```
Lis docs/kmp-migration/omnipod-dash-ios-next-run.md et docs/kmp-migration/omnipod-dash-ios.md,
puis fais les étapes 1 à 9 dans l'ordre, en multi-agents comme décrit dans le fichier.
Toutes les décisions sont dans le fichier : ne t'arrête pas pour demander.
Commite chaque étape quand elle est verte sur la JVM et sur le simulateur iOS (je t'y autorise),
sans la ligne Co-Authored-By. Ne pousse rien.
Si une étape reste rouge après deux essais, note le problème dans le fichier et passe à la suivante
seulement si elle n'en dépend pas.
À la fin : un rapport en français, simple, dans le chat.
```

### Stored format after part A

Steps 2 to 5 each added a codec version (4 to 7). None of them was ever committed or stored by a
phone, so before the commit they were merged into one version 4 (activation fields, command time,
last bolus, basal programs). Versions 1 to 3 are still read; the v3 bytes of 2026-11-13 are still
pinned in `DashPodStateCodecVersionsTest`.
