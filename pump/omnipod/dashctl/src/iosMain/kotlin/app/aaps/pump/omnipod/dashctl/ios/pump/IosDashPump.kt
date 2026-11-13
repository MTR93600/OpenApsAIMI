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
import app.aaps.pump.omnipod.dashctl.driver.DashBolusProgress
import app.aaps.pump.omnipod.dashctl.driver.DashBolusWait
import app.aaps.pump.omnipod.dashctl.driver.DashBolusWaitPod
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosSecureRandomBytes
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.Response
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.pod.response.VersionResponse
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashCommandNotConfirmedException
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashResolvedCommand
import app.aaps.pump.omnipod.dashctl.session.DashPodStatus
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.concurrent.Volatile
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
 * The final outcome of one delivery command, given to `IosDashPump.onCommandDecided`, as Android
 * `OmnipodDashPumpPlugin.handleCommandConfirmation` gets it. The receiver records it (pump history,
 * `PumpSync`).
 *
 * @property confirmation SUCCESS (the pod took it), DENIED or SENDING_FAILURE (it did not).
 * @property decidedLate true when a later status decided it, after the call that sent the command
 *   had already returned a failure. A late SUCCESS bolus is insulin the caller was told was not
 *   delivered: it must be recorded, or a retry delivers it a second time.
 * @property decidedAtMs when the outcome became known. This is not the delivery time: a start or a
 *   stop (temp basal, bolus) is recorded at [DashActiveCommand.createdAtMs] of [command], as Android
 *   records it at the `createdAt` of its history record.
 * @property endedBolus for a confirmed stop of the bolus (STOP_BOLUS, SUCCESS), or a confirmed
 *   SUSPEND_DELIVERY or STOP_ALL that stopped a bolus still running: the bolus it ended,
 *   with [DashLastBolus.deliveredUnits] the amount that went in. The receiver records the bolus again
 *   with that amount, at [DashLastBolus.createdAtMs], as Android `handleCommandConfirmation` on
 *   CANCEL_BOLUS (`syncBolusWithPumpId` with the delivered units). Null otherwise.
 */
