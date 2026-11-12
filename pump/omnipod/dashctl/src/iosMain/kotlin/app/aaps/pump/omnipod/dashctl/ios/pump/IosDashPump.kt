package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.actions.CustomAction
import app.aaps.core.interfaces.pump.actions.CustomActionType
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosSecureRandomBytes
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.session.DashCommandNotConfirmedException
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandSession
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import app.aaps.pump.omnipod.dashctl.session.DashPairing
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashSendResult
import app.aaps.pump.omnipod.dashctl.session.DashSessionEstablisher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thrown when a command is attempted without a paired pod and established session.
 * This is the explicit safety guard: no bytes go to the radio unless pairing
 * completed AND the session keys are in place.
 */
class DashPumpNotPairedException(message: String) : Exception(message)

/**
 * Omnipod Dash [Pump] for iOS.
 *
 * Lifecycle (D3):
 * 1. [pair] — [DashPairing] negotiates the LTK (X25519 + SPS1/SPS2), stored in
 *    the Keychain via [DashKeychainStateStore].
 * 2. [establishSession] — [DashSessionEstablisher] runs EAP-AKA (Milenage),
 *    derives the CK, builds [DashEnDecrypt] and the [DashCommandSession].
 * 3. Commands — [deliverTreatment], [setTempBasalAbsolute], … via the session.
 *
 * All crypto goes through the iOS D2 seams ([IosAesCcmCipher], [IosAesEcbBlockCipher],
 * [IosX25519Dh], [IosSecureRandomBytes]).
 *
 * **Safety guard**: every method that sends bytes to the pod calls [requireSession]
 * first. Without a paired pod (LTK) AND an established session, the call fails
 * closed — no command is built, no bytes are sent.
 *
 * Threading: commands are serialized by [commandMutex]; BLE access is already
 * serialized by `IosBleTransport` on the CoreBluetooth queue.
 */
