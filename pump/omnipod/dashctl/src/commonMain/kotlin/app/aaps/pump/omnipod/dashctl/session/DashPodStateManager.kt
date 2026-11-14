package app.aaps.pump.omnipod.dashctl.session

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.utils.Round
import app.aaps.pump.omnipod.dashctl.comm.session.EapSqn
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.DefaultStatusResponse
import app.aaps.pump.omnipod.dashctl.pod.response.Response
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse
import app.aaps.pump.omnipod.dashctl.pod.response.VersionResponse
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt

/**
 * Owns the [DashPodState] and persists it through [DashPodStateStore].
 *
 * Ported from the Android `OmnipodDashPodStateManagerImpl` (the parts the iOS driver uses):
 * - [increaseMessageSequenceNumber] moves the 4-bit command sequence number on ((n + 1) and 0x0f)
 *   and persists. The outer packet counter is not here: as on Android it lives in the session
 *   (`SessionKeys.msgSequenceNumber`), starts from the pair result or from 1, and is not stored.
 * - [increaseEapAkaSequenceNumber] increments the EAP-AKA SQN and returns it as 6 big-endian bytes,
 *   without persisting (same as the original). [commitEapAkaSequenceNumber] persists it after a
 *   session set up, [updateEapAkaSequenceNumber] takes the SQN of the pod after a resynchronization.
 * - [updateFromPairing] resets the EAP-AKA SQN to 1 and stores the LTK and the pod id.
 * - [updateFromVersionResponse] and [updateFromSetUniqueIdResponse] store what the pod tells about
 *   itself during the activation (versions, lot, sequence, pulse rates, prime volumes, pod life).
 * - [updateFromDefaultStatus] and [updateFromStatus] store a status; the first default status also
 *   sets the activation time. Both update the [lastBolus] from the bolus pulses left.
 * - [resolveActiveCommand] also does what Android `handleCommandConfirmation` does to the pod state,
 *   in the same write: a confirmed bolus becomes the [lastBolus], a confirmed stop of the bolus ends it,
 *   a confirmed basal program becomes the running [basalProgram], a confirmed temp basal becomes the
 *   [tempBasal] and a confirmed cancel, suspend or resume clears it.
 * - [completeBolusOnPodKaput] is the pod state part of Android `checkPodKaput`: a faulted pod ends the
 *   last bolus with what it had left.
 *
 * Deviation from the original, deliberate: [updateFromPairing] persists
 * immediately. The original only persisted on the next state change; losing the
 * LTK between pairing and the next store would leave a paired pod the PDM can no
 * longer talk to.
 *
 * Not thread-safe: callers must serialize access (the BLE manager drives all
 * calls from its own sequencing).
 */
