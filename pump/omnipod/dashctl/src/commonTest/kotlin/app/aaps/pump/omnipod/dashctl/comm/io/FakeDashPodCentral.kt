package app.aaps.pump.omnipod.dashctl.comm.io

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandAbort
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandCTS
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandFail
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandNack
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandRTS
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandSuccess
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandType
import app.aaps.pump.omnipod.dashctl.comm.message.CrcMismatchException
import app.aaps.pump.omnipod.dashctl.comm.message.IncorrectPacketException
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadJoiner
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadSplitter
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A [BleCentral] with a simulated Dash pod behind it. It plays the pod side of the Android
 * `MessageIO`, at the level of BLE events, so a real [DashBleLink] and a real `MessageIO` can be
 * tested together.
 *
 * Every pod step below is the mirror of a phone step in the Android source
 * `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/message/MessageIO.kt`
 * ("Android line N" in the comments): the pod receives a message the way the phone does in
 * `receiveMessage`, and sends one the way the phone does in `sendMessage`.
 *
 * Pod receives a message from the phone:
 * - phone writes RTS on CMD: the pod answers CTS on CMD (unless [answerCts] is false);
 * - phone writes DATA packets: the pod joins them with [PayloadJoiner];
 * - after the last packet the pod answers [finalAnswer]: SUCCESS, FAIL, or nothing. A bad packet
 *   gives ABORT. A packet listed in [nackOnce] is treated as lost the first time: the pod does not
 *   keep it and answers NACK for it.
 *
 * Pod sends a message to the phone ([podSendsMessage]): RTS on CMD, wait for the phone's CTS, send
 * the packets of [PayloadSplitter] as values on DATA, answer a phone NACK by sending that packet
 * again, and record the phone's SUCCESS (or FAIL, ABORT) in [log].
 *
 * Events: every write gets a [BleCentralEvent.Written] (status from [writeStatus], or none when it
 * gives null), every enableNotify gets a [BleCentralEvent.DescriptorWritten] for the CCC descriptor,
 * as CoreBluetoothCentral reports it. A pod value ([BleCentralEvent.Value]) is sent only on a
 * characteristic whose indications the phone turned on, like a real pod; otherwise it is only
 * written to [log] as "not delivered". With [otherThread] false, the events are given to [listener]
 * at once, inside the call, on the caller's thread. With [otherThread] true, they are given one by
 * one, in order, from one coroutine on `Dispatchers.Default` (like the CoreBluetooth serial queue),
 * after [deliveryDelayMs]. Call [close] at the end of the test.
 *
 * State: the pod state is changed only inside the calls of the link ([write], ...) and by
 * [podSendsMessage]. MessageIO makes these calls on the test thread, so the state needs no lock.
 * Only the delivery of events runs on the other thread.
 */
