package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import app.aaps.pump.omnipod.dashctl.comm.endecrypt.EnDecrypt
import app.aaps.pump.omnipod.dashctl.comm.exceptions.SessionEstablishmentException
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
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
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosSecureRandomBytes
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.pod.command.GetStatusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.Response
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashCommandNotConfirmedException
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStatus
import kotlin.time.Clock
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
 * @property decidedAtMs when the outcome became known. This is not the delivery time.
 */
data class DashCommandOutcome(
    val confirmation: DashCommandConfirmation,
    val command: DashActiveCommand,
    val decidedLate: Boolean,
    val decidedAtMs: Long,
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
 * Deliberate difference from Android: before a delivery command, when the 4-bit sequence it would
 * be built with is the one the last status reports for the last programming command, a status is
 * read first. Android sends the command, and a later status then decides SUCCESS for it even when
 * the pod never got it (see [DashCommandConfirmation.of]).
 *
 * [messageIO] is the link to the pod. It belongs to one BLE connection; who owns the connection
 * (and builds a new `DashBleLink` after a drop) is phase 3 of the plan. As on Android, a session is
 * only set up on a fresh connection: an exception while pairing, setting up the session, sending a
 * command or reading its answer makes Android disconnect (`SharedBleManager`), and here it closes
 * this link for good. Every later call is refused and sends nothing; a new connection needs a new
 * `IosDashPump` on a new `MessageIO`. [disconnect] closes the link the same way.
 *
 * Threading: commands are serialized by [commandMutex]. `MessageIO` blocks the calling thread;
 * the BLE calls themselves run on the CoreBluetooth queue (`QueuedBleCentral`).
 */
class IosDashPump(
    private val messageIO: MessageIO,
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
) : Pump {

    private var session: Session? = null
    private val commandMutex = Mutex()
    private var connected = false
    private var busy = false

    /**
     * The outer counter after a fresh pairing on this connection: the next [establishSession]
     * starts from it, as `pairNewPod` passes `pairResult.msgSeq` to `establishSession`. Null
     * otherwise, and the session then starts from 1.
     */
    private var pairMsgSeq: Byte? = null

    /**
     * True once this link must not be used again: where Android disconnects (an exception in
     * pairing, session set up, sending or reading). Never set back to false.
     */
    private var linkClosed = false

    // -- Safety guard -------------------------------------------------------

    /**
     * Android `disconnect(false)` after an exception: the session ends and this link is not used
     * again. The pod may still hold half a message or a late answer, so a new session on it could
     * read that answer as its own.
     */
    private fun closeLink(reason: String) {
        aapsLogger.warn(LTag.PUMPBTCOMM, "Dash link closed: $reason")
        linkClosed = true
        connected = false
        endSession()
    }

    private fun requireLinkOpen() {
        if (linkClosed) {
            throw DashPumpNotPairedException("Refusing: the link to the pod was closed after an error. Connect again.")
        }
    }

    /**
     * Fails closed unless the pod is paired (LTK present), the session is
     * established, the link is still usable and we are connected. Every
     * command-sending method must call this first.
     */
    private fun requireSession(): Session {
        requireLinkOpen()
        if (stateManager.ltk == null) {
            throw DashPumpNotPairedException("Refusing command: pod is not paired (no LTK)")
        }
        val s = session ?: throw DashPumpNotPairedException("Refusing command: no session established")
        if (!connected) {
            throw DashPumpNotPairedException("Refusing command: not connected to the pod")
        }
        return s
    }

    fun isPaired(): Boolean = stateManager.ltk != null

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
     * followed by [establishSession] on the same connection.
     */
    fun pair(podAddress: ByteArray = Ids(null).podId.address) {
        if (stateManager.ltk != null) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Pod already paired, pairing skipped")
            return
        }
        requireLinkOpen()
        val ids = Ids(podIdOf(podAddress))
        try {
            val pairResult = LTKExchanger(aapsLogger, messageIO, ids, newKeyExchange()).negotiateLTK()
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
     */
    fun establishSession() {
        requireLinkOpen()
        val ltk = stateManager.ltk ?: throw DashPumpNotPairedException("Missing LTK, activate the pod first")
        val uniqueId = stateManager.uniqueId ?: throw DashPumpNotPairedException("Missing unique id, activate the pod first")
        val ids = Ids(uniqueId)
        val msgSeq = pairMsgSeq ?: 1.toByte()
        pairMsgSeq = null
        session = null
        try {
            var response = negotiate(ltk, ids, msgSeq)
            if (response is SessionNegotiationResynchronization) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Updating pod EAP SQN to: ${response.synchronizedEapSqn}")
                stateManager.updateEapAkaSequenceNumber(response.synchronizedEapSqn.toLong())
                response = negotiate(ltk, ids, msgSeq)
                if (response is SessionNegotiationResynchronization) {
                    throw SessionEstablishmentException("Received resynchronization SQN for the second time")
                }
            }
            stateManager.commitEapAkaSequenceNumber()
            val keys = response as SessionKeys
            session = Session(
                aapsLogger = aapsLogger,
                msgIO = messageIO,
                ids = ids,
                sessionKeys = keys,
                enDecrypt = EnDecrypt(aapsLogger, keys.nonce, keys.ck, aesCcm)
            )
        } catch (e: Exception) {
            closeLink("session set up failed: ${e.message}")
            throw e
        }
    }

    private fun negotiate(ltk: ByteArray, ids: Ids, msgSeq: Byte): SessionNegotiationResponse =
        SessionEstablisher(
            aapsLogger = aapsLogger,
            msgIO = messageIO,
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
    override fun isInitialized(): Boolean = isPaired() && session != null
    override fun isSuspended(): Boolean = false
    override fun isBusy(): Boolean = busy
    override fun isConnected(): Boolean = connected
    override fun isConnecting(): Boolean = false
    override fun isHandshakeInProgress(): Boolean = false

    override fun connect(reason: String) {
        connected = true
    }

    /** Closes this link for good, as Android `disconnect` closes the GATT connection. */
    override fun disconnect(reason: String) {
        closeLink("disconnect: $reason")
    }

    override fun stopConnecting() = Unit

    // -- Commands -----------------------------------------------------------

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult =
        commandMutex.withLock {
            busy = true
            try {
                val units = detailedBolusInfo.insulin
                // Android passes podStateManager.pulseRate!!; it is only known after activation.
                // Without it the bolus bytes would be wrong, so refuse before anything is opened or sent.
                val pulseRate = stateManager.pulseRate
                    ?: return@withLock failure("Bolus refused: the pulse rate of the pod is not known (activation not finished)")
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
                if (outcome.confirmation == DashCommandConfirmation.SUCCESS) {
                    DashPumpEnactResult(success = true, enacted = true, bolusDelivered = units, comment = "Bolus $units U confirmed by the pod")
                } else {
                    failure("Bolus $units U: ${outcome.describe()}")
                }
            } catch (e: DashCommandNotConfirmedException) {
                failure(e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                failure(e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }

    override fun stopBolusDelivering() {
        runBlocking {
            commandMutex.withLock {
                try {
                    sendDeliveryCommand(DashCommandKind.STOP_BOLUS) { uid, seq ->
                        stopCommand(DeliveryType.BOLUS, uid, seq)
                    }
                } catch (e: DashCommandNotConfirmedException) {
                    // Fail closed: a stop sent now would become the last programming command, and the
                    // open one could no longer be told apart. Nothing sent.
                } catch (e: DashPumpNotPairedException) {
                    // Fail closed: nothing sent.
                }
            }
        }
    }

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult = commandMutex.withLock {
        busy = true
        try {
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

    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult =
        // Basal profile programming is a D4-UI flow (verified pairing + user confirm).
        failure("Basal profile programming not yet wired")

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
                sendCommand(build = ::statusCommand)
                val (confirmation, command) = stateManager.resolveActiveCommand()
                handleCommandConfirmation(confirmation, command, decidedLate = true)
            } catch (e: DashPumpNotPairedException) {
                aapsLogger.info(LTag.PUMPBTCOMM, "Pod status not read: ${e.message}")
            } finally {
                busy = false
            }
        }
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
        build: (uniqueId: Int, sequence: Short) -> Command,
    ): DeliveryOutcome {
        requireSession()
        val (confirmation, previous) = stateManager.resolveActiveCommand()
        handleCommandConfirmation(confirmation, previous, decidedLate = true)
        if (confirmation == DashCommandConfirmation.NOT_CONFIRMED) {
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
                requestedBolusUnits = requestedBolusUnits,
                tempBasalRate = tempBasalRate,
                tempBasalMinutes = tempBasalMinutes,
            )
            check(stateManager.activeCommand?.sequence == command.sequenceNumber.toInt()) {
                "The active command must take the sequence the command is built with"
            }
            stateManager.markActiveCommandSent()
        }
        // Only here, never for a status read: a status that could not be sent must not close an
        // open delivery command as "not sent".
        if (!sent.reachedRadio) stateManager.markActiveCommandNotSent()
        val (result, command) = stateManager.resolveActiveCommand()
        handleCommandConfirmation(result, command, decidedLate = false)
        return DeliveryOutcome(result, sent.error)
    }

    /**
     * True when the next command would be built with the sequence the last status reports for the
     * last programming command the pod took. False when no status was read yet.
     */
    private fun sequenceSameAsLastProgramming(): Boolean =
        stateManager.podStatus?.lastProgrammingCommandSequence == stateManager.messageSequenceNumber

    /**
     * Gives a final outcome to [onCommandDecided], as Android `handleCommandConfirmation`. A SUCCESS
     * bolus also sets [lastBolusAmount] and [lastBolusTime]. Nothing for an open command.
     */
    private fun handleCommandConfirmation(confirmation: DashCommandConfirmation, command: DashActiveCommand?, decidedLate: Boolean) {
        if (command == null || !confirmation.isFinal) return
        val at = now()
        if (confirmation == DashCommandConfirmation.SUCCESS && command.kind == DashCommandKind.BOLUS) {
            command.requestedBolusUnits?.let {
                lastBolusAmountFlow.value = PumpInsulin(it)
                lastBolusTimeFlow.value = at
            }
        }
        if (decidedLate) aapsLogger.warn(LTag.PUMPBTCOMM, "Command decided late: $confirmation for $command")
        onCommandDecided(DashCommandOutcome(confirmation, command, decidedLate, at))
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
            is DefaultStatusResponse -> stateManager.updateFromStatus(DashPodStatus.of(response, now()))
            is AlarmStatusResponse   -> stateManager.updateFromStatus(DashPodStatus.of(response, now()))
            is SetUniqueIdResponse   -> stateManager.updateFromSetUniqueIdResponse(response)
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

    override fun isThisProfileSet(profile: PumpProfile): Boolean = false

    override suspend fun loadTDDs(): PumpEnactResult = failure("TDD not yet wired")

    override fun canHandleDST(): Boolean = false

    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) = Unit
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = null

    companion object {

        /** `OmnipodDashManagerImpl.NONCE`: the Omnipod Dash seems to use a fixed nonce. */
        const val NONCE = 1229869870

        /** Creates a fully-wired driver: iOS crypto seams + Keychain store. */
        fun create(messageIO: MessageIO, aapsLogger: AAPSLogger, onCommandDecided: (DashCommandOutcome) -> Unit): IosDashPump =
            IosDashPump(messageIO = messageIO, aapsLogger = aapsLogger, onCommandDecided = onCommandDecided)

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
