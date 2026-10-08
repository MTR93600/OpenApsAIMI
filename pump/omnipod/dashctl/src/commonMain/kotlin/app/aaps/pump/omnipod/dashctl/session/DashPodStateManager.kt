package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress

/**
 * Owns the [DashPodState] and persists it through [DashPodStateStore].
 *
 * Ported from the Android `OmnipodDashPodStateManagerImpl` (sequence-number parts):
 * - [increaseMessageSequenceNumber] wraps the 4-bit counter and persists.
 * - [seedMessageSequenceNumber] sets the 4-bit counter (D4 seeds it from
 *   `pairResult.msgSeq` after a fresh pairing, like the original) and persists.
 * - [increaseEapAkaSequenceNumber] increments the EAP-AKA SQN and returns it as
 *   6 big-endian bytes, without persisting (same as the original).
 * - [commitEapAkaSequenceNumber] persists after a successful session establishment.
 * - [updateFromPairing] resets the EAP-AKA SQN to 1 and stores the LTK.
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

    /** Full state snapshot; the LTK is defensively copied. */
    fun currentState(): DashPodState = state.copy(ltk = state.ltk?.copyOf())

    /**
     * Advances the 4-bit BLE message sequence number ((n + 1) and 0x0f)
     * and persists the state.
     */
    fun increaseMessageSequenceNumber() {
        state = state.copy(
            messageSequenceNumber = (state.messageSequenceNumber + 1) and DashPodState.MESSAGE_SEQ_MAX
        )
        persist()
    }

    /**
     * Seeds the 4-bit BLE message sequence number and persists the state.
     * The value is masked to the counter range (0..15).
     *
     * D4 must call this with `pairResult.msgSeq` after a fresh pairing, before
     * the first [DashSessionEstablisher.negotiateSessionKeys] call — mirroring
     * the original, which seeded the establishment's `msgSeq` from
     * `pairResult.msgSeq` in `OmnipodDashBleManagerImpl`. For a reconnect (no
     * fresh pairing), the persisted counter is used as-is.
     */
    fun seedMessageSequenceNumber(value: Int) {
        state = state.copy(messageSequenceNumber = value and DashPodState.MESSAGE_SEQ_MAX)
        persist()
    }

    /**
     * Increments the EAP-AKA sequence number and returns it as 6 big-endian bytes
     * (the `EapSqn` wire format). Does not persist; call [commitEapAkaSequenceNumber]
     * once the session is established.
     */
    fun increaseEapAkaSequenceNumber(): ByteArray {
        state = state.copy(eapAkaSequenceNumber = state.eapAkaSequenceNumber + 1)
        return eapSqnBytes(state.eapAkaSequenceNumber)
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

    /** Clears all state back to defaults and persists. */
    fun reset() {
        state = DashPodState()
        persist()
    }

    private fun persist() {
        store.save(state)
    }

    companion object {

        const val EAP_SQN_SIZE = 6

        /**
         * 6-byte big-endian encoding of an EAP-AKA sequence number,
         * matching the Android `EapSqn.fromLong` (low 6 bytes of the BE long).
         */
        fun eapSqnBytes(value: Long): ByteArray =
            ByteArray(EAP_SQN_SIZE) { i ->
                ((value ushr (8 * (EAP_SQN_SIZE - 1 - i))) and 0xff).toByte()
            }
    }
}
