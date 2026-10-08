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
    /** BLE message sequence number, 4-bit (0-15). */
    val messageSequenceNumber: Int = 0,
    /** Pod unique id, set once the pod is paired. */
    val uniqueId: Long? = null,
    val activationProgress: ActivationProgress = ActivationProgress.NOT_STARTED,
    val bluetoothAddress: String? = null
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
            bluetoothAddress == other.bluetoothAddress
    }

    override fun hashCode(): Int {
        var result = ltk.contentHashCode()
        result = 31 * result + eapAkaSequenceNumber.hashCode()
        result = 31 * result + messageSequenceNumber
        result = 31 * result + (uniqueId?.hashCode() ?: 0)
        result = 31 * result + activationProgress.hashCode()
        result = 31 * result + (bluetoothAddress?.hashCode() ?: 0)
        return result
    }

    companion object {
        const val LTK_SIZE = 16
        const val MESSAGE_SEQ_MAX = 15
    }
}
