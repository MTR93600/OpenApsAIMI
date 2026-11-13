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
import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import app.aaps.pump.omnipod.dashctl.driver.SimulatedCrash
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
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

    /** Every outcome the pump gave to `onCommandDecided`, of both kinds, in order. */
    val outcomes = mutableListOf<DashPumpOutcome>()

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
            }
        },
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

    /**
     * The sequence of the last programming command the pod took, as it reports it. 0x0f before
     * any, so it never matches a first command built with sequence 0.
     */
    var lastProgrammingSequence = 0x0f

    /** Bolus pulses the pod reports as not delivered yet. */
    var bolusPulsesRemaining = 0

    /**
     * True while the pod delivers a bolus: the status then reports `BOLUS_AND_BASAL_ACTIVE` (or
     * `BOLUS_AND_TEMP_BASAL_ACTIVE`). Set by a bolus the pod takes when [bolusPulsesOnNextBolus] is
     * set; a stop of the bolus clears it. Tests move [bolusPulsesRemaining] to make the bolus go on.
     */
    var bolusRunning = false

    /**
     * When set, the next bolus the pod takes starts running with this many pulses left. Null: the pod
     * reports no bolus running, as if it had delivered it at once.
     */
    var bolusPulsesOnNextBolus: Int? = null

    /** The stops of the bolus the pod got, with the pulses that were left at each one. */
    val bolusStops = mutableListOf<Int>()

    /**
     * True while the pod runs a temp basal: set by a temp basal command it takes, cleared by a stop
     * of the temp basal. The status then reports `TEMP_BASAL_ACTIVE`.
     */
    var tempBasalRunning = false

    /**
     * True while the pod runs a basal program: set by a basal program it takes, cleared by a stop with
     * the basal bit (a suspend, a stop of all delivery). With no temp basal and no bolus, the status
     * then reports `BASAL_ACTIVE`. False by default, so a status reports the delivery status of the
     * Android capture (`SUSPENDED`) as before.
     */
    var basalRunning = false

    /**
     * The pod status the default status reports (`PodStatus` value): RUNNING_ABOVE_MIN_VOLUME by
     * default. A deactivation the pod takes sets DEACTIVATED (0x0f).
     */
    var podStatusCode = ActivatingPodModel.RUNNING

    /**
     * The alerts the status reports (the `AlertUtil.encodeAlertSet` byte). A silence of alerts the
     * pod takes clears the alerts it names.
     */
    var activeAlerts = 0

    /**
     * When set (an `AlarmType` value), the pod has a fault: it takes no command and answers every
     * command with an alarm status ([podAlarmStatus]) that reports this alarm, pod status ALARM,
     * delivery suspended, the last programming command it took and the bolus pulses it had left.
     */
    var faultAlarmType: Int? = null

    /** The reservoir pulses the default status reports: 1023 (more than 50 U) by default, as the capture. */
    var reservoirPulses = 0x3ff

    init {
        answerWithStatus()
    }

    /** The pod answers as a new pod being activated ([ActivatingPodModel]). */
    fun answerAsActivatingPod(model: ActivatingPodModel) {
        pod.answerFor = model::answer
    }

    /** The pod answers each command with a default status (Android `DefaultStatusResponse`). */
    fun answerWithStatus() {
        pod.answerFor = answer@{ command ->
            faultAlarmType?.let { return@answer podAlarmStatus(it, lastProgrammingSequence, bolusPulsesRemaining) }
            val sequence = (command[4].toInt() and 0xff shr 2) and 0x0f
            if (command[6] != CommandType.GET_STATUS.value) lastProgrammingSequence = sequence
            if (isTempBasal(command)) tempBasalRunning = true
            if (isStopOf(command, TEMP_BASAL_BIT)) tempBasalRunning = false
            if (isBasalProgram(command)) basalRunning = true
            if (isStopOf(command, BASAL_BIT)) basalRunning = false
            if (isBolus(command)) bolusPulsesOnNextBolus?.let {
                bolusRunning = true
                bolusPulsesRemaining = it
            }
            val stopsBolus = isStopOf(command, BOLUS_BIT) || command[6] == CommandType.DEACTIVATE.value
            if (stopsBolus) {
                bolusRunning = false
                bolusStops += bolusPulsesRemaining
            }
            if (command[6] == CommandType.DEACTIVATE.value) {
                tempBasalRunning = false
                basalRunning = false
                podStatusCode = DEACTIVATED
            }
            if (command[6] == CommandType.SILENCE_ALERTS.value) activeAlerts = activeAlerts and (command[12].toInt() and 0xff).inv()
            // The answer to the stop reports the pulses that were not delivered; after it, none are left.
            val answer = podDefaultStatus(podStatusCode, lastProgrammingSequence, bolusPulsesRemaining, deliveryStatus(), activeAlerts, reservoirPulses)
            if (stopsBolus) bolusPulsesRemaining = 0
            answer
        }
    }

    private fun deliveryStatus(): Int = when {
        bolusRunning && tempBasalRunning -> BOLUS_AND_TEMP_BASAL_ACTIVE
        bolusRunning                     -> BOLUS_AND_BASAL_ACTIVE
        tempBasalRunning                 -> TEMP_BASAL_ACTIVE
        basalRunning                     -> BASAL_ACTIVE
        else                             -> CAPTURE_DELIVERY
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
     */
    fun pairAndConnect(
        podAddress: ByteArray? = POD_ADDRESS,
        withPulseRate: Boolean = true,
        pulseRateBeforePairing: Boolean = false,
        activated: Boolean = true,
    ) {
        val setUniqueId = SetUniqueIdResponse(parityHex(SET_UNIQUE_ID_RESPONSE))
        if (withPulseRate && pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId, 0L)
        if (podAddress == null) pump.pair() else pump.pair(podAddress)
        if (withPulseRate && !pulseRateBeforePairing) stateManager.updateFromSetUniqueIdResponse(setUniqueId, 0L)
        pump.establishSession()
        if (activated) markActivated()
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

        /** The delivery status of the Android capture (0, `SUSPENDED`): no temp basal. */
        const val CAPTURE_DELIVERY = ActivatingPodModel.CAPTURE_DELIVERY

        /** `PodStatus.DEACTIVATED`. */
        const val DEACTIVATED = 0x0f

        /** `PodStatus.ALARM`. */
        const val ALARM = 0x0d

        /** `DeliveryStatus.BASAL_ACTIVE`. */
        const val BASAL_ACTIVE = 0x1

        /** `DeliveryStatus.TEMP_BASAL_ACTIVE`. */
        const val TEMP_BASAL_ACTIVE = 0x2

        /** `DeliveryStatus.BOLUS_AND_BASAL_ACTIVE`. */
        const val BOLUS_AND_BASAL_ACTIVE = 0x5

        /** `DeliveryStatus.BOLUS_AND_TEMP_BASAL_ACTIVE`. */
        const val BOLUS_AND_TEMP_BASAL_ACTIVE = 0x6

        /** The basal bit of `StopDeliveryCommand.DeliveryType`. */
        const val BASAL_BIT = 0x01

        /** The temp basal bit of `StopDeliveryCommand.DeliveryType`. */
        const val TEMP_BASAL_BIT = 0x02

        /** The bolus bit of `StopDeliveryCommand.DeliveryType`. */
        const val BOLUS_BIT = 0x04

        /** `AlarmType.ALARM_OCCLUDED`. */
        const val OCCLUDED = 0x14

        /**
         * An alarm status (the layout of `AlarmStatusResponse`): the Android vector of
         * `AlarmStatusResponseTest` (`021602080100000501BD00000003FF01950000000000670A`) with pod status
         * ALARM (0x0d), delivery SUSPENDED (0), the bolus pulses left, the last programming sequence and
         * the alarm type set.
         */
        fun podAlarmStatus(alarmType: Int, lastProgrammingSequence: Int, bolusPulsesRemaining: Int): ByteArray =
            parityHex(
                "021602" + "0d" + "00" +
                    (bolusPulsesRemaining and 0x7ff).toString(16).padStart(4, '0') +
                    (lastProgrammingSequence and 0x0f).toString(16).padStart(2, '0') +
                    "01bd" + (alarmType and 0xff).toString(16).padStart(2, '0') +
                    "0000" + "03ff" + "0195" + "00" + "00000000" + "670a"
            )

        /** A bolus command: 0x1a (program insulin), then 0x17 after the 0x1a part. */
        fun isBolus(command: ByteArray): Boolean {
            if (command.size < 8 || command[6] != CommandType.PROGRAM_INSULIN.value) return false
            val next = 8 + (command[7].toInt() and 0xff)
            return next < command.size && command[next] == CommandType.PROGRAM_BOLUS.value
        }

        /** A temp basal command: 0x1a (program insulin), then 0x16 after the 0x1a part. */
        fun isTempBasal(command: ByteArray): Boolean {
            if (command.size < 8 || command[6] != CommandType.PROGRAM_INSULIN.value) return false
            val next = 8 + (command[7].toInt() and 0xff)
            return next < command.size && command[next] == CommandType.PROGRAM_TEMP_BASAL.value
        }

        /** A basal program: 0x1a (program insulin), then 0x13 after the 0x1a part. */
        fun isBasalProgram(command: ByteArray): Boolean {
            if (command.size < 8 || command[6] != CommandType.PROGRAM_INSULIN.value) return false
            val next = 8 + (command[7].toInt() and 0xff)
            return next < command.size && command[next] == CommandType.PROGRAM_BASAL.value
        }

        /** A stop (0x1f) whose delivery type has [bit]; the type is the low nibble of byte 12. */
        fun isStopOf(command: ByteArray, bit: Int): Boolean =
            command.size > 12 && command[6] == CommandType.STOP_DELIVERY.value && (command[12].toInt() and bit) != 0

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
