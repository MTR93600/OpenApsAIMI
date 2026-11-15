package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.actions.CustomAction
import app.aaps.core.interfaces.pump.actions.CustomActionType
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.comm.Ids
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection
import app.aaps.pump.omnipod.dashctl.comm.connection.DashLinkClient
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodLink
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.dashctl.comm.pair.LTKExchanger
import app.aaps.pump.omnipod.dashctl.comm.session.CommandAckError
import app.aaps.pump.omnipod.dashctl.comm.session.CommandReceiveError
import app.aaps.pump.omnipod.dashctl.comm.session.CommandReceiveSuccess
import app.aaps.pump.omnipod.dashctl.comm.session.CommandSendErrorConfirming
import app.aaps.pump.omnipod.dashctl.comm.session.CommandSendErrorSending
import app.aaps.pump.omnipod.dashctl.comm.session.CommandSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.session.Session
import app.aaps.pump.omnipod.dashctl.comm.session.SessionEstablisher
import app.aaps.pump.omnipod.dashctl.comm.session.SessionKeys
import app.aaps.pump.omnipod.dashctl.comm.session.SessionNegotiationResponse
import app.aaps.pump.omnipod.dashctl.comm.session.SessionNegotiationResynchronization
import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.crypto.SecureRandomBytes
import app.aaps.pump.omnipod.dashctl.driver.DashActivation
import app.aaps.pump.omnipod.dashctl.driver.DashActivationException
import app.aaps.pump.omnipod.dashctl.driver.DashActivationPod
import app.aaps.pump.omnipod.dashctl.driver.DashAlerts
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import app.aaps.pump.omnipod.dashctl.driver.DashBolusProgress
import app.aaps.pump.omnipod.dashctl.driver.DashBolusWait
import app.aaps.pump.omnipod.dashctl.driver.DashBolusWaitPod
import app.aaps.pump.omnipod.dashctl.driver.DashExchange
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosSecureRandomBytes
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.pod.command.DeactivateCommand
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBeepsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.SilenceAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.Response
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashCommandNotConfirmedException
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateUnknownException
import app.aaps.pump.omnipod.dashctl.session.DashResolvedCommand
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thrown when a command is attempted without a paired pod and established session, or on a link
 * that was closed after an error. This is the explicit safety guard: no bytes go to the radio
 * unless pairing completed AND the session keys are in place AND the link is still usable.
 */
class DashPumpNotPairedException(message: String) : Exception(message)

/**
 * What `IosDashPump.onCommandDecided` gets: the final outcome of a delivery command
 * ([DashCommandOutcome]), or a bolus a pod fault ended ([DashBolusEndedByFault]). The receiver records
 * each one (pump history, `PumpSync`).
 */
sealed interface DashPumpOutcome {

    /** When the outcome became known. Not the delivery time: see each outcome. */
    val decidedAtMs: Long
}

/**
 * The pod is kaput (ALARM or DEACTIVATED) and the last bolus had not ended: it ended with what the pod
 * still had to give at its last status. Port of the bolus part of Android
 * `OmnipodDashPumpPlugin.checkPodKaput` (`markComplete`, then `syncBolusWithPumpId` with the delivered
 * units at the `createdAt` of the bolus history record).
 *
 * The receiver corrects the record of this bolus to [DashLastBolus.deliveredUnits] (asked - left),
 * the same way as [DashCommandOutcome.endedBolus]. It updates one record, it never adds a second
 * bolus. That record was made when the bolus was sent (`onCommandSent`, Android `pumpSyncBolusStart`),
 * at the asked units, so it is there before any correction, also when a fault in the bolus wait gives
 * this correction while [IosDashPump.deliverTreatment] still runs.
 * The key of the record is the command time: [DashLastBolus.createdAtMs] is
 * [DashActiveCommand.createdAtMs] of that outcome (Android updates the record by its `pumpId`; here
 * [DashLastBolus.historyId] is 0, there is no pod history yet). Null only for a bolus whose command was
 * stored by codec version 3 or before; the receiver then corrects nothing, because any record it wrote
 * belongs to another bolus, and the bolus stays counted at the asked amount.
 * Without the correction the part the pod never gave would stay in insulin on board.
 *
 * The `bolusDelivered` of the result of [IosDashPump.deliverTreatment] carries the same amount for the
 * caller only. It is not a record: no queue records a bolus from it (on Android too, only the pump
 * records boluses, through `PumpSync`). A receiver that recorded the bolus from it as well would count
 * it twice.
 *
 * Given once per bolus: the bolus is marked ended in the stored state before this is given. It also
 * comes when the fault is first seen after a crash or a restart (the next status read).
 *
 * @property alarmType the alarm of the pod ([DashPodStateManager.alarmType]), null when the pod only
 *   reported DEACTIVATED or the alarm status was not read.
 */
data class DashBolusEndedByFault(
    val bolus: DashLastBolus,
    val alarmType: AlarmType?,
    override val decidedAtMs: Long,
) : DashPumpOutcome

/**
 * A bolus the pod reported that the app did not track (`DashPodStateManager.updateFromDefaultStatus`),
 * as OmnipodKit (Trio) `PodState.updateDeliveryStatus` makes a dose of it. Deliberate difference from
 * Android, which ignores it, so insulin the pod gives is never counted.
 *
 * The receiver writes one bolus record of [DashLastBolus.requestedUnits] (the units left at that
 * status) at [DashLastBolus.createdAtMs], with that time as the pump id, then calls [onWritten]. Until
 * then the state keeps its mark and every status gives it again, so a write that failed is tried again
 * (a cleared mark with nothing written means insulin counted too low). The same pump id makes a second
 * write update the one record, so the bolus is never counted twice. Its end (a stop, a fault) corrects
 * that record, as for any other bolus.
 *
 * @property onWritten tells the pod state that the record is written (`DashPodStateManager.untrackedBolusWritten`).
 *   A receiver that does not write it does not call it.
 */
data class DashUntrackedBolus(
    val bolus: DashLastBolus,
    override val decidedAtMs: Long,
    val onWritten: () -> Unit = {},
) : DashPumpOutcome

/**
 * The final outcome of one delivery command, given to `IosDashPump.onCommandDecided`, as Android
 * `OmnipodDashPumpPlugin.handleCommandConfirmation` gets it. The receiver records it (pump history,
 * `PumpSync`).
 *
 * @property confirmation SUCCESS (the pod took it), DENIED or SENDING_FAILURE (it did not).
 *   NOT_CONFIRMED only together with [podDiscarded].
 * @property decidedLate true when a later status decided it, after the call that sent the command
 *   had already returned a failure. A late SUCCESS bolus is insulin the caller was told was not
 *   delivered: it must be recorded, or a retry delivers it a second time.
 * @property decidedAtMs when the outcome became known. This is not the delivery time: a start or a
 *   stop (temp basal, bolus) is recorded at [DashActiveCommand.createdAtMs] of [command], as Android
 *   records it at the `createdAt` of its history record.
 * @property endedBolus for a confirmed stop of the bolus (STOP_BOLUS, SUCCESS), or a confirmed
 *   SUSPEND_DELIVERY or STOP_ALL that stopped a bolus still running: the bolus it ended,
 *   with [DashLastBolus.deliveredUnits] the amount that went in. The receiver corrects the record of
 *   that bolus to that amount (one record, keyed by [DashLastBolus.createdAtMs], which is the command
 *   time of the SUCCESS outcome of the bolus; never a second bolus), as Android
 *   `handleCommandConfirmation` on CANCEL_BOLUS (`syncBolusWithPumpId` with the delivered units, same
 *   `pumpId`). Also for a stop decided inside the call: see [DashBolusEndedByFault]. Null otherwise.
 * @property podDiscarded true when the pod was discarded (`IosDashPump.discardPod`) while [command]
 *   was still not confirmed. [confirmation] is then NOT_CONFIRMED, and nobody will ever know whether
 *   the pod took it. Android records a bolus and a temp basal as soon as they were sent, so its
 *   "Discard pod" keeps them. The receiver must do the same: keep what it wrote when the command was
 *   sent, and when nothing was written then (the write threw, or the app stopped in between), count
 *   a BOLUS at [DashActiveCommand.requestedBolusUnits] and a TEMP_BASAL as started, both at
 *   [DashActiveCommand.createdAtMs]. Counting nothing could leave insulin the pod gave out of insulin
 *   on board, and the loop could then dose again on top of it.
 */
data class DashCommandOutcome(
    val confirmation: DashCommandConfirmation,
    val command: DashActiveCommand,
    val decidedLate: Boolean,
    override val decidedAtMs: Long,
    val endedBolus: DashLastBolus? = null,
    val podDiscarded: Boolean = false,
) : DashPumpOutcome

/**
 * Omnipod Dash [Pump] for iOS, on the stack ported from Android: [LTKExchanger] (pairing),
 * [SessionEstablisher] (EAP-AKA) and [Session] (encrypted commands and their answers), all over
 * [MessageIO] and `DashBleLink`.
 *
 * Lifecycle:
 * 1. [pair]: the LTK is negotiated and stored with the pod id in the Keychain.
 * 2. [establishSession]: EAP-AKA gives the session key, as `SharedBleManager.establishSession`.
 * 3. Commands: each one is sent, its answer is read and acknowledged, and a status answer decides
 *    whether the pod took a delivery command ([DashCommandConfirmation]).
 *
 * The two counters, exactly as on Android:
 * - the outer packet counter is `SessionKeys.msgSequenceNumber`, inside the [Session]. The first
 *   session after a fresh pairing starts from the pair result (`OmnipodDashBleManagerImpl.pairNewPod`),
 *   every other one from 1 (`SharedBleManager.connect`). It is not stored;
 * - the inner 4-bit command sequence is [DashPodStateManager.messageSequenceNumber], stored. Each
 *   command is built with it, it goes up by one when the command was sent (or sent but not
 *   confirmed) and by one when an answer was read (`OmnipodDashManagerImpl.PodEventInterceptor`).
 *
 * **Safety guard**: every method that sends bytes to the pod calls [requireSession] first. Without
 * a paired pod (LTK), an established session and a connection, the call fails closed: no command
 * is built and no bytes are sent.
 *
 * **Delivery commands** go through the active command of [DashPodStateManager]. A result reports
 * success (and a bolus reports insulin delivered) only on [DashCommandConfirmation.SUCCESS]. DENIED
 * and SENDING_FAILURE are failures with nothing delivered. NOT_CONFIRMED is a failure with nothing
 * reported delivered, and the command stays open: every later delivery command is refused until a
 * status ([getPumpStatus]) decides it. Every final outcome, at once or late, goes to
 * [onCommandDecided] ([DashCommandOutcome]), as Android `handleCommandConfirmation` writes it to
 * `PumpSync`. A late SUCCESS bolus also sets [lastBolusAmount] and [lastBolusTime].
 *
 * **A bolus is followed to its end** ([DashBolusWait], Android `waitForBolusDeliveryToComplete`):
 * after its SUCCESS, [deliverTreatment] keeps the command lock, reads a status from time to time and
 * shows the progress in [BolusProgressData], until the pod no longer delivers it. [stopBolusDelivering]
 * only asks for the stop, as Android `bolusCanceled`; the wait sends it. What went in is asked -
 * left ([DashLastBolus.deliveredUnits]). The last bolus is stored, so a crash during the wait keeps
 * what is needed to count it.
 *
 * Deliberate difference from Android: before a delivery command, when the 4-bit sequence it would
 * be built with is the one the last status reports for the last programming command, a status is
 * read first. Android sends the command, and a later status then decides SUCCESS for it even when
 * the pod never got it (see [DashCommandConfirmation.of]).
 *
 * Deliberate differences from Android taken from OmnipodKit (Trio), all so that insulin is not
 * counted wrong:
 * - an answer whose 4-bit message sequence or unique id does not match the command is no answer
 *   (`Session.readAndAckResponse`): the command stays open and the link is closed;
 * - a bolus and a temp basal are decided on a status of this session ([refusedByDeliveryStatus]):
 *   a status is read first when the stored one is missing, older or shows a conflict, and the
 *   command is refused with nothing sent on a suspended pod or (bolus) a bolus running;
 * - no command other than a status read leaves while a command is open, on any path ([sendCommand]).
 *
 * The link to the pod ([DashPodLink]) belongs to one BLE connection. `DashPodConnection` makes a
 * new one for each connection and gives it to [attachLink]; this pump and its [DashPodStateManager]
 * (counters, open command) live on across drops. As on Android, a session is only set up on a fresh
 * connection: an exception while pairing, setting up the session, sending a command or reading its
 * answer makes Android disconnect (`SharedBleManager`), and here it closes the link for good
 * ([DashPodLink.close] cancels the BLE connection). Every later call is refused and sends nothing
 * until a new connection is attached. A link that was lost, or that a newer connection replaced, is
 * refused the same way. [disconnect] closes the link too, except while an activation runs
 * ([runActivation]), as Android.
 *
 * The BLE connection itself comes from the connector (`connectorFactory`, on the iPhone a
 * `DashHeldConnection`): [connect] holds a connection to the stored pod that is made again after
 * every drop, with a new link and session each time, until [disconnect] or [stopConnecting]; each
 * operation waits for it first when there is no session (Android `observeConnectToPod`, outside the
 * command lock); part 1 of the activation scans for a new pod and connects to it (Android
 * `pairNewPod`); a deactivated or discarded pod is forgotten and never connected again.
 *
 * **Beeps** come from [beeps] (all off until the plugin gives the user settings), and go into the
 * same fields as on Android: the reminders of a bolus, a temp basal and a basal program, and the beep
 * of a stop and of a suspend. The activation commands never beep, as on Android.
 *
 * Threading: commands, [pair], [establishSession] and `DashPodConnection.connect` are serialized by
 * [commandMutex], as Android serializes `sendCommand`, `connect` and `pairNewPod` with its busy flag.
 * Commands and pairing wait for it; `DashPodConnection.connect` does not wait
 * ([DashLinkClient.tryLockLink]), so a connect from inside a command is refused instead of blocking.
 * `MessageIO` blocks the calling thread; the BLE calls themselves run on the CoreBluetooth queue
 * (`QueuedBleCentral`).
 */
