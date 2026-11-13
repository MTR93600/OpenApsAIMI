package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.comm.session.EapSqn
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.response.SetUniqueIdResponse

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
 * - [updateFromSetUniqueIdResponse] stores the pulse rate of the pod and its id.
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
     * The pod answered SetUniqueId during activation. Port of the Android
     * `updateFromSetUniqueIdResponse`, reduced to what the iOS driver uses today: the pulse rate (a
     * bolus is built with it) and the unique id the pod took. Android also stores the prime pulse
     * rate, the prime volumes, the pod life, the versions, the lot and the pod sequence number; they
     * come with the activation (plan, phase 2).
     */
    fun updateFromSetUniqueIdResponse(response: SetUniqueIdResponse) {
        state = state.copy(
            pulseRate = response.pumpRate,
            uniqueId = response.uniqueIdReceivedInCommand,
        )
        persist()
    }

    // -- Active command (ported from OmnipodDashPodStateManagerImpl) -------------

    /**
     * Opens the active command for a delivery command about to be built with the current message
     * sequence number. Stored before any byte goes, so a crash cannot lose it.
     *
     * @throws DashCommandNotConfirmedException while another command is open: the pod may have taken
     *   it, and sending a new one (a retried bolus) could deliver twice. Ask for a status first.
     */
    fun createActiveCommand(
        kind: DashCommandKind,
        historyId: Long,
        requestedBolusUnits: Double? = null,
        tempBasalRate: Double? = null,
        tempBasalMinutes: Int? = null,
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
     */
    fun resolveActiveCommand(): Pair<DashCommandConfirmation, DashActiveCommand?> {
        val command = state.activeCommand
        val confirmation = commandConfirmation()
        if (confirmation.isFinal) {
            state = state.copy(activeCommand = null)
            persist()
        }
        return confirmation to command
    }

    /** A default or alarm status arrived: store it, and count it, which orders the active command. */
    fun updateFromStatus(status: DashPodStatus) {
        state = state.copy(podStatus = status, statusResponseCount = state.statusResponseCount + 1)
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

}