class FakeDashPodCentral(
    private val otherThread: Boolean,
    private val deliveryDelayMs: Long = 5
) : BleCentral {

    /** Where the events go. Set it to `link::onEvent`. */
    var listener: (BleCentralEvent) -> Unit = {}

    /** False: the pod never answers RTS with CTS. */
    var answerCts = true

    /** What the pod answers after the last DATA packet of a message from the phone. */
    var finalAnswer = FinalAnswer.SUCCESS

    /**
     * When the final answer is sent, compared to the write confirmation of the last DATA packet.
     * [AnswerTiming.AFTER_CONFIRM_LATE] needs [otherThread].
     */
    var finalAnswerTiming = AnswerTiming.AFTER_CONFIRM

    /** Indexes of phone DATA packets that the pod "loses" once and answers with NACK. */
    val nackOnce = mutableSetOf<Int>()

    /** Indexes of pod DATA packets that are "lost" once on the way to the phone (not sent). */
    val dropToPhoneOnce = mutableSetOf<Int>()

    /**
     * When set, the pod sends only this many of its DATA packets after the phone's CTS, then drops
     * the link: a pod that goes out of range in the middle of a message.
     */
    var disconnectAfterPacketsToPhone: Int? = null

    /** Status of the write confirmation, or null for no confirmation at all. */
    var writeStatus: (characteristicUuid: String, data: ByteArray) -> Int? = { _, _ -> 0 }

    /** When true for a write, the pod drops the link instead of answering it. */
    var disconnectOnWrite: (characteristicUuid: String, data: ByteArray) -> Boolean = { _, _ -> false }

    /** Phone writes and pod values, in order: "phone CMD 00", "pod DATA 0002...", "link lost". */
    val log = mutableListOf<String>()

    /** The DATA packets the phone wrote, as written (resent packets included). */
    val dataFromPhone = mutableListOf<ByteArray>()

    /** The whole messages the pod received from the phone (after the CRC check). */
    val messagesFromPhone = mutableListOf<ByteArray>()

    /** True once the link was dropped. Nothing is answered after that. */
    var disconnected = false
        private set

    /** The characteristics (CMD, DATA) whose indications the phone turned on with [enableNotify]. */
    private val indicationsOn = mutableSetOf<String>()

    private var joiner: PayloadJoiner? = null
    private var lastIndex = -1
    private var outgoing: List<ByteArray> = emptyList()

    private val outbox = Channel<Delivery>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    init {
        if (otherThread) {
            scope.launch {
                for (delivery in outbox) {
                    if (delivery.delayMs > 0) delay(delivery.delayMs)
                    listener(delivery.event)
                }
            }
        }
    }

    /** Stop the delivery thread. */
    fun close() {
        outbox.close()
        scope.cancel()
    }

    /**
     * The pod wants to send [payload] (a whole `MessagePacket.asByteArray()`). It sends RTS on CMD
     * now, like the phone in Android line 60, and the packets when the phone answers CTS.
     */
    fun podSendsMessage(payload: ByteArray) {
        outgoing = PayloadSplitter(payload).splitInPackets().map { it.toByteArray() }
        emit(listOfNotNull(podValue(CMD, BleCommandRTS.data)))
    }

    /** Drop the link now, as a pod that goes out of range. */
    fun disconnect() {
        if (disconnected) return
        disconnected = true
        log += "link lost"
        emit(listOf(BleCentralEvent.Link(POD_ID, connected = false, status = LINK_LOST_STATUS)))
    }

    // ---------------------------------------------------------------- BleCentral

    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
        if (disconnected) return false
        val type = if (sameUuid(characteristicUuid, CMD)) "CMD" else if (sameUuid(characteristicUuid, DATA)) "DATA" else "?"
        log += "phone $type ${data.toParityHex()}"
        if (disconnectOnWrite(characteristicUuid, data)) {
            disconnect()
            return true
        }
        val confirm = writeStatus(characteristicUuid, data)?.let { BleCentralEvent.Written(POD_ID, characteristicUuid, it) }
        when (type) {
            "CMD"  -> onCmdFromPhone(data, confirm)
            "DATA" -> onDataFromPhone(data, confirm)
            else   -> emit(listOfNotNull(confirm))
        }
        return true
    }

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
        if (disconnected) return false
        log += "phone enableNotify ${if (sameUuid(characteristicUuid, CMD)) "CMD" else "DATA"} indicate=$indicate"
        when {
            sameUuid(characteristicUuid, CMD)  -> indicationsOn += CMD
            sameUuid(characteristicUuid, DATA) -> indicationsOn += DATA
        }
        emit(listOf(BleCentralEvent.DescriptorWritten(POD_ID, DashBleLink.CCC_DESCRIPTOR_UUID, 0)))
        return true
    }

    override fun discoverServices(id: String) {
        if (disconnected) return
        log += "phone discoverServices"
        val service = BleGattService(DashBleLink.SERVICE_UUID, emptyList())
        emit(listOf(BleCentralEvent.Services(POD_ID, listOf(service), 0)))
    }

    override fun discoverCharacteristics(id: String, serviceUuid: String) {
        if (disconnected) return
        log += "phone discoverCharacteristics"
        val both = setOf(BleCharProperty.Write, BleCharProperty.Indicate)
        val characteristics = listOf(BleGattCharacteristic(CMD, both), BleGattCharacteristic(DATA, both))
        emit(listOf(BleCentralEvent.Characteristics(POD_ID, serviceUuid, characteristics, 0)))
    }

    override fun adapterState(): BleAdapterState = BleAdapterState.PoweredOn
    override fun startScan() = Unit
    override fun stopScan() = Unit
    override fun connect(id: String): Boolean = true
    override fun cancel(id: String) = Unit
    override fun release(id: String) = Unit
    override fun cachedName(id: String): String? = null

    // ---------------------------------------------------------------- pod logic

    private fun onCmdFromPhone(data: ByteArray, confirm: BleCentralEvent?) {
        val events = mutableListOf<BleCentralEvent>()
        confirm?.let { events += it }
        when (data.firstOrNull()) {
            // Mirror of Android line 112 then 119: the receiver reads RTS and answers CTS.
            BleCommandType.RTS.value     -> {
                joiner = null
                lastIndex = -1
                if (answerCts) podValue(CMD, BleCommandCTS.data)?.let { events += it }
            }

            // Mirror of Android lines 64 and 76: the sender waits for CTS, then writes every packet
            // on DATA. They are indications here, one value per packet.
            BleCommandType.CTS.value     -> {
                for ((index, packet) in outgoing.withIndex()) {
                    if (index == disconnectAfterPacketsToPhone) {
                        emit(events)
                        disconnect()
                        return
                    }
                    if (dropToPhoneOnce.remove(index)) {
                        log += "pod lost DATA $index"
                        continue
                    }
                    podValue(DATA, packet)?.let { events += it }
                }
            }

            // Mirror of Android lines 187-193: the sender sends the packet named by the NACK again.
            BleCommandType.NACK.value    -> {
                val index = data[1].toInt()
                outgoing.getOrNull(index)?.let { podValue(DATA, it) }?.let { events += it }
            }

            // Mirror of Android line 90: the sender waits for SUCCESS (or FAIL, ABORT). Kept in the log.
            BleCommandType.SUCCESS.value,
            BleCommandType.FAIL.value,
            BleCommandType.ABORT.value   -> outgoing = emptyList()

            // HELLO (DashBleLink.prepare) and anything else: confirmed, no answer.
            else                         -> Unit
        }
        emit(events)
    }

    private fun onDataFromPhone(data: ByteArray, confirm: BleCentralEvent?) {
        dataFromPhone += data
        val index = data.firstOrNull()?.toInt() ?: -1
        // A lost packet. The phone looks for a NACK only once, right after the write is confirmed
        // (Android line 81, then peekCommand at line 183, which does not wait). So the NACK must
        // already be in the CMD queue at that moment: it goes BEFORE the write confirmation.
        // The receiver sends this NACK in Android lines 221-222 (timeout) and 233-234 (out of order).
        if (nackOnce.remove(index)) {
            log += "pod lost DATA $index"
            emit(listOfNotNull(podValue(CMD, BleCommandNack(index.toByte()).data), confirm))
            return
        }
        val answer: ByteArray? = try {
            if (index == 0) {
                // Mirror of Android lines 127 and 132: the first packet starts the joiner.
                joiner = PayloadJoiner(data)
            } else {
                // Mirror of Android lines 142 and 151.
                (joiner ?: throw IncorrectPacketException(data, 0)).accumulate(data)
            }
            lastIndex = index
            if (isComplete()) {
                // Mirror of Android lines 153-154: CRC check, then SUCCESS.
                val message = joiner!!.finalize()
                messagesFromPhone += message
                joiner = null
                when (finalAnswer) {
                    FinalAnswer.SUCCESS -> BleCommandSuccess.data
                    FinalAnswer.FAIL    -> BleCommandFail.data
                    FinalAnswer.NONE    -> null
                }
            } else null
        } catch (e: IncorrectPacketException) {
            joiner = null
            BleCommandAbort.data // Mirror of Android line 158.
        } catch (e: CrcMismatchException) {
            joiner = null
            BleCommandFail.data // Mirror of Android line 162.
        }
        if (answer == null) {
            emit(listOfNotNull(confirm))
            return
        }
        val value = podValue(CMD, answer)
        when (finalAnswerTiming) {
            AnswerTiming.BEFORE_CONFIRM     -> emit(listOfNotNull(value, confirm))
            AnswerTiming.AFTER_CONFIRM      -> emit(listOfNotNull(confirm, value))
            AnswerTiming.AFTER_CONFIRM_LATE -> {
                check(otherThread) { "A late answer needs the delivery thread" }
                emit(listOfNotNull(confirm))
                emit(listOfNotNull(value), LATE_ANSWER_MS)
            }
        }
    }

    /**
     * True when the last packet of the message is in. Same rule as Android lines 134-152: packets
     * 1 to fullFragments, then one more when the last one says so (oneExtraPacket).
     */
    private fun isComplete(): Boolean {
        val j = joiner ?: return false
        return (lastIndex == j.fullFragments && !j.oneExtraPacket) || lastIndex == j.fullFragments + 1
    }

    /**
     * An indication from the pod, or null when the phone has not turned indications on for [uuid]:
     * a real pod (Android and CoreBluetooth alike) sends nothing then. Both cases go to [log].
     */
    private fun podValue(uuid: String, data: ByteArray): BleCentralEvent? {
        val name = if (uuid == CMD) "CMD" else "DATA"
        if (uuid !in indicationsOn) {
            log += "pod $name ${data.toParityHex()} not delivered: indications off"
            return null
        }
        log += "pod $name ${data.toParityHex()}"
        return BleCentralEvent.Value(POD_ID, uuid, data.copyOf())
    }

    private fun emit(events: List<BleCentralEvent>, extraDelayMs: Long = 0) {
        if (events.isEmpty()) return
        if (!otherThread) {
            events.forEach(listener)
            return
        }
        for ((i, event) in events.withIndex()) {
            outbox.trySend(Delivery(if (i == 0) deliveryDelayMs + extraDelayMs else 0, event))
        }
    }

    private class Delivery(val delayMs: Long, val event: BleCentralEvent)

    enum class FinalAnswer { SUCCESS, FAIL, NONE }

    enum class AnswerTiming {
        /** Before the write confirmation of the last packet: the phone sees it in peekCommand. */
        BEFORE_CONFIRM,

        /** Right after the write confirmation, in the same burst. */
        AFTER_CONFIRM,

        /** [LATE_ANSWER_MS] after the write confirmation: the phone reads it in expectCommandType. */
        AFTER_CONFIRM_LATE
    }

    companion object {

        const val POD_ID = "6E1C0D2A-4B3F-4C6D-9E8F-0A1B2C3D4E5F"
        val CMD = CharacteristicType.CMD.value
        val DATA = CharacteristicType.DATA.value
        const val LATE_ANSWER_MS = 150L
        const val LINK_LOST_STATUS = 19
    }
}

/** A logger that drops everything. */
internal object SilentPodLogger : AAPSLogger {

    override fun debug(message: String) {}
    override fun debug(enable: Boolean, tag: LTag, message: String) {}
    override fun debug(tag: LTag, message: String) {}
    override fun debug(tag: LTag, accessor: () -> String) {}
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun warn(tag: LTag, message: String) {}
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun info(tag: LTag, message: String) {}
    override fun info(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun error(tag: LTag, message: String) {}
    override fun error(tag: LTag, message: String, throwable: Throwable) {}
    override fun error(tag: LTag, format: String, vararg arguments: Any?) {}
    override fun error(message: String) {}
    override fun error(message: String, throwable: Throwable) {}
    override fun error(format: String, vararg arguments: Any?) {}
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {}
}
