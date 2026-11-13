package app.aaps.pump.omnipod.dashctl.session

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
class DashPodStateManager(private val store: DashPodStateStore) {

    private var state: DashPodState = store.load() ?: DashPodState()

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
        state = if (updated.alarmType == null) updated.copy(alarmType = response.alarmType) else updated
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

    // -- Alerts (ported from OmnipodDashPodStateManagerImpl) ----------------------

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
        DashCommandConfirmation.of(state.activeCommand, state.statusResponseCount, state.podStatus?.lastProgrammingCommandSequence)

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
                    val created = newLastBolus(it, nowMs, historyId = command.historyId, createdAtMs = command.createdAtMs)
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
        val updated = statusApplied(status)
        state = if (updated.activationTime == null) {
            updated.copy(activationTime = status.receivedAtMs - status.minutesSinceActivation * MS_PER_MINUTE)
        } else {
            updated
        }
        persist()
    }

    private fun statusApplied(status: DashPodStatus): DashPodState = state.copy(
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
    fun createLastBolus(requestedUnits: Double, nowMs: Long, historyId: Long = 0L, createdAtMs: Long? = null): DashLastBolus {
        val bolus = newLastBolus(requestedUnits, nowMs, historyId, createdAtMs)
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

    private fun persist() {
        store.save(state)
    }

    private companion object {

        /** A bolus the pod took: nothing delivered yet, so all of it is left. */
        fun newLastBolus(requestedUnits: Double, nowMs: Long, historyId: Long, createdAtMs: Long?) = DashLastBolus(
            startTimeMs = nowMs,
            requestedUnits = requestedUnits,
            bolusUnitsRemaining = requestedUnits,
            deliveryComplete = false,
            historyId = historyId,
            createdAtMs = createdAtMs,
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
