package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnection
import app.aaps.pump.omnipod.dashctl.comm.connection.PodCentralSwitch
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.session.CountingRandomBytes
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.PodKeys
import app.aaps.pump.omnipod.dashctl.comm.session.ResponseUtil
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import app.aaps.pump.omnipod.dashctl.driver.RunningPodModel
import app.aaps.pump.omnipod.dashctl.driver.SimulatedCrash
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore

/** Keeps the pod state in memory, in place of the Keychain. */
internal class MemoryDashPodStateStore : DashPodStateStore {

    var saved: DashPodState? = null

    /** Every state written, in order: what a crash between two writes would leave in the store. */
    val history = mutableListOf<DashPodState>()

    /** When it gives true for a state just written, the app stops there ([SimulatedCrash]). */
    var crashWhen: ((DashPodState) -> Boolean)? = null
    override fun load(): DashPodState? = saved
    override fun save(state: DashPodState) {
        saved = state
        history += state
        if (crashWhen?.invoke(state) == true) {
            crashWhen = null
            throw SimulatedCrash()
        }
    }

    override fun clear() {
        saved = null
    }
}

/**
 * The real [IosDashPump] on the real [DashPodConnection], `MessageIO` and `DashBleLink`, against
 * [FakeDashPod] at the level of BLE events. The crypto is the iOS one.
 *
 * One pump and one connection for the whole test, as on a phone. [reconnect] drops the BLE link and
 * connects again: the connection makes a new link and gives it to the same pump.
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
    /** The bolus progress the pump updates; null when a test does not look at it. */
    bolusProgressData: BolusProgressData? = null,
    /** The beep settings the pump reads; all off by default, as the pump. */
    beeps: DashBeepSettings = DashBeepSettings(),
) {

    /** Every final outcome the pump gave to `onCommandDecided`, on every link, in order. */
    val decided = mutableListOf<DashCommandOutcome>()

    /** Runs inside `onCommandDecided`, so inside the command that decided it. For tests of nested calls. */
    var onDecided: (DashCommandOutcome) -> Unit = {}

    /** Every bolus a pod fault ended, as the pump gave it to `onCommandDecided`, in order. */
    val endedByFault = mutableListOf<DashBolusEndedByFault>()

    /** Every bolus the pod reported that the pump did not track, as the pump gave it to `onCommandDecided`. */
    val untracked = mutableListOf<DashUntrackedBolus>()

    /** Every outcome the pump gave to `onCommandDecided`, of every kind, in order. */
    val outcomes = mutableListOf<DashPumpOutcome>()

    /**
     * Runs inside `onCommandDecided` for every outcome of both kinds, after it is kept above. The
     * plugin tests give the `PumpSync` recorder here, as `IosDashDriverHost` does.
     */
    var onOutcome: (DashPumpOutcome) -> Unit = {}

    /** Runs inside `onCommandSent`: the plugin tests give the `PumpSync` recorder here, as `IosDashDriverHost` does. */
    var onSent: (DashActiveCommand) -> Unit = {}

    /** A pump that expects AAPS to run a temp basal or not (Android `PumpSync`), read by `cancelTempBasal`. */
    var expectedTempBasalRunning = true

    /** The wall clock the pump reads (epoch ms). Tests move it to tell the send time from the decision time. */
    var nowMs = 1_000L

    val pump: IosDashPump = IosDashPump(
        aapsLogger = SilentPodLogger,
        onCommandDecided = {
            outcomes += it
            when (it) {
                is DashCommandOutcome    -> {
                    decided += it
                    onDecided(it)
                }

                is DashBolusEndedByFault -> endedByFault += it
                is DashUntrackedBolus    -> untracked += it
            }
            onOutcome(it)
        },
        onCommandSent = { onSent(it) },
        stateManager = stateManager,
        newKeyExchange = {
            DashKeyExchange(IosX25519Dh(), IosAesCmac(), pdmNonce = parityHex(PDM_NONCE), pdmPrivate = parityHex(PDM_PRIVATE))
        },
        randomBytes = CountingRandomBytes(),
        now = { nowMs },
        bolusProgressData = bolusProgressData,
        beeps = { beeps },
        expectedTempBasalRunning = { expectedTempBasalRunning },
    )

    /** The one central of the phone, in front of the fake central of the current link. */
    val central = PodCentralSwitch(pod)

    val connection = DashPodConnection(central, FakeDashPodCentral.POD_ID, SilentPodLogger, pump)

    /** The pod log size once the link is prepared: anything after it was sent by the pump. */
    private var setupLogSize = 0

    /** What the last [DashPodConnection.connect] returned. */
    var lastConnect: DashConnectResult = connectLink()
        private set

    /** The pod side of the running pod: what it took and what it reports ([RunningPodModel]). */
    val running = RunningPodModel()

    /** See [RunningPodModel.lastProgrammingSequence]. */
    var lastProgrammingSequence by running::lastProgrammingSequence

    /** See [RunningPodModel.bolusPulsesRemaining]. */
    var bolusPulsesRemaining by running::bolusPulsesRemaining

    /** See [RunningPodModel.bolusRunning]. */
    var bolusRunning by running::bolusRunning

    /** See [RunningPodModel.bolusPulsesOnNextBolus]. */
    var bolusPulsesOnNextBolus by running::bolusPulsesOnNextBolus

    /** See [RunningPodModel.bolusStops]. */
    val bolusStops: MutableList<Int> get() = running.bolusStops

    /** See [RunningPodModel.tempBasalRunning]. */
    var tempBasalRunning by running::tempBasalRunning

    /** See [RunningPodModel.basalRunning]. */
    var basalRunning by running::basalRunning

    /** See [RunningPodModel.podStatusCode]. */
    var podStatusCode by running::podStatusCode

    /** See [RunningPodModel.activeAlerts]. */
    var activeAlerts by running::activeAlerts

    /** See [RunningPodModel.faultAlarmType]. */
    var faultAlarmType by running::faultAlarmType

    /** See [RunningPodModel.faultAlarmMinutes]. */
    var faultAlarmMinutes by running::faultAlarmMinutes

    /** See [RunningPodModel.reservoirPulses]. */
    var reservoirPulses by running::reservoirPulses

    init {
        answerWithStatus()
    }

    /** The pod answers as a new pod being activated ([ActivatingPodModel]). */
    fun answerAsActivatingPod(model: ActivatingPodModel) {
        pod.answerFor = model::answer
    }

    /** The pod answers each command with a default status (Android `DefaultStatusResponse`), see [RunningPodModel]. */
    fun answerWithStatus() {
        pod.answerFor = running::answer
    }

    /** How many bolus commands the pod got (taken or not). */
    fun bolusCommands(): Int = pod.commands.count { isBolus(it) }

    /** What the pump wrote to the radio after the link was prepared. */
    fun writesFromPump(): List<String> = pod.central.log.drop(setupLogSize).filter { it.startsWith("phone") }

    /** The pod goes out of range: the BLE link drops. The pump and its state stay. */
    fun dropLink() {
        pod.central.disconnect()
    }

    /**
     * The link was lost and the phone connects again: [DashPodConnection.connect] makes a new link
     * for the same pump and, when the pod is paired, sets up the session from outer counter 1.
     * [FakeDashPod.fromPhone] is cleared first, so it holds only the messages of the new link.
     */
    fun reconnect(): DashConnectResult {
        dropLink()
        pod.reconnect()
        pod.fromPhone.clear()
        answerWithStatus()
        lastConnect = connectLink()
        return lastConnect
    }

    /**
     * Fresh pairing with [podAddress] (null: the default `pair()`, the Android id), the SetUniqueId
     * answer of the Android test (pulse rate 16, unique id 0x02420001), then a session, then
     * connected. The SetUniqueId answer is given to the state directly, without the activation.
     *
     * [activated]: the state is then the one of a pod whose activation is completed and that runs
     * (COMPLETED, pod status RUNNING_ABOVE_MIN_VOLUME), so delivery commands may go
     * (`observePodRunning`). The activation itself is tested in `IosDashPumpActivationTest`.
     *
     * [pulseRateBeforePairing]: the SetUniqueId answer is given before pairing, so pairing writes
     * the unique id last and the session and the commands use the id pairing stored. For tests that
     * check what pairing stores; the SetUniqueId vector carries 0x02420001 and would hide it.
     *
     * [basalOfThisSession] (only with [activated]): the pod runs its basal ([basalRunning]) and the
     * state holds the status it reports now, as read in this session, without any message (as after
     * an activation done in this session). So a bolus or a temp basal goes without the status read
     * that the pump does first on a missing or older status (`IosDashPump.refusedByDeliveryStatus`).
     * False: no status is known, and the pod reports the delivery status of the Android capture
     * (SUSPENDED), for the tests of what happens with no status.
     */
    fun pairAndConnect(
        podAddress: ByteArray? = POD_ADDRESS,
        withPulseRate: Boolean = true,
        pulseRateBeforePairing: Boolean = false,
        activated: Boolean = true,
        basalOfThisSession: Boolean = true,
    ) {
        val setUniqueId = SetUniqueIdResponse(parityHex(SET_UNIQUE_ID_RESPONSE))
        if (withPulseRate && pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId, 0L)
        if (podAddress == null) pump.pair() else pump.pair(podAddress)
        if (withPulseRate && !pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId, 0L)
        pump.establishSession()
        if (activated) markActivated()
        if (activated && basalOfThisSession) giveStatusOfThisSession(basal = true)
    }

    /**
     * The pod runs its basal when [basal], and the state takes the status the pod reports now
     * ([RunningPodModel.currentStatus]) as if it was read in this session. No message is sent.
     */
    fun giveStatusOfThisSession(basal: Boolean = running.basalRunning) {
        running.basalRunning = basal
        stateManager.updateFromResponse(ResponseUtil.parseResponse(running.currentStatus()), nowMs)
    }

    /** The state of a pod whose activation is completed and that runs, without running the activation. */
    fun markActivated() {
        stateManager.overwriteForTest {
            it.copy(activationProgress = ActivationProgress.COMPLETED, reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME)
        }
    }

    /** A new link on the current fake central; the pod log is marked so [writesFromPump] starts after the set up. */
    private fun connectLink(): DashConnectResult {
        pod.central.listener = connection::onEvent
        val before = pod.central.log.size
        val result = connection.connect()
        // The link set up ends with the DATA indications. What comes after it (the session on a
        // paired pod) was sent by the pump; the set up itself was not.
        val setupEnd = pod.central.log.drop(before).indexOfFirst { it.startsWith("phone enableNotify DATA") }
        setupLogSize = if (setupEnd < 0) pod.central.log.size else before + setupEnd + 1
        return result
    }

    companion object {

        val POD_ADDRESS = parityHex("02420001")

        const val PDM_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        const val PDM_NONCE = "000102030405060708090a0b0c0d0e0f"

        /** Android `SetUniqueIdResponseTest`: pulse rate 16, unique id 37879809 (0x02420001). */
        const val SET_UNIQUE_ID_RESPONSE = ActivatingPodModel.SET_UNIQUE_ID_RESPONSE

        // The pod constants and command checks live in RunningPodModel; the names stay here for the tests.
        const val CAPTURE_DELIVERY = RunningPodModel.CAPTURE_DELIVERY
        const val DEACTIVATED = RunningPodModel.DEACTIVATED
        const val ALARM = RunningPodModel.ALARM
        const val BASAL_ACTIVE = RunningPodModel.BASAL_ACTIVE
        const val TEMP_BASAL_ACTIVE = RunningPodModel.TEMP_BASAL_ACTIVE
        const val BOLUS_AND_BASAL_ACTIVE = RunningPodModel.BOLUS_AND_BASAL_ACTIVE
        const val BOLUS_AND_TEMP_BASAL_ACTIVE = RunningPodModel.BOLUS_AND_TEMP_BASAL_ACTIVE
        const val BASAL_BIT = RunningPodModel.BASAL_BIT
        const val TEMP_BASAL_BIT = RunningPodModel.TEMP_BASAL_BIT
        const val BOLUS_BIT = RunningPodModel.BOLUS_BIT
        const val OCCLUDED = RunningPodModel.OCCLUDED

        fun podAlarmStatus(alarmType: Int, lastProgrammingSequence: Int, bolusPulsesRemaining: Int): ByteArray =
            RunningPodModel.podAlarmStatus(alarmType, lastProgrammingSequence, bolusPulsesRemaining)

        fun isBolus(command: ByteArray): Boolean = RunningPodModel.isBolus(command)
        fun isTempBasal(command: ByteArray): Boolean = RunningPodModel.isTempBasal(command)
        fun isBasalProgram(command: ByteArray): Boolean = RunningPodModel.isBasalProgram(command)
        fun isStopOf(command: ByteArray, bit: Int): Boolean = RunningPodModel.isStopOf(command, bit)

        /**
         * A default status of a running pod ([podDefaultStatus] with RUNNING_ABOVE_MIN_VOLUME): the
         * Android capture `1d050016b00000000bff` with the last programming sequence, the bolus pulses
         * left and the delivery status (high nibble of byte 1) set. The capture itself says
         * CLUTCH_DRIVE_ENGAGED, a pod in the middle of its activation, to which no delivery command
         * may go (`observePodRunning`).
         */
        fun defaultStatus(lastProgrammingSequence: Int, bolusPulsesRemaining: Int = 0, deliveryStatus: Int = CAPTURE_DELIVERY): ByteArray =
            podDefaultStatus(ActivatingPodModel.RUNNING, lastProgrammingSequence, bolusPulsesRemaining, deliveryStatus)
    }
}