@OptIn(ExperimentalAtomicApi::class)
class IosDashPump(
    private val aapsLogger: AAPSLogger,
    /**
     * Gets every final outcome of a delivery command ([DashCommandOutcome]) and every bolus a pod
     * fault ended ([DashBolusEndedByFault]). No default on purpose: whoever builds the pump has to
     * decide where a late bolus and a bolus correction are recorded.
     */
    private val onCommandDecided: (DashPumpOutcome) -> Unit,
    /**
     * Gets a delivery command (its active command) once it reached the pod: the write was taken
     * (`CommandSendSuccess`) or may have been (`CommandSendErrorConfirming`), before its answer is read.
     * Android `podEvent.isCommandSent()`, where the plugin writes a bolus, a temp basal and the
     * PUMP_SUSPEND temp basal of a suspend to `PumpSync` (`pumpSyncBolusStart`, `pumpSyncTempBasal`).
     * Called with the command lock held, on the queue thread. Not called for a command that never left
     * the phone, nor when the write threw (Android has no `CommandSent` event then either).
     */
    private val onCommandSent: (DashActiveCommand) -> Unit = {},
    private val stateManager: DashPodStateManager = DashPodStateManager(DashKeychainStateStore()),
    /** A new key pair for each pairing, as Android `LTKExchanger` builds a new `KeyExchange`. */
    private val newKeyExchange: () -> DashKeyExchange = {
        DashKeyExchange(x25519 = IosX25519Dh(), aesCmac = IosAesCmac(), randomBytes = IosSecureRandomBytes())
    },
    private val aesCcm: AesCcmCipher = IosAesCcmCipher(),
    private val aesEcb: AesEcbBlockCipher = IosAesEcbBlockCipher(),
    private val randomBytes: SecureRandomBytes = IosSecureRandomBytes(),
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * The bolus progress the bolus dialog shows, as `IosMedtrumPump` gets it. Null in tests that do
     * not look at it.
     */
    private val bolusProgressData: BolusProgressData? = null,
    /**
     * The timer of the bolus wait. A test gives the virtual time of its test dispatcher (the default
     * `delay` does that inside `runTest`).
     */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * The beep settings of the user, read each time a command is built (Android reads its
     * preferences there). All off by default: beeps stay off until the plugin (phase 4) gives the user
     * settings here.
     */
    private val beeps: () -> DashBeepSettings = { DashBeepSettings() },
    /**
     * True when AAPS expects a temp basal to run (Android `pumpSync.expectedPumpState().temporaryBasal
     * != null`). [cancelTempBasal] sends nothing only when this is false and the pod has no confirmed
     * temp basal running, as Android. True by default, so until the plugin (phase 4) gives the real
     * answer every cancel is sent (fail closed).
     */
    private val expectedTempBasalRunning: () -> Boolean = { true },
    /**
     * Makes the BLE side of this pump: on the iPhone a `DashHeldConnection` on the Dash central
     * (`IosDashBleHost`). It gets this pump as its [DashLinkClient]. Null: this pump makes no
     * connection itself ([connect] does nothing, nothing is scanned); the tests that connect a
     * `DashPodConnection` by hand use that.
     */
    connectorFactory: ((DashLinkClient) -> DashPodConnector)? = null,
) : Pump, DashLinkClient {

    private var session: Session? = null

    /**
     * [DashPodStateManager.statusResponseCount] when [session] was set up: a stored status with a
     * higher count was read in this session. See [refusedByDeliveryStatus].
     */
    private var statusCountAtSessionStart: Long = 0L
    private val commandMutex = Mutex()
    private var busy = false

    /** Android `bolusDeliveryInProgress`: true while [deliverTreatment] runs. */
    @Volatile
    private var bolusDeliveryInProgress = false

    /** Android `bolusCanceled`: the user asked to stop the bolus that [deliverTreatment] delivers. */
    @Volatile
    private var bolusCanceled = false

    /** Android `OmnipodDashManagerImpl.activationRunning`: true inside [runActivation]. */
    @Volatile
    private var activationRunning = false

    /** The link of the current BLE connection, given by [attachLink]. Null before the first one. */
    private var link: DashPodLink? = null

    /**
     * 1 from a queue [connect] until the session it waits for is made ([startSession]), else 0.
     * Android counts one success per `connect` that completed, not one per session: a link the held
     * connection makes again by itself, or the reconnect inside the bolus wait, is not counted.
     */
    private val queueConnectWaiting = AtomicInt(0)

    /**
     * Connection counts not yet written to the state. [DashPodStateManager] is not thread-safe and
     * [stopConnecting] (and a [connect] that finds a session) run on the queue thread, outside
     * [commandMutex], while the held connection may be setting up a session with the lock held. A
     * write from there could undo the EAP SQN that the session set up writes at the same time, so
     * the counts wait here and [flushConnectionCountsWithLock] writes them with the lock held.
     */
    private val pendingSuccessfulConnections = AtomicInt(0)
    private val pendingFailedConnections = AtomicInt(0)

    /**
     * The outer counter after a fresh pairing on this connection: the next [establishSession]
     * starts from it, as `pairNewPod` passes `pairResult.msgSeq` to `establishSession`. Null
     * otherwise, and the session then starts from 1.
     */
    private var pairMsgSeq: Byte? = null

    // -- Safety guard -------------------------------------------------------

    /**
     * Android `disconnect(false)` after an exception: the session ends and this link is not used
     * again. The pod may still hold half a message or a late answer, so a new session on it could
     * read that answer as its own.
     */
    private fun closeLink(reason: String) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash link closed: $reason")
        link?.close(reason)
        endSession()
    }

    /** The link of the current connection, or an exception when there is none or it is not usable. */
    private fun requireLinkOpen(): DashPodLink {
        val current = link ?: throw DashPumpNotPairedException("Refusing: no connection to the pod")
        if (!current.isUsable) {
            throw DashPumpNotPairedException("Refusing: the link to the pod was lost or closed. Connect again.")
        }
        return current
    }

    override fun tryLockLink(): Boolean = commandMutex.tryLock()

    override fun unlockLink() {
        commandMutex.unlock()
    }

    /**
     * A new BLE connection (`DashPodConnection`). The session of the old connection ends; the stored
     * state (both counters, the open command) stays. The next [establishSession] starts the outer
     * counter from 1, as `SharedBleManager.connect` (`establishSession(1)`), unless [pair] runs on
     * this link first. `DashPodConnection` holds [commandMutex] while it calls this, so no command
     * runs and a command never changes link halfway.
     */
    override fun attachLink(link: DashPodLink) {
        endSession()
        this.link = link
        aapsLogger.info(LTag.PUMPBTCOMM, "Dash link ${link.number} attached")
    }

    /**
     * Called by `DashPodConnection` with [commandMutex] held: [establishSession] without the lock. The
     * first session made after a queue [connect] counts as one connection that worked, as Android
     * counts each `connect` that completed (`incrementSuccessfulConnectionAttemptsAfterRetries`), for
     * the connection quality row. Later sessions of the same held connect are not counted.
     */
    override fun startSession() {
        establishSessionWithLock()
        if (queueConnectWaiting.exchange(0) == 1) pendingSuccessfulConnections.incrementAndFetch()
        flushConnectionCountsWithLock()
    }

    /** Writes the counts of [pendingSuccessfulConnections] and [pendingFailedConnections]. Call it with [commandMutex] held. */
    private fun flushConnectionCountsWithLock() {
        repeat(pendingSuccessfulConnections.exchange(0)) { stateManager.incrementSuccessfulConnectionAttemptsAfterRetries() }
        repeat(pendingFailedConnections.exchange(0)) { stateManager.incrementFailedConnectionsAfterRetries() }
    }

    /**
     * Runs [block] as one activation, as Android `OmnipodDashManagerImpl` marks the whole activation
     * (`activationRunning`, from `pairNewPod` on). While it runs, [isBusy] is true, so the queue sends
     * nothing, and [disconnect] is ignored. On Android, closing the link during the activation broke
     * the pairing (#5219): [pair] and [establishSession] are separate calls here, and a disconnect
     * between them would cancel the BLE link and lose the outer counter of the pair result.
     *
     * The caller connects (`DashConnectResult.LinkReady`), pairs and sets up the session inside
     * [block].
     */
    fun <T> runActivation(block: () -> T): T {
        val before = activationRunning
        activationRunning = true
        try {
            return block()
        } finally {
            activationRunning = before
        }
    }

    /** True while [runActivation] runs (Android `isActivationRunning`). */
    val isActivationRunning: Boolean get() = activationRunning

    override fun hasSession(): Boolean = session != null && link?.isUsable == true

    /**
     * Fails closed unless the pod is paired (LTK present), the session is
     * established, and the link of the current connection is still usable
     * (not lost, not closed, not replaced). Every command-sending method
     * must call this first.
     */
    private fun requireSession(): Session {
        requireLinkOpen()
        if (stateManager.ltk == null) {
            throw DashPumpNotPairedException("Refusing command: pod is not paired (no LTK)")
        }
        return session ?: throw DashPumpNotPairedException("Refusing command: no session established")
    }

    override fun isPaired(): Boolean = stateManager.ltk != null

    /**
     * True when the stored pod state is known (see `DashPodStateManager.isStateKnown`). While it is
     * not, the store is read again first, so this is also the retry of that read.
     */
    fun isPodStateKnown(): Boolean = stateManager.reloadIfUnknown()

    /**
     * Null when the stored pod state is known ([isPodStateKnown], which reads it again first). Else
     * the failure of [name], with nothing sent: the pod on the body may still run with the key this
     * phone cannot read now, so no command, no pairing and no discard may go until it is read.
     * Deliberate difference from Android, which has no such read failure (see
     * `DashPodStateManager.isStateKnown`).
     */
    private fun refusedStateUnknown(name: String): PumpEnactResult? {
        if (isPodStateKnown()) return null
        val reason = stateManager.stateUnreadableReason
        aapsLogger.error(LTag.PUMP, "$name refused: the stored pod state cannot be read ($reason). Nothing is sent.")
        return failure("$name refused: the stored pod state cannot be read now ($reason). Nothing was sent. Unlock the phone, then try again.")
    }

    // -- Pairing & session ----------------------------------------------------

    /**
     * Pairs with a new pod and gives it the id [podAddress] (4 bytes). The pod id is stored as the
     * unique id with the LTK, and every later packet is addressed to it, as on Android
     * (`LTKExchanger` sends `ids.podId` in SP1, `updateFromPairing(ids.podId, ...)` stores it).
     *
     * The default is the id Android always gives: the controller id plus one (`Ids.podId` when the
     * pod has no unique id yet).
     *
     * Does nothing when a pod is already paired, as Android (`PodEvent.AlreadyPaired`). Must be
     * followed by [establishSession] on the same connection. Run both inside [runActivation].
     * Waits for a running command, as it holds [commandMutex].
     */
    fun pair(podAddress: ByteArray = Ids(null).podId.address) {
        runBlocking { commandMutex.withLock { pairWithLock(podAddress) } }
    }

    private fun pairWithLock(podAddress: ByteArray) {
        if (stateManager.ltk != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Pod already paired, pairing skipped")
            return
        }
        val current = requireLinkOpen()
        val ids = Ids(podIdOf(podAddress))
        try {
            val pairResult = LTKExchanger(aapsLogger, current.messageIO, ids, newKeyExchange()).negotiateLTK()
            stateManager.updateFromPairing(uniqueId = ids.podId.toLong(), ltk = pairResult.ltk)
            pairMsgSeq = pairResult.msgSeq
        } catch (e: Exception) {
            closeLink("pairing failed: ${e.message}")
            throw e
        }
    }

    /**
     * Sets up the encrypted session (EAP-AKA). Port of `SharedBleManager.establishSession`: one try
     * with the next EAP SQN; after a resynchronization, the SQN of the pod is taken and one more try
     * is made with the next SQN and the same outer counter; a second resynchronization is an error.
     * The SQN is stored once the session is set up.
     *
     * Only on a fresh link, as Android: when it fails, the link is closed, and a second call on
     * the same pump sends nothing. Android never puts a second challenge on the same connection.
     * Waits for a running command, as it holds [commandMutex].
     */
    fun establishSession() {
        runBlocking { commandMutex.withLock { establishSessionWithLock() } }
    }

    private fun establishSessionWithLock() {
        val current = requireLinkOpen()
        val ltk = stateManager.ltk ?: throw DashPumpNotPairedException("Missing LTK, activate the pod first")
        val uniqueId = stateManager.uniqueId ?: throw DashPumpNotPairedException("Missing unique id, activate the pod first")
        val ids = Ids(uniqueId)
        val msgSeq = pairMsgSeq ?: 1.toByte()
        pairMsgSeq = null
        session = null
        try {
            var response = negotiate(current, ltk, ids, msgSeq)
            if (response is SessionNegotiationResynchronization) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Updating pod EAP SQN to: ${response.synchronizedEapSqn}")
                stateManager.updateEapAkaSequenceNumber(response.synchronizedEapSqn.toLong())
                response = negotiate(current, ltk, ids, msgSeq)
                if (response is SessionNegotiationResynchronization) {
                    throw SessionEstablishmentException("Received resynchronization SQN for the second time")
                }
            }
            stateManager.commitEapAkaSequenceNumber()
            val keys = response as SessionKeys
            statusCountAtSessionStart = stateManager.statusResponseCount
            session = Session(
                aapsLogger = aapsLogger,
                msgIO = current.messageIO,
                ids = ids,
                sessionKeys = keys,
                enDecrypt = EnDecrypt(aapsLogger, keys.nonce, keys.ck, aesCcm)
            )
        } catch (e: Exception) {
            closeLink("session set up failed: ${e.message}")
            throw e
        }
    }

    private fun negotiate(link: DashPodLink, ltk: ByteArray, ids: Ids, msgSeq: Byte): SessionNegotiationResponse =
        SessionEstablisher(
            aapsLogger = aapsLogger,
            msgIO = link.messageIO,
            ltk = ltk,
            eapSqn = stateManager.increaseEapAkaSequenceNumber(),
            ids = ids,
            msgSeq = msgSeq,
            aesEcb = aesEcb,
            randomBytes = randomBytes
        ).negotiateSessionKeys()

    /** Tears down the session; the LTK stays in the Keychain. */
    fun endSession() {
        session = null
        pairMsgSeq = null
    }

    // -- Activation -----------------------------------------------------------

    /** The exchanges of the activation. Called with [commandMutex] held. */
    private val activationPod = object : DashActivationPod {
        override fun send(build: (uniqueId: Int, sequence: Short) -> Command, beforeSend: () -> Unit): DashExchange {
            // Named: a trailing lambda would be `afterSent`, and the prime or cannula mark would then be
            // stored only after the bytes went. A stop or a send error in between would leave no mark,
            // and the next try would give that bolus a second time.
            val sent = sendCommand(build, beforeSend = { beforeSend() })
            return DashExchange(sent.reachedRadio, sent.error)
        }

        /** The link may have dropped during the prime or the cannula wait; the activation holds the command lock. */
        override fun connectAfterWait() = reconnectInsideBolus("status after the activation wait")
    }

    private fun activation() = DashActivation(stateManager, activationPod, aapsLogger, now, sleep)

    /**
     * Android `OmnipodDashManagerImpl.activatePodPart1`, as the wizard calls it (`doInitializePod`):
     * - refused unless the activation progress is before PHASE_1_COMPLETED (Android
     *   `observePodReadyForActivationPart1`), before anything is sent;
     * - a pod not paired yet is paired on the link of the connection ([pair], with the id Android
     *   gives) and the session is set up on it ([establishSession]), as Android `pairNewPod`. With a
     *   connector and no usable link, the pod is found first, as Android `pairNewPod`: a scan for a
     *   pod that can be paired ([DashPodConnector.connectNewPod]), its peripheral id stored
     *   (Android `podState.bluetoothAddress`), then the held connection to it, all inside the
     *   activation (so a [disconnect] from the queue cannot break it) and outside the command lock.
     *   Without a connector the caller connects first (`DashPodConnection.connect` gives
     *   `LinkReady`). A pod already paired needs a session, as after a reconnect: with a connector
     *   it is awaited first (Android `observeConnectToPod`);
     * - then the steps of [DashActivation.activatePart1], each stored as it is done.
     *
     * The whole call is one activation ([isActivationRunning], [isBusy], a [disconnect] is ignored)
     * and holds the command lock, the waits too. A failure is a result, not an exception: the stored
     * progress says where to go on, and calling again resumes there.
     *
     * [lowReservoirAlertSettings]: the user settings the trigger was made from. When part 1 is done
     * they are stored, as the Android wizard calls `updateLowReservoirAlertSettings` after part 1, so
     * a later [updateAlertConfiguration] with the same settings sends nothing. Null stores nothing.
     *
     * Not here yet: the history record (phase 4).
     */
    suspend fun activatePodPart1(
        lowReservoirAlertTrigger: AlertTrigger.ReservoirVolumeTrigger?,
        lowReservoirAlertSettings: DashLowReservoirAlertSettings? = null,
    ): PumpEnactResult =
        activationCall(
            "Pod activation part 1",
            connectFirst = { connectForActivation(newPod = true) },
            onFailure = ::releaseUnpairedPod,
        ) {
            if (!stateManager.activationProgress.isBefore(ActivationProgress.PHASE_1_COMPLETED)) {
                throw DashActivationException("Pod is in an incorrect state")
            }
            if (stateManager.ltk == null) {
                pairWithLock(Ids(null).podId.address)
                establishSessionWithLock()
            }
            activation().activatePart1(lowReservoirAlertTrigger)
            lowReservoirAlertSettings?.let { stateManager.updateLowReservoirAlertSettings(it) }
        }

    /**
     * Android `OmnipodDashManagerImpl.activatePodPart2`, as the wizard calls it (`doInsertCannula`):
     * refused unless the progress is from PHASE_1_COMPLETED and before COMPLETED, then the steps of
     * [DashActivation.activatePart2]. The program the pod took is the running one from
     * PROGRAMMED_BASAL on (see [DashActivation]). Same rules as [activatePodPart1].
     *
     * [expirationAlertSettings]: the user settings the hours were made from. When part 2 is done they
     * are stored, as the Android wizard calls `updateExpirationAlertSettings` after part 2. Null
     * stores nothing.
     *
     * Not here yet (phase 4, the plugin): the history record, and the `PumpSync` records after part 2
     * (the end of the fake TBR, `connectNewPump`, the cannula and insulin change events), and a status
     * read once it is done.
     *
     * An open command not confirmed is decided first ([decideOpenCommandBeforePart2]), as
     * [deactivatePod] does.
     */
    suspend fun activatePodPart2(
        basalProgram: BasalProgram,
        userConfiguredExpirationReminderHours: Long?,
        userConfiguredExpirationAlarmHours: Long?,
        expirationAlertSettings: DashExpirationAlertSettings? = null,
    ): PumpEnactResult =
        activationCall("Pod activation part 2", connectFirst = { connectForActivation(newPod = false) }) {
            decideOpenCommandBeforePart2()
            activation().activatePart2(basalProgram, userConfiguredExpirationReminderHours, userConfiguredExpirationAlarmHours)
            expirationAlertSettings?.let { stateManager.updateExpirationAlertSettings(it) }
        }

    /**
     * Deliberate difference from Android (insulin counted wrong, and a user action lost): when a
     * command is open and NOT_CONFIRMED at the start of part 2, a status is read first, with the
     * command lock held, as [deactivatePod] does (owner decision). When that status decides the
     * command, its outcome is given to [onCommandDecided] (so its insulin is counted right) and part 2
     * goes on. When no status can be read, or it does not decide the command, the command stays open
     * and part 2 is refused as before, with nothing but that status sent ([sendCommand] sends no
     * programming command while a command is open). Android sends part 2 at once, and its basal
     * program then moves the last programming sequence of the pod, so no later status could tell what
     * happened to the open command; before this change part 2 was refused here until something else
     * read a status, and the user could not finish the activation from the wizard.
     *
     * Only for a part 2 that may run (progress from PHASE_1_COMPLETED and before COMPLETED): a part 2
     * that is refused for its progress sends nothing, as Android. Called with [commandMutex] held.
     */
    private fun decideOpenCommandBeforePart2() {
        val progress = stateManager.activationProgress
        if (!progress.isAtLeast(ActivationProgress.PHASE_1_COMPLETED) || !progress.isBefore(ActivationProgress.COMPLETED)) return
        if (stateManager.activeCommand == null || stateManager.commandConfirmation() != DashCommandConfirmation.NOT_CONFIRMED) return
        aapsLogger.info(LTag.PUMP, "Activation part 2: the last command is not confirmed, reading a status first")
        readStatusWithLock()
    }

    /**
     * The connection an activation step needs, made before the command lock is taken (the set up
     * needs that lock). Returns null when the step may go on, or the reason it may not. Without a
     * connector nothing is done here: the step then fails closed without a link or a session.
     *
     * [newPod]: part 1. A pod not paired yet and no usable link: scan, store the peripheral id, and
     * connect to it ([DashPodConnector.connectNewPod], Android `pairNewPod`); the answer must be
     * `LinkReady`. Android scans again on every part 1 before the pairing, so this does too.
     * Otherwise (a paired pod, or part 2): the held connection is awaited until a session is ready.
     */
    private fun connectForActivation(newPod: Boolean): String? {
        val c = connector ?: return null
        // Android checks the activation progress first (`observePodReadyForActivationPart1` and
        // `observePodReadyForActivationPart2`): the block refuses, with nothing scanned, no
        // connection and nothing sent.
        val progress = stateManager.activationProgress
        if (newPod && !progress.isBefore(ActivationProgress.PHASE_1_COMPLETED)) return null
        if (!newPod && !(progress.isAtLeast(ActivationProgress.PHASE_1_COMPLETED) && progress.isBefore(ActivationProgress.COMPLETED))) return null
        if (newPod && stateManager.ltk == null) {
            if (link?.isUsable == true) return null
            val result = try {
                c.connectNewPod(DashHeldConnection.CONNECT_TIMEOUT_MS) { stateManager.updateBluetoothAddress(it) }
            } catch (e: IllegalStateException) {
                // Another pod than the one stored by an earlier try (Android: the setter throws and
                // `pairNewPod` fails). Nothing is connected to it.
                return "Could not connect to a new pod: ${e.message}"
            }
            return if (result is DashConnectResult.LinkReady) null else "Could not connect to a new pod: $result"
        }
        if (hasSession()) return null
        val address = stateManager.bluetoothAddress ?: return "No pod address stored"
        return when (val result = c.awaitConnection(address, DashHeldConnection.CONNECT_TIMEOUT_MS)) {
            is DashConnectResult.SessionReady, is DashConnectResult.AlreadyConnected -> null
            else                                                                     -> "Could not connect to the pod: $result"
        }
    }

    /**
     * Android `pairNewPod` calls `disconnect(false)` on every failure (connect timeout, pairing,
     * session). Here: when part 1 failed and the pod is still not paired, the held connection to it
     * stops. Otherwise it would stay connected (or keep connecting) to the new pod, a connected pod
     * does not advertise, and the scan of the next try would not find it again.
     */
    private fun releaseUnpairedPod() {
        if (stateManager.ltk != null) return
        connector?.stopHolding("pod activation part 1 failed before the pairing")
    }

    /**
     * One activation call: [activationRunning] for the whole call, then [connectFirst] (outside the
     * lock), then [commandMutex] for [block]; errors are a failure result. On a failure [onFailure]
     * runs once the lock is given back.
     */
    private suspend fun activationCall(
        name: String,
        connectFirst: () -> String? = { null },
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
    ): PumpEnactResult {
        val result = activationCallInner(name, connectFirst, block)
        if (!result.success) onFailure()
        return result
    }

    private suspend fun activationCallInner(
        name: String,
        connectFirst: () -> String?,
        block: suspend () -> Unit,
    ): PumpEnactResult {
        refusedStateUnknown(name)?.let { return it }
        val before = activationRunning
        activationRunning = true
        try {
            connectFirst()?.let {
                aapsLogger.error(LTag.PUMP, "$name: $it")
                return failure("$name: $it")
            }
            return commandMutex.withLock {
                busy = true
                try {
                    block()
                    aapsLogger.info(LTag.PUMP, "$name completed")
                    DashPumpEnactResult(success = true, enacted = true, comment = "$name completed")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    aapsLogger.error(LTag.PUMP, "Error in $name", e)
                    failure("$name: ${e.message}")
                } finally {
                    busy = false
                }
            }
        } finally {
            activationRunning = before
        }
    }

    // -- Pump identity ------------------------------------------------------

    override fun manufacturer(): ManufacturerType = ManufacturerType.Insulet
    override fun model(): PumpType = PumpType.OMNIPOD_DASH
    override fun serialNumber(): String =
        stateManager.currentState().uniqueId?.toString() ?: ""

    override val pumpDescription: PumpDescription = PumpDescription().apply {
        pumpType = PumpType.OMNIPOD_DASH
        isBolusCapable = true
        bolusStep = 0.05
        isTempBasalCapable = true
        isExtendedBolusCapable = false
    }

    override val isFakingTempsByExtendedBoluses: Boolean = false

    // -- State --------------------------------------------------------------

    private val lastBolusTimeFlow = MutableStateFlow<Long?>(null)
    private val lastBolusAmountFlow = MutableStateFlow<PumpInsulin?>(null)

    /**
     * The last bolus the pod took, set when its SUCCESS is known (at once or late), and by
     * [syncPumpFlows] from the stored [DashPodStateManager.lastBolus] (Android: its `startTime` and
     * `requestedUnits`). The time is when SUCCESS became known, not when the pod delivered it.
     */
    override val lastBolusTime: StateFlow<Long?> = lastBolusTimeFlow
    override val lastBolusAmount: StateFlow<PumpInsulin?> = lastBolusAmountFlow

    /** Android `lastDataTime`: when the last answer with a pod status arrived, set by [syncPumpFlows]. */
    private val lastDataTimeFlow = MutableStateFlow(0L)
    override val lastDataTime: StateFlow<Long> = lastDataTimeFlow

    // Not tracked yet on iOS (needs `BasalProgram.rateAt`, phase 4): a placeholder, as in the iOS Medtrum driver.
    override val baseBasalRate: PumpRate = PumpRate(0.0)

    /** Android `reservoirLevel`, set by [syncPumpFlows]. */
    private val reservoirLevelFlow = MutableStateFlow(PumpInsulin(0.0))
    override val reservoirLevel: StateFlow<PumpInsulin> = reservoirLevelFlow
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    /**
     * Android `syncPumpFlows`, after a status read and before a bolus: the flows the overview and the
     * queue read are taken from the stored state.
     * - [lastDataTime]: [DashPodStateManager.lastUpdatedSystemMs];
     * - [lastBolusTime] and [lastBolusAmount]: the start time and the asked units of the stored last
     *   bolus, null when there is none;
     * - [reservoirLevel]: 0 before the activation is completed; then the last reservoir reading below
     *   1023 ([DashPodStateManager.pulsesRemaining]) x 0.05 U, or [RESERVOIR_OVER_50_UNITS_DEFAULT] when
     *   there was none yet (the pod has more than 50 U, its reading is then 1023). As Android, a later
     *   status with 1023 or more (an alarm status reads the field as an unmasked 16-bit short) keeps the
     *   last lower reading, so the reservoir check of a bolus never sees 75 U after a low reading.
     */
    private fun syncPumpFlows() {
        lastDataTimeFlow.value = stateManager.lastUpdatedSystemMs
        val bolus = stateManager.lastBolus
        lastBolusTimeFlow.value = bolus?.startTimeMs
        lastBolusAmountFlow.value = bolus?.requestedUnits?.let { PumpInsulin(it) }
        reservoirLevelFlow.value = if (stateManager.activationProgress.isBefore(ActivationProgress.COMPLETED)) {
            PumpInsulin(0.0)
        } else {
            PumpInsulin(stateManager.pulsesRemaining?.let { it * PodConstants.POD_PULSE_BOLUS_UNITS } ?: RESERVOIR_OVER_50_UNITS_DEFAULT)
        }
    }

    /**
     * Android `podStateManager.tempBasalActive`: delivery is not suspended and the temp basal the pod
     * confirmed last ([DashPodStateManager.tempBasal]) has not run out.
     */
    fun tempBasalActive(): Boolean = stateManager.tempBasalActive(now())

    override fun isConfigured(): Boolean = isPaired()
    override fun isInitialized(): Boolean = isPaired() && hasSession()
    /** Android `isSuspended()`: the last status shows delivery suspended ([DashPodStateManager.isSuspended]). */
    override fun isSuspended(): Boolean = stateManager.isSuspended
    /**
     * Android `isBusy`: a command runs, an activation runs ([runActivation], [activatePodPart1],
     * [activatePodPart2]), or an activation was started and is not completed. The queue then sends
     * nothing (#5219).
     */
    override fun isBusy(): Boolean {
        val progress = stateManager.activationProgress
        return busy || activationRunning ||
            (progress != ActivationProgress.NOT_STARTED && progress.isBefore(ActivationProgress.COMPLETED))
    }

    /**
     * Android `isConnected()`: `!podStateManager.isPodRunning || bluetoothConnectionState == CONNECTED`.
     * With no running pod (none paired, deactivated, discarded, in alarm or half activated) the queue
     * sees a connected pump and runs the command at once; the command then connects itself when it
     * needs the pod ([connectBeforeCommand]) or answers without it ("no running pod"). Otherwise true
     * while the link of the current connection is usable and has a session.
     */
    override fun isConnected(): Boolean = !stateManager.isPodRunning || hasSession()

    /** True while the held connection is wanted and no session is ready (Android: a connect thread runs). */
    override fun isConnecting(): Boolean = connector?.isConnecting ?: false

    /** True while the BLE link is up and its session is being set up (Android: connecting and CONNECTED). */
    override fun isHandshakeInProgress(): Boolean = connector?.isHandshakeInProgress ?: false

    /**
     * Android `connect(reason)`: starts the connection and returns at once; the queue then waits
     * for [isConnected]. Here the held connection to the stored pod ([DashPodConnector.hold]): iOS
     * keeps the connect open with no time limit, and each time the link comes up a new link and a
     * new session are made (`DashPodConnection.connect`). A drop is made good by itself until
     * [disconnect] or [stopConnecting].
     *
     * Does nothing when no pod address is stored (no pod paired: the activation scans for the pod
     * and connects itself, see [activatePodPart1]) or when there is no connector.
     *
     * Connection quality, as Android: a connect that finds a session already up counts as one success
     * at once (Android `connect` completes with `AlreadyConnected`); otherwise the next session made
     * counts as one ([startSession]). A second connect while the first still waits counts nothing more
     * (Android returns "Already connecting"). The count is written with the command lock held
     * ([flushConnectionCountsWithLock]), so it shows after the next session or command.
     */
    override fun connect(reason: String) {
        if (!isPodStateKnown()) {
            aapsLogger.error(LTag.PUMP, "Dash connect ($reason): the stored pod state cannot be read (${stateManager.stateUnreadableReason}), nothing to connect to")
            return
        }
        val address = stateManager.bluetoothAddress
        if (address == null) {
            aapsLogger.info(LTag.PUMP, "Dash connect ($reason): no pod address stored, nothing to connect to")
            return
        }
        val c = connector ?: return
        if (hasSession()) {
            if (queueConnectWaiting.load() == 0) pendingSuccessfulConnections.incrementAndFetch()
        } else {
            queueConnectWaiting.store(1)
        }
        c.hold(address, reason)
    }

    /**
     * Closes the current link for good, as Android `disconnect(false)` disconnects the GATT, and the
     * held connection stops: nothing connects again until [connect]. Ignored while an activation
     * runs ([runActivation]), as Android (#5219).
     */
    override fun disconnect(reason: String) {
        if (activationRunning) {
            aapsLogger.info(LTag.PUMP, "Dash disconnect ignored, pod activation is running ($reason)")
            return
        }
        // First stop holding, so the drop that the close makes is not connected again.
        connector?.stopHolding("disconnect: $reason")
        closeLink("disconnect: $reason")
    }

    /**
     * Android `stopConnecting` (the queue gave up waiting): the connect stops and the link is closed
     * (`disconnect(true)`). As Android, not held back by a running activation.
     */
    override fun stopConnecting() {
        // Android: the queue gave up waiting, so this connection failed. Written later, with the lock
        // held: the held connection may be setting up a session now (see pendingFailedConnections).
        queueConnectWaiting.store(0)
        pendingFailedConnections.incrementAndFetch()
        connector?.stopHolding("stop connecting")
        closeLink("stop connecting")
    }

    /**
     * Android `observeConnectToPod` before an operation: when there is no session, wait until the
     * held connection is up and set up, at most [DashHeldConnection.CONNECT_TIMEOUT_MS] (Android
     * `DEFAULT_CONNECT_TIMEOUT_MS`). Outside the command lock, because the set up needs that lock.
     * The result is only logged: without a session the operation then fails closed by itself, with
     * nothing sent.
     *
     * [stateReady]: the state check Android runs before its connect (`observePodRunning`,
     * `observeUniqueIdSet`, ...). When it is false nothing is connected: the operation refuses by
     * itself, with no BLE contact and no new session, as Android.
     *
     * The bolus wait holds the command lock, so it connects with [reconnectInsideBolus] instead.
     */
    private fun connectBeforeCommand(name: String, stateReady: Boolean) {
        val c = connector ?: return
        if (!stateReady) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Connect before $name skipped: Pod is in an incorrect state")
            return
        }
        if (hasSession()) return
        val address = stateManager.bluetoothAddress ?: return
        val result = c.awaitConnection(address, DashHeldConnection.CONNECT_TIMEOUT_MS)
        aapsLogger.info(LTag.PUMPBTCOMM, "Connect before $name: $result")
    }

    /**
     * [connectBeforeCommand], then [action] with [commandMutex] held. Refused with nothing sent while
     * the stored pod state is not known ([refusedStateUnknown]).
     */
    private suspend inline fun connectThenLock(name: String, stateReady: Boolean, action: () -> PumpEnactResult): PumpEnactResult {
        refusedStateUnknown(name)?.let { return it }
        return connectThenLockKnownState(name, stateReady, action)
    }

    /** [connectThenLock] for a caller that checked [isPodStateKnown] itself. */
    private suspend inline fun <T> connectThenLockKnownState(name: String, stateReady: Boolean, action: () -> T): T {
        connectBeforeCommand(name, stateReady)
        return commandMutex.withLock {
            flushConnectionCountsWithLock()
            action()
        }
    }

    /**
     * Android connects before each status read and each stop of the bolus wait (`getPodStatus`,
     * `stopBolus`, both start with `observeConnectToPod`). The wait holds [commandMutex] for the
     * whole bolus, so the held connection cannot set up a new link on its own then (it gets
     * `DashConnectResult.Busy`). When there is no session, the connection is made here, with the lock
     * the wait holds, at most [DashHeldConnection.CONNECT_TIMEOUT_MS]. Without this, a stop asked
     * after a drop would never reach the pod and the whole bolus would go in.
     *
     * [stateReady]: as [connectBeforeCommand] (Android `stopBolus` checks `observePodRunning` first).
     */
    private fun reconnectInsideBolus(name: String, stateReady: Boolean = true) {
        val c = connector ?: return
        if (!stateReady || hasSession()) return
        val address = stateManager.bluetoothAddress ?: return
        val result = c.awaitConnectionHoldingLock(address, DashHeldConnection.CONNECT_TIMEOUT_MS)
        aapsLogger.info(LTag.PUMPBTCOMM, "Connect before $name (bolus wait): $result")
    }

    // -- Commands -----------------------------------------------------------

    /**
     * Delivers a bolus as Android `OmnipodDashPumpPlugin.deliverTreatment`:
     * - refused (nothing sent) while a bolus runs or the pod is suspended, decided on a status of this
     *   session ([refusedByDeliveryStatus], deliberate difference: Android decides on the stored
     *   status, which may be old);
     * - a stop asked before the bolus is sent: nothing is sent, the result is a success with nothing
     *   delivered, as Android (`observeDeliveryNotCanceled`, `success(bolusCanceled)`);
     * - the bolus goes through its active command. Only after its SUCCESS the bolus is followed to its
     *   end ([DashBolusWait]): the result is a success with what went in.
     *
     * - a bolus whose answer was lost (NOT_CONFIRMED): a status is read to decide it ([decideOpenBolus],
     *   as Android's wait reads the status after a lost answer; with a stop asked this is Android's first
     *   block of `waitForBolusDeliveryToComplete`). When the pod took it, it is followed as any bolus
     *   and the wait sends a stop that was asked. When no status can be read, the result is a success
     *   with the asked amount, as Android (`success(true).enacted(true)`, its wait returns
     *   `requestedBolusAmount`): the bolus was recorded in `PumpSync` when it was sent, so the queue
     *   must also save its carbs (it saves them only on success), and the user must not be told to give
     *   it again. The command stays open: every later delivery command is refused until a status
     *   decides it, and the plugin raises the "bolus uncertain" alarm (`afterBolus`), as Android.
     *   Only when the write gave a `CommandSent` (so the bolus is in `PumpSync`): when the write threw,
     *   Android fails the call and records nothing, and so does this (the command stays open here).
     *
     * Deliberate differences from Android, all fail closed:
     * - a bolus that is DENIED or never sent, or that a status decides as not taken, is a failure with
     *   nothing delivered, and is not followed. Android runs the wait after a DENIED too, and then
     *   reports the amount of the bolus before (its `lastBolus`).
     *
     * As Android, a bolus larger than the reservoir ([syncPumpFlows], [reservoirLevel]) is refused with
     * nothing sent. Android checks it before the pulse rate (which it reads with `!!`); here the pulse
     * rate comes first, both refuse with nothing sent.
     */
    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        refusedStateUnknown("Bolus")?.let { return it }
        // Android sets the flag first: a stop pressed while the connection is made is kept, and the
        // bolus is then not sent ([sendBolusAndWait]).
        bolusDeliveryInProgress = true
        try {
            connectBeforeCommand("bolus", stateReady = stateManager.isActivatedAndRunning)
            return commandMutex.withLock {
                busy = true
                try {
                    deliverBolusWithLock(detailedBolusInfo)
                } catch (e: DashCommandNotConfirmedException) {
                    failure(e.message ?: "Last command not confirmed")
                } catch (e: DashPumpNotPairedException) {
                    failure(e.message ?: "Not paired")
                } finally {
                    busy = false
                }
            }
        } finally {
            bolusCanceled = false
            bolusDeliveryInProgress = false
        }
    }

    private suspend fun deliverBolusWithLock(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        // Android: the SMB beep setting for an SMB, the bolus beep setting for any other bolus.
        val isSmb = detailedBolusInfo.bolusType == BS.Type.SMB
        val bolusBeeps = beeps().let { if (isSmb) it.smbBeeps else it.bolusBeeps }
        return sendBolusAndWait(detailedBolusInfo.insulin, bolusBeeps, isSmb, basalCorrection = false)
    }

    /**
     * Android `deliverBasalCorrection` (this fork), from the queue command `CommandDeliverBasalCorrection`:
     * one 0.05 U pulse that makes up basal the pod gave too little.
     * - [DashPodStateManager.needsBasalCorrection] false now: success, nothing sent ("no longer
     *   appropriate");
     * - otherwise the 2 minutes cooldown starts now ([DashPodStateManager.updateLastBasalCorrectionTime]),
     *   before anything is sent, as Android;
     * - that check and the cooldown are done with [commandMutex] held: both can write the state (the
     *   check resets `basalExpected` after a large drift), and a session the held
     *   connection sets up at the same time writes it too. The lock is given back before the
     *   reservoir checks and the connect, as Android checks before it connects;
     * - not enough insulin, or a bolus running: failure, nothing sent;
     * - the pulse is a bolus with no beeps, through its own active command marked as a basal correction
     *   (the plugin never writes it to `PumpSync`, as Android never syncs a BASAL_CORRECTION), followed to
     *   its end as a bolus. While it runs its pulses count as basal
     *   ([DashPodStateManager.basalCorrectionInProgress]).
     */
    suspend fun deliverBasalCorrection(): PumpEnactResult {
        refusedStateUnknown("Basal correction")?.let { return it }
        val needed = commandMutex.withLock {
            flushConnectionCountsWithLock()
            stateManager.needsBasalCorrection(now()).also { if (it) stateManager.updateLastBasalCorrectionTime(now()) }
        }
        if (!needed) {
            aapsLogger.info(LTag.PUMP, "Basal correction no longer appropriate")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Basal correction no longer appropriate")
        }
        val units = PodConstants.POD_PULSE_BOLUS_UNITS
        // Same staleness guard as the bolus: the reservoir is taken from the pod state first.
        syncPumpFlows()
        if (units > reservoirLevel.value.cU) {
            aapsLogger.info(LTag.PUMP, "Basal correction skipped: not enough insulin in reservoir ($units > ${reservoirLevel.value.cU})")
            return failure("Not enough insulin in reservoir")
        }
        if (stateManager.podStatus?.deliveryStatus?.bolusDeliveringActive() == true) {
            aapsLogger.info(LTag.PUMP, "Basal correction skipped: bolus already in progress")
            return failure("Bolus already in progress")
        }
        // As the bolus: the flag first, so a stop pressed while the connection is made is kept.
        bolusDeliveryInProgress = true
        stateManager.basalCorrectionInProgress = true
        aapsLogger.info(LTag.PUMP, "Delivering basal correction")
        try {
            connectBeforeCommand("basal correction", stateReady = stateManager.isActivatedAndRunning)
            return commandMutex.withLock {
                busy = true
                try {
                    sendBolusAndWait(units, bolusBeeps = false, isSmb = false, basalCorrection = true)
                } catch (e: DashCommandNotConfirmedException) {
                    failure(e.message ?: "Last command not confirmed")
                } catch (e: DashPumpNotPairedException) {
                    failure(e.message ?: "Not paired")
                } finally {
                    busy = false
                }
            }
        } finally {
            bolusCanceled = false
            bolusDeliveryInProgress = false
            stateManager.basalCorrectionInProgress = false
        }
    }

    /** The bolus of [deliverTreatment] and of [deliverBasalCorrection], with [commandMutex] held. */
    private suspend fun sendBolusAndWait(units: Double, bolusBeeps: Boolean, isSmb: Boolean, basalCorrection: Boolean): PumpEnactResult {
        // Android passes podStateManager.pulseRate!!; it is only known after activation.
        // Without it the bolus bytes would be wrong, so refuse before anything is opened or sent.
        val pulseRate = stateManager.pulseRate
            ?: return failure("Bolus refused: the pulse rate of the pod is not known (activation not finished)")
        // Android: the flows are taken from the pod state first, so the reservoir is the stored one.
        syncPumpFlows()
        if (units > reservoirLevel.value.cU) {
            return failure("Bolus refused: not enough insulin in the reservoir (${reservoirLevel.value.cU} U)")
        }
        if (bolusCanceled) {
            aapsLogger.info(LTag.PUMP, "Bolus canceled before it was sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Bolus canceled, nothing sent")
        }
        refusedByDeliveryStatus(forBolus = true)?.let { return failure("Bolus refused: $it") }
        // Again, as the check above may have read a status: a stop asked during that read must still
        // send nothing (a user action lost, and insulin given after the user said stop).
        if (bolusCanceled) {
            aapsLogger.info(LTag.PUMP, "Bolus canceled during the status read, before it was sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Bolus canceled, nothing sent")
        }
        val outcome = sendDeliveryCommand(DashCommandKind.BOLUS, requestedBolusUnits = units, basalCorrection = basalCorrection) { uid, seq ->
            ProgramBolusCommand.Builder()
                .setUniqueId(uid)
                .setSequenceNumber(seq)
                .setNonce(NONCE)
                .setNumberOfUnits(units)
                .setDelayBetweenPulsesInEighthSeconds(pulseRate.toByte())
                .setProgramReminder(ProgramReminder(atStart = bolusBeeps, atEnd = bolusBeeps, atInterval = 0))
                .build()
        }
        var confirmation = outcome.confirmation
        if (confirmation == DashCommandConfirmation.NOT_CONFIRMED) {
            confirmation = decideOpenBolus()
        }
        if (confirmation == DashCommandConfirmation.NOT_CONFIRMED && outcome.sentEvent) {
            aapsLogger.warn(LTag.PUMP, "Bolus $units U not confirmed and no status read: counted as given, as Android, until a status decides it")
            return DashPumpEnactResult(
                success = true, enacted = true, bolusDelivered = units,
                comment = "Bolus $units U: the pod did not confirm it. It is counted as given until a status decides it."
            )
        }
        if (confirmation != DashCommandConfirmation.SUCCESS) {
            return failure("Bolus $units U: ${DeliveryOutcome(confirmation, outcome.error).describe()}")
        }
        val delivered = DashBolusWait(bolusWaitPod, bolusProgress, sleep)
            .waitForBolusDeliveryToComplete(units, isSmb = isSmb)
        aapsLogger.info(LTag.PUMP, "deliverTreatment: deliveredBolusAmount=$delivered")
        return DashPumpEnactResult(success = true, enacted = true, bolusDelivered = delivered, comment = "Bolus $delivered U of $units U delivered")
    }

    /**
     * A bolus whose answer was lost. A status is read, up to [DashBolusWait.BOLUS_RETRIES] times
     * [DashBolusWait.BOLUS_RETRY_INTERVAL_MS] apart, to decide the bolus. With a stop asked this is
     * Android `waitForBolusDeliveryToComplete`, first block (`bolusCanceled && activeCommand != null`):
     * when the pod took it, the wait then sends the stop. Without this the stop would be dropped and
     * the pod would deliver all of it, while every other delivery command, a stop of all delivery too,
     * is refused until a status is read. Without a stop it is the status read of Android's wait, which
     * runs after a lost answer too.
     *
     * The outcome is decided inside the call, so it goes to [onCommandDecided] as not late and the
     * result of [deliverTreatment] reports what went in. Nothing read: the bolus stays open and
     * nothing is reported delivered (fail closed).
     */
    private suspend fun decideOpenBolus(): DashCommandConfirmation {
        repeat(DashBolusWait.BOLUS_RETRIES) {
            reconnectInsideBolus("status")
            val read = readStatusDeciding(decidedLate = false)
            if (read.read || read.confirmation.isFinal) return read.confirmation
            sleep(DashBolusWait.BOLUS_RETRY_INTERVAL_MS)
        }
        return stateManager.commandConfirmation()
    }

    /** [BolusProgressData] for the bolus wait, as `IosMedtrumPump` updates it. */
    private val bolusProgress: DashBolusProgress? = bolusProgressData?.let { data ->
        object : DashBolusProgress {
            override fun delivered(units: Double) = data.updateProgress(PumpInsulin(units))
            override fun checkingDeliveryStatus() = data.updateProgress(100, TextRef.Literal("Checking delivery status"))
        }
    }

    /** The exchanges of the bolus wait. Called with [commandMutex] held, inside [deliverTreatment]. */
    private val bolusWaitPod = object : DashBolusWaitPod {
        override fun isCanceled(): Boolean = bolusCanceled

        override fun readStatus(): Boolean {
            reconnectInsideBolus("status")
            return readStatusWithLock()
        }

        /**
         * Android `cancelBolus`. While a command is still open (a stop whose answer was lost), a
         * status is read instead: it decides the open stop, and a second stop sent now would become
         * the last programming command and hide what happened to the first one.
         */
        override fun cancelBolus(): Boolean {
            reconnectInsideBolus("stop bolus", stateReady = stateManager.isActivatedAndRunning)
            if (stateManager.activeCommand != null) return readStatusWithLock()
            return try {
                // Android `cancelBolus` takes the bolus beep setting, also for an SMB.
                val beep = beeps().bolusBeeps
                val stop = sendDeliveryCommand(DashCommandKind.STOP_BOLUS) { uid, seq ->
                    stopCommand(DeliveryType.BOLUS, uid, seq, beep)
                }
                stop.confirmation == DashCommandConfirmation.SUCCESS || stop.confirmation == DashCommandConfirmation.DENIED
            } catch (e: DashCommandNotConfirmedException) {
                false
            } catch (e: DashPumpNotPairedException) {
                false
            }
        }

        override fun bolusDeliveringActive(): Boolean =
            stateManager.podStatus?.deliveryStatus?.bolusDeliveringActive() == true

        override fun lastBolus(): DashLastBolus? = stateManager.lastBolus

        override fun completeLastBolus(): DashLastBolus? = stateManager.markLastBolusComplete()
    }

    /**
     * Android `stopBolusDelivering`: only asks for the stop, while [deliverTreatment] runs. The bolus
     * wait sends the stop of the bolus inside the command, so the stop never waits for the command
     * lock the bolus holds. Outside a bolus it does nothing, as Android.
     */
    override fun stopBolusDelivering() {
        aapsLogger.info(LTag.PUMP, "stopBolusDelivering called")
        if (bolusDeliveryInProgress) bolusCanceled = true
    }

    /**
     * Sets a temp basal as Android `OmnipodDashPumpPlugin.setTempBasalAbsolute`: first
     * `observeNoActiveTempBasal` ([cancelRunningTempBasalFirst]), a cancel with its own active command
     * (STOP_TEMP_BASAL, Android `CANCEL_TEMPORARY_BASAL`), then the new temp basal with its own active
     * command (TEMP_BASAL). Each outcome goes to [onCommandDecided] on its own, so a confirmed cancel
     * is reported even when the new temp basal then fails.
     *
     * Deliberate difference from Android (fail closed, plan decision): a temp basal that is sent but
     * not confirmed is a failure here. Android returns success for it and only shows a notification.
     * The command stays open in both, so the next delivery command is refused until a status.
     *
     * Deliberate difference from Android (OmnipodKit): before the cancel, the delivery status is
     * checked on a status of this session ([refusedByDeliveryStatus]); a suspended pod gets nothing.
     * So the cancel is also decided on that fresh status.
     */
    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = connectThenLock("TBR", stateReady = stateManager.isActivatedAndRunning) {
        busy = true
        try {
            requireSession()
            refusedByDeliveryStatus(forBolus = false)?.let { return@connectThenLock failure("TBR refused: $it") }
            cancelRunningTempBasalFirst(newTempBasalFollows = true)?.let { return@connectThenLock it }
            val tempBasalBeeps = beeps().tempBasalBeeps
            val outcome = sendDeliveryCommand(
                DashCommandKind.TEMP_BASAL, tempBasalRate = absoluteRate, tempBasalMinutes = durationInMinutes
            ) { uid, seq ->
                ProgramTempBasalCommand.Builder()
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(NONCE)
                    .setProgramReminder(ProgramReminder(atStart = tempBasalBeeps, atEnd = tempBasalBeeps, atInterval = 0))
                    .setRateInUnitsPerHour(absoluteRate)
                    .setDurationInMinutes(durationInMinutes.toShort())
                    .build()
            }
            if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                DashPumpEnactResult(
                    success = true, enacted = true, absolute = absoluteRate, duration = durationInMinutes,
                    comment = "TBR $absoluteRate U/h for $durationInMinutes min confirmed by the pod"
                )
            } else {
                failure("TBR $absoluteRate U/h for $durationInMinutes min: ${outcome.describe()}")
            }
        } catch (e: DashCommandNotConfirmedException) {
            failure(e.message ?: "Last command not confirmed")
        } catch (e: DashPumpNotPairedException) {
            failure(e.message ?: "Not paired")
        } finally {
            busy = false
        }
    }

    /**
     * Deliberate difference from Android, for a bolus ([forBolus]) and a temp basal, called with
     * [commandMutex] held: the delivery status is checked on a status of this session before the
     * command is sent. Why: the pod faults (0x31) on a bolus while a bolus runs and on a temp basal or
     * a bolus while it is suspended, and the pod then stops all insulin; or the command is refused
     * and insulin is counted wrong. Android decides on the stored status, which can be from an earlier
     * session or from before an app restart. As OmnipodKit (Trio) `PodCommsSession.bolus` (a
     * `getStatus` first when `lastDeliveryStatusReceived` is nil or shows a bolus, then refused
     * `unfinalizedBolus` or `podSuspended`) and `OmniPumpManager.enactTempBasal` (no temp basal on a
     * suspended pod).
     *
     * - A command still not confirmed: [DashCommandNotConfirmedException], nothing sent, as every
     *   delivery command (Android `observeNoActiveCommand`). No status is read for it here.
     * - The pod is not activated and running: null, [sendDeliveryCommand] refuses it.
     * - A status is read first when the stored one is missing, was not read in this session, has an
     *   unknown delivery status, or shows a conflict (suspended; for a bolus also a bolus running).
     * - Then the command is refused, with nothing sent, when no status of this session could be read,
     *   when the pod is suspended, or (bolus) when a bolus runs.
     *
     * Returns the reason of the refusal, or null when the command may be sent.
     */
    private fun refusedByDeliveryStatus(forBolus: Boolean): String? {
        requireSession()
        if (resolveAndHandle(decidedLate = true).confirmation == DashCommandConfirmation.NOT_CONFIRMED) {
            throw DashCommandNotConfirmedException("Refusing command: the last command is not confirmed by the pod. Read the pod status first.")
        }
        if (!stateManager.isActivatedAndRunning) return null
        if (deliveryConflict(forBolus) != null || !hasDeliveryStatusOfThisSession()) {
            aapsLogger.info(LTag.PUMP, "Reading a fresh pod status before the ${if (forBolus) "bolus" else "temp basal"}")
            readStatusWithLock()
        }
        if (!hasDeliveryStatusOfThisSession()) return "no pod status could be read in this session, nothing sent"
        return deliveryConflict(forBolus)?.let { "$it, nothing sent" }
    }

    /** True when the stored status was read in this session and has a known delivery status. */
    private fun hasDeliveryStatusOfThisSession(): Boolean {
        val status = stateManager.podStatus ?: return false
        return status.deliveryStatus != DeliveryStatus.UNKNOWN && stateManager.statusResponseCount > statusCountAtSessionStart
    }

    /** What in the stored delivery status would make the pod fault on a bolus ([forBolus]) or a temp basal. Null when nothing. */
    private fun deliveryConflict(forBolus: Boolean): String? {
        val delivery = stateManager.podStatus?.deliveryStatus ?: return null
        return when {
            delivery.suspended()                           -> "the pod is suspended"
            forBolus && delivery.bolusDeliveringActive() -> "a bolus is already in progress"
            else                                           -> null
        }
    }

    /**
     * Android `OmnipodDashPumpPlugin.observeNoActiveTempBasal`, called with [commandMutex] held.
     *
     * - The decision is taken on the last status known before anything is sent: when it shows no temp
     *   basal (`deliveryStatus.tempBasalActive()` is false), nothing is sent. When no status is known
     *   yet, the cancel is sent, as Android (`deliveryStatus?.tempBasalActive() == false` is false
     *   for null).
     * - The cancel goes through [sendDeliveryCommand] with its own active command.
     * - SUCCESS: the new temp basal goes next (null is returned).
     * - DENIED: with [newTempBasalFollows], the new temp basal is not sent (a failure is returned), as
     *   OmnipodKit (Trio) `enactTempBasal` stops on a cancel that failed. Deliberate difference from
     *   Android, which goes on (`updateActiveCommand` emits `CommandConfirmed(success = false)`): the
     *   old temp basal still runs, so the pod refuses the new one or runs the old one, and the temp
     *   basal AAPS then counts is not the one that runs (insulin counted wrong). Without
     *   [newTempBasalFollows] (the stop of a temp basal not expected) a DENIED stop returns null, as
     *   Android.
     * - NOT_CONFIRMED: the cancel stays open and the new temp basal is not sent (Android: the
     *   `observeNoActiveCommand` of the set fails).
     * - SENDING_FAILURE: the new temp basal is not sent (Android: `updateActiveCommand` raises the
     *   send error).
     *
     * Returns the failure to give back, or null when the new temp basal may be sent. Throws
     * [DashCommandNotConfirmedException] while another command is open: nothing is sent.
     */
    private fun cancelRunningTempBasalFirst(newTempBasalFollows: Boolean): PumpEnactResult? {
        if (stateManager.podStatus?.deliveryStatus?.tempBasalActive() == false) {
            aapsLogger.info(LTag.PUMP, "No temporary basal to cancel")
            return null
        }
        aapsLogger.info(LTag.PUMP, "Canceling existing temp basal")
        val beep = beeps().tempBasalBeeps
        val cancel = sendDeliveryCommand(DashCommandKind.STOP_TEMP_BASAL) { uid, seq ->
            stopCommand(DeliveryType.TEMP_BASAL, uid, seq, beep)
        }
        return when (cancel.confirmation) {
            DashCommandConfirmation.SUCCESS         -> null
            DashCommandConfirmation.DENIED          ->
                if (newTempBasalFollows) failure("Cancel of the running TBR: ${cancel.describe()}. New TBR not sent") else null

            else                                    -> failure("Cancel of the running TBR: ${cancel.describe()}. New TBR not sent")
        }
    }

    /**
     * The cancel part of Android `verifyPumpState` (`observeNoActiveTempBasal`, then the confirmation):
     * the pod runs a temp basal that AAPS does not expect, so it is stopped. Decided on the last status,
     * as [setTempBasalAbsolute] decides its cancel: nothing is sent when that status shows no temp basal.
     * Success when nothing had to be sent, or the pod took or refused the stop (Android goes on after a
     * refused one); a stop that is not confirmed stays open as any other command.
     */
    suspend fun cancelTempBasalNotExpected(): PumpEnactResult = lockedCommand {
        cancelRunningTempBasalFirst(newTempBasalFollows = false) ?: DashPumpEnactResult(success = true, enacted = true, comment = "No temp basal running on the pod")
    }

    override suspend fun setTempBasalPercent(
        percent: Int,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult =
        // Dash does not support percent TBR; fail closed rather than approximate.
        failure("Percent TBR not supported on Dash")

    /**
     * Android `OmnipodDashPumpPlugin.cancelTempBasal`: nothing to cancel when the pod has no confirmed
     * temp basal running ([tempBasalActive]) and AAPS expects none ([expectedTempBasalRunning]): success,
     * not enacted, nothing sent. Otherwise the cancel goes with its own active command (STOP_TEMP_BASAL).
     * Success only when the pod confirmed it (fail closed, see the class comment).
     */
    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        connectThenLock("cancel TBR", stateReady = stateManager.isActivatedAndRunning) {
            busy = true
            try {
                if (!tempBasalActive() && !expectedTempBasalRunning()) {
                    aapsLogger.info(LTag.PUMP, "cancelTempBasal: no temp basal to cancel, nothing sent")
                    return@connectThenLock DashPumpEnactResult(success = true, enacted = false, comment = "No temp basal to cancel")
                }
                val beep = beeps().tempBasalBeeps
                val outcome = sendDeliveryCommand(DashCommandKind.STOP_TEMP_BASAL) { uid, seq ->
                    stopCommand(DeliveryType.TEMP_BASAL, uid, seq, beep)
                }
                if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                    DashPumpEnactResult(success = true, enacted = true, isTempCancel = true, comment = "TBR cancel confirmed by the pod")
                } else {
                    failure("Cancel TBR: ${outcome.describe()}")
                }
            } catch (e: DashCommandNotConfirmedException) {
                failure(e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                failure(e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }

    /**
     * Sends a new basal program as Android `OmnipodDashPumpPlugin.setNewBasalProfile`:
     * - before the activation is completed nothing is sent, and the result is a success that was not
     *   enacted, as Android (the program is sent by the activation);
     * - the profile is mapped as Android ([mapProfileToBasalProgram]);
     * - delivery is suspended first when the last status does not show it suspended
     *   ([suspendDeliveryIfActive], Android `suspendDeliveryIfActive`), with its own active command;
     * - then the program goes with its own active command (BASAL_PROGRAM, Android SET_BASAL_PROFILE),
     *   built at the time it is sent, beep from [beeps] (Android `hasBasalBeepEnabled`). When the pod
     *   takes it, it becomes the running program ([DashPodStateManager.basalProgram]) in the same write
     *   that closes the command. Each outcome goes to [onCommandDecided] on its own.
     *
     * A program sent but not confirmed is a failure and stays open, as Android (`failWhenUnconfirmed`):
     * every later delivery command is refused until a status decides it. A late SUCCESS reaches
     * [onCommandDecided] with `decidedLate`; Android then asks for the profile again
     * (`EventProfileChangeRequested`), which is the plugin's job here (phase 4).
     *
     * Deliberate differences from Android, both fail closed:
     * - a program the pod refused (DENIED) is a failure. Android returns success there, because no
     *   command is left open. The suspend before it was taken, so the pod delivers no basal at all:
     *   the caller must not be told that the profile runs.
     * - a profile that cannot be mapped is a failure with nothing sent. Android throws out of
     *   `setNewBasalProfile`; nothing is sent there either.
     *
     * The suspend and the program are held back unless the pod is activated and runs, as Android
     * `observePodRunning` (see [sendDeliveryCommand]). With a connector, the connection is awaited
     * first ([connectBeforeCommand], Android `observeConnectToPod`). The time zone is the one of the phone when the program is built, as Android; a change of
     * time zone or DST is ignored, as Android (`timezoneOrDSTChanged`).
     */
    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        // First: an unknown state shows as "not activated", which would answer success.
        refusedStateUnknown("Basal program")?.let { return it }
        if (!stateManager.isActivationCompleted) {
            aapsLogger.info(LTag.PUMP, "setNewBasalProfile: pod activation not completed, nothing sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Pod not activated, basal program not sent")
        }
        val basalProgram = try {
            mapProfileToBasalProgram(profile, PumpType.OMNIPOD_DASH)
        } catch (e: IllegalArgumentException) {
            return failure("Basal program refused: ${e.message}")
        }
        return connectThenLock("basal program", stateReady = stateManager.isActivatedAndRunning) {
            busy = true
            try {
                requireSession()
                suspendDeliveryIfActive()?.let { return@connectThenLock it }
                val beep = beeps().basalBeeps
                val outcome = sendDeliveryCommand(DashCommandKind.BASAL_PROGRAM, basalProgram = basalProgram) { uid, seq ->
                    basalProgramCommand(basalProgram, uid, seq, beep)
                }
                if (outcome.answeredWithStatus) stateManager.updateTimeZone(now())
                if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                    DashPumpEnactResult(success = true, enacted = true, comment = "Basal program confirmed by the pod")
                } else {
                    failure("Basal program: ${outcome.describe()}")
                }
            } catch (e: DashCommandNotConfirmedException) {
                failure(e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                failure(e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }
    }

    /**
     * Android `OmnipodDashPumpPlugin.suspendDeliveryIfActive`, called with [commandMutex] held.
     *
     * - Nothing is sent when the last status shows delivery suspended. When no status is known yet,
     *   the suspend is sent, as Android (`deliveryStatus == SUSPENDED` is false for null).
     * - The suspend ([sendSuspendDelivery], Android `omnipodManager.suspendDelivery`) goes through
     *   [sendDeliveryCommand] with its own active command (SUSPEND_DELIVERY).
     * - SUCCESS and DENIED: the program goes next (null is returned). Android goes on after a denied
     *   suspend: `updateActiveCommand` emits `CommandConfirmed(success = false)`.
     * - NOT_CONFIRMED: the suspend stays open and the program is not sent (Android: the
     *   `observeNoActiveCommand` of the program fails).
     * - SENDING_FAILURE: the program is not sent (Android: `updateActiveCommand` raises the send error).
     *
     * Returns the failure to give back, or null when the program may be sent. Throws
     * [DashCommandNotConfirmedException] while another command is open: nothing is sent.
     */
    private fun suspendDeliveryIfActive(): PumpEnactResult? {
        if (stateManager.podStatus?.deliveryStatus == DeliveryStatus.SUSPENDED) {
            aapsLogger.info(LTag.PUMP, "Delivery already suspended")
            return null
        }
        aapsLogger.info(LTag.PUMP, "Suspending delivery before the new basal program")
        val suspend = sendSuspendDelivery()
        return when (suspend.confirmation) {
            DashCommandConfirmation.SUCCESS,
            DashCommandConfirmation.DENIED -> null

            else                           -> failure("Suspend before the basal program: ${suspend.describe()}. Basal program not sent")
        }
    }

    /**
     * Android `OmnipodDashManagerImpl.observeSendProgramBasalCommand`, `hasBasalBeepEnabled` from
     * [beeps]. The current time is read when the command is built, as Android reads `Date()` there:
     * the pod starts the program at the slot of that time.
     */
    private fun basalProgramCommand(basalProgram: BasalProgram, uid: Int, seq: Short, beep: Boolean): Command =
        ProgramBasalCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setProgramReminder(ProgramReminder(atStart = beep, atEnd = false, atInterval = 0))
            .setBasalProgram(basalProgram)
            .setCurrentTime(now())
            .build()

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        failure("Extended bolus not supported on Dash")

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        failure("Extended bolus not supported on Dash")

    /**
     * Emergency stop: halts all delivery. Still guarded — a stop with no
     * session cannot reach the pod, so it fails closed like everything else.
     */
    fun stopDelivery(): PumpEnactResult = runBlocking {
        // No Android counterpart and no state check: a stop of all delivery always connects.
        connectThenLock("stop", stateReady = true) {
            try {
                // No Android counterpart (the Dash plugin has no stop of all delivery): silent.
                val outcome = sendDeliveryCommand(DashCommandKind.STOP_ALL) { uid, seq ->
                    stopCommand(DeliveryType.ALL, uid, seq, beep = false)
                }
                if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                    DashPumpEnactResult(success = true, enacted = true, comment = "Stop confirmed by the pod")
                } else {
                    failure("Stop: ${outcome.describe()}")
                }
            } catch (e: DashCommandNotConfirmedException) {
                failure(e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                failure(e.message ?: "Not paired")
            }
        }
    }

    /**
     * Android `OmnipodDashManagerImpl.suspendDelivery`: `SuspendDeliveryCommand` (the stop of all
     * delivery and the "suspend in progress" alert of the pod), long single beep when the basal beep
     * setting is on ([beeps]). It goes through the active command (SUSPEND_DELIVERY) and is held back
     * unless the pod is activated and runs (`observePodRunning`).
     *
     * A confirmed suspend ends a bolus that is still running, with what the answer left, and gives it
     * to [onCommandDecided] as [DashCommandOutcome.endedBolus] (see
     * [DashPodStateManager.resolveActiveCommand]).
     *
     * The Android Dash plugin has no suspend of its own (the button is hidden, `CommandSuspendDelivery`
     * is not handled): it only suspends before a new basal program. This is the manager call, for the
     * plugin. Deliberate difference, as for every delivery command here: success only when the pod
     * confirmed it (Android also returns success after a refused or a not confirmed command).
     */
    suspend fun suspendDelivery(): PumpEnactResult = lockedCommand {
        val outcome = sendSuspendDelivery()
        if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
            DashPumpEnactResult(success = true, enacted = true, comment = "Suspend confirmed by the pod")
        } else {
            failure("Suspend: ${outcome.describe()}")
        }
    }

    /**
     * The suspend itself, called with [commandMutex] held. As Android (`doOnComplete` of
     * `suspendDelivery`), [DashPodStateManager.suspendAlertsEnabled] is set once the answer of the pod
     * was read, whatever the answer says.
     */
    private fun sendSuspendDelivery(): DeliveryOutcome {
        val beep = beeps().basalBeeps
        val outcome = sendDeliveryCommand(DashCommandKind.SUSPEND_DELIVERY) { uid, seq ->
            SuspendDeliveryCommand.Builder()
                .setSequenceNumber(seq)
                .setUniqueId(uid)
                .setNonce(NONCE)
                .setBeepType(DashBeepSettings.stopBeep(beep))
                .build()
        }
        if (outcome.error == null) stateManager.updateSuspendAlertsEnabled(true)
        return outcome
    }

    /**
     * Android `OmnipodDashPumpPlugin.resumeDelivery`: the basal program of [profile] (Android: the
     * profile AAPS expects to run) sent to a suspended pod, with its own active command
     * (RESUME_DELIVERY), basal beep from [beeps]. No suspend first.
     * - a profile that cannot be mapped ([mapProfileToBasalProgram]): failure, nothing sent;
     * - the last status does not show delivery suspended: failure "Expected suspended delivery",
     *   nothing sent (Android `observeDeliverySuspended`);
     * - held back unless the pod is activated and runs (`observePodRunning`).
     *
     * Deliberate differences from Android:
     * - success only when the pod confirmed it (Android also returns success after a refused or a not
     *   confirmed resume). A not confirmed resume stays open and blocks the next delivery command.
     * - a confirmed resume makes the program the running one ([DashPodStateManager.basalProgram]), as a
     *   confirmed basal program. Android keeps the program from before, so `isThisProfileSet` could say
     *   yes for a profile the pod does not run.
     *
     * Android then turns the "suspend ended" alert off (`CommandDisableSuspendAlerts` on a confirmed
     * RESUME_DELIVERY or SET_BASAL_PROFILE). The plugin does the same: on such an outcome it queues the
     * command, which calls [disableSuspendAlerts] (`DashPluginRules.onOutcome`).
     */
    suspend fun resumeDelivery(profile: PumpProfile): PumpEnactResult {
        val basalProgram = try {
            mapProfileToBasalProgram(profile, PumpType.OMNIPOD_DASH)
        } catch (e: IllegalArgumentException) {
            return failure("Resume refused: ${e.message}")
        }
        return lockedCommand {
            if (!stateManager.isSuspended) return@lockedCommand failure("Expected suspended delivery")
            val beep = beeps().basalBeeps
            val outcome = sendDeliveryCommand(DashCommandKind.RESUME_DELIVERY, basalProgram = basalProgram) { uid, seq ->
                basalProgramCommand(basalProgram, uid, seq, beep)
            }
            if (outcome.answeredWithStatus) stateManager.updateTimeZone(now())
            if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                DashPumpEnactResult(success = true, enacted = true, comment = "Resume confirmed by the pod")
            } else {
                failure("Resume: ${outcome.describe()}")
            }
        }
    }

    // -- Alerts and beeps -----------------------------------------------------

    /**
     * Android `OmnipodDashPumpPlugin.silenceAlerts`: silences the alerts of the last status
     * ([DashPodStateManager.activeAlerts]), all of them as Android (its "TODO filter alert types"),
     * with its own active command (SILENCE_ALERTS). No status known yet: failure "No active alerts",
     * nothing sent. An empty set is sent as Android does. Held back unless the pod is activated and
     * runs (`observePodRunning`). Success only when the pod confirmed it.
     */
    suspend fun silenceAlerts(): PumpEnactResult = lockedCommand {
        val alerts = stateManager.activeAlerts ?: return@lockedCommand failure("No active alerts")
        val outcome = sendDeliveryCommand(DashCommandKind.SILENCE_ALERTS) { uid, seq ->
            SilenceAlertsCommand.Builder()
                .setSequenceNumber(seq)
                .setUniqueId(uid)
                .setNonce(NONCE)
                .setAlertTypes(alerts)
                .build()
        }
        resultOf(outcome, "Silence alerts")
    }

    /**
     * Silences one pod alert, [alert], from its notice or the pump screen: one `SilenceAlertsCommand`
     * with that slot only, with its own active command (SILENCE_ALERTS), as OmnipodKit (Trio)
     * `acknowledgeAlert` -> `acknowledgeAlerts(AlertSet(slots: [slot]))`.
     *
     * Deliberate difference from Android, which can only silence all alerts at once: the
     * acknowledgement is stored first ([DashPodStateManager.addAcknowledgedAlert]), before the wait for
     * the connection, so a silence that does not reach the pod (out of range, app killed during the
     * wait) is not a user action lost; the next status read sends it again
     * (`DashPluginRules.updatePodAlerts`). Then:
     * - SUCCESS: the pod silenced it, it leaves the stored set;
     * - DENIED: the pod refused, it leaves the stored set so its notice shows again (fail closed);
     * - not sent or not confirmed: it stays stored, and is sent again later.
     *
     * Nothing is stored or sent when the stored pod state cannot be read, when the last status does
     * not report [alert] (success, not enacted: nothing to do), or for "suspend ended" while delivery
     * is suspended: as OmnipodKit, that alert keeps beeping until delivery runs again (the resume
     * turns it off). In the last two cases a silence the notice already stored
     * (`DashPluginRules.acknowledgeAlert`) is forgotten, so it is not sent again and not hidden. Held back unless the pod is activated and runs. Success only when the pod
     * confirmed it.
     */
    suspend fun acknowledgeAlert(alert: AlertType): PumpEnactResult {
        refusedStateUnknown("Silence alert")?.let { return it }
        if (stateManager.activeAlerts?.contains(alert) != true || alert == AlertType.UNKNOWN) {
            if (alert != AlertType.UNKNOWN) stateManager.forgetAcknowledgedAlert(alert)
            return DashPumpEnactResult(success = true, enacted = false, comment = "Alert $alert is not active")
        }
        if (alert == AlertType.SUSPEND_ENDED && stateManager.isSuspended) {
            // The notice may have stored it before delivery became suspended: forgotten, so it shows again.
            stateManager.forgetAcknowledgedAlert(alert)
            return failure("The suspend ended alert stays on while delivery is suspended. Resume delivery to stop it.")
        }
        stateManager.addAcknowledgedAlert(alert)
        return lockedCommand {
            val outcome = sendDeliveryCommand(DashCommandKind.SILENCE_ALERTS) { uid, seq ->
                SilenceAlertsCommand.Builder()
                    .setSequenceNumber(seq)
                    .setUniqueId(uid)
                    .setNonce(NONCE)
                    .setAlertTypes(setOf(alert))
                    .build()
            }
            when (outcome.confirmation) {
                DashCommandConfirmation.SUCCESS,
                DashCommandConfirmation.DENIED -> stateManager.forgetAcknowledgedAlert(alert)
                else                           -> Unit
            }
            resultOf(outcome, "Silence alert")
        }
    }

    /**
     * Android `OmnipodDashManagerImpl.programAlerts`: one `ProgramAlertsCommand` with [alerts], with its
     * own active command (PROGRAM_ALERTS). Held back unless the pod is activated and runs. Success
     * only when the pod confirmed it.
     */
    suspend fun programAlerts(alerts: List<AlertConfiguration>): PumpEnactResult = lockedCommand {
        resultOf(sendAlerts(alerts), "Program alerts")
    }

    /**
     * Android `OmnipodDashPumpPlugin.disableSuspendAlerts`: the "suspend ended" alert turned off
     * ([DashAlerts.disableSuspendAlerts]).
     *
     * As Android, [DashPodStateManager.suspendAlertsEnabled] is cleared whenever the command did not
     * fail to send: after SUCCESS, DENIED and NOT_CONFIRMED. Android clears it on `success && enacted`,
     * and `executeProgrammingCommand` completes (success, enacted) in those three cases; only a send
     * failure makes it fail. Nothing reads the flag today, so this has no effect on delivery. The
     * result stays a success only on SUCCESS (fail closed, see the class comment).
     */
    suspend fun disableSuspendAlerts(): PumpEnactResult = lockedCommand {
        val outcome = sendAlerts(DashAlerts.disableSuspendAlerts())
        if (outcome.confirmation != DashCommandConfirmation.SENDING_FAILURE) stateManager.updateSuspendAlertsEnabled(false)
        resultOf(outcome, "Disable suspend alerts")
    }

    /**
     * Android `OmnipodDashPumpPlugin.updateAlertConfiguration`, with the user settings [settings]:
     * - the pod was last programmed with the same settings ([DashPodStateManager.sameAlertSettings]):
     *   success, not enacted, nothing sent;
     * - no running pod ([DashPodStateManager.isPodRunning]): success, not enacted, nothing sent;
     * - otherwise the four alerts of [DashAlerts.updateAlertConfigurations], computed from the pod life
     *   left now ([DashPodStateManager.expiryMs]), in one `ProgramAlertsCommand`.
     *
     * Deliberate differences from Android:
     * - the settings are stored only when the pod confirmed the alerts. Android stores them whenever
     *   the command did not fail to send, also after a refused or a not confirmed one, and then skips
     *   the next update as "the settings did not change": the low reservoir and expiration alerts the
     *   user set would never reach the pod.
     * - no pod expiry known: failure, nothing sent (Android throws).
     */
    suspend fun updateAlertConfiguration(settings: DashAlertSettings): PumpEnactResult = lockedCommand {
        if (stateManager.sameAlertSettings(settings)) {
            aapsLogger.debug(LTag.PUMP, "Ignoring updateAlertConfiguration because the settings did not change")
            return@lockedCommand DashPumpEnactResult(success = true, enacted = false, comment = "Alert settings did not change")
        }
        if (!stateManager.isPodRunning) {
            aapsLogger.debug(LTag.PUMP, "Ignoring updateAlertConfiguration because there is no active pod")
            return@lockedCommand DashPumpEnactResult(success = true, enacted = false, comment = "No active pod")
        }
        val expiry = stateManager.expiryMs ?: return@lockedCommand failure("Alert configuration: pod expiry not known, nothing sent")
        val outcome = sendAlerts(DashAlerts.updateAlertConfigurations(settings, expiry - now(), aapsLogger))
        if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
            stateManager.updateExpirationAlertSettings(settings.expiration)
            stateManager.updateLowReservoirAlertSettings(settings.lowReservoir)
        }
        resultOf(outcome, "Alert configuration")
    }

    /** One `ProgramAlertsCommand` (Android `observeSendProgramAlertsCommand`, no multi command flag). Called with [commandMutex] held. */
    private fun sendAlerts(alerts: List<AlertConfiguration>): DeliveryOutcome =
        sendDeliveryCommand(DashCommandKind.PROGRAM_ALERTS) { uid, seq ->
            ProgramAlertsCommand.Builder()
                .setUniqueId(uid)
                .setSequenceNumber(seq)
                .setNonce(NONCE)
                .setAlertConfigurations(alerts)
                .setMultiCommandFlag(false)
                .build()
        }

    /**
     * Android `OmnipodDashPumpPlugin.playTestBeep`: `ProgramBeepsCommand` with a long single beep now
     * and every reminder off (Android `playBeep(LONG_SINGLE_BEEP)`), with its own active command
     * (PROGRAM_BEEPS). The user asks for this beep, so it does not depend on [beeps]. Held back unless
     * the pod is activated and runs. Success only when the pod confirmed it.
     */
    suspend fun playTestBeep(): PumpEnactResult = lockedCommand {
        val outcome = sendDeliveryCommand(DashCommandKind.PROGRAM_BEEPS) { uid, seq ->
            ProgramBeepsCommand.Builder()
                .setSequenceNumber(seq)
                .setUniqueId(uid)
                .setBasalReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                .setTempBasalReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                .setBolusReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
                .setImmediateBeepType(BeepType.LONG_SINGLE_BEEP)
                .build()
        }
        resultOf(outcome, "Test beep")
    }

    // -- Deactivation -----------------------------------------------------------

    /**
     * Android `OmnipodDashPumpPlugin.deactivatePod`: `DeactivateCommand`, with its own active command
     * (DEACTIVATE). Not held back on a pod that does not run (Android `deactivatePod` has no
     * `observePodRunning`): a pod in alarm, or one whose activation failed half way, is deactivated
     * too.
     *
     * An open command (an earlier answer was lost): Android sends the deactivation anyway
     * (`checkNoActiveCommand = false`). Here a status is read first, in the same call, which decides
     * the open command (its outcome reaches [onCommandDecided], so it is counted), and then the
     * deactivation goes. Only when the pod gives no status at all the deactivation is refused, with
     * the open command kept; the pod would not get the deactivation then either. A pod that does not
     * answer at all is discarded instead ([discardPod]). Deliberate difference, kept for safety: the
     * outcome of the open command (a bolus, a temp basal) is never lost behind the deactivation.
     *
     * When the pod took it, the whole pod state is cleared, as Android `podStateManager.reset()`
     * ([DashPodStateManager.reset]: key, unique id, counters, last bolus, basal program, alert settings),
     * and the link is closed (Android removes the bond). The outcome reaches [onCommandDecided] first;
     * a bolus that was still running is given as [DashCommandOutcome.endedBolus] with what the answer
     * left (see [DashPodStateManager.resolveActiveCommand]). Not confirmed: failure, the command stays
     * open and the state stays, as Android. Never sent (SENDING_FAILURE): failure, the state stays, as
     * Android (its `updateActiveCommand` fails on a send failure, so the `doOnComplete` that resets is
     * not reached).
     *
     * Deliberate difference from Android (two pods delivering): a refused deactivation (DENIED)
     * clears the state only when the pod reports that it delivers nothing
     * ([DashPodStateManager.isPodKaput]: ALARM or DEACTIVATED). Android clears it after any refusal,
     * so a pod that still runs its basal would be forgotten, and a new pod would deliver basal next to
     * it. Otherwise: failure, the state stays.
     *
     * Not done here, as Android does not do it either: a deactivation found SUCCESS only later by a
     * status does not clear the state (Android: "Will not sync confirmed command"). The user then
     * deactivates again or discards the pod.
     */
    suspend fun deactivatePod(): PumpEnactResult = lockedCommand(needsRunningPod = false) {
        if (stateManager.activeCommand != null && stateManager.commandConfirmation() == DashCommandConfirmation.NOT_CONFIRMED) {
            aapsLogger.info(LTag.PUMP, "Deactivation: the last command is not confirmed, reading a status first")
            readStatusWithLock()
        }
        val outcome = sendDeliveryCommand(DashCommandKind.DEACTIVATE) { uid, seq ->
            DeactivateCommand.Builder()
                .setSequenceNumber(seq)
                .setUniqueId(uid)
                .setNonce(NONCE)
                .build()
        }
        val podGone = outcome.confirmation == DashCommandConfirmation.SUCCESS ||
            (outcome.confirmation == DashCommandConfirmation.DENIED && stateManager.isPodKaput)
        if (podGone) {
            resetPodState("pod deactivated (${outcome.confirmation})")
            DashPumpEnactResult(success = true, enacted = true, comment = "Pod deactivated")
        } else {
            failure("Deactivate: ${outcome.describe()}")
        }
    }

    /**
     * Android "Discard pod" (`podStateManager.reset()` from the overview or the wizard): the pod state
     * is cleared without asking the pod, for a pod that cannot be deactivated (no answer, out of
     * range, dead). The link is closed. Waits for a running command, as it holds [commandMutex].
     *
     * An open command is given to [onCommandDecided] before the state is cleared. One that the last
     * status already decided gets its real outcome. One still not confirmed gets NOT_CONFIRMED with
     * [DashCommandOutcome.podDiscarded], so the receiver counts it as Android does: Android records a
     * bolus and a temp basal as soon as they were sent, so its reset keeps them. Without this, a bolus
     * the pod may have given would be lost from insulin on board (deliberate difference from the
     * Android code, needed because here a not confirmed bolus is only counted when an outcome comes).
     *
     * A bolus that was confirmed and still runs ([DashPodStateManager.lastBolus] not complete) is not
     * given again: it was already counted at the asked amount when it was confirmed, and the reset
     * keeps that amount, as Android. When the pod is kaput, [checkPodKaput] (run by the resolve first)
     * ends it with what the last status left and gives it as [DashBolusEndedByFault] before the reset.
     *
     * Throws [DashPodStateUnknownException], with nothing changed, while the stored pod state is not
     * known ([isPodStateKnown]): the discard would delete a key this phone cannot read now.
     */
    suspend fun discardPod() {
        // Throws: nothing may clear the stored item before it was read (it may hold the key of the pod on the body).
        if (!isPodStateKnown()) throw DashPodStateUnknownException(stateManager.stateUnreadableReason ?: "not read")
        commandMutex.withLock {
            val resolved = resolveAndHandle(decidedLate = true)
            val open = resolved.command
            if (resolved.confirmation == DashCommandConfirmation.NOT_CONFIRMED && open != null) {
                aapsLogger.warn(LTag.PUMP, "Pod discarded with a command not confirmed, outcome unknown: $open")
                onCommandDecided(
                    DashCommandOutcome(DashCommandConfirmation.NOT_CONFIRMED, open, decidedLate = true, decidedAtMs = now(), podDiscarded = true)
                )
            }
            resetPodState("pod discarded")
        }
    }

    /** Android `podStateManager.reset()`, and the link of the old pod is not used again. */
    private fun resetPodState(reason: String) {
        aapsLogger.info(LTag.PUMP, "Pod state cleared: $reason")
        // The old pod is not connected again (also not after a drop or an iOS relaunch).
        connector?.forget(reason)
        stateManager.reset()
        closeLink(reason)
    }

    /**
     * One command call: [commandMutex] and [isBusy] for the whole [block]; an open command or a missing
     * session is a failure result. [needsRunningPod]: Android checks `observePodRunning` before it
     * connects; only the deactivation has no such check (a pod in alarm must still be deactivated).
     */
    private suspend fun lockedCommand(
        needsRunningPod: Boolean = true,
        block: () -> PumpEnactResult,
    ): PumpEnactResult = connectThenLock("command", stateReady = !needsRunningPod || stateManager.isActivatedAndRunning) {
        busy = true
        try {
            block()
        } catch (e: DashCommandNotConfirmedException) {
            failure(e.message ?: "Last command not confirmed")
        } catch (e: DashPumpNotPairedException) {
            failure(e.message ?: "Not paired")
        } finally {
            busy = false
        }
    }

    /** Success only when the pod confirmed the command (fail closed, see the class comment). */
    private fun resultOf(outcome: DeliveryOutcome, name: String): PumpEnactResult =
        if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
            DashPumpEnactResult(success = true, enacted = true, comment = "$name confirmed by the pod")
        } else {
            failure("$name: ${outcome.describe()}")
        }

    /**
     * Asks the pod for its default status (Android `getPumpStatus`, `getPodStatus`). The answer goes
     * into the state ([DashPodStateManager.updateFromResponse]): a default status, or the alarm status a
     * faulted pod sends in its place, which also keeps the alarm type. Then, as Android: an open command
     * is decided (the way out of NOT_CONFIRMED), `checkPodKaput` runs ([checkPodKaput]: a bolus the
     * fault stopped is ended and given to [onCommandDecided]), and the flows are taken from the state
     * ([syncPumpFlows]), also when nothing could be read. Not a programming command, so no active
     * command is opened. Fails closed like everything else; errors are logged.
     *
     * Android `verifyPumpState` and the plugin part of `checkPodKaput` follow in the plugin
     * (`DashPluginRules.afterCommand`). Not here: the pod history.
     *
     * As Android: before the activation is completed, nothing is read unless [reason] is
     * [REQUESTED_BY_USER] (no race on BLE with the activation), and after a status read on a pod whose
     * activation is not completed, the progress is taken from the pod status
     * ([DashPodStateManager.recoverActivationFromPodStatus]).
     *
     * As Android `getStatus` (`observeUniqueIdSet`): nothing is sent before the activation reached
     * SET_UNIQUE_ID ("Pod is in an incorrect state", logged).
     *
     * The recover runs only when this read stored a status. A NAK is an answer but no status: the
     * stored pod status is then the one from before, which after a lost answer to the prime or the
     * cannula bolus says "not taken", and the recover would clear the mark of that bolus
     * (`DashPodStateManager.activationStepInFlight`), so the next activation would send it again.
     * Android has no mark, and its `podStatus` is also stale after a NAK.
     */
    override suspend fun getPumpStatus(reason: String) {
        if (refusedStateUnknown("Status read ($reason)") != null) return
        if (reason != REQUESTED_BY_USER && !stateManager.isActivationCompleted) {
            aapsLogger.info(LTag.PUMP, "getPumpStatus ($reason): pod activation not completed, nothing read")
            return
        }
        connectThenLockKnownState("status", stateReady = stateManager.activationProgress.isAtLeast(ActivationProgress.SET_UNIQUE_ID)) {
            busy = true
            try {
                if (!stateManager.activationProgress.isAtLeast(ActivationProgress.SET_UNIQUE_ID)) {
                    aapsLogger.error(LTag.PUMP, "getPumpStatus ($reason): Pod is in an incorrect state (activation ${stateManager.activationProgress}), nothing read")
                } else {
                    val statusesBefore = stateManager.statusResponseCount
                    val read = readStatusWithLock()
                    if (read && stateManager.statusResponseCount > statusesBefore && !stateManager.isActivationCompleted) {
                        stateManager.recoverActivationFromPodStatus()?.let {
                            aapsLogger.info(LTag.PUMP, "recoverActivationFromPodStatus msg=$it")
                        }
                    }
                }
                syncPumpFlows()
            } finally {
                busy = false
            }
        }
    }

    /**
     * One status read with [commandMutex] held: the answer goes into the state and decides an open
     * command. True when a status answer was read. Errors are logged.
     */
    private fun readStatusWithLock(): Boolean = readStatusDeciding(decidedLate = true).read

    /** What one status read gave: whether an answer was read, and what is known now about the open command. */
    private class StatusRead(val read: Boolean, val confirmation: DashCommandConfirmation)

    /**
     * One status read with [commandMutex] held, as [readStatusWithLock]. [decidedLate] false only when
     * the call that sent the open command has not returned yet, so its result reports the outcome.
     */
    private fun readStatusDeciding(decidedLate: Boolean): StatusRead =
        try {
            val sent = sendCommand(build = ::statusCommand)
            val resolved = resolveAndHandle(decidedLate)
            StatusRead(sent.error == null, resolved.confirmation)
        } catch (e: DashPumpNotPairedException) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Pod status not read: ${e.message}")
            StatusRead(false, stateManager.commandConfirmation())
        }

    // -- Unconfirmed commands -------------------------------------------------

    /** What happened to one delivery command. */
    private class DeliveryOutcome(
        val confirmation: DashCommandConfirmation,
        val error: String?,
        /**
         * The pod answered the command with a default status. Android `updateTimeZone` runs on the
         * completion of a basal program send, which needs that answer (`DefaultStatusResponse::class`).
         */
        val answeredWithStatus: Boolean = false,
        /**
         * The write was taken or may have been (Android `CommandSent`), so [onCommandSent] ran and the
         * command is recorded in `PumpSync` from its send. False when nothing left, and when the write
         * threw (Android has no `CommandSent` then).
         */
        val sentEvent: Boolean = false,
    ) {

        fun describe(): String = when (confirmation) {
            DashCommandConfirmation.SUCCESS           -> "confirmed by the pod"
            DashCommandConfirmation.DENIED            -> "refused by the pod, nothing delivered"
            DashCommandConfirmation.SENDING_FAILURE   -> "not sent (${error ?: "send error"}), nothing delivered"
            DashCommandConfirmation.NOT_CONFIRMED     ->
                "not confirmed by the pod (${error ?: "no status in the answer"}). Read the pod status before any new command"

            DashCommandConfirmation.NO_ACTIVE_COMMAND -> "no command was open"
        }
    }

    /**
     * Sends one command that changes delivery, through the active command
     * ([DashPodStateManager.createActiveCommand]), as `OmnipodDashPumpPlugin.executeProgrammingCommand`
     * does on Android, stops included.
     *
     * An open command whose outcome a status already decided is closed first. One still not confirmed
     * refuses the new command ([DashCommandNotConfirmedException]): the pod may have taken it, so a
     * retried bolus could be delivered twice, and a stop sent now would become the last programming
     * command and hide what happened to it. The pod has to be asked for its status first.
     *
     * The command is built first (a wrong value fails before anything is stored), then opened and
     * marked sent before the write, so a crash at any later point leaves a command that needs a
     * status, never one that may be sent again. Only a send error before any byte could reach the pod
     * (`CommandSendErrorSending`) marks it not sent.
     * After the answer is read, the active command is resolved from the status in it.
     *
     * As Android `observePodRunning`, nothing is sent unless the activation is completed and the pod
     * reports that it runs ([DashPodStateManager.isActivatedAndRunning]); the result is then
     * SENDING_FAILURE with no active command. The stop of all delivery ([stopDelivery]) and the
     * deactivation ([deactivatePod]) are not held back: Android has no such check on them (a pod in
     * alarm must still be deactivated), and they never add insulin.
     *
     * Deliberate difference from Android: when the last status reports the sequence this command
     * would be built with as the last programming command, a status is read first, which moves the
     * sequence on. Otherwise a later status could not tell this command from the old one, and would
     * decide SUCCESS for a command the pod never got ([DashCommandConfirmation.of]).
     */
    private fun sendDeliveryCommand(
        kind: DashCommandKind,
        requestedBolusUnits: Double? = null,
        tempBasalRate: Double? = null,
        tempBasalMinutes: Int? = null,
        basalProgram: BasalProgram? = null,
        basalCorrection: Boolean = false,
        build: (uniqueId: Int, sequence: Short) -> Command,
    ): DeliveryOutcome {
        requireSession()
        if (resolveAndHandle(decidedLate = true).confirmation == DashCommandConfirmation.NOT_CONFIRMED) {
            throw DashCommandNotConfirmedException("Refusing command: the last command is not confirmed by the pod. Read the pod status first.")
        }
        if (kind != DashCommandKind.STOP_ALL && kind != DashCommandKind.DEACTIVATE && !stateManager.isActivatedAndRunning) {
            return DeliveryOutcome(
                DashCommandConfirmation.SENDING_FAILURE,
                "the pod is not activated and running (activation ${stateManager.activationProgress}, " +
                    "pod status ${stateManager.reportedPodStatus}), nothing sent"
            )
        }
        if (sequenceSameAsLastProgramming()) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Next sequence is the last programming sequence of the pod: reading a status first")
            sendCommand(build = ::statusCommand)
            if (sequenceSameAsLastProgramming()) {
                return DeliveryOutcome(
                    DashCommandConfirmation.SENDING_FAILURE,
                    "the pod could not be told apart from its last command, nothing sent"
                )
            }
        }
        val sent = sendCommand(build, beforeSend = { command ->
            stateManager.createActiveCommand(
                kind = kind,
                // No pod history on iOS yet (plan, phase 2).
                historyId = 0L,
                createdAtMs = now(),
                requestedBolusUnits = requestedBolusUnits,
                tempBasalRate = tempBasalRate,
                tempBasalMinutes = tempBasalMinutes,
                basalProgram = basalProgram,
                basalCorrection = basalCorrection,
            )
            check(stateManager.activeCommand?.sequence == command.sequenceNumber.toInt()) {
                "The active command must take the sequence the command is built with"
            }
            stateManager.markActiveCommandSent()
        }, afterSent = {
            stateManager.activeCommand?.let(onCommandSent)
        })
        // Only here, never for a status read: a status that could not be sent must not close an
        // open delivery command as "not sent".
        if (!sent.reachedRadio) stateManager.markActiveCommandNotSent()
        return DeliveryOutcome(resolveAndHandle(decidedLate = false).confirmation, sent.error, sent.response is DefaultStatusResponse, sent.sentEvent)
    }

    /**
     * True when the next command would be built with the sequence the last status reports for the
     * last programming command the pod took. False when no status was read yet.
     */
    private fun sequenceSameAsLastProgramming(): Boolean =
        stateManager.podStatus?.lastProgrammingCommandSequence == stateManager.messageSequenceNumber

    /**
     * Closes the active command when a status decided it ([DashPodStateManager.resolveActiveCommand],
     * which in the same write makes a SUCCESS bolus the last bolus and ends it on a SUCCESS stop of
     * the bolus), then gives the outcome to [handleCommandConfirmation].
     */
    private fun resolveAndHandle(decidedLate: Boolean): DashResolvedCommand {
        val at = now()
        val resolved = stateManager.resolveActiveCommand(at)
        handleCommandConfirmation(resolved, decidedLate, at)
        recordUntrackedBolus(at)
        checkPodKaput(at)
        return resolved
    }

    /**
     * Gives a bolus the pod reported and the app did not track to [onCommandDecided] as
     * [DashUntrackedBolus]. The mark in the state is cleared only by its receiver, once the record is
     * written ([DashUntrackedBolus.onWritten]); until then each [resolveAndHandle] gives it again.
     * Before [checkPodKaput], so a fault that ends it corrects a record that is already there.
     */
    private fun recordUntrackedBolus(at: Long) {
        val bolus = stateManager.untrackedBolusToRecord() ?: return
        aapsLogger.warn(LTag.PUMP, "The pod delivers a bolus the app did not track: ${bolus.requestedUnits} U left, recorded now")
        val pumpId = bolus.createdAtMs
        onCommandDecided(DashUntrackedBolus(bolus, at) { stateManager.untrackedBolusWritten(pumpId) })
    }

    /**
     * Android `OmnipodDashPumpPlugin.checkPodKaput`, which Android runs after every status read and
     * every programming command; here after every [resolveAndHandle], so after each status read, each
     * delivery command, before each delivery command and before a pod is discarded.
     *
     * The bolus part: a pod in ALARM or DEACTIVATED ends a last bolus that has not ended, with what its
     * last status left ([DashPodStateManager.completeBolusOnPodKaput]), and gives it to
     * [onCommandDecided] as [DashBolusEndedByFault]. The bolus is stored, so a fault first seen after a
     * crash is corrected by the first status read after it. The bolus is ended in the stored state
     * before the outcome is given: a crash between the two loses the correction, and the bolus then
     * stays counted at the asked amount (more than went in, never less), as on Android.
     *
     * The PUMP_SUSPEND temp basal Android records for a kaput pod and the fault notice and announcement
     * are the plugin's (`DashPluginRules.checkPodKaput`, after the command).
     */
    private fun checkPodKaput(at: Long) {
        val ended = stateManager.completeBolusOnPodKaput() ?: return
        aapsLogger.warn(LTag.PUMP, "Pod kaput (${stateManager.reportedPodStatus}, ${stateManager.alarmType}): bolus ended, ${ended.deliveredUnits()} U of ${ended.requestedUnits} U")
        onCommandDecided(DashBolusEndedByFault(ended, stateManager.alarmType, at))
    }

    /**
     * Gives a final outcome to [onCommandDecided], as Android `handleCommandConfirmation`, after the
     * pod state took it. A SUCCESS bolus also sets [lastBolusAmount] and [lastBolusTime]. Nothing for
     * an open command.
     */
    private fun handleCommandConfirmation(resolved: DashResolvedCommand, decidedLate: Boolean, at: Long) {
        val confirmation = resolved.confirmation
        val command = resolved.command
        if (command == null || !confirmation.isFinal) return
        if (confirmation == DashCommandConfirmation.SUCCESS && command.kind == DashCommandKind.BOLUS) {
            command.requestedBolusUnits?.let {
                lastBolusAmountFlow.value = PumpInsulin(it)
                lastBolusTimeFlow.value = at
            }
        }
        if (decidedLate) aapsLogger.warn(LTag.PUMPBTCOMM, "Command decided late: $confirmation for $command")
        onCommandDecided(DashCommandOutcome(confirmation, command, decidedLate, at, resolved.endedBolus))
    }

    /**
     * The result of [sendCommand]. [reachedRadio] is false only for `CommandSendErrorSending`: the
     * pod cannot have the command. [error] is null when an answer was read.
     */
    private class SentCommand(val reachedRadio: Boolean, val error: String?, val response: Response? = null, val sentEvent: Boolean = false)

    /**
     * One command and its answer: port of `SharedBleManager.sendCommand` with the counter updates of
     * `OmnipodDashManagerImpl.PodEventInterceptor`.
     *
     * - The command is built with the stored unique id and 4-bit sequence; [beforeSend] runs between
     *   build and send (the delivery path opens its active command there).
     * - Deliberate difference from Android: a command other than a status read is not sent while an
     *   active command is open (not closed by a status), on every path, the activation included.
     *   Why: a programming command, a PROGRAM_BEEPS too, moves the last programming sequence of the
     *   pod, so a later status could no longer tell what happened to the open command (a bolus or a
     *   temp basal counted wrong). OmnipodKit (Trio) `PodCommsSession.send` adds no beep block while it
     *   still resolves an unacknowledged command. The delivery path already refuses this earlier
     *   ([sendDeliveryCommand]); this is the last check before the radio. An open command that a
     *   status already decided is closed first ([resolveAndHandle]), as the activation reads statuses
     *   without closing it; only one still NOT_CONFIRMED blocks.
     * - `CommandSendErrorSending`: nothing reached the pod and the sequence stays. Android keeps the
     *   connection.
     * - `CommandSendSuccess` and `CommandSendErrorConfirming`: the sequence goes up by one. In the
     *   second case the pod may have the command, so it stays open.
     * - The answer is read and acknowledged. When there is one (also when the ACK failed), the
     *   sequence goes up by one and a status goes into [DashPodStateManager.updateFromStatus].
     * - No answer: Android raises an error and keeps the connection; here the error is returned.
     * - An exception while sending (an RTS of the pod already waiting, a NACK index out of range) or
     *   while reading (a broken MAC, an answer that does not parse): Android disconnects; here the
     *   link is closed ([closeLink]) and the error is returned. Some bytes may have reached the pod,
     *   so the command counts as sent. As on Android, the sequence does not move after an exception
     *   while sending (no `CommandSent` event).
     */
    private fun sendCommand(
        build: (uniqueId: Int, sequence: Short) -> Command,
        beforeSend: (Command) -> Unit = {},
        afterSent: () -> Unit = {},
    ): SentCommand {
        val s = requireSession()
        val uniqueId = stateManager.uniqueId
            ?: throw DashPumpNotPairedException("Refusing command: no uniqueId (pod not activated)")
        val command = build(uniqueId.toInt(), stateManager.messageSequenceNumber.toShort())
        // A status read on the activation path does not close the command it decides, so close it here
        // first; only a command no status has decided yet blocks.
        if (command !is GetStatusCommand && stateManager.activeCommand != null) resolveAndHandle(decidedLate = true)
        if (command !is GetStatusCommand && stateManager.activeCommand != null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Command not sent: another command is still open (${stateManager.activeCommand})")
            return SentCommand(reachedRadio = false, error = "another command is still open, nothing sent")
        }
        beforeSend(command)
        val sendResult = try {
            s.sendCommand(command)
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Could not send the command", e)
            closeLink("send failed: ${e.message}")
            return SentCommand(reachedRadio = true, error = "could not send the command: ${e.message}")
        }
        when (sendResult) {
            is CommandSendErrorSending    ->
                return SentCommand(reachedRadio = false, error = "could not send the command: ${sendResult.msg}")

            is CommandSendSuccess         -> {
                stateManager.increaseMessageSequenceNumber()
                afterSent()
            }

            is CommandSendErrorConfirming -> {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Command sent but not confirmed: ${sendResult.msg}")
                stateManager.increaseMessageSequenceNumber()
                afterSent()
            }
        }
        // From here on the command reached the radio with a CommandSent event.
        val readResult = try {
            s.readAndAckResponse()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Could not read the answer of the pod", e)
            closeLink("read failed: ${e.message}")
            return SentCommand(reachedRadio = true, error = "could not read the answer: ${e.message}", sentEvent = true)
        }
        val response = when (readResult) {
            is CommandReceiveSuccess -> readResult.result
            is CommandAckError       -> readResult.result
            is CommandReceiveError   -> null
        }
        response?.let(::onResponse)
        val error = if (readResult is CommandReceiveError) "could not read the answer: ${readResult.msg}" else null
        return SentCommand(reachedRadio = true, error = error, response = response, sentEvent = true)
    }

    /** `PodEventInterceptor`: the sequence goes up, then `handleResponse` ([DashPodStateManager.updateFromResponse]). */
    private fun onResponse(response: Response) {
        stateManager.increaseMessageSequenceNumber()
        if (!stateManager.updateFromResponse(response, now())) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Answer not used: $response")
        }
    }

    private fun statusCommand(uid: Int, seq: Short): Command = DashActivation.statusCommand(uid, seq)

    /** Android `observeSendStopDeliveryCommand`: a long single beep when [beep], silent otherwise. */
    private fun stopCommand(type: DeliveryType, uid: Int, seq: Short, beep: Boolean): Command =
        StopDeliveryCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setDeliveryType(type)
            .setBeepType(DashBeepSettings.stopBeep(beep))
            .build()

    private fun failure(comment: String) = DashPumpEnactResult(success = false, enacted = false, comment = comment)

    /**
     * Android `OmnipodDashPumpPlugin.isThisProfileSet`, the same three answers:
     * - true before the activation is completed, so the queue sends no basal to a pod that cannot take
     *   one (the activation sends the program);
     * - false when the last status shows delivery suspended (a basal change stopped half way);
     * - otherwise whether the profile maps ([mapProfileToBasalProgram]) to the program the pod
     *   confirmed last; false when none was confirmed.
     *
     * A profile with no basal values throws [IllegalArgumentException], as Android: it is never
     * taken as an empty program.
     */
    override fun isThisProfileSet(profile: PumpProfile): Boolean {
        if (!stateManager.isActivationCompleted) return true
        if (stateManager.isSuspended) return false
        val running = stateManager.basalProgram
        val equal = mapProfileToBasalProgram(profile, PumpType.OMNIPOD_DASH) == running
        aapsLogger.info(LTag.PUMP, "isThisProfileSet: $equal, running=$running")
        return equal
    }

    override suspend fun loadTDDs(): PumpEnactResult = failure("TDD not yet wired")

    override fun canHandleDST(): Boolean = false

    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) = Unit
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = null

    /**
     * The BLE connection side, see `connectorFactory`. Declared last on purpose: the factory may open
     * the central, and on a state restore iOS can then start a link set up at once, which calls back
     * into this pump ([tryLockLink], [hasSession], ...) from a worker. Every other field must be set by
     * then, or that call would find an empty [commandMutex].
     */
    private val connector: DashPodConnector? = connectorFactory?.invoke(this)

    /**
     * [listener] runs (on a worker) when the paired pod shows a fault in its advertisement while it
     * is not connected ([DashPodConnector.setFaultListener]). The plugin queues a status read there,
     * so the alarm is read at once and not at the next loop. Nothing without a connector.
     */
    fun setPodFaultListener(listener: () -> Unit) {
        connector?.setFaultListener(listener)
    }

    companion object {

        /** `OmnipodDashManagerImpl.NONCE`: the Omnipod Dash seems to use a fixed nonce. */
        const val NONCE = DashActivation.NONCE

        /** Android `RESERVOIR_OVER_50_UNITS_DEFAULT`: the reservoir shown when the pod reports more than 50 U. */
        const val RESERVOIR_OVER_50_UNITS_DEFAULT = 75.0

        /** Android `getPumpStatus`: the only reason that reads a status before the activation is completed. */
        const val REQUESTED_BY_USER = "REQUESTED BY USER"

        /**
         * Creates a fully-wired driver: iOS crypto seams + Keychain store. Give it to a
         * `DashPodConnection`, which attaches a link for each BLE connection.
         */
        fun create(
            aapsLogger: AAPSLogger,
            onCommandDecided: (DashPumpOutcome) -> Unit,
            bolusProgressData: BolusProgressData? = null,
        ): IosDashPump =
            IosDashPump(aapsLogger = aapsLogger, onCommandDecided = onCommandDecided, bolusProgressData = bolusProgressData)

        private fun podIdOf(address: ByteArray): Long {
            require(address.size == 4) { "The pod address has to be 4 bytes" }
            return address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        }
    }
}

