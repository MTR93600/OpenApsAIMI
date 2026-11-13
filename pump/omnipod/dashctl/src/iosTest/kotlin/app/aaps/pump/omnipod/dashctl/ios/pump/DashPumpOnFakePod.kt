package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.pump.omnipod.dashctl.comm.io.DashBleLink
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupResult
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.session.CountingRandomBytes
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.PodKeys
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore

/** Keeps the pod state in memory, in place of the Keychain. */
internal class MemoryDashPodStateStore : DashPodStateStore {

    var saved: DashPodState? = null
    override fun load(): DashPodState? = saved
    override fun save(state: DashPodState) {
        saved = state
    }

    override fun clear() {
        saved = null
    }
}

/**
 * The real [IosDashPump] on the real `MessageIO` and `DashBleLink`, against [FakeDashPod] at the
 * level of BLE events. The crypto is the iOS one.
 *
 * The phone key is the RFC 7748 key used by the parity tests, the pod computes its own keys
 * ([PodKeys.Generated]), and the random values are 1, 2, 3, ... so every run sends the same bytes.
 *
 * By default the pod answers every command with a default status that reports the last
 * programming command it took, as a real pod does ([answerWithStatus]).
 */
internal class DashPumpOnFakePod(
    val store: MemoryDashPodStateStore = MemoryDashPodStateStore(),
    val stateManager: DashPodStateManager = DashPodStateManager(store),
    val pod: FakeDashPod = FakeDashPod(PodKeys.Generated()),
) {

    var pump: IosDashPump = newPump()
        private set

    /** The pod log size once the link is prepared: anything after it was sent by the pump. */
    private var setupLogSize = pod.central.log.size

    /**
     * The sequence of the last programming command the pod took, as it reports it. 0x0f before
     * any, so it never matches a first command built with sequence 0.
     */
    var lastProgrammingSequence = 0x0f

    /** Bolus pulses the pod reports as not delivered yet. */
    var bolusPulsesRemaining = 0

    /** Every final outcome the pump gave to `onCommandDecided`, on every link, in order. */
    val decided = mutableListOf<DashCommandOutcome>()

    init {
        answerWithStatus()
    }

    /** The pod answers each command with a default status (Android `DefaultStatusResponse`). */
    fun answerWithStatus() {
        pod.answerFor = { command ->
            val sequence = (command[4].toInt() and 0xff shr 2) and 0x0f
            if (command[6] != CommandType.GET_STATUS.value) lastProgrammingSequence = sequence
            defaultStatus(lastProgrammingSequence, bolusPulsesRemaining)
        }
    }

    /** What the pump wrote to the radio after the link was prepared. */
    fun writesFromPump(): List<String> = pod.central.log.drop(setupLogSize).filter { it.startsWith("phone") }

    /** The link was lost and the phone connects again: a new link, and a new pump on the same state. */
    fun reconnect() {
        pod.reconnect()
        pod.fromPhone.clear()
        answerWithStatus()
        pump = newPump()
        setupLogSize = pod.central.log.size
    }

    /**
     * Fresh pairing with [podAddress] (null: the default `pair()`, the Android id), the SetUniqueId
     * answer of the Android test (pulse rate 16, unique id 0x02420001), then a session, then
     * connected. The activation is not ported yet, so its SetUniqueId answer is given to the state
     * directly.
     *
     * [pulseRateBeforePairing]: the SetUniqueId answer is given before pairing, so pairing writes
     * the unique id last and the session and the commands use the id pairing stored. For tests that
     * check what pairing stores; the SetUniqueId vector carries 0x02420001 and would hide it.
     */
    fun pairAndConnect(
        podAddress: ByteArray? = POD_ADDRESS,
        withPulseRate: Boolean = true,
        pulseRateBeforePairing: Boolean = false,
    ) {
        val setUniqueId = SetUniqueIdResponse(parityHex(SET_UNIQUE_ID_RESPONSE))
        if (withPulseRate && pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId)
        if (podAddress == null) pump.pair() else pump.pair(podAddress)
        if (withPulseRate && !pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId)
        pump.establishSession()
        pump.connect("test")
    }

    private fun newPump(): IosDashPump {
        val link = DashBleLink(pod.central, FakeDashPodCentral.POD_ID, SilentPodLogger)
        pod.central.listener = link::onEvent
        check(link.prepare() == DashLinkSetupResult.Ready) { "link not ready" }
        return IosDashPump(
            messageIO = MessageIO(SilentPodLogger, link.cmd, link.data),
            aapsLogger = SilentPodLogger,
            onCommandDecided = { decided += it },
            stateManager = stateManager,
            newKeyExchange = {
                DashKeyExchange(IosX25519Dh(), IosAesCmac(), pdmNonce = parityHex(PDM_NONCE), pdmPrivate = parityHex(PDM_PRIVATE))
            },
            randomBytes = CountingRandomBytes(),
            now = { 1_000L },
        )
    }

    companion object {

        val POD_ADDRESS = parityHex("02420001")

        const val PDM_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        const val PDM_NONCE = "000102030405060708090a0b0c0d0e0f"

        /** Android `SetUniqueIdResponseTest`: pulse rate 16, unique id 37879809 (0x02420001). */
        const val SET_UNIQUE_ID_RESPONSE = "011B13881008340A50040A00010300040308146CC1000954D402420001"

        /**
         * A default status (the layout of `DefaultStatusResponse`): the Android capture
         * `1d050016b00000000bff` with the last programming sequence and the bolus pulses left set.
         */
        fun defaultStatus(lastProgrammingSequence: Int, bolusPulsesRemaining: Int = 0): ByteArray {
            val totalPulses = 0x2d
            val first = (totalPulses shl 15) or ((lastProgrammingSequence and 0x0f) shl 11) or (bolusPulsesRemaining and 0x7ff)
            return byteArrayOf(0x1d, 0x05) +
                ByteArray(4) { (first shr (8 * (3 - it))).toByte() } +
                parityHex("00000bff")
        }
    }
}