data class DashCommandOutcome(
    val confirmation: DashCommandConfirmation,
    val command: DashActiveCommand,
    val decidedLate: Boolean,
    val decidedAtMs: Long,
    val endedBolus: DashLastBolus? = null,
)

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
     * Gets every final outcome of a delivery command. No default on purpose: whoever builds the
     * pump has to decide where a late bolus is recorded.
     */
    private val onCommandDecided: (DashCommandOutcome) -> Unit,
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
     * The last bolus the pod took, set when its SUCCESS is known (at once or late). The time is when
     * SUCCESS became known, not when the pod delivered it. Not stored: lost at restart.
     */
    override val lastBolusTime: StateFlow<Long?> = lastBolusTimeFlow
    override val lastBolusAmount: StateFlow<PumpInsulin?> = lastBolusAmountFlow

    // Not tracked yet on iOS: these are placeholders, the same as in the iOS Medtrum driver.
    override val lastDataTime: StateFlow<Long> = MutableStateFlow(0L)
    override val baseBasalRate: PumpRate = PumpRate(0.0)
    override val reservoirLevel: StateFlow<PumpInsulin> = MutableStateFlow(PumpInsulin(0.0))
    override val batteryLevel: StateFlow<Int?> = MutableStateFlow(null)

    override fun isConfigured(): Boolean = isPaired()
    override fun isInitialized(): Boolean = isPaired() && hasSession()
    /** Android `isSuspended()`: the last status shows delivery suspended ([DashPodStateManager.isSuspended]). */
    override fun isSuspended(): Boolean = stateManager.isSuspended
    /** A command runs, or an activation runs ([runActivation]), as Android `isBusy`. */
    override fun isBusy(): Boolean = busy || activationRunning

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
     * - the reservoir check of Android (`requestedBolusAmount > reservoirLevel`) is not here yet: the
     *   reservoir level needs the activation (plan, step 6).
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
        if (stateManager.podStatus?.deliveryStatus?.bolusDeliveringActive() == true) {
            return failure("Bolus refused: a bolus is already in progress")
        }
        if (bolusCanceled) {
            aapsLogger.info(LTag.PUMP, "Bolus canceled before it was sent")
            return DashPumpEnactResult(success = true, enacted = false, comment = "Bolus canceled, nothing sent")
        }
        val outcome = sendDeliveryCommand(DashCommandKind.BOLUS, requestedBolusUnits = units) { uid, seq ->
            ProgramBolusCommand.Builder()
                .setUniqueId(uid)
                .setSequenceNumber(seq)
                .setNonce(NONCE)
                .setNumberOfUnits(units)
                .setDelayBetweenPulsesInEighthSeconds(pulseRate.toByte())
                .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
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
                val stop = sendDeliveryCommand(DashCommandKind.STOP_BOLUS) { uid, seq ->
                    stopCommand(DeliveryType.BOLUS, uid, seq)
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
            val outcome = sendDeliveryCommand(
                DashCommandKind.TEMP_BASAL, tempBasalRate = absoluteRate, tempBasalMinutes = durationInMinutes
            ) { uid, seq ->
                ProgramTempBasalCommand.Builder()
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(NONCE)
                    .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
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
        val cancel = sendDeliveryCommand(DashCommandKind.STOP_TEMP_BASAL) { uid, seq ->
            stopCommand(DeliveryType.TEMP_BASAL, uid, seq)
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

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        commandMutex.withLock {
            busy = true
            try {
                val outcome = sendDeliveryCommand(DashCommandKind.STOP_TEMP_BASAL) { uid, seq ->
                    stopCommand(DeliveryType.TEMP_BASAL, uid, seq)
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
     *   built at the time it is sent, beeps off (Android `hasBasalBeepEnabled` false). When the pod
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
     * Not here yet: `observePodRunning` (pod activated and running) before the commands, which comes
     * with the activation (plan, step 6), and the connect before the commands (`observeConnectToPod`,
     * step 9). The time zone is the one of the phone when the program is built, as Android; a change of
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
                val outcome = sendDeliveryCommand(DashCommandKind.BASAL_PROGRAM, basalProgram = basalProgram) { uid, seq ->
                    basalProgramCommand(basalProgram, uid, seq)
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
     * - The suspend (Android `observeSuspendDeliveryCommand`, beeps off) goes through
     *   [sendDeliveryCommand] with its own active command (SUSPEND_DELIVERY).
     * - SUCCESS and DENIED: the program goes next (null is returned). Android goes on after a denied
     *   suspend: `updateActiveCommand` emits `CommandConfirmed(success = false)`.
     * - NOT_CONFIRMED: the suspend stays open and the program is not sent (Android: the
     *   `observeNoActiveCommand` of the program fails).
     * - SENDING_FAILURE: the program is not sent (Android: `updateActiveCommand` raises the send error).
     *
     * Not kept yet: Android sets `suspendAlertsEnabled` after the suspend (step 7 of the plan).
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
        val suspend = sendDeliveryCommand(DashCommandKind.SUSPEND_DELIVERY) { uid, seq ->
            SuspendDeliveryCommand.Builder()
                .setSequenceNumber(seq)
                .setUniqueId(uid)
                .setNonce(NONCE)
                .setBeepType(BeepType.SILENT)
                .build()
        }
        return when (suspend.confirmation) {
            DashCommandConfirmation.SUCCESS,
            DashCommandConfirmation.DENIED -> null

            else                           -> failure("Suspend before the basal program: ${suspend.describe()}. Basal program not sent")
        }
    }

    /**
     * Android `OmnipodDashManagerImpl.observeSendProgramBasalCommand` with `hasBasalBeepEnabled` false.
     * The current time is read when the command is built, as Android reads `Date()` there: the pod
     * starts the program at the slot of that time.
     */
    private fun basalProgramCommand(basalProgram: BasalProgram, uid: Int, seq: Short): Command =
        ProgramBasalCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = false, atInterval = 0))
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
                val outcome = sendDeliveryCommand(DashCommandKind.STOP_ALL) { uid, seq ->
                    stopCommand(DeliveryType.ALL, uid, seq)
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

    /** Suspend: halts delivery until resumed. Guarded like everything else. */
    fun suspendDelivery(): PumpEnactResult = stopDelivery()

    /**
     * Asks the pod for its default status (Android `getStatus`). The answer goes into
     * [DashPodStateManager.updateFromStatus], and an open command is then decided. This is the way
     * out of NOT_CONFIRMED. Not a programming command, so no active command is opened. Fails closed
     * like everything else; errors are logged.
     */
    override suspend fun getPumpStatus(reason: String) {
        commandMutex.withLock {
            busy = true
            try {
                readStatusWithLock()
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
        return resolved
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

    /** `PodEventInterceptor.handleResponse` for what the iOS state keeps today. */
    private fun onResponse(response: Response) {
        stateManager.increaseMessageSequenceNumber()
        when (response) {
            is DefaultStatusResponse -> stateManager.updateFromDefaultStatus(DashPodStatus.of(response, now()))
            is AlarmStatusResponse   -> stateManager.updateFromStatus(DashPodStatus.of(response, now()))
            is VersionResponse       -> stateManager.updateFromVersionResponse(response, now())
            is SetUniqueIdResponse   -> stateManager.updateFromSetUniqueIdResponse(response, now())
            else                     -> aapsLogger.info(LTag.PUMPBTCOMM, "Answer not used yet: $response")
        }
    }

    private fun statusCommand(uid: Int, seq: Short): Command =
        GetStatusCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setStatusResponseType(ResponseType.StatusResponseType.DEFAULT_STATUS_RESPONSE)
            .build()

    private fun stopCommand(type: DeliveryType, uid: Int, seq: Short): Command =
        StopDeliveryCommand.Builder()
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(NONCE)
            .setDeliveryType(type)
            .setBeepType(BeepType.SILENT)
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
        const val NONCE = 1229869870

        /**
         * Creates a fully-wired driver: iOS crypto seams + Keychain store. Give it to a
         * `DashPodConnection`, which attaches a link for each BLE connection.
         */
        fun create(
            aapsLogger: AAPSLogger,
            onCommandDecided: (DashCommandOutcome) -> Unit,
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