class IosDashPump(
    private val transport: DashMessageTransport,
    private val stateManager: DashPodStateManager = DashPodStateManager(DashKeychainStateStore()),
    private val keyExchange: DashKeyExchange = DashKeyExchange(
        x25519 = IosX25519Dh(),
        aesCmac = IosAesCmac(),
        randomBytes = IosSecureRandomBytes()
    )
) : Pump {

    private var session: DashCommandSession? = null
    private val commandMutex = Mutex()
    private var connected = false
    private var busy = false

    // -- Safety guard -------------------------------------------------------

    /**
     * Fails closed unless the pod is paired (LTK present), the session is
     * established, and we are connected. Every command-sending method must
     * call this first.
     */
    private fun requireSession(): DashCommandSession {
        val ltk = stateManager.ltk
        if (ltk == null) {
            throw DashPumpNotPairedException("Refusing command: pod is not paired (no LTK)")
        }
        val s = session
        if (s == null) {
            throw DashPumpNotPairedException("Refusing command: no session established")
        }
        if (!connected) {
            throw DashPumpNotPairedException("Refusing command: not connected to the pod")
        }
        return s
    }

    fun isPaired(): Boolean = stateManager.ltk != null

    // -- Pairing & session ----------------------------------------------------

    /**
     * Pairs with the pod at [podAddress] (4 bytes). Stores the LTK in the Keychain.
     * Must be called before [establishSession].
     */
    fun pair(podAddress: ByteArray) {
        val result = DashPairing(
            transport = transport,
            keyExchange = keyExchange,
            podAddress = podAddress
        ).negotiateLtk()
        stateManager.updateFromPairing(uniqueId = 0L, ltk = result.ltk)
    }

    /**
     * Establishes the encrypted command session (EAP-AKA). Must be called after
     * [pair] (or on a stored LTK). Builds the [DashCommandSession].
     */
    fun establishSession() {
        val keys = DashSessionEstablisher(
            transport = transport,
            stateManager = stateManager,
            aesEcb = IosAesEcbBlockCipher(),
            randomBytes = IosSecureRandomBytes()
        ).negotiateSessionKeys()
        val enDecrypt = DashEnDecrypt(
            aesCcm = IosAesCcmCipher(),
            nonce = keys.nonce,
            ck = keys.ck
        )
        session = DashCommandSession(
            transport = transport,
            stateManager = stateManager,
            enDecrypt = enDecrypt
        )
    }

    /** Tears down the session; the LTK stays in the Keychain. */
    fun endSession() {
        session = null
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

    // Not tracked yet on iOS: these are placeholders, the same as in the iOS Medtrum driver.
    override val lastDataTime: StateFlow<Long> = MutableStateFlow(0L)
    override val lastBolusTime: StateFlow<Long?> = MutableStateFlow(null)
    override val lastBolusAmount: StateFlow<PumpInsulin?> = MutableStateFlow(null)
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

    override fun disconnect(reason: String) {
        connected = false
        endSession()
    }

    override fun stopConnecting() = Unit

    // -- Commands -----------------------------------------------------------

    override suspend fun deliverTreatment(detailedBolusInfo: app.aaps.core.interfaces.pump.DetailedBolusInfo): PumpEnactResult =
        commandMutex.withLock {
            try {
                busy = true
                val result = sendDeliveryCommand(DashCommandKind.BOLUS, requestedBolusUnits = detailedBolusInfo.insulin) { uid, seq, nonce ->
                    ProgramBolusCommand.Builder()
                        .setNumberOfUnits(detailedBolusInfo.insulin)
                        .setDelayBetweenPulsesInEighthSeconds(2)
                        .setProgramReminder(ProgramReminder(false, false, 0))
                        .setUniqueId(uid)
                        .setSequenceNumber(seq)
                        .setNonce(nonce)
                        .build()
                        .encoded
                }
                when (result) {
                    // Written is not delivered: no insulin is reported until a pod status confirms it.
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "Bolus ${detailedBolusInfo.insulin} U sent, not confirmed by the pod yet"
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "Bolus failed: ${result.msg}"
                    )
                }
            } catch (e: DashCommandNotConfirmedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }

    override fun stopBolusDelivering() {
        runBlocking {
        commandMutex.withLock {
            try {
                sendDeliveryCommand(DashCommandKind.STOP_BOLUS) { uid, seq, nonce ->
                    stopCommand(DeliveryType.BOLUS, uid, seq, nonce)
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
        try {
            busy = true
            val result = sendDeliveryCommand(
                DashCommandKind.TEMP_BASAL, tempBasalRate = absoluteRate, tempBasalMinutes = durationInMinutes
            ) { uid, seq, nonce ->
                ProgramTempBasalCommand.Builder()
                    .setProgramReminder(ProgramReminder(false, false, 0))
                    .setRateInUnitsPerHour(absoluteRate)
                    .setDurationInMinutes(durationInMinutes.toShort())
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(nonce)
                    .build()
                    .encoded
            }
            when (result) {
                is DashSendResult.Success -> DashPumpEnactResult(
                    success = false, enacted = false,
                    comment = "TBR $absoluteRate U/h for $durationInMinutes min sent, not confirmed by the pod yet"
                )
                is DashSendResult.Error -> DashPumpEnactResult(
                    success = false, enacted = false,
                    comment = "TBR failed: ${result.msg}"
                )
            }
        } catch (e: DashCommandNotConfirmedException) {
            DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Last command not confirmed")
        } catch (e: DashPumpNotPairedException) {
            DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
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
        DashPumpEnactResult(success = false, enacted = false, comment = "Percent TBR not supported on Dash")

    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult =
        commandMutex.withLock {
            try {
                busy = true
                val result = sendDeliveryCommand(DashCommandKind.STOP_TEMP_BASAL) { uid, seq, nonce ->
                    stopCommand(DeliveryType.TEMP_BASAL, uid, seq, nonce)
                }
                when (result) {
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "TBR cancel sent, not confirmed by the pod yet"
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "Cancel TBR failed: ${result.msg}"
                    )
                }
            } catch (e: DashCommandNotConfirmedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }

    override suspend fun setNewBasalProfile(profile: app.aaps.core.interfaces.pump.PumpProfile): PumpEnactResult =
        // Basal profile programming is a D4-UI flow (verified pairing + user confirm).
        DashPumpEnactResult(success = false, enacted = false, comment = "Basal profile programming not yet wired")

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "Extended bolus not supported on Dash")

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "Extended bolus not supported on Dash")

    /**
     * Emergency stop: halts all delivery. Still guarded — a stop with no
     * session cannot reach the pod, so it fails closed like everything else.
     */
    fun stopDelivery(): PumpEnactResult = runBlocking {
        commandMutex.withLock {
            try {
                val result = sendDeliveryCommand(DashCommandKind.STOP_ALL) { uid, seq, nonce ->
                    stopCommand(DeliveryType.ALL, uid, seq, nonce)
                }
                when (result) {
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = false, enacted = false, comment = "Stop sent, not confirmed by the pod yet"
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false, comment = "Stop failed: ${result.msg}"
                    )
                }
            } catch (e: DashCommandNotConfirmedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Last command not confirmed")
            } catch (e: DashPumpNotPairedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
            }
        }
    }

    /** Suspend: halts delivery until resumed. Guarded like everything else. */
    fun suspendDelivery(): PumpEnactResult = stopDelivery()

    // -- Unconfirmed commands -------------------------------------------------

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
     * The command is opened before its bytes are built and marked sent before the write, so a crash
     * at any point leaves a command that needs a status, never one that may be sent again.
     *
     * The answer of the pod is not read here yet (plan, phase 2): the command stays open after a
     * successful write, and every later delivery command is refused until a status closes it.
     */
    private fun sendDeliveryCommand(
        kind: DashCommandKind,
        requestedBolusUnits: Double? = null,
        tempBasalRate: Double? = null,
        tempBasalMinutes: Int? = null,
        build: (uniqueId: Int, sequence: Short, nonce: Int) -> ByteArray,
    ): DashSendResult {
        val s = requireSession()
        val (confirmation, _) = stateManager.resolveActiveCommand()
        if (confirmation == DashCommandConfirmation.NOT_CONFIRMED) {
            throw DashCommandNotConfirmedException("Refusing command: the last command is not confirmed by the pod. Read the pod status first.")
        }
        val (uid, seq, nonce) = commandHeaders()
        stateManager.createActiveCommand(
            kind = kind,
            // No pod history on iOS yet (plan, phase 2).
            historyId = 0L,
            requestedBolusUnits = requestedBolusUnits,
            tempBasalRate = tempBasalRate,
            tempBasalMinutes = tempBasalMinutes,
        )
        val payload = build(uid, seq, nonce)
        stateManager.markActiveCommandSent()
        val result = s.sendCommand(header(payload.size), payload)
        if (result is DashSendResult.Error) stateManager.markActiveCommandNotSent()
        return result
    }

    private fun stopCommand(type: DeliveryType, uid: Int, seq: Short, nonce: Int): ByteArray =
        StopDeliveryCommand.Builder()
            .setDeliveryType(type)
            .setBeepType(BeepType.SILENT)
            .setUniqueId(uid)
            .setSequenceNumber(seq)
            .setNonce(nonce)
            .build()
            .encoded

    // -- Plumbing -----------------------------------------------------------

    /**
     * 16-byte message header used as CCM associated data (D3).
     *
     * Format mirrors the original Android `MessagePacket.asByteArray(forEncryption=true)`:
     * - bytes 0-1: "TW" magic
     * - byte 2: f1 flags (version=0, sas=true, tfs=false, eqos=0) = 0x10
     * - byte 3: f2 flags (type=ENCRYPTED=1) = 0x01
     * - byte 4: message sequence number (pre-increment; D3 increments on send)
     * - byte 5: ackNumber = 0
     * - bytes 6-7: payload size encoded as (size ushr 3), (size shl 5)
     * - bytes 8-11: source = controller ID (4242, big-endian)
     * - bytes 12-15: destination = pod uniqueId (big-endian)
     */
    private fun header(payloadSize: Int): ByteArray {
        val uniqueId = stateManager.uniqueId
            ?: throw DashPumpNotPairedException("Refusing command: no uniqueId (pod not activated)")
        val out = ByteArray(16)
        out[0] = 0x54 // 'T'
        out[1] = 0x57 // 'W'
        out[2] = 0x10 // f1: sas=true
        out[3] = 0x01 // f2: type=ENCRYPTED
        out[4] = stateManager.messageSequenceNumber.toByte()
        out[5] = 0 // ackNumber
        out[6] = (payloadSize ushr 3).toByte()
        out[7] = (payloadSize shl 5).toByte()
        // source: controller ID 4242 (big-endian), as in the original Android code
        out[8] = 0
        out[9] = 0
        out[10] = 0x10
        out[11] = 0x92.toByte()
        // destination: pod uniqueId (big-endian)
        val uid = uniqueId.toInt()
        out[12] = (uid ushr 24).toByte()
        out[13] = (uid ushr 16).toByte()
        out[14] = (uid ushr 8).toByte()
        out[15] = uid.toByte()
        return out
    }

    /**
     * Required header fields for every command: uniqueId from the paired pod,
     * the current 4-bit message sequence number, and a fresh random nonce.
     */
    private fun commandHeaders(): Triple<Int, Short, Int> {
        val uniqueId = stateManager.uniqueId
            ?: throw DashPumpNotPairedException("Refusing command: no uniqueId (pod not activated)")
        val nonceBytes = IosSecureRandomBytes().nextBytes(4)
        var nonce = 0
        for (b in nonceBytes) nonce = (nonce shl 8) or (b.toInt() and 0xFF)
        return Triple(uniqueId.toInt(), stateManager.messageSequenceNumber.toShort(), nonce)
    }

    override suspend fun getPumpStatus(reason: String) = Unit

    override fun isThisProfileSet(profile: app.aaps.core.interfaces.pump.PumpProfile): Boolean = false

    override suspend fun loadTDDs(): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "TDD not yet wired")

    override fun canHandleDST(): Boolean = false

    override fun getCustomActions(): List<CustomAction>? = null
    override fun executeCustomAction(customActionType: CustomActionType) = Unit
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult? = null

    companion object {

        /** Creates a fully-wired driver: iOS crypto seams + Keychain store. */
        fun create(transport: DashMessageTransport): IosDashPump {
            val stateManager = DashPodStateManager(DashKeychainStateStore())
            return IosDashPump(transport = transport, stateManager = stateManager)
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
