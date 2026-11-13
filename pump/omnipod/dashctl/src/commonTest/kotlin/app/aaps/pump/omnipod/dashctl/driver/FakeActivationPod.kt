package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.comm.session.ResponseUtil
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.Command
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateCodec
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore

/** The app stops at once: thrown by [CrashingStore]. Not an `Exception`, so nothing in the driver catches it. */
class SimulatedCrash : Error("simulated crash")

/**
 * Keeps the state as the stored bytes ([DashPodStateCodec]), as the Keychain does. After
 * [crashAfterSaves] saves (null: never) the next save is stored and then the app "stops"
 * ([SimulatedCrash]): what is in [bytes] is what a restart finds.
 */
class CrashingStore(var crashAfterSaves: Int? = null) : DashPodStateStore {

    var bytes: ByteArray? = null
    var saves = 0

    /** Each activation progress written, once per change, in order. */
    val progressHistory = mutableListOf<ActivationProgress>()

    override fun load(): DashPodState? = bytes?.let { DashPodStateCodec.decode(it) }

    override fun save(state: DashPodState) {
        bytes = DashPodStateCodec.encode(state)
        saves++
        if (progressHistory.lastOrNull() != state.activationProgress) progressHistory += state.activationProgress
        if (crashAfterSaves == saves) throw SimulatedCrash()
    }

    override fun clear() {
        bytes = null
    }
}

/**
 * [DashActivationPod] on [ActivatingPodModel], without BLE: the command is built with the stored unique
 * id and 4-bit sequence, the pod takes it, then the state counts the send and the answer as
 * `IosDashPump.sendCommand` does (`PodEventInterceptor`). The pod takes the command before the phone
 * stores anything after the send, as on a real link: a crash there leaves a pod ahead of the phone.
 */
class FakeActivationPod(
    var state: DashPodStateManager,
    val model: ActivatingPodModel,
    private val nowMs: () -> Long,
) : DashActivationPod {

    /** Every command that reached the pod, encoded, in order. */
    val sent = mutableListOf<ByteArray>()

    /** The pod takes the next command that matches, but its answer is lost. Cleared when used. */
    var loseAnswerOf: ((ByteArray) -> Boolean)? = null

    /** The next command that matches never reaches the pod (the write fails). Cleared when used. */
    var failBeforeRadioOf: ((ByteArray) -> Boolean)? = null

    /** No status can be read: every GetStatus fails before the radio. */
    var noStatus = false

    /**
     * The pod answers every GetStatus with a NAK (Android `NakResponseTest`, ILLEGAL_PARAM), for
     * example after a lost answer left the message sequence out of step. A NAK is an answer, so the
     * exchange has no error, but it carries no pod status.
     */
    var nakStatus = false

    override fun send(build: (uniqueId: Int, sequence: Short) -> Command, beforeSend: () -> Unit): DashExchange {
        val uniqueId = state.uniqueId ?: return DashExchange(false, "no unique id")
        val command = build(uniqueId.toInt(), state.messageSequenceNumber.toShort())
        beforeSend()
        val bytes = command.encoded
        if (noStatus && bytes[6] == STATUS) return DashExchange(false, "status not sent")
        failBeforeRadioOf?.let {
            if (it(bytes)) {
                failBeforeRadioOf = null
                return DashExchange(false, "could not send the command")
            }
        }
        sent += bytes
        val answer = if (nakStatus && bytes[6] == STATUS) parityHex(NAK_RESPONSE) else model.answer(bytes)
        state.increaseMessageSequenceNumber()
        loseAnswerOf?.let {
            if (it(bytes)) {
                loseAnswerOf = null
                return DashExchange(true, "answer lost")
            }
        }
        state.increaseMessageSequenceNumber()
        state.updateFromResponse(ResponseUtil.parseResponse(answer), nowMs())
        return DashExchange(true, null)
    }

    /** The command types the pod got. */
    fun types(): List<Byte> = sent.map { it[6] }

    companion object {

        private const val STATUS: Byte = 0x0e

        /** Android `NakResponseTest`: ILLEGAL_PARAM. */
        const val NAK_RESPONSE = "0603070009"

        /** A bolus: 0x1a (program insulin), then 0x17 after the 0x1a part. */
        fun isBolus(command: ByteArray): Boolean =
            command[6] == 0x1a.toByte() && command[8 + (command[7].toInt() and 0xff)] == 0x17.toByte()
    }
}
