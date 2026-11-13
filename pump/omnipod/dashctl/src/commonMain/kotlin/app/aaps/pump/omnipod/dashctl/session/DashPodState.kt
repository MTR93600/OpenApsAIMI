package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
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
            activationStepInFlight == other.activationStepInFlight
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
        return result
    }

    companion object {
        const val LTK_SIZE = 16
        const val MESSAGE_SEQ_MAX = 15

        /** The activation steps that send a bolus: see [activationStepInFlight]. */
        val STEPS_WITH_A_BOLUS: Set<ActivationProgress> = setOf(ActivationProgress.PRIMING, ActivationProgress.INSERTING_CANNULA)
    }
}
