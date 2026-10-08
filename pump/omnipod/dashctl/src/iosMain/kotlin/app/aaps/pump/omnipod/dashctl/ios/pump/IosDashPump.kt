package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.CustomAction
import app.aaps.core.interfaces.queue.CustomActionType
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
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.crypto.DashEnDecrypt
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.session.DashCommandSession
import app.aaps.pump.omnipod.dashctl.session.DashMessageTransport
import app.aaps.pump.omnipod.dashctl.session.DashPairing
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashSendResult
import app.aaps.pump.omnipod.dashctl.session.DashSessionEstablisher
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
    override fun model(): PumpType = PumpType.OmnipodDash
    override fun serialNumber(): String =
        stateManager.currentState().uniqueId?.toString() ?: ""

    // -- State --------------------------------------------------------------

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
                val s = requireSession()
                busy = true
                val (uid, seq, nonce) = commandHeaders()
                val command = ProgramBolusCommand.Builder()
                    .setNumberOfUnits(detailedBolusInfo.insulin)
                    .setDelayBetweenPulsesInEighthSeconds(2)
                    .setProgramReminder(ProgramReminder(false, false, 0))
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(nonce)
                    .build()
                val payload = command.encode()
                when (val result = s.sendCommand(header(payload.size), payload)) {
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = true,
                        enacted = true,
                        comment = "Bolus ${detailedBolusInfo.insulin} U sent",
                        bolusDelivered = detailedBolusInfo.insulin
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "Bolus failed: ${result.msg}"
                    )
                }
            } catch (e: DashPumpNotPairedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
            } finally {
                busy = false
            }
        }

    override fun stopBolusDelivering() = runBlocking {
        commandMutex.withLock {
            try {
                val s = requireSession()
                val (uid, seq, nonce) = commandHeaders()
                val command = StopDeliveryCommand.Builder()
                    .setDeliveryType(DeliveryType.BOLUS)
                    .setBeepType(BeepType.NO_BEEP)
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(nonce)
                    .build()
                val payload = command.encode()
                s.sendCommand(header(payload.size), payload)
            } catch (e: DashPumpNotPairedException) {
                // Fail closed: nothing sent.
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
            val s = requireSession()
            busy = true
            val (uid, seq, nonce) = commandHeaders()
            val command = ProgramTempBasalCommand.Builder()
                .setProgramReminder(ProgramReminder(false, false, 0))
                .setRateInUnitsPerHour(absoluteRate)
                .setDurationInMinutes(durationInMinutes.toShort())
                .setUniqueId(uid)
                .setSequenceNumber(seq)
                .setNonce(nonce)
                .build()
            val payload = command.encode()
                when (val result = s.sendCommand(header(payload.size), payload)) {
                is DashSendResult.Success -> DashPumpEnactResult(
                    success = true, enacted = true,
                    comment = "TBR $absoluteRate U/h for $durationInMinutes min",
                    duration = durationInMinutes, absolute = absoluteRate
                )
                is DashSendResult.Error -> DashPumpEnactResult(
                    success = false, enacted = false,
                    comment = "TBR failed: ${result.msg}"
                )
            }
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
                val s = requireSession()
                busy = true
                val (uid, seq, nonce) = commandHeaders()
                val command = StopDeliveryCommand.Builder()
                    .setDeliveryType(DeliveryType.TEMP_BASAL)
                    .setBeepType(BeepType.NO_BEEP)
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(nonce)
                    .build()
                val payload = command.encode()
                when (val result = s.sendCommand(header(payload.size), payload)) {
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = true, enacted = true,
                        comment = "TBR cancelled", isTempCancel = true
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false,
                        comment = "Cancel TBR failed: ${result.msg}"
                    )
                }
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
                val s = requireSession()
                val (uid, seq, nonce) = commandHeaders()
                val command = StopDeliveryCommand.Builder()
                    .setDeliveryType(DeliveryType.ALL)
                    .setBeepType(BeepType.NO_BEEP)
                    .setUniqueId(uid)
                    .setSequenceNumber(seq)
                    .setNonce(nonce)
                    .build()
                val payload = command.encode()
                when (val result = s.sendCommand(header(payload.size), payload)) {
                    is DashSendResult.Success -> DashPumpEnactResult(
                        success = true, enacted = true, comment = "All delivery stopped"
                    )
                    is DashSendResult.Error -> DashPumpEnactResult(
                        success = false, enacted = false, comment = "Stop failed: ${result.msg}"
                    )
                }
            } catch (e: DashPumpNotPairedException) {
                DashPumpEnactResult(success = false, enacted = false, comment = e.message ?: "Not paired")
            }
        }
    }

    /** Suspend: halts delivery until resumed. Guarded like everything else. */
    fun suspendDelivery(): PumpEnactResult = stopDelivery()

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
    override var queued: Boolean = false
) : PumpEnactResult {

    override fun success(success: Boolean): PumpEnactResult = apply { this.success = success }
    override fun enacted(enacted: Boolean): PumpEnactResult = apply { this.enacted = enacted }
    override fun comment(comment: String): PumpEnactResult = apply { this.comment = comment }
    override fun comment(ref: TextRef): PumpEnactResult = apply { /* TextRef resolution is UI work */ }
}