/** Minimal [PumpEnactResult] for the iOS Dash driver. */
data class DashPumpEnactResult(
    override var success: Boolean = false,
    override var enacted: Boolean = false,
    override var comment: String = "",
    override var duration: Int = 0,
    override var absolute: Double = 0.0,
    override var percent: Int = 100,
    override var isPercent: Boolean = false,
    override var isTempCancel: Boolean = false,
    override var bolusDelivered: Double = 0.0,
    override var queued: Boolean = false,
    override var cancelled: Boolean = false
) : PumpEnactResult {

    override fun success(success: Boolean): PumpEnactResult = apply { this.success = success }
    override fun enacted(enacted: Boolean): PumpEnactResult = apply { this.enacted = enacted }
    override fun comment(comment: String): PumpEnactResult = apply { this.comment = comment }
    override fun comment(ref: TextRef): PumpEnactResult = apply { /* TextRef resolution is UI work */ }
    override fun cancelled(cancelled: Boolean): PumpEnactResult = apply { this.cancelled = cancelled }
    override fun duration(duration: Int): PumpEnactResult = apply { this.duration = duration }
    override fun absolute(absolute: Double): PumpEnactResult = apply { this.absolute = absolute }
    override fun percent(percent: Int): PumpEnactResult = apply { this.percent = percent }
    override fun isPercent(isPercent: Boolean): PumpEnactResult = apply { this.isPercent = isPercent }
    override fun isTempCancel(isTempCancel: Boolean): PumpEnactResult = apply { this.isTempCancel = isTempCancel }
    override fun bolusDelivered(bolusDelivered: Double): PumpEnactResult = apply { this.bolusDelivered = bolusDelivered }
    override fun queued(queued: Boolean): PumpEnactResult = apply { this.queued = queued }
}
