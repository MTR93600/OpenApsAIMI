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
import app.aaps.pump.omnipod.dashctl.comm.connection.DashLinkClient
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
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
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
import app.aaps.pump.omnipod.dashctl.session.DashResolvedCommand
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.concurrent.Volatile
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
 * bolus. That record was made from the SUCCESS [DashCommandOutcome] of the bolus, at the asked units
 * (Android `pumpSyncBolusStart`), and that outcome comes first, also when it was decided inside the call
 * and a fault in the bolus wait gives this correction while [IosDashPump.deliverTreatment] still runs.
 * The key of the record is the command time: [DashLastBolus.createdAtMs] is
 * [DashActiveCommand.createdAtMs] of that outcome (Android updates the record by its `pumpId`; here
 * [DashLastBolus.historyId] is 0, there is no pod history yet). Null only for a bolus whose command was
 * stored by codec version 3 or before; the receiver then takes the last SUCCESS bolus it recorded.
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
 *   "Discard pod" keeps them. The receiver must do the same: count a BOLUS at
 *   [DashActiveCommand.requestedBolusUnits] and a TEMP_BASAL as started, both at
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
class IosDashPump(
    private val aapsLogger: AAPSLogger,
    /**
     * Gets every final outcome of a delivery command ([DashCommandOutcome]) and every bolus a pod
     * fault ended ([DashBolusEndedByFault]). No default on purpose: whoever builds the pump has to
     * decide where a late bolus and a bolus correction are recorded.
     */
    private val onCommandDecided: (DashPumpOutcome) -> Unit,
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
) : Pump, DashLinkClient {

    private var session: Session? = null
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

    /** Called by `DashPodConnection` with [commandMutex] held: [establishSession] without the lock. */
    override fun startSession() {
        establishSessionWithLock()
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
            val sent = sendCommand(build) { beforeSend() }
            return DashExchange(sent.reachedRadio, sent.error)
        }
    }

    private fun activation() = DashActivation(stateManager, activationPod, aapsLogger, now, sleep)

    /**
     * Android `OmnipodDashManagerImpl.activatePodPart1`, as the wizard calls it (`doInitializePod`):
     * - refused unless the activation progress is before PHASE_1_COMPLETED (Android
     *   `observePodReadyForActivationPart1`), before anything is sent;
     * - a pod not paired yet is paired on the link of the connection ([pair], with the id Android
     *   gives) and the session is set up on it ([establishSession]), as Android `pairNewPod`. The
     *   caller connects first (`DashPodConnection.connect` gives `LinkReady`). A pod already paired
     *   needs a session, as after a reconnect;
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
        activationCall("Pod activation part 1") {
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
     */
    suspend fun activatePodPart2(
        basalProgram: BasalProgram,
        userConfiguredExpirationReminderHours: Long?,
        userConfiguredExpirationAlarmHours: Long?,
        expirationAlertSettings: DashExpirationAlertSettings? = null,
    ): PumpEnactResult =
        activationCall("Pod activation part 2") {
            activation().activatePart2(basalProgram, userConfiguredExpirationReminderHours, userConfiguredExpirationAlarmHours)
            expirationAlertSettings?.let { stateManager.updateExpirationAlertSettings(it) }
        }

    /** One activation call: [activationRunning] and [commandMutex] for the whole [block]; errors are a failure result. */
    private suspend fun activationCall(name: String, block: suspend () -> Unit): PumpEnactResult {
        val before = activationRunning
        activationRunning = true
        try {
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

    /** True while the link of the current connection is usable and has a session. */
    override fun isConnected(): Boolean = hasSession()
    override fun isConnecting(): Boolean = false
    override fun isHandshakeInProgress(): Boolean = false

    /**
     * Does nothing yet: the BLE connection is made by the central owner (plan, step 9) through
     * `DashPodConnection.connect`, which gives the link to [attachLink]. Until then a command after a
     * drop is refused (fail closed) until the owner connects again.
     */
    override fun connect(reason: String) = Unit

    /**
     * Closes the current link for good, as Android `disconnect(false)` disconnects the GATT. Ignored
     * while an activation runs ([runActivation]), as Android (#5219).
     */
    override fun disconnect(reason: String) {
        if (activationRunning) {
            aapsLogger.info(LTag.PUMP, "Dash disconnect ignored, pod activation is running ($reason)")
            return
        }
        closeLink("disconnect: $reason")
    }

    override fun stopConnecting() = Unit

    // -- Commands -----------------------------------------------------------

    /**
     * Delivers a bolus as Android `OmnipodDashPumpPlugin.deliverTreatment`:
     * - refused (nothing sent) while the last status shows a bolus running, as Android;
     * - a stop asked before the bolus is sent: nothing is sent, the result is a success with nothing
     *   delivered, as Android (`observeDeliveryNotCanceled`, `success(bolusCanceled)`);
     * - the bolus goes through its active command. Only after its SUCCESS the bolus is followed to its
     *   end ([DashBolusWait]): the result is a success with what went in.
     *
     * - a bolus that is not confirmed while the user asked to stop it: a status is read first to decide
     *   it ([decideBolusBeforeStop], Android's first block of `waitForBolusDeliveryToComplete`); when
     *   the pod took it, it is followed and the wait sends the stop.
     *
     * Deliberate differences from Android, all fail closed:
     * - a bolus that is DENIED, never sent or not confirmed (no stop asked) is a failure with nothing
     *   delivered, and is not followed. Android runs the wait after a DENIED and after a not confirmed
     *   bolus too, and after a DENIED it reports the amount of the bolus before (its `lastBolus`). A
     *   bolus that a later status confirms reaches [onCommandDecided]. Still open: a stop asked after
     *   this failure returned does nothing (the bolus dialog is closed); Android would still be in its
     *   wait and send it.
     *
     * As Android, a bolus larger than the reservoir ([syncPumpFlows], [reservoirLevel]) is refused with
     * nothing sent. Android checks it before the pulse rate (which it reads with `!!`); here the pulse
     * rate comes first, both refuse with nothing sent.
     */
    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        bolusDeliveryInProgress = true
        try {
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
        val units = detailedBolusInfo.insulin
        // Android passes podStateManager.pulseRate!!; it is only known after activation.
        // Without it the bolus bytes would be wrong, so refuse before anything is opened or sent.
        val pulseRate = stateManager.pulseRate
            ?: return failure("Bolus refused: the pulse rate of the pod is not known (activation not finished)")
        // Android: the flows are taken from the pod state first, so the reservoir is the stored one.
        syncPumpFlows()
        if (units > reservoirLevel.value.cU) {
            return failure("Bolus refused: not enough insulin in the reservoir (${reservoirLevel.value.cU} U)")
        }
        if (stateManager.podStatus?.deliveryStatus?.bolusDeliveringActive() == true) {
            return failure("Bolus refused: a bolus is already in progress")
        }
        if (bolusCanceled) {
            aapsLogger.info(LTag.PUMP, "Bolus canceled before it was sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Bolus canceled, nothing sent")
        }
        // Android: the SMB beep setting for an SMB, the bolus beep setting for any other bolus.
        val bolusBeeps = beeps().let { if (detailedBolusInfo.bolusType == BS.Type.SMB) it.smbBeeps else it.bolusBeeps }
        val outcome = sendDeliveryCommand(DashCommandKind.BOLUS, requestedBolusUnits = units) { uid, seq ->
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
        if (confirmation == DashCommandConfirmation.NOT_CONFIRMED && bolusCanceled) {
            confirmation = decideBolusBeforeStop()
        }
        if (confirmation != DashCommandConfirmation.SUCCESS) {
            return failure("Bolus $units U: ${DeliveryOutcome(confirmation, outcome.error).describe()}")
        }
        val delivered = DashBolusWait(bolusWaitPod, bolusProgress, sleep)
            .waitForBolusDeliveryToComplete(units, isSmb = detailedBolusInfo.bolusType == BS.Type.SMB)
        aapsLogger.info(LTag.PUMP, "deliverTreatment: deliveredBolusAmount=$delivered")
        return DashPumpEnactResult(success = true, enacted = true, bolusDelivered = delivered, comment = "Bolus $delivered U of $units U delivered")
    }

    /**
     * Android `waitForBolusDeliveryToComplete`, first block (`bolusCanceled && activeCommand != null`):
     * the user asked to stop a bolus that is not confirmed. A status is read, up to
     * [DashBolusWait.BOLUS_RETRIES] times [DashBolusWait.BOLUS_RETRY_INTERVAL_MS] apart, to decide
     * the bolus. When the pod took it, the wait then sends the stop. Without this the stop would be
     * dropped and the pod would deliver all of it, while every other delivery command, a stop of all
     * delivery too, is refused until a status is read.
     *
     * The outcome is decided inside the call, so it goes to [onCommandDecided] as not late and the
     * result of [deliverTreatment] reports what went in. Nothing read: the bolus stays open and
     * nothing is reported delivered (fail closed).
     */
    private suspend fun decideBolusBeforeStop(): DashCommandConfirmation {
        repeat(DashBolusWait.BOLUS_RETRIES) {
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

        override fun readStatus(): Boolean = readStatusWithLock()

        /**
         * Android `cancelBolus`. While a command is still open (a stop whose answer was lost), a
         * status is read instead: it decides the open stop, and a second stop sent now would become
         * the last programming command and hide what happened to the first one.
         */
        override fun cancelBolus(): Boolean {
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
     */
    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = commandMutex.withLock {
        busy = true
        try {
            requireSession()
            cancelRunningTempBasalFirst()?.let { return@withLock it }
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
     * Android `OmnipodDashPumpPlugin.observeNoActiveTempBasal`, called with [commandMutex] held.
     *
     * - The decision is taken on the last status known before anything is sent: when it shows no temp
     *   basal (`deliveryStatus.tempBasalActive()` is false), nothing is sent. When no status is known
     *   yet, the cancel is sent, as Android (`deliveryStatus?.tempBasalActive() == false` is false
     *   for null).
     * - The cancel goes through [sendDeliveryCommand] with its own active command.
     * - SUCCESS and DENIED: the new temp basal goes next (null is returned). Android goes on after a
     *   denied cancel too: `updateActiveCommand` emits `CommandConfirmed(success = false)`.
     * - NOT_CONFIRMED: the cancel stays open and the new temp basal is not sent (Android: the
     *   `observeNoActiveCommand` of the set fails).
     * - SENDING_FAILURE: the new temp basal is not sent (Android: `updateActiveCommand` raises the
     *   send error).
     *
     * Returns the failure to give back, or null when the new temp basal may be sent. Throws
     * [DashCommandNotConfirmedException] while another command is open: nothing is sent.
     */
    private fun cancelRunningTempBasalFirst(): PumpEnactResult? {
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
            DashCommandConfirmation.SUCCESS,
            DashCommandConfirmation.DENIED          -> null

            else                                    -> failure("Cancel of the running TBR: ${cancel.describe()}. New TBR not sent")
        }
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
        commandMutex.withLock {
            busy = true
            try {
                if (!tempBasalActive() && !expectedTempBasalRunning()) {
                    aapsLogger.info(LTag.PUMP, "cancelTempBasal: no temp basal to cancel, nothing sent")
                    return@withLock DashPumpEnactResult(success = true, enacted = false, comment = "No temp basal to cancel")
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
     * `observePodRunning` (see [sendDeliveryCommand]). Not here yet: the connect before the commands
     * (`observeConnectToPod`, step 9). The time zone is the one of the phone when the program is built, as Android; a change of
     * time zone or DST is ignored, as Android (`timezoneOrDSTChanged`).
     */
    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        if (!stateManager.isActivationCompleted) {
            aapsLogger.info(LTag.PUMP, "setNewBasalProfile: pod activation not completed, nothing sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Pod not activated, basal program not sent")
        }
        val basalProgram = try {
            mapProfileToBasalProgram(profile, PumpType.OMNIPOD_DASH)
        } catch (e: IllegalArgumentException) {
            return failure("Basal program refused: ${e.message}")
        }
        return commandMutex.withLock {
            busy = true
            try {
                requireSession()
                suspendDeliveryIfActive()?.let { return@withLock it }
                val beep = beeps().basalBeeps
                val outcome = sendDeliveryCommand(DashCommandKind.BASAL_PROGRAM, basalProgram = basalProgram) { uid, seq ->
                    basalProgramCommand(basalProgram, uid, seq, beep)
                }
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
        commandMutex.withLock {
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
     * RESUME_DELIVERY or SET_BASAL_PROFILE). That is the plugin's job here (phase 4): on such an
     * outcome it calls [disableSuspendAlerts].
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
     * too. As on Android, an open command still blocks it (Android `createActiveCommand` fails) until a
     * status decides it; a pod that does not answer at all is discarded instead ([discardPod]).
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
    suspend fun deactivatePod(): PumpEnactResult = lockedCommand {
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
     */
    suspend fun discardPod() {
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
        stateManager.reset()
        closeLink(reason)
    }

    /**
     * One command call: [commandMutex] and [isBusy] for the whole [block]; an open command or a missing
     * session is a failure result.
     */
    private suspend fun lockedCommand(block: () -> PumpEnactResult): PumpEnactResult = commandMutex.withLock {
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
     * Not here, for the plugin (phase 4): Android `verifyPumpState` (it compares the temp basal of the
     * pod with the one `PumpSync` expects) and the history.
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
        if (reason != REQUESTED_BY_USER && !stateManager.isActivationCompleted) {
            aapsLogger.info(LTag.PUMP, "getPumpStatus ($reason): pod activation not completed, nothing read")
            return
        }
        commandMutex.withLock {
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
    private class DeliveryOutcome(val confirmation: DashCommandConfirmation, val error: String?) {

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
        val sent = sendCommand(build) { command ->
            stateManager.createActiveCommand(
                kind = kind,
                // No pod history on iOS yet (plan, phase 2).
                historyId = 0L,
                createdAtMs = now(),
                requestedBolusUnits = requestedBolusUnits,
                tempBasalRate = tempBasalRate,
                tempBasalMinutes = tempBasalMinutes,
                basalProgram = basalProgram,
            )
            check(stateManager.activeCommand?.sequence == command.sequenceNumber.toInt()) {
                "The active command must take the sequence the command is built with"
            }
            stateManager.markActiveCommandSent()
        }
        // Only here, never for a status read: a status that could not be sent must not close an
        // open delivery command as "not sent".
        if (!sent.reachedRadio) stateManager.markActiveCommandNotSent()
        return DeliveryOutcome(resolveAndHandle(decidedLate = false).confirmation, sent.error)
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
        checkPodKaput(at)
        return resolved
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
     * Not here, for the plugin (phase 4): the PUMP_SUSPEND temp basal Android records for a kaput pod,
     * the fault notification and announcement (with [DashPodStateManager.alarmType] and
     * [DashPodStateManager.alarmSynced]).
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
    private class SentCommand(val reachedRadio: Boolean, val error: String?)

    /**
     * One command and its answer: port of `SharedBleManager.sendCommand` with the counter updates of
     * `OmnipodDashManagerImpl.PodEventInterceptor`.
     *
     * - The command is built with the stored unique id and 4-bit sequence; [beforeSend] runs between
     *   build and send (the delivery path opens its active command there).
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
    ): SentCommand {
        val s = requireSession()
        val uniqueId = stateManager.uniqueId
            ?: throw DashPumpNotPairedException("Refusing command: no uniqueId (pod not activated)")
        val command = build(uniqueId.toInt(), stateManager.messageSequenceNumber.toShort())
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

            is CommandSendSuccess         -> stateManager.increaseMessageSequenceNumber()

            is CommandSendErrorConfirming -> {
                aapsLogger.warn(LTag.PUMPBTCOMM, "Command sent but not confirmed: ${sendResult.msg}")
                stateManager.increaseMessageSequenceNumber()
            }
        }
        val readResult = try {
            s.readAndAckResponse()
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMPBTCOMM, "Could not read the answer of the pod", e)
            closeLink("read failed: ${e.message}")
            return SentCommand(reachedRadio = true, error = "could not read the answer: ${e.message}")
        }
        val error = when (readResult) {
            is CommandReceiveSuccess -> {
                onResponse(readResult.result)
                null
            }

            is CommandAckError       -> {
                onResponse(readResult.result)
                null
            }

            is CommandReceiveError   -> "could not read the answer: ${readResult.msg}"
        }
        return SentCommand(reachedRadio = true, error = error)
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
