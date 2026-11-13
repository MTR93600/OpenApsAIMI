package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress

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
) {

    init {
        require(messageSequenceNumber in 0..MESSAGE_SEQ_MAX) {
            "messageSequenceNumber has to be 0..$MESSAGE_SEQ_MAX"
        }
        require(ltk == null || ltk.size == LTK_SIZE) {
            "LTK has to be $LTK_SIZE bytes long"
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
            pulseRate == other.pulseRate
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
        return result
    }

    companion object {
        const val LTK_SIZE = 16
        const val MESSAGE_SEQ_MAX = 15
    }
}
