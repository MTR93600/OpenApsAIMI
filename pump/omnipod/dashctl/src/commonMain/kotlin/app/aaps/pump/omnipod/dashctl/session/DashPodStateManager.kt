package app.aaps.pump.omnipod.dashctl.session

import app.aaps.core.interfaces.utils.Round
import app.aaps.pump.omnipod.dashctl.comm.session.EapSqn
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion
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
 *   a confirmed basal program becomes the running [basalProgram].
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

    /** Android `lastBolus`: see [DashLastBolus]. */
    val lastBolus: DashLastBolus?
        get() = state.lastBolus

    /** Android `basalProgram`: the basal program the pod confirmed last, see [DashPodState.basalProgram]. */
    val basalProgram: BasalProgram?
        get() = state.basalProgram

    /** Android `isActivationCompleted`: the activation reached [ActivationProgress.COMPLETED]. */
    val isActivationCompleted: Boolean
        get() = state.activationProgress == ActivationProgress.COMPLETED

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
     * - SUSPEND_DELIVERY or STOP_ALL, SUCCESS: a [lastBolus] that has not ended ends the same way, and
     *   is given back as [DashResolvedCommand.endedBolus]. Deliberate difference from Android, which
     *   does not end the bolus there: the pod stops all delivery, so a running bolus stops too, and
     *   the next status (0 left) would then count all of it. A bolus that already ended is not
     *   changed and not given back.
     * - BASAL_PROGRAM, SUCCESS: the program of the command becomes the running [basalProgram] (Android
     *   SET_BASAL_PROFILE, `podStateManager.basalProgram = command.basalProgram`). Any other outcome
     *   keeps the program before.
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
        if (command != null && confirmation == DashCommandConfirmation.SUCCESS) {
            when (command.kind) {
                DashCommandKind.BOLUS      -> command.requestedBolusUnits?.let {
                    lastBolus = newLastBolus(it, nowMs, historyId = command.historyId, createdAtMs = command.createdAtMs)
                }

                DashCommandKind.STOP_BOLUS -> lastBolus?.let {
                    endedBolus = if (it.deliveryComplete) it else it.copy(deliveryComplete = true)
                    lastBolus = endedBolus
                }

                DashCommandKind.SUSPEND_DELIVERY,
                DashCommandKind.STOP_ALL   -> lastBolus?.takeIf { !it.deliveryComplete }?.let {
                    endedBolus = it.copy(deliveryComplete = true)
                    lastBolus = endedBolus
                }

                DashCommandKind.BASAL_PROGRAM -> basalProgram = command.basalProgram

                else                       -> Unit
            }
        }
        state = state.copy(activeCommand = null, lastBolus = lastBolus, basalProgram = basalProgram)
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

    /** Clears all state back to defaults and persists. */
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
 * @property endedBolus the bolus a confirmed stop of the bolus, suspend or stop of all delivery ended;
 *   null otherwise.
 */
data class DashResolvedCommand(
    val confirmation: DashCommandConfirmation,
    val command: DashActiveCommand?,
    val endedBolus: DashLastBolus? = null,
)
