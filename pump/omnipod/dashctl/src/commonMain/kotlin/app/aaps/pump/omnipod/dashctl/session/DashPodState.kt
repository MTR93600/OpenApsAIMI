package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion

/**
 * Persistent state of a Dash pod pairing/session.
 *
 * Ported from the Android `OmnipodDashPodStateManagerImpl.PodState`, reduced to the
 * fields the common pairing/session logic needs. The class is immutable; the
 * [DashPodStateManager] owns the single mutable copy and persists it through
 * [DashPodStateStore].
 *
 * Byte arrays compare by content.
 */
data class DashPodState(
    /** 16-byte Long Term Key from pairing, null while unpaired. */
    val ltk: ByteArray? = null,
    /** EAP-AKA sequence number, starts at 1 after pairing. */
    val eapAkaSequenceNumber: Long = 1L,
    /**
     * The 4-bit command sequence number (0-15), Android `PodState.messageSequenceNumber`. Each pod
     * command is built with it, and the pod reports it back for the last programming command it
     * took. It goes up by one when a command is sent and by one when an answer is read. It is not
     * the outer packet counter: that one lives in the session (`SessionKeys.msgSequenceNumber`)
     * and is not stored.
     */
    val messageSequenceNumber: Int = 0,
    /** Pod unique id, set once the pod is paired. */
    val uniqueId: Long? = null,
    val activationProgress: ActivationProgress = ActivationProgress.NOT_STARTED,
    val bluetoothAddress: String? = null,
    /** The last status the pod reported, null before the first one. */
    val podStatus: DashPodStatus? = null,
    /**
     * How many status answers arrived since this state exists. It orders the events of an active
     * command: see [DashActiveCommand].
     */
    val statusResponseCount: Long = 0L,
    /** The delivery command not yet confirmed by a status, null when none is open. */
    val activeCommand: DashActiveCommand? = null,
    /**
     * The pulse rate of the pod, from its answer to SetUniqueId during activation (Android
     * `PodState.pulseRate`). Null until then. A bolus is built with it, so no bolus is sent while
     * it is null.
     */
    val pulseRate: Short? = null,

    // -- Activation fields (Android `updateFromSetUniqueIdResponse` / `updateFromVersionResponse`) --

    /** Android `PodState.primePulseRate`, from SetUniqueId. */
    val primePulseRate: Short? = null,
    /** Android `PodState.firstPrimeBolusVolume`: the clutch drive pulses, from SetUniqueId. */
    val firstPrimeBolusVolume: Short? = null,
    /** Android `PodState.secondPrimeBolusVolume`: the prime pulses, from SetUniqueId. */
    val secondPrimeBolusVolume: Short? = null,
    /** Android `PodState.podLifeInHours`, from SetUniqueId. The expiry is computed from it. */
    val podLifeInHours: Short? = null,
    /** From the Version and the SetUniqueId answers. */
    val firmwareVersion: SoftwareVersion? = null,
    /** From the Version and the SetUniqueId answers. */
    val bleVersion: SoftwareVersion? = null,
    /** From the Version and the SetUniqueId answers. */
    val lotNumber: Long? = null,
    /** From the Version and the SetUniqueId answers. */
    val podSequenceNumber: Long? = null,
    /**
     * Android `PodState.podStatus`: the pod status of the last answer that has one. Unlike
     * [podStatus], the Version and SetUniqueId answers also set it, as on Android, because they carry
     * a pod status but not the rest of a status. The activation reads it.
     */
    val reportedPodStatus: PodStatus? = null,
    /**
     * Android `PodState.activationTime`, epoch ms: set once, by the first default status, to the time
     * of that status minus its minutes since activation.
     */
    val activationTime: Long? = null,
    /**
     * Android `PodState.lastUpdatedSystem`, epoch ms: when the last status, Version or SetUniqueId
     * answer arrived. 0 before the first one. The expiry is computed from it.
     */
    val lastUpdatedSystemMs: Long = 0L,
    /**
     * Android `PodState.lastBolus`: the last bolus the pod took, kept until the next one. Null before
     * the first. See [DashLastBolus].
     */
    val lastBolus: DashLastBolus? = null,
    /**
     * Android `PodState.basalProgram`: the basal program the pod confirmed last (a SUCCESS of a
     * [DashCommandKind.BASAL_PROGRAM] command). Null before the first one. `isThisProfileSet` compares
     * a profile with it.
     */
    val basalProgram: BasalProgram? = null,
    /**
     * The activation step whose bolus may have reached the pod while its answer was not read:
     * [ActivationProgress.PRIMING] (the prime) or [ActivationProgress.INSERTING_CANNULA] (the cannula
     * insertion). Null when none. Set before the bytes go and cleared in the same write that stores
     * the step, so after a crash the activation reads a status before it sends that bolus again
     * (`DashActivation`). Android has no such field: it sends the bolus again.
     */
    val activationStepInFlight: ActivationProgress? = null,
    /**
     * Android `PodState.suspendAlertsEnabled`: set when the answer to a suspend of delivery was read
     * (the suspend also programs the "suspend in progress" alert of the pod), cleared when that alert
     * was turned off again (`disableSuspendAlerts`). Android only stores it; nothing reads it.
     */
    val suspendAlertsEnabled: Boolean = false,
    /**
     * The expiration alert settings the pod was last programmed with (Android
     * `updateExpirationAlertSettings`). Null until the first time. See [DashExpirationAlertSettings].
     */
    val expirationAlertSettings: DashExpirationAlertSettings? = null,
    /**
     * The low reservoir alert settings the pod was last programmed with (Android
     * `updateLowReservoirAlertSettings`). Null until the first time. See [DashLowReservoirAlertSettings].
     */
    val lowReservoirAlertSettings: DashLowReservoirAlertSettings? = null,
    /**
     * Android `PodState.tempBasal`: the temp basal the pod confirmed last, null when none or when a
     * cancel, a suspend or a resume was confirmed after it. See [DashTempBasal].
     */
    val tempBasal: DashTempBasal? = null,
    /**
     * Android `PodState.alarmType`: the alarm of the first alarm status the pod sent, null before
     * one. A faulted pod answers every later command with the same alarm status, so only the first
     * one is kept (Android latches it too).
     */
    val alarmType: AlarmType? = null,
    /**
     * Android `PodState.alarmSynced`: the plugin already showed and recorded the fault of [alarmType].
     * Only stored here; the plugin (phase 4) sets it.
     */
    val alarmSynced: Boolean = false,
    /**
     * Android `PodState.pulsesRemaining`: the reservoir pulses of the last status that reported less
     * than [RESERVOIR_READING_OVER_50_UNITS]. A later status with 1023 or more (more than 50 U, or the
     * unmasked 16-bit reservoir field of an alarm status) does not replace it, as Android
     * `updatePodState`. Null before such a reading. The reservoir shown and the reservoir check of a
     * bolus read it, so it must not jump back up to the 75 U default.
     */
    val pulsesRemaining: Int? = null,

    // -- Basal drift, time zone, fault reference, connection counters (step P4) ------------------

    /**
     * Android `PodState.bolusPulsesDelivered`: the pulses of all the boluses the pod gave since the
     * first status after the activation (a basal correction does not count). Null until that first
     * status, then seeded with the total pulses of it. The basal the pod gave is the total minus this.
     */
    val bolusPulsesDelivered: Int? = null,
    /**
     * Android `PodState.basalExpected`, units: the basal the pod should have given since the first
     * status after the activation, from the basal program and the temp basal in force between two
     * statuses (`DashBasalDrift.integrateExpectedDelivery`). Null until that first status, then seeded
     * with the basal given, so the drift starts at 0.
     */
    val basalExpected: Double? = null,
    /** Android `PodState.lastBasalCorrectionTime`, epoch ms: the last basal correction tried (2 minutes cooldown). */
    val lastBasalCorrectionTime: Long? = null,
    /** Android `PodState.timeZone`: the time zone id of the phone when the pod got its last basal program. */
    val timeZoneId: String? = null,
    /** Android `PodState.timeZoneOffset`, ms: the offset of [timeZoneId] from UTC at that time. */
    val timeZoneOffsetMs: Int? = null,
    /** Android `PodState.timeZoneUpdated`, epoch ms: when the time zone was stored. */
    val timeZoneUpdatedMs: Long? = null,
    /**
     * Android `PodState.pdmRef`: the PDM reference of the first alarm status
     * (`AlarmStatusResponse.pdmRef`), kept with [alarmType] and in the same write.
     */
    val pdmRef: String? = null,
    /** Android `PodState.successfulConnectionAttemptsAfterRetries`: connections to the pod that worked. */
    val successfulConnectionAttemptsAfterRetries: Int = 0,
    /** Android `PodState.failedConnectionsAfterRetries`: connections to the pod that failed after all retries. */
    val failedConnectionsAfterRetries: Int = 0,

    // -- Dose accounting of OmnipodKit (Trio), deliberate differences from Android ----------------

    /**
     * When the pod stopped delivery because of its fault, epoch ms, from the first alarm status: its
     * arrival time minus (minutes since activation - minutes since activation of the alarm). Null when
     * the pod gave no valid alarm time (0 or 0xffff, or later than its own clock), or no fault was seen.
     * Kept with [alarmType], in the same write. As OmnipodKit `setDeliveryStoppedAt(podTime:
     * faultEventTimeSinceActivation)`. Deliberate difference from Android, which writes the PUMP_SUSPEND
     * temp basal of a faulted pod at the time the fault was seen: when the phone sees the fault late, the
     * basal of all that time is counted as given (insulin counted wrong).
     */
    val faultTimeMs: Long? = null,
    /**
     * The [DashLastBolus.createdAtMs] of a bolus the pod reported that the app did not track, while it
     * is not written to `PumpSync` yet. Set in the same write that makes that bolus the [lastBolus],
     * cleared once its record was given, so a crash in between writes it after the restart (with the
     * same pump id, so `PumpSync` keeps one record). See `DashPodStateManager.updateFromDefaultStatus`.
     */
    val untrackedBolusToRecordAt: Long? = null,
    /**
     * The pod alerts the user silenced one by one on the phone (the notice or the pump screen) that the
     * pod has not silenced yet. Stored before the silence is sent, so it survives a lost link or an app
     * restart: the notice of such an alert is not shown again, and the silence is sent again at the next
     * status read. An alert leaves the set when the pod no longer reports it, or when the pod refused to
     * silence it (the notice then shows again). As OmnipodKit (Trio) `alertsWithPendingAcknowledgment`
     * and `silenceAcknowledgedAlerts`. Deliberate difference from Android, which has no single alert
     * silence: without it a silence the pod did not get is a user action lost, and the notice comes back
     * after every restart while the pod keeps beeping.
     */
    val acknowledgedAlerts: Set<AlertType> = emptySet(),
) {

    init {
        require(messageSequenceNumber in 0..MESSAGE_SEQ_MAX) {
            "messageSequenceNumber has to be 0..$MESSAGE_SEQ_MAX"
        }
        require(ltk == null || ltk.size == LTK_SIZE) {
            "LTK has to be $LTK_SIZE bytes long"
        }
        require(activationStepInFlight == null || activationStepInFlight in STEPS_WITH_A_BOLUS) {
            "Only the prime and the cannula insertion can be in flight"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DashPodState) return false
        return ltk.contentEquals(other.ltk) &&
            eapAkaSequenceNumber == other.eapAkaSequenceNumber &&
            messageSequenceNumber == other.messageSequenceNumber &&
            uniqueId == other.uniqueId &&
            activationProgress == other.activationProgress &&
            bluetoothAddress == other.bluetoothAddress &&
            podStatus == other.podStatus &&
            statusResponseCount == other.statusResponseCount &&
            activeCommand == other.activeCommand &&
            pulseRate == other.pulseRate &&
            primePulseRate == other.primePulseRate &&
            firstPrimeBolusVolume == other.firstPrimeBolusVolume &&
            secondPrimeBolusVolume == other.secondPrimeBolusVolume &&
            podLifeInHours == other.podLifeInHours &&
            firmwareVersion == other.firmwareVersion &&
            bleVersion == other.bleVersion &&
            lotNumber == other.lotNumber &&
            podSequenceNumber == other.podSequenceNumber &&
            reportedPodStatus == other.reportedPodStatus &&
            activationTime == other.activationTime &&
            lastUpdatedSystemMs == other.lastUpdatedSystemMs &&
            lastBolus == other.lastBolus &&
            basalProgram == other.basalProgram &&
            activationStepInFlight == other.activationStepInFlight &&
            suspendAlertsEnabled == other.suspendAlertsEnabled &&
            expirationAlertSettings == other.expirationAlertSettings &&
            lowReservoirAlertSettings == other.lowReservoirAlertSettings &&
            tempBasal == other.tempBasal &&
            alarmType == other.alarmType &&
            alarmSynced == other.alarmSynced &&
            pulsesRemaining == other.pulsesRemaining &&
            bolusPulsesDelivered == other.bolusPulsesDelivered &&
            basalExpected == other.basalExpected &&
            lastBasalCorrectionTime == other.lastBasalCorrectionTime &&
            timeZoneId == other.timeZoneId &&
            timeZoneOffsetMs == other.timeZoneOffsetMs &&
            timeZoneUpdatedMs == other.timeZoneUpdatedMs &&
            pdmRef == other.pdmRef &&
            successfulConnectionAttemptsAfterRetries == other.successfulConnectionAttemptsAfterRetries &&
            failedConnectionsAfterRetries == other.failedConnectionsAfterRetries &&
            faultTimeMs == other.faultTimeMs &&
            untrackedBolusToRecordAt == other.untrackedBolusToRecordAt &&
            acknowledgedAlerts == other.acknowledgedAlerts
    }

    override fun hashCode(): Int {
        var result = ltk.contentHashCode()
        result = 31 * result + eapAkaSequenceNumber.hashCode()
        result = 31 * result + messageSequenceNumber
        result = 31 * result + (uniqueId?.hashCode() ?: 0)
        result = 31 * result + activationProgress.hashCode()
        result = 31 * result + (bluetoothAddress?.hashCode() ?: 0)
        result = 31 * result + (podStatus?.hashCode() ?: 0)
        result = 31 * result + statusResponseCount.hashCode()
        result = 31 * result + (activeCommand?.hashCode() ?: 0)
        result = 31 * result + (pulseRate?.hashCode() ?: 0)
        result = 31 * result + (primePulseRate?.hashCode() ?: 0)
        result = 31 * result + (firstPrimeBolusVolume?.hashCode() ?: 0)
        result = 31 * result + (secondPrimeBolusVolume?.hashCode() ?: 0)
        result = 31 * result + (podLifeInHours?.hashCode() ?: 0)
        result = 31 * result + (firmwareVersion?.hashCode() ?: 0)
        result = 31 * result + (bleVersion?.hashCode() ?: 0)
        result = 31 * result + (lotNumber?.hashCode() ?: 0)
        result = 31 * result + (podSequenceNumber?.hashCode() ?: 0)
        result = 31 * result + (reportedPodStatus?.hashCode() ?: 0)
        result = 31 * result + (activationTime?.hashCode() ?: 0)
        result = 31 * result + lastUpdatedSystemMs.hashCode()
        result = 31 * result + (lastBolus?.hashCode() ?: 0)
        result = 31 * result + (basalProgram?.hashCode() ?: 0)
        result = 31 * result + (activationStepInFlight?.hashCode() ?: 0)
        result = 31 * result + suspendAlertsEnabled.hashCode()
        result = 31 * result + (expirationAlertSettings?.hashCode() ?: 0)
        result = 31 * result + (lowReservoirAlertSettings?.hashCode() ?: 0)
        result = 31 * result + (tempBasal?.hashCode() ?: 0)
        result = 31 * result + (alarmType?.hashCode() ?: 0)
        result = 31 * result + (pulsesRemaining ?: 0)
        result = 31 * result + alarmSynced.hashCode()
        result = 31 * result + (bolusPulsesDelivered ?: 0)
        result = 31 * result + (basalExpected?.hashCode() ?: 0)
        result = 31 * result + (lastBasalCorrectionTime?.hashCode() ?: 0)
        result = 31 * result + (timeZoneId?.hashCode() ?: 0)
        result = 31 * result + (timeZoneOffsetMs ?: 0)
        result = 31 * result + (timeZoneUpdatedMs?.hashCode() ?: 0)
        result = 31 * result + (pdmRef?.hashCode() ?: 0)
        result = 31 * result + successfulConnectionAttemptsAfterRetries
        result = 31 * result + failedConnectionsAfterRetries
        result = 31 * result + (faultTimeMs?.hashCode() ?: 0)
        result = 31 * result + (untrackedBolusToRecordAt?.hashCode() ?: 0)
        result = 31 * result + acknowledgedAlerts.hashCode()
        return result
    }

    companion object {
        const val LTK_SIZE = 16
        const val MESSAGE_SEQ_MAX = 15

        /** The reservoir reading of a pod with more than 50 U (Android `updatePodState` keeps readings below it). */
        const val RESERVOIR_READING_OVER_50_UNITS = 1023

        /** The activation steps that send a bolus: see [activationStepInFlight]. */
        val STEPS_WITH_A_BOLUS: Set<ActivationProgress> = setOf(ActivationProgress.PRIMING, ActivationProgress.INSERTING_CANNULA)
    }
}