class DashPodStateManager(
    private val store: DashPodStateStore,
    /**
     * The time zone of the phone (Android `TimeZone.getDefault()`), read each time: the pod time zone
     * ([updateTimeZone], [sameTimeZone]) and the basal drift use it. A parameter so tests can set it.
     */
    private val deviceTimeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    /** Serializes [reloadIfUnknown]: the status checker and the queue may both try at once. */
    private val readLock = AapsLock()

    /** Why the stored state could not be read, or null when it is known. See [isStateKnown]. */
    @Volatile
    private var unreadableReason: String? = null

    private var state: DashPodState = readStored() ?: DashPodState()

    /**
     * False when the store could not read what it holds ([DashPodStateRead.Unreadable]): on the
     * iPhone the Keychain before the first unlock after a reboot, or bytes that do not decode. The
     * state then shows as "no pod", but a pod may run on the body. While it is false:
     * - every write is refused ([DashPodStateUnknownException]), so the stored item, maybe with the key
     *   of that pod, is never replaced (a new activation or a discard would delete it for good);
     * - the driver refuses every pod command and the plugin writes no "no pod" temp basal;
     * - [reloadIfUnknown] reads the store again (the next command, the status checker, or iOS saying
     *   the protected data is there).
     *
     * Deliberate difference from Android, which starts from an empty `PodState` when its stored JSON
     * does not decode (and logs it). SharedPreferences has no "locked" read failure, the Keychain has.
     */
    val isStateKnown: Boolean
        get() = unreadableReason == null

    /** Why the stored state could not be read, null when [isStateKnown]. */
    val stateUnreadableReason: String?
        get() = unreadableReason

    /**
     * Reads the store again while the state is not known ([isStateKnown]). A state read now replaces
     * the empty one; "nothing stored" makes the empty one the known state. Returns [isStateKnown].
     * Does nothing when the state is known.
     */
    fun reloadIfUnknown(): Boolean {
        if (unreadableReason == null) return true
        return readLock.withLock {
            if (unreadableReason == null) return@withLock true
            val stored = readStored()
            if (unreadableReason == null) state = stored ?: DashPodState()
            unreadableReason == null
        }
    }

    /** The stored state, null when none; sets [unreadableReason] for an unreadable one. */
    private fun readStored(): DashPodState? = when (val read = store.read()) {
        is DashPodStateRead.Loaded     -> {
            unreadableReason = null
            read.state
        }

        DashPodStateRead.Empty         -> {
            unreadableReason = null
            null
        }

        is DashPodStateRead.Unreadable -> {
            unreadableReason = read.reason
            null
        }
    }

    /**
     * Android `basalCorrectionInProgress` (transient there too, never stored): true while a basal
     * correction is delivered, so its pulse is counted as basal and not as a bolus pulse
     * ([DashPodState.bolusPulsesDelivered]).
     */
    @Volatile
    var basalCorrectionInProgress: Boolean = false

    val ltk: ByteArray?
        get() = state.ltk?.copyOf()
    val messageSequenceNumber: Int
        get() = state.messageSequenceNumber
    val eapAkaSequenceNumber: Long
        get() = state.eapAkaSequenceNumber
    val uniqueId: Long?
        get() = state.uniqueId
    val activationProgress: ActivationProgress
        get() = state.activationProgress
    val bluetoothAddress: String?
        get() = state.bluetoothAddress
    val podStatus: DashPodStatus?
        get() = state.podStatus
    val activeCommand: DashActiveCommand?
        get() = state.activeCommand
    val pulseRate: Short?
        get() = state.pulseRate
    val primePulseRate: Short?
        get() = state.primePulseRate
    val firstPrimeBolusVolume: Short?
        get() = state.firstPrimeBolusVolume
    val secondPrimeBolusVolume: Short?
        get() = state.secondPrimeBolusVolume
    val podLifeInHours: Short?
        get() = state.podLifeInHours
    val firmwareVersion: SoftwareVersion?
        get() = state.firmwareVersion
    val bleVersion: SoftwareVersion?
        get() = state.bleVersion
    val lotNumber: Long?
        get() = state.lotNumber
    val podSequenceNumber: Long?
        get() = state.podSequenceNumber

    /** Android `podStatus`: see [DashPodState.reportedPodStatus]. */
    val reportedPodStatus: PodStatus?
        get() = state.reportedPodStatus
    val activationTime: Long?
        get() = state.activationTime
    val lastUpdatedSystemMs: Long
        get() = state.lastUpdatedSystemMs

    /**
     * How many statuses (default or alarm) were stored, see [DashPodState.statusResponseCount]. A
     * caller compares it before and after a read to know whether that read gave a status. A NAK is
     * an answer but no status, and leaves [reportedPodStatus] as it was.
     */
    val statusResponseCount: Long
        get() = state.statusResponseCount

    /** Android `lastBolus`: see [DashLastBolus]. */
    val lastBolus: DashLastBolus?
        get() = state.lastBolus

    /** Android `basalProgram`: the basal program the pod confirmed last, see [DashPodState.basalProgram]. */
    val basalProgram: BasalProgram?
        get() = state.basalProgram

    /** Android `isActivationCompleted`: the activation reached [ActivationProgress.COMPLETED]. */
    val isActivationCompleted: Boolean
        get() = state.activationProgress == ActivationProgress.COMPLETED

    /** Android `suspendAlertsEnabled`: see [DashPodState.suspendAlertsEnabled]. */
    val suspendAlertsEnabled: Boolean
        get() = state.suspendAlertsEnabled

    /** See [DashPodState.expirationAlertSettings]. */
    val expirationAlertSettings: DashExpirationAlertSettings?
        get() = state.expirationAlertSettings

    /** See [DashPodState.lowReservoirAlertSettings]. */
    val lowReservoirAlertSettings: DashLowReservoirAlertSettings?
        get() = state.lowReservoirAlertSettings

    /**
     * Android `activeAlerts`: the alerts of the last status, null before the first status. Silencing
     * them (`silenceAlerts`) sends this set.
     */
    val activeAlerts: Set<AlertType>?
        get() = state.podStatus?.activeAlerts

    /** See [DashPodState.acknowledgedAlerts]. */
    val acknowledgedAlerts: Set<AlertType>
        get() = state.acknowledgedAlerts

    /**
     * Android `isPodKaput`: the pod status of the last answer is ALARM or DEACTIVATED. Such a pod
     * delivers nothing.
     */
    val isPodKaput: Boolean
        get() = state.reportedPodStatus == PodStatus.ALARM || state.reportedPodStatus == PodStatus.DEACTIVATED

    /** Android `tempBasal`: see [DashPodState.tempBasal]. */
    val tempBasal: DashTempBasal?
        get() = state.tempBasal

    /** Android `alarmType`: see [DashPodState.alarmType]. */
    val alarmType: AlarmType?
        get() = state.alarmType

    /**
     * Android `pulsesRemaining`: see [DashPodState.pulsesRemaining]. A state stored by version 3 or
     * before has no such field; it then takes the stored status when that reads below 1023, which is
     * what Android would have kept from that status.
     */
    val pulsesRemaining: Int?
        get() = state.pulsesRemaining
            ?: state.podStatus?.reservoirPulsesRemaining?.takeIf { it < DashPodState.RESERVOIR_READING_OVER_50_UNITS }

    /** Android `alarmSynced`: see [DashPodState.alarmSynced]. */
    val alarmSynced: Boolean
        get() = state.alarmSynced

    /**
     * Android `tempBasalActive`: delivery is not suspended and the confirmed [tempBasal] has not run out
     * at [nowMs]. False when no temp basal was confirmed.
     */
    fun tempBasalActive(nowMs: Long): Boolean = !isSuspended && state.tempBasal?.runsAt(nowMs) == true

    /** See [DashPodState.activationStepInFlight]. */
    val activationStepInFlight: ActivationProgress?
        get() = state.activationStepInFlight

    /**
     * Android `isPodRunning`: the pod status of the last answer ([reportedPodStatus]) is one of the
     * two running ones. False before the first answer.
     */
    val isPodRunning: Boolean
        get() = state.reportedPodStatus?.isRunning() == true

    /**
     * Android `observePodRunning`, checked before every delivery command: the activation is completed
     * and the pod reports that it runs. False on a pod in alarm, deactivated, or half activated.
     */
    val isActivatedAndRunning: Boolean
        get() = isActivationCompleted && isPodRunning

    /**
     * Android `isSuspended`: the last status shows delivery suspended. False before the first status,
     * as Android (`deliveryStatus?.equals(SUSPENDED) == true`).
     */
    val isSuspended: Boolean
        get() = state.podStatus?.deliveryStatus == DeliveryStatus.SUSPENDED

    /**
     * When the pod expires, epoch ms, or null before the pod life and a status are known. Android
     * `expiry`: now + pod life - minutes since activation - time since the last update - 8 hours,
     * which is the same instant as the formula here. The 8 hours are taken off the reported pod life
     * as on Android.
     */
    val expiryMs: Long?
        get() {
            val podLife = state.podLifeInHours ?: return null
            val minutes = state.podStatus?.minutesSinceActivation ?: return null
            return state.lastUpdatedSystemMs - minutes * MS_PER_MINUTE + (podLife - EXPIRY_GRACE_HOURS) * MS_PER_HOUR
        }

    // -- Basal drift (ported from OmnipodDashPodStateManagerImpl, this fork) ---------------------

    /** Android `basalPulsesDelivered`: total pulses minus the bolus pulses, null while one is not known. */
    private fun basalPulsesDelivered(s: DashPodState): Int? {
        val total = s.podStatus?.totalPulsesDelivered ?: return null
        val bolus = s.bolusPulsesDelivered ?: return null
        return total - bolus
    }

    /** Android `basalDelivered`, units: 0 while [basalPulsesDelivered] is not known. */
    private fun basalDelivered(s: DashPodState): Double = (basalPulsesDelivered(s) ?: 0) * PodConstants.POD_PULSE_BOLUS_UNITS

    /** Android `basalDrift`, units: positive the pod gave more basal than expected, negative less. 0 before [DashPodState.basalExpected]. */
    private fun basalDrift(s: DashPodState): Double {
        val delivered = basalDelivered(s)
        return delivered - (s.basalExpected ?: delivered)
    }

    /** The basal drift now, units (see [needsBasalCorrection]). For the log and the tests. */
    val basalDrift: Double
        get() = basalDrift(state)

    /** Android `lastBasalCorrectionTime`: see [DashPodState.lastBasalCorrectionTime]. */
    val lastBasalCorrectionTime: Long?
        get() = state.lastBasalCorrectionTime

    /** Android setter `lastBasalCorrectionTime`: the 2 minutes cooldown starts at [timeMs]. Persists. */
    fun updateLastBasalCorrectionTime(timeMs: Long) {
        state = state.copy(lastBasalCorrectionTime = timeMs)
        persist()
    }

    /**
     * Android `needsBasalCorrection`, the same rules and limits: true when the pod gave between 0.025 U
     * and 0.1 U less basal than expected, so one 0.05 U pulse makes it up.
     * - false while the activation is not completed, the pod is suspended or kaput, or within 2 minutes
     *   of the last correction ([DashPodState.lastBasalCorrectionTime]);
     * - a drift of 0.1 U or more either way: the expected basal is reset to the basal given (stored),
     *   false. A reset is preferred to a wrong correction;
     * - a drift above -0.025 U: false;
     * - a zero temp basal (the loop asked for no insulin): false, unless a bolus started less than
     *   5 minutes ago (the zero temp basal of a bolus).
     */
    fun needsBasalCorrection(nowMs: Long): Boolean {
        val correctionThreshold = -PodConstants.POD_PULSE_BOLUS_UNITS / 2
        if (!isActivationCompleted) return false
        if (isSuspended || isPodKaput) return false
        state.lastBasalCorrectionTime?.let { if (nowMs - it < BASAL_CORRECTION_COOLDOWN_MS) return false }
        val drift = basalDrift(state)
        if (drift >= PodConstants.POD_PULSE_BOLUS_UNITS * 2 || drift <= -PodConstants.POD_PULSE_BOLUS_UNITS * 2) {
            state = state.copy(basalExpected = basalDelivered(state))
            persist()
            return false
        }
        if (drift > correctionThreshold) return false
        if (state.tempBasal?.rate == 0.0) {
            val timeSinceLastBolus = state.lastBolus?.startTimeMs?.let { nowMs - it }
            if (timeSinceLastBolus == null || timeSinceLastBolus >= RECENT_BOLUS_MS) return false
        }
        return true
    }

    // -- Pod time zone (ported from OmnipodDashPodStateManagerImpl) -------------------------------

    /** Android `timeZoneId`: see [DashPodState.timeZoneId]. */
    val timeZoneId: String?
        get() = state.timeZoneId

    /** Android `timeZoneUpdated`: see [DashPodState.timeZoneUpdatedMs]. */
    val timeZoneUpdatedMs: Long?
        get() = state.timeZoneUpdatedMs

    /** See [DashPodState.timeZoneOffsetMs]. */
    val timeZoneOffsetMs: Int?
        get() = state.timeZoneOffsetMs

    /** The offset of the phone time zone from UTC at [nowMs], ms (Android `TimeZone.getOffset`). */
    private fun deviceOffsetMs(nowMs: Long): Int =
        deviceTimeZone().offsetAt(Instant.fromEpochMilliseconds(nowMs)).totalSeconds * 1000

    /**
     * Android `updateTimeZone`, called when the pod took a basal program: the time zone of the phone
     * now, its offset now, and now. Persists.
     */
    fun updateTimeZone(nowMs: Long) {
        state = state.copy(timeZoneOffsetMs = deviceOffsetMs(nowMs), timeZoneId = deviceTimeZone().id, timeZoneUpdatedMs = nowMs)
        persist()
    }

    /**
     * Android `sameTimeZone`: the offset of the phone now is the stored pod offset. False when no pod
     * offset is stored, as Android (an Int compared with null).
     */
    fun sameTimeZone(nowMs: Long): Boolean = deviceOffsetMs(nowMs) == state.timeZoneOffsetMs

    /**
     * Android `time`: the time on the pod, epoch ms: the activation time, plus the minutes since
     * activation of the last status, plus the time since that status. Null while one is not known
     * (also the pod offset, as Android).
     */
    fun podTimeMs(nowMs: Long): Long? {
        val activationTime = state.activationTime ?: return null
        val minutes = state.podStatus?.minutesSinceActivation ?: return null
        if (state.timeZoneOffsetMs == null) return null
        return activationTime + minutes * MS_PER_MINUTE + (nowMs - state.lastUpdatedSystemMs)
    }

    /** Android `timeDrift`: the pod time minus the phone time, ms; null when [podTimeMs] is. */
    fun timeDriftMs(nowMs: Long): Long? = podTimeMs(nowMs)?.let { it - nowMs }

    // -- Fault reference and connection counters ------------------------------------------------

    /** Android `pdmRef`: see [DashPodState.pdmRef]. */
    val pdmRef: String?
        get() = state.pdmRef

    /** See [DashPodState.faultTimeMs]: when the fault stopped delivery, null when the pod did not say. */
    val faultTimeMs: Long?
        get() = state.faultTimeMs

    // -- Dose accounting of OmnipodKit (Trio) ----------------------------------------------------

    /**
     * The rate of the running basal program at [atMs], U/h, read in the time zone of the pod as the
     * basal drift does ([DashBasalDrift.integrateExpectedDelivery]); in the phone time zone while the pod
     * time zone is not known. Null while no program is known ([basalProgram]).
     */
    fun scheduledBasalRateAt(atMs: Long): Double? {
        val program = state.basalProgram ?: return null
        val podOffset = state.timeZoneOffsetMs ?: return program.rateAt(atMs, deviceTimeZone())
        return program.rateAt(atMs + podOffset - deviceOffsetMs(atMs), deviceTimeZone())
    }

    /**
     * Whether [command], when nobody knows if the pod took it, is counted as taken: OmnipodKit
     * `PodState.resolveAnyPendingCommandWithUncertainty`, "assume commands failed/succeeded in the
     * direction of positive net delivery". See [dashCountAsTakenWhileUnknown]; the scheduled rate is
     * the one at the command time, the temp basal a cancel stops is the stored [tempBasal] when it
     * still runs at that time.
     */
    fun countAsTakenWhileUnknown(command: DashActiveCommand): Boolean {
        val at = command.createdAtMs ?: state.lastUpdatedSystemMs
        return dashCountAsTakenWhileUnknown(command, scheduledBasalRateAt(at), state.tempBasal?.takeIf { it.runsAt(at) })
    }

    /**
     * The bolus of [DashPodState.untrackedBolusToRecordAt], while it is not written to `PumpSync`: the
     * caller writes it, then calls [untrackedBolusWritten]. Null when there is none, or when the
     * [lastBolus] is not that bolus any more (the mark is then dropped: no record can be written for it).
     */
    fun untrackedBolusToRecord(): DashLastBolus? {
        val at = state.untrackedBolusToRecordAt ?: return null
        val bolus = state.lastBolus?.takeIf { it.createdAtMs == at }
        if (bolus == null) clearUntrackedBolusMark()
        return bolus
    }

    /**
     * The record of the untracked bolus with this [pumpId] (its time) is written: the mark is cleared and
     * persisted. A [pumpId] of another bolus changes nothing, so a late answer about an older bolus
     * cannot clear the mark of a newer one that is still not counted.
     */
    fun untrackedBolusWritten(pumpId: Long?) {
        if (pumpId == null || state.untrackedBolusToRecordAt != pumpId) return
        clearUntrackedBolusMark()
    }

    private fun clearUntrackedBolusMark() {
        if (state.untrackedBolusToRecordAt == null) return
        state = state.copy(untrackedBolusToRecordAt = null)
        persist()
    }

    /** Android `successfulConnectionAttemptsAfterRetries`. */
    val successfulConnectionAttemptsAfterRetries: Int
        get() = state.successfulConnectionAttemptsAfterRetries

    /** Android `failedConnectionsAfterRetries`. */
    val failedConnectionsAfterRetries: Int
        get() = state.failedConnectionsAfterRetries

    /** Android `incrementSuccessfulConnectionAttemptsAfterRetries`. Persists (Android stores it with the next write). */
    fun incrementSuccessfulConnectionAttemptsAfterRetries() {
        state = state.copy(successfulConnectionAttemptsAfterRetries = state.successfulConnectionAttemptsAfterRetries + 1)
        persist()
    }

    /** Android `incrementFailedConnectionsAfterRetries`. Persists. */
    fun incrementFailedConnectionsAfterRetries() {
        state = state.copy(failedConnectionsAfterRetries = state.failedConnectionsAfterRetries + 1)
        persist()
    }

    /** Android `connectionSuccessRatio`: worked / (worked + failed), 0 before the first try. */
    fun connectionSuccessRatio(): Float {
        val worked = state.successfulConnectionAttemptsAfterRetries
        val failed = state.failedConnectionsAfterRetries
        if (worked + failed == 0) return 0.0F
        return worked.toFloat() / (worked + failed)
    }

    /** Full state snapshot; the LTK is defensively copied. */
    fun currentState(): DashPodState = state.copy(ltk = state.ltk?.copyOf())

    /**
     * Moves the 4-bit command sequence number on ((n + 1) and 0x0f) and persists the state.
     * Called as the Android `PodEventInterceptor` does: once when a command was sent (also when the
     * pod did not confirm it), and once when an answer was read.
     */
    fun increaseMessageSequenceNumber() {
        state = state.copy(
            messageSequenceNumber = (state.messageSequenceNumber + 1) and DashPodState.MESSAGE_SEQ_MAX
        )
        persist()
    }

    /**
     * Increments the EAP-AKA sequence number and returns it as 6 big-endian bytes
     * (the `EapSqn` wire format). Does not persist; call [commitEapAkaSequenceNumber]
     * once the session is established.
     */
    fun increaseEapAkaSequenceNumber(): ByteArray {
        state = state.copy(eapAkaSequenceNumber = state.eapAkaSequenceNumber + 1)
        return EapSqn(state.eapAkaSequenceNumber).value
    }

    /**
     * The pod asked for a resynchronization and gave its own SQN: take it and persist, as the
     * Android setter `eapAkaSequenceNumber` does. The next [increaseEapAkaSequenceNumber] goes
     * one above it.
     */
    fun updateEapAkaSequenceNumber(sequenceNumber: Long) {
        state = state.copy(eapAkaSequenceNumber = sequenceNumber)
        persist()
    }

    /** Persists the (previously increased) EAP-AKA sequence number. */
    fun commitEapAkaSequenceNumber() {
        persist()
    }

    /**
     * The scan found the pod to pair (Android `pairNewPod`: `podState.bluetoothAddress = podAddress`,
     * stored before the connect). On iOS this is the CoreBluetooth peripheral identifier. The held
     * connection connects to it again after every drop and after iOS starts the app again. Persists
     * immediately. Already part of the stored format (codec flag bit 2), so no new version.
     *
     * As the Android setter: the same address again does nothing, and another address while one is
     * stored throws [IllegalStateException] and keeps the stored one. An activation that failed half
     * way then never switches to another pod in range; the user discards the pod first ([reset]).
     */
    fun updateBluetoothAddress(address: String) {
        val stored = state.bluetoothAddress
        if (stored == address) return
        check(stored == null) { "Trying to set Bluetooth Address to $address, but it is already set to $stored" }
        state = state.copy(bluetoothAddress = address)
        persist()
    }

    /**
     * Records a completed pairing: stores the LTK (defensively copied), the pod
     * unique id and resets the EAP-AKA sequence number to 1. Persists immediately.
     */
    fun updateFromPairing(uniqueId: Long, ltk: ByteArray) {
        require(ltk.size == DashPodState.LTK_SIZE) {
            "LTK has to be ${DashPodState.LTK_SIZE} bytes long"
        }
        state = state.copy(
            uniqueId = uniqueId,
            ltk = ltk.copyOf(),
            eapAkaSequenceNumber = 1L
        )
        persist()
    }

    /**
     * The pod answered GetVersion, the first step of the activation. Port of the Android
     * `updateFromVersionResponse`: the versions, the pod status, the lot and the pod sequence number.
     * The unique id in the answer is not taken, as on Android.
     *
     * @param nowMs the wall clock now (Android `System.currentTimeMillis()`).
     */
    fun updateFromVersionResponse(response: VersionResponse, nowMs: Long) {
        state = state.copy(
            bleVersion = SoftwareVersion(response.bleVersionMajor, response.bleVersionMinor, response.bleVersionInterim),
            firmwareVersion = SoftwareVersion(response.firmwareVersionMajor, response.firmwareVersionMinor, response.firmwareVersionInterim),
            reportedPodStatus = response.podStatus,
            lotNumber = response.lotNumber,
            podSequenceNumber = response.podSequenceNumber,
            lastUpdatedSystemMs = nowMs,
        )
        persist()
    }

    /**
     * The pod answered SetUniqueId during activation. Port of the Android
     * `updateFromSetUniqueIdResponse`: the pulse rate (a bolus is built with it), the prime pulse rate,
     * the prime volumes, the pod life, the versions, the pod status, the lot, the pod sequence number
     * and the unique id the pod took.
     *
     * @param nowMs the wall clock now (Android `System.currentTimeMillis()`).
     */
    fun updateFromSetUniqueIdResponse(response: SetUniqueIdResponse, nowMs: Long) {
        state = state.copy(
            pulseRate = response.pumpRate,
            primePulseRate = response.primePumpRate,
            firstPrimeBolusVolume = response.numberOfEngagingClutchDrivePulses,
            secondPrimeBolusVolume = response.numberOfPrimePulses,
            podLifeInHours = response.podExpirationTimeInHours,
            bleVersion = SoftwareVersion(response.bleVersionMajor, response.bleVersionMinor, response.bleVersionInterim),
            firmwareVersion = SoftwareVersion(response.firmwareVersionMajor, response.firmwareVersionMinor, response.firmwareVersionInterim),
            reportedPodStatus = response.podStatus,
            lotNumber = response.lotNumber,
            podSequenceNumber = response.podSequenceNumber,
            uniqueId = response.uniqueIdReceivedInCommand,
            lastUpdatedSystemMs = nowMs,
        )
        persist()
    }

    /**
     * An answer was read (Android `PodEventInterceptor.handleResponse`): a Version or SetUniqueId
     * answer and a status go into the state. Other answers (a NAK) change nothing. Returns false for
     * them, so the caller can log them.
     *
     * @param nowMs the wall clock now (Android `System.currentTimeMillis()`).
     */
    fun updateFromResponse(response: Response, nowMs: Long): Boolean {
        when (response) {
            is DefaultStatusResponse -> updateFromDefaultStatus(DashPodStatus.of(response, nowMs))
            is AlarmStatusResponse   -> updateFromAlarmStatus(response, nowMs)
            is VersionResponse       -> updateFromVersionResponse(response, nowMs)
            is SetUniqueIdResponse   -> updateFromSetUniqueIdResponse(response, nowMs)
            else                     -> return false
        }
        return true
    }

    /**
     * The pod answered with an alarm status: what a faulted pod sends in place of a default status.
     * Android `updateFromAlarmStatusResponse`: stored as any status ([updateFromStatus]), and the alarm
     * type of the first one is kept in the same write ([alarmType]); a later alarm status does not
     * change it. An alarm status does not set the activation time, as Android.
     */
    fun updateFromAlarmStatus(response: AlarmStatusResponse, nowMs: Long) {
        val updated = statusApplied(DashPodStatus.of(response, nowMs))
        state = if (updated.alarmType == null) {
            updated.copy(alarmType = response.alarmType, pdmRef = response.pdmRef, faultTimeMs = faultTimeOf(response, nowMs))
        } else {
            updated
        }
        persist()
    }

    /** Android setter `alarmSynced`: the plugin showed and recorded the fault. Persists. */
    fun updateAlarmSynced(synced: Boolean) {
        if (state.alarmSynced == synced) return
        state = state.copy(alarmSynced = synced)
        persist()
    }

    /**
     * The pod state part of Android `OmnipodDashPumpPlugin.checkPodKaput`: when the pod is kaput
     * ([isPodKaput]: ALARM or DEACTIVATED) and the [lastBolus] has not ended, it ends now with what
     * the last status left (Android `markComplete`), in one write. Returns that bolus, whose
     * [DashLastBolus.deliveredUnits] is asked - left; the caller records it again with that amount at
     * its [DashLastBolus.createdAtMs] (Android `syncBolusWithPumpId`). Returns null otherwise, also
     * for a bolus that already ended, so the same bolus is never given twice.
     *
     * The bolus is stored, so a fault seen first after a crash or a restart still ends it: the next
     * status read finds the pod kaput and the bolus not ended.
     */
    fun completeBolusOnPodKaput(): DashLastBolus? {
        if (!isPodKaput) return null
        val bolus = state.lastBolus?.takeIf { !it.deliveryComplete } ?: return null
        val ended = bolus.copy(deliveryComplete = true)
        state = state.copy(lastBolus = ended)
        persist()
        return ended
    }

    /**
     * Android `podStateManager.tempBasal = null` in `verifyPumpState`: AAPS expected a temp basal, the
     * pod runs its basal program, so the stored temp basal is dropped. Persists.
     */
    fun clearTempBasal() {
        if (state.tempBasal == null) return
        state = state.copy(tempBasal = null)
        persist()
    }

    // -- Alerts (ported from OmnipodDashPodStateManagerImpl) ----------------------

    /**
     * The user silenced [alert] on the phone: it is kept in [acknowledgedAlerts] until the pod no
     * longer reports it ([forgetAcknowledgedAlertsExcept]) or refused to silence it
     * ([forgetAcknowledgedAlert]). Persists, before anything is sent (see
     * [DashPodState.acknowledgedAlerts]).
     */
    fun addAcknowledgedAlert(alert: AlertType) {
        require(alert != AlertType.UNKNOWN) { "an unknown alert has no slot to silence" }
        if (alert in state.acknowledgedAlerts) return
        state = state.copy(acknowledgedAlerts = state.acknowledgedAlerts + alert)
        persist()
    }

    /** [alert] leaves [acknowledgedAlerts]. Persists. */
    fun forgetAcknowledgedAlert(alert: AlertType) {
        if (alert !in state.acknowledgedAlerts) return
        state = state.copy(acknowledgedAlerts = state.acknowledgedAlerts - alert)
        persist()
    }

    /** Every alert not in [stillActive] leaves [acknowledgedAlerts]: the pod no longer reports it. Persists. */
    fun forgetAcknowledgedAlertsExcept(stillActive: Set<AlertType>) {
        val kept = state.acknowledgedAlerts intersect stillActive
        if (kept == state.acknowledgedAlerts) return
        state = state.copy(acknowledgedAlerts = kept)
        persist()
    }

    /** Android setter `suspendAlertsEnabled`. Persists. */
    fun updateSuspendAlertsEnabled(enabled: Boolean) {
        if (state.suspendAlertsEnabled == enabled) return
        state = state.copy(suspendAlertsEnabled = enabled)
        persist()
    }

    /** Android `updateExpirationAlertSettings`. Persists. */
    fun updateExpirationAlertSettings(settings: DashExpirationAlertSettings) {
        state = state.copy(expirationAlertSettings = settings)
        persist()
    }

    /** Android `updateLowReservoirAlertSettings`. Persists. */
    fun updateLowReservoirAlertSettings(settings: DashLowReservoirAlertSettings) {
        state = state.copy(lowReservoirAlertSettings = settings)
        persist()
    }

    /**
     * Android `sameAlertSettings`: the pod was last programmed with exactly these settings. False when
     * either part was never stored.
     */
    fun sameAlertSettings(settings: DashAlertSettings): Boolean =
        state.expirationAlertSettings == settings.expiration && state.lowReservoirAlertSettings == settings.lowReservoir

    // -- Activation (ported from OmnipodDashPodStateManagerImpl) ------------------

    /**
     * Android setter `activationProgress`: one activation step is done. In the same write the step in
     * flight is cleared and, when given, [basalProgram] becomes the running program (the program the
     * activation sent, see `DashActivation`). Persists.
     */
    fun updateActivationProgress(progress: ActivationProgress, basalProgram: BasalProgram? = null) {
        state = state.copy(
            activationProgress = progress,
            activationStepInFlight = null,
            basalProgram = basalProgram?.let { BasalProgram(it.segments) } ?: state.basalProgram,
        )
        persist()
    }

    /**
     * The bolus of [step] (the prime or the cannula insertion) is about to go. Stored before the
     * write, as [markActiveCommandSent]: after a crash at any later point the activation reads a
     * status before it sends this bolus again. See [DashPodState.activationStepInFlight].
     */
    fun markActivationStepSent(step: ActivationProgress) {
        require(step in DashPodState.STEPS_WITH_A_BOLUS) { "Only the prime and the cannula insertion are marked" }
        state = state.copy(activationStepInFlight = step)
        persist()
    }

    /** The write failed before any byte left: the pod cannot have the bolus of the step in flight. */
    fun clearActivationStepInFlight() {
        if (state.activationStepInFlight == null) return
        state = state.copy(activationStepInFlight = null)
        persist()
    }

    /**
     * Android `recoverActivationFromPodStatus`, the same table, on the pod status of the last answer
     * ([reportedPodStatus], Android `podState.podStatus`):
     * FILLED gives NOT_STARTED, UID_SET gives SET_UNIQUE_ID, CLUTCH_DRIVE_ENGAGED gives PRIME_COMPLETED,
     * BASAL_PROGRAM_SET gives PROGRAMMED_BASAL, the two running ones give CANNULA_INSERTED.
     * ENGAGING_CLUTCH_DRIVE and PRIMING return "Busy" and change nothing; any other status changes
     * nothing. As Android, the progress can go back.
     *
     * When a progress is set, the step in flight is cleared in the same write: the progress now comes
     * from the pod itself, which is what the step in flight waits for. Each status of the table says
     * whether the pod took the prime or the cannula bolus. So the caller must run this only on a
     * status the read it just did stored (`statusResponseCount` went up): after a NAK the stored
     * status is the one from before the bolus, and it would clear the mark on a wrong "not taken".
     *
     * @return "Busy" when the pod is still priming, null otherwise.
     */
    fun recoverActivationFromPodStatus(): String? {
        val progress = when (state.reportedPodStatus) {
            PodStatus.FILLED                                                       -> ActivationProgress.NOT_STARTED
            PodStatus.UID_SET                                                      -> ActivationProgress.SET_UNIQUE_ID
            PodStatus.ENGAGING_CLUTCH_DRIVE, PodStatus.PRIMING                     -> return "Busy"
            PodStatus.CLUTCH_DRIVE_ENGAGED                                         -> ActivationProgress.PRIME_COMPLETED
            PodStatus.BASAL_PROGRAM_SET                                            -> ActivationProgress.PROGRAMMED_BASAL
            PodStatus.RUNNING_ABOVE_MIN_VOLUME, PodStatus.RUNNING_BELOW_MIN_VOLUME -> ActivationProgress.CANNULA_INSERTED
            else                                                                   -> null
        }
        progress?.let { updateActivationProgress(it) }
        return null
    }

    // -- Active command (ported from OmnipodDashPodStateManagerImpl) -------------

    /**
     * Opens the active command for a delivery command about to be built with the current message
     * sequence number. Stored before any byte goes, so a crash cannot lose it.
     *
     * @param createdAtMs the wall clock now (Android `System.currentTimeMillis()`, the `createdAt` of
     *   the history record Android writes for the command). See [DashActiveCommand.createdAtMs].
     * @param basalProgram the program a [DashCommandKind.BASAL_PROGRAM] command sends. A copy is kept,
     *   so the caller cannot change the stored one (`BasalProgram.addSegment`).
     * @throws DashCommandNotConfirmedException while another command is open: the pod may have taken
     *   it, and sending a new one (a retried bolus) could deliver twice. Ask for a status first.
     */
    fun createActiveCommand(
        kind: DashCommandKind,
        historyId: Long,
        createdAtMs: Long,
        requestedBolusUnits: Double? = null,
        tempBasalRate: Double? = null,
        tempBasalMinutes: Int? = null,
        basalProgram: BasalProgram? = null,
        basalCorrection: Boolean = false,
    ): DashActiveCommand {
        state.activeCommand?.let {
            throw DashCommandNotConfirmedException("Trying to send a command and the last command was not confirmed: $it")
        }
        val command = DashActiveCommand(
            sequence = state.messageSequenceNumber,
            kind = kind,
            historyId = historyId,
            statusCountAtCreation = state.statusResponseCount,
            requestedBolusUnits = requestedBolusUnits,
            tempBasalRate = tempBasalRate,
            tempBasalMinutes = tempBasalMinutes,
            createdAtMs = createdAtMs,
            basalProgram = basalProgram?.let { BasalProgram(it.segments) },
            basalCorrection = basalCorrection,
        )
        state = state.copy(activeCommand = command)
        persist()
        return command
    }

    /**
     * The bytes of the active command are about to go. Called before the write: a crash in the middle
     * then leaves "maybe sent", which needs a status, and never "not sent", which would allow a resend.
     */
    fun markActiveCommandSent() {
        val command = state.activeCommand ?: return
        state = state.copy(activeCommand = command.copy(sent = true))
        persist()
    }

    /** The write failed before any byte left: the pod cannot have the command. */
    fun markActiveCommandNotSent() {
        val command = state.activeCommand ?: return
        state = state.copy(activeCommand = command.copy(sent = false))
        persist()
    }

    /** What is known now about the active command. */
    fun commandConfirmation(): DashCommandConfirmation =
        DashCommandConfirmation.of(
            state.activeCommand,
            state.statusResponseCount,
            state.podStatus?.lastProgrammingCommandSequence,
            state.podStatus?.deliveryStatus,
            trackedBolusRunning = state.lastBolus?.deliveryComplete == false,
        )

    /**
     * Closes the active command when its outcome is known ([DashCommandConfirmation.isFinal]), and
     * keeps it open otherwise. The command is returned either way, for the history.
     *
     * A final outcome also does to the pod state what Android `handleCommandConfirmation` does:
     * - BOLUS, SUCCESS: the bolus becomes the [lastBolus] (Android `createLastBolus`), at [nowMs].
     * - STOP_BOLUS, SUCCESS: the [lastBolus] ends (Android CANCEL_BOLUS, `markComplete`). What was left
     *   at the last status, which is the answer to the stop when it was read, is not counted.
     * - SUSPEND_DELIVERY, STOP_ALL or DEACTIVATE, SUCCESS: a [lastBolus] that has not ended ends the
     *   same way, and is given back as [DashResolvedCommand.endedBolus]. Deliberate difference from
     *   Android, which does not end the bolus there: the pod stops all delivery, so a running bolus
     *   stops too, and the next status (0 left) would then count all of it. After a deactivation
     *   Android resets the state and keeps the whole bolus as it was recorded when it started. A
     *   bolus that already ended is not changed and not given back.
     * - BASAL_PROGRAM, SUCCESS: the program of the command becomes the running [basalProgram] (Android
     *   SET_BASAL_PROFILE, `podStateManager.basalProgram = command.basalProgram`). Any other outcome
     *   keeps the program before.
     * - RESUME_DELIVERY, SUCCESS: the same. Deliberate difference from Android, which does not change
     *   `basalProgram` on a resume: when the resumed profile is not the program stored before, a
     *   later profile equal to the old program would count as set ("isThisProfileSet") while the pod
     *   runs another one, so the basal would be wrong.
     * - TEMP_BASAL, SUCCESS: the temp basal of the command becomes the [tempBasal], started at the
     *   [DashActiveCommand.createdAtMs] of the command (Android SET_TEMPORARY_BASAL,
     *   `podStateManager.tempBasal = command.tempBasal`, whose start is the time the command was made).
     * - STOP_TEMP_BASAL, SUSPEND_DELIVERY or RESUME_DELIVERY, SUCCESS: the [tempBasal] is cleared
     *   (Android CANCEL_TEMPORARY_BASAL, SUSPEND_DELIVERY, RESUME_DELIVERY). STOP_ALL and DEACTIVATE
     *   clear it too (the pod stops every delivery, the temp basal with it; Android has no STOP_ALL and
     *   resets the whole state after a deactivation).
     *
     * Deliberate difference from Android (it counts insulin wrong): a BOLUS decided SUCCESS by the
     * status of a pod that is kaput takes the bolus pulses left from that status at once. Android
     * creates the last bolus with all of it left, and its `checkPodKaput` then ends it with all of it
     * left, so a bolus the pod took and partly gave before the fault is recorded as 0 U.
     *
     * Closing the command and changing the last bolus or the basal program are one write: with two
     * writes, a crash between them would lose a program the pod runs, and `isThisProfileSet` would then
     * compare with the old one.
     *
     * Closing the command and changing the last bolus are one write. With two writes, a crash between
     * them would leave no open command and no last bolus (a bolus the pod took, counted nowhere), or a
     * closed stop with a bolus not ended (the next status, 0 left, would count all of it).
     *
     * @param nowMs the wall clock now: the start time of a new last bolus.
     * @return [DashResolvedCommand.endedBolus] is the bolus a confirmed stop ended, with the amount that
     *   went in. The caller records it again with that amount, as Android `syncBolusWithPumpId` on
     *   CANCEL_BOLUS.
     */
    fun resolveActiveCommand(nowMs: Long): DashResolvedCommand {
        val command = state.activeCommand
        val confirmation = commandConfirmation()
        if (!confirmation.isFinal) return DashResolvedCommand(confirmation, command)
        var lastBolus = state.lastBolus
        var endedBolus: DashLastBolus? = null
        var basalProgram = state.basalProgram
        var tempBasal = state.tempBasal
        if (command != null && confirmation == DashCommandConfirmation.SUCCESS) {
            when (command.kind) {
                DashCommandKind.BOLUS      -> command.requestedBolusUnits?.let {
                    val created = newLastBolus(it, nowMs, historyId = command.historyId, createdAtMs = command.createdAtMs, basalCorrection = command.basalCorrection)
                    val faultStatus = state.podStatus?.takeIf { isPodKaput }
                    lastBolus = if (faultStatus != null) updateLastBolusFromStatus(created, faultStatus.bolusPulsesRemaining) else created
                }

                DashCommandKind.TEMP_BASAL -> {
                    val rate = command.tempBasalRate
                    val minutes = command.tempBasalMinutes
                    if (rate != null && minutes != null) {
                        tempBasal = DashTempBasal(startTimeMs = command.createdAtMs ?: nowMs, rate = rate, durationInMinutes = minutes)
                    }
                }

                DashCommandKind.STOP_TEMP_BASAL -> tempBasal = null

                DashCommandKind.STOP_BOLUS -> lastBolus?.let {
                    endedBolus = if (it.deliveryComplete) it else it.copy(deliveryComplete = true)
                    lastBolus = endedBolus
                }

                DashCommandKind.SUSPEND_DELIVERY,
                DashCommandKind.STOP_ALL,
                DashCommandKind.DEACTIVATE -> {
                    lastBolus?.takeIf { !it.deliveryComplete }?.let {
                        endedBolus = it.copy(deliveryComplete = true)
                        lastBolus = endedBolus
                    }
                    tempBasal = null
                }

                DashCommandKind.BASAL_PROGRAM -> basalProgram = command.basalProgram

                DashCommandKind.RESUME_DELIVERY -> {
                    basalProgram = command.basalProgram
                    tempBasal = null
                }

                else                       -> Unit
            }
        }
        state = state.copy(activeCommand = null, lastBolus = lastBolus, basalProgram = basalProgram, tempBasal = tempBasal)
        persist()
        return DashResolvedCommand(confirmation, command, endedBolus)
    }

    /**
     * A status arrived (Android `updatePodState`, used for the alarm status): store it, and count it,
     * which orders the active command. Its arrival time is the last update, as Android
     * `lastUpdatedSystem`.
     */
    fun updateFromStatus(status: DashPodStatus) {
        state = statusApplied(status)
        persist()
    }

    /**
     * A default status arrived. Android `updateFromDefaultStatusResponse`: as [updateFromStatus], and
     * the first one also sets the activation time to its arrival time minus its minutes since
     * activation. An alarm status does not set it, as on Android.
     */
    fun updateFromDefaultStatus(status: DashPodStatus) {
        val untracked = untrackedBolusIn(status)
        val applied = statusApplied(status)
        val updated = if (untracked == null) applied else applied.copy(lastBolus = untracked, untrackedBolusToRecordAt = untracked.createdAtMs)
        state = if (updated.activationTime == null) {
            updated.copy(activationTime = status.receivedAtMs - status.minutesSinceActivation * MS_PER_MINUTE)
        } else {
            updated
        }
        persist()
    }

    /**
     * A bolus the pod reports in [status] that the app does not track, as OmnipodKit
     * `PodState.updateDeliveryStatus` ("active bolus that we aren't tracking"): it makes a dose of the
     * units still to come. Deliberate difference from Android, which ignores it, so insulin the pod
     * gives is never counted (a bolus whose answer and status were lost, a bolus from a command closed
     * as not sent). Returned as a new last bolus of the units left, started and recorded at the arrival
     * time of [status], so it is counted once and its end (a stop, a fault) corrects that one record.
     *
     * Only on a pod whose activation is completed and that runs (the prime and the cannula insertion
     * are boluses too, OmnipodKit `readyForDelivery`), with pulses left, while no bolus command is open
     * (its own status decides it) and no last bolus is still running (that one is the bolus). Once it
     * is the last bolus, a later status does not find it again, also after a restart: it is stored.
     */
    private fun untrackedBolusIn(status: DashPodStatus): DashLastBolus? {
        if (!isActivationCompleted || !status.podStatus.isRunning()) return null
        if (status.bolusPulsesRemaining <= 0) return null
        if (state.activeCommand?.kind == DashCommandKind.BOLUS) return null
        if (state.lastBolus?.deliveryComplete == false) return null
        val units = Round.roundTo(status.bolusPulsesRemaining * PodConstants.POD_PULSE_BOLUS_UNITS, PodConstants.POD_PULSE_BOLUS_UNITS)
        return newLastBolus(units, status.receivedAtMs, historyId = 0L, createdAtMs = status.receivedAtMs)
    }

    /**
     * A status applied to the state, with the basal drift tracking of Android `updatePodState`, in the
     * same order, from the state before this status:
     * - [DashPodState.basalExpected]: plus the basal expected from the last update to this status
     *   ([DashBasalDrift.integrateExpectedDelivery]). When that is unknown (no pod time zone, a part
     *   with no rate), or while it is null, it is seeded again with the basal given, once the
     *   activation is completed, as Android (the drift starts again from 0);
     * - [DashPodState.bolusPulsesDelivered]: plus the bolus part of the new pulses while a bolus that is
     *   not a running basal correction ([basalCorrectionInProgress]) has not ended
     *   ([DashBasalDrift.calculateBolusPulseIncrease]); null is seeded with the total pulses of this
     *   status, once the activation is completed.
     */
    private fun statusApplied(status: DashPodStatus): DashPodState {
        val now = status.receivedAtMs
        val previousBolusPulsesRemaining = state.lastBolus?.let {
            Round.roundTo(it.bolusUnitsRemaining / PodConstants.POD_PULSE_BOLUS_UNITS, 1.0).toInt()
        }
        val basalExpected = state.basalExpected?.let { expected ->
            DashBasalDrift.integrateExpectedDelivery(
                startTime = state.lastUpdatedSystemMs,
                endTime = now,
                timeZoneOffset = state.timeZoneOffsetMs,
                tempBasal = state.tempBasal,
                basalProgram = state.basalProgram,
                deviceTimeZone = deviceTimeZone(),
            )?.let { delta -> expected + delta }
        } ?: basalDelivered(state).takeIf { isActivationCompleted }
        val bolusPulsesDelivered = state.bolusPulsesDelivered?.let { bolus ->
            state.podStatus?.totalPulsesDelivered
                ?.takeIf { state.lastBolus?.deliveryComplete == false && !basalCorrectionInProgress }
                ?.let { previousTotalPulses ->
                    bolus + DashBasalDrift.calculateBolusPulseIncrease(
                        previousTotalPulses,
                        status.totalPulsesDelivered,
                        previousBolusPulsesRemaining,
                        status.bolusPulsesRemaining
                    )
                } ?: bolus
        } ?: status.totalPulsesDelivered.takeIf { isActivationCompleted }
        return statusOnly(status).copy(basalExpected = basalExpected, bolusPulsesDelivered = bolusPulsesDelivered)
    }

    private fun statusOnly(status: DashPodStatus): DashPodState = state.copy(
        podStatus = status,
        statusResponseCount = state.statusResponseCount + 1,
        reportedPodStatus = status.podStatus,
        lastUpdatedSystemMs = status.receivedAtMs,
        lastBolus = updateLastBolusFromStatus(state.lastBolus, status.bolusPulsesRemaining),
        pulsesRemaining = status.reservoirPulsesRemaining
            .takeIf { it < DashPodState.RESERVOIR_READING_OVER_50_UNITS } ?: state.pulsesRemaining,
    )

    // -- Last bolus (ported from OmnipodDashPodStateManagerImpl) ------------------

    /**
     * Android `createLastBolus`: the pod took a bolus of [requestedUnits]. Nothing is delivered yet,
     * so all of it is left. Replaces the bolus before.
     */
    fun createLastBolus(requestedUnits: Double, nowMs: Long, historyId: Long = 0L, createdAtMs: Long? = null, basalCorrection: Boolean = false): DashLastBolus {
        val bolus = newLastBolus(requestedUnits, nowMs, historyId, createdAtMs, basalCorrection)
        state = state.copy(lastBolus = bolus)
        persist()
        return bolus
    }

    /**
     * Android `markLastBolusComplete` and `LastBolus.markComplete`: the bolus ended, what was left at
     * the last status was not delivered. Returns the bolus, or null when there is none. A bolus that
     * already ended is returned as it is.
     */
    fun markLastBolusComplete(): DashLastBolus? {
        val bolus = state.lastBolus ?: return null
        if (bolus.deliveryComplete) return bolus
        val ended = bolus.copy(deliveryComplete = true)
        state = state.copy(lastBolus = ended)
        persist()
        return ended
    }

    /** Tests only: puts the state of a later point (an activated pod) in place without running it. */
    internal fun overwriteForTest(transform: (DashPodState) -> DashPodState) {
        state = transform(state)
        persist()
    }

    /**
     * Android `reset()`: clears all state back to defaults and persists, as after a deactivation or
     * when the user discards the pod. Nothing is kept: the key, the unique id, both sequence counters,
     * an open command, the last bolus, the basal program and the alert settings are all gone, so the
     * next pod starts from a fresh state.
     */
    fun reset() {
        state = DashPodState()
        persist()
    }

    /** Writes the state. Refused while the stored state is not known ([isStateKnown]). */
    private fun persist() {
        unreadableReason?.let { throw DashPodStateUnknownException(it) }
        store.save(state)
    }

    private companion object {

        /** A bolus the pod took: nothing delivered yet, so all of it is left. */
        fun newLastBolus(requestedUnits: Double, nowMs: Long, historyId: Long, createdAtMs: Long?, basalCorrection: Boolean = false) = DashLastBolus(
            startTimeMs = nowMs,
            requestedUnits = requestedUnits,
            bolusUnitsRemaining = requestedUnits,
            deliveryComplete = false,
            historyId = historyId,
            createdAtMs = createdAtMs,
            basalCorrection = basalCorrection,
        )

        /**
         * Android `updateLastBolusFromResponse`: the units left are the pulses left (rounded to a
         * pulse), and 0 left ends the bolus.
         *
         * Deliberate difference from Android: a bolus that already ended does not change any more.
         * Android goes on writing the units left into it, so after a stopped bolus the next status
         * (0 left, no bolus running) would make its delivered amount the whole bolus. The amount of
         * an ended bolus must stay what it was when it ended.
         */
        fun updateLastBolusFromStatus(bolus: DashLastBolus?, bolusPulsesRemaining: Int): DashLastBolus? {
            if (bolus == null || bolus.deliveryComplete) return bolus
            val remainingUnits = Round.roundTo(bolusPulsesRemaining * PodConstants.POD_PULSE_BOLUS_UNITS, PodConstants.POD_PULSE_BOLUS_UNITS)
            return bolus.copy(bolusUnitsRemaining = remainingUnits, deliveryComplete = remainingUnits == 0.0)
        }

        const val MS_PER_MINUTE = 60_000L

        /** An alarm time the pod gives when it has none (Android `pdmRef` reads the same two values). */
        const val NO_ALARM_MINUTES = 0xffff

        /**
         * [DashPodState.faultTimeMs] from the first alarm status [response], received at [nowMs]: the
         * minutes since activation of the alarm, on the clock of the pod in that same status. Null when
         * the alarm time is 0 or 0xffff (none), or later than the clock of the pod.
         */
        fun faultTimeOf(response: AlarmStatusResponse, nowMs: Long): Long? {
            val alarmMinutes = response.alarmTime.toInt() and 0xffff
            val activeMinutes = response.minutesSinceActivation.toInt() and 0xffff
            if (alarmMinutes == 0 || alarmMinutes == NO_ALARM_MINUTES || alarmMinutes > activeMinutes) return null
            return nowMs - (activeMinutes - alarmMinutes) * MS_PER_MINUTE
        }

        /** Android: 2 minutes at least between two basal corrections. */
        const val BASAL_CORRECTION_COOLDOWN_MS = 2 * 60 * 1000L

        /** Android: a zero temp basal does not block a correction within 5 minutes of a bolus. */
        const val RECENT_BOLUS_MS = 5 * 60 * 1000L
        const val MS_PER_HOUR = 3_600_000L
        const val EXPIRY_GRACE_HOURS = 8
    }

}

/**
 * What [DashPodStateManager.resolveActiveCommand] found.
 *
 * @property confirmation what is known about the command. Not final: the command stays open.
 * @property command the command, open or closed now; null when none was open.
 * @property endedBolus the bolus a confirmed stop of the bolus, suspend, stop of all delivery or deactivation ended;
 *   null otherwise.
 */
data class DashResolvedCommand(
    val confirmation: DashCommandConfirmation,
    val command: DashActiveCommand?,
    val endedBolus: DashLastBolus? = null,
)
