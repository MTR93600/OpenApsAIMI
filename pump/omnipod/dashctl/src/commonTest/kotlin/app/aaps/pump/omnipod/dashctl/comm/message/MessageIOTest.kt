package app.aaps.pump.omnipod.dashctl.comm.message

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommand
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandHello
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandRTS
import app.aaps.pump.omnipod.dashctl.comm.io.BleCharacteristicIO
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmError
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmIncorrectData
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmResult
import app.aaps.pump.omnipod.dashctl.comm.io.BleConfirmSuccess
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendResult
import app.aaps.pump.omnipod.dashctl.comm.io.BleSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.io.CmdBleIO
import app.aaps.pump.omnipod.dashctl.comm.io.DataBleIO
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadSplitter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Android has no test for MessageIO. Every expectation here is derived from the Android source
 * `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/message/MessageIO.kt`
 * ("Android line N" below), and from the legacy Android characteristic code
 * (`legacy/io/BleIO.kt`, `legacy/io/CmdBleIO.kt`) that the fakes copy.
 *
 * The fakes never block: an empty queue is a timeout at once. A packet put in a "late" list is
 * given only to a reader that waits (receivePacket), never to peekCommand. This models a packet
 * that arrives while MessageIO waits.
 */
class MessageIOTest {

    private val controllerId = Id(byteArrayOf(1, 2, 3, 4))
    private val podId = Id(byteArrayOf(5, 6, 7, 8))

    private fun message(payloadSize: Int): MessagePacket = MessagePacket(
        type = MessageType.CLEAR,
        source = controllerId,
        destination = podId,
        payload = ByteArray(payloadSize) { (it + 1).toByte() },
        sequenceNumber = 1
    )

    private fun packetsOf(msg: MessagePacket): List<ByteArray> =
        PayloadSplitter(msg.asByteArray()).splitInPackets().map { it.toByteArray() }

    // ---------------------------------------------------------------- sendMessage

    @Test
    fun `smallest message is sent as first packet plus extra packet`() {
        // A message has a 16 byte header, so it never fits the 13 bytes of a lone first packet:
        // the smallest message is the "one packet" case of PayloadSplitter (up to 18 bytes),
        // which is a FirstBlePacket plus a LastOptionalPlusOneBlePacket.
        val msg = message(0)
        assertEquals("54571000010000000102030405060708", msg.asByteArray().ioHex())
        // Computed by hand: index 0, 0 fragments, CRC32 42b40331, size 0x10, first 13 bytes;
        // then index 1, size 3, last 3 bytes, zero padding to 20 bytes.
        val p0 = "000042b403311054571000010000000102030405"
        val p1 = "0103060708000000000000000000000000000000"

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) } // RTS -> CTS
        pod.onDataWrite = { if (it.ioHex() == p1) pod.cmdQueue.addLast(byteArrayOf(0x04)) } // last -> SUCCESS

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        assertEquals(MessageSendSuccess, result)
        // Android line 60 (RTS), 76 (each DATA packet). SUCCESS is seen by peekForNack (line 197)
        // and consumed by expectCommandType (line 90).
        assertEquals(listOf("CMD 00", "DATA $p0", "DATA $p1"), pod.writes)
        assertTrue(pod.cmdQueue.isEmpty())
        // Line 64 and line 90 use the default timeout of expectCommandType.
        assertEquals(listOf(1000L, 1000L), pod.cmdReadTimeouts)
    }

    @Test
    fun `message split in several packets is sent in order`() {
        // 16 + 44 = 60 bytes: first (18) + 2 middle (19 each) + last (4). No extra packet.
        val msg = message(44)
        val packets = packetsOf(msg)
        assertEquals(4, packets.size)

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) }
        pod.onDataWrite = { if (it.contentEquals(packets.last())) pod.cmdQueue.addLast(byteArrayOf(0x04)) }

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        assertEquals(MessageSendSuccess, result)
        assertEquals(listOf("CMD 00") + packets.map { "DATA ${it.ioHex()}" }, pod.writes)
    }

    @Test
    fun `pod answers NACK on a packet and the packet is sent again`() {
        val msg = message(44)
        val packets = packetsOf(msg)
        var nackSent = false

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) }
        pod.onDataWrite = {
            if (it.contentEquals(packets[1]) && !nackSent) {
                nackSent = true
                pod.cmdQueue.addLast(byteArrayOf(0x02, 0x01)) // NACK for index 1
            }
            if (it.contentEquals(packets.last())) pod.cmdQueue.addLast(byteArrayOf(0x04))
        }

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        assertEquals(MessageSendSuccess, result)
        // Android lines 183-194: peekForNack sees the NACK, consumes it (receivePacket) and sends
        // packets[idx] again, then the loop goes on with the next packet.
        val expected = listOf("CMD 00") +
            listOf(packets[0], packets[1], packets[1], packets[2], packets[3]).map { "DATA ${it.ioHex()}" }
        assertEquals(expected, pod.writes)
        assertTrue(pod.cmdQueue.isEmpty())
    }

    @Test
    fun `pod never answers CTS`() {
        val pod = FakePod() // writes are confirmed, but the pod sends nothing back

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(message(44))

        // Android line 64-66: expectCommandType gives BleConfirmError("Error reading packet")
        // (legacy CmdBleIO.expectCommandType) and its toString is the message.
        assertEquals(MessageSendErrorSending("BleConfirmError(msg=Error reading packet)"), result)
        assertEquals(listOf("CMD 00"), pod.writes) // no DATA packet is sent
        assertEquals(listOf(1000L), pod.cmdReadTimeouts)
    }

    @Test
    fun `pod answers SUCCESS before the last packet`() {
        val msg = message(44)
        val packets = packetsOf(msg)

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) }
        pod.onDataWrite = { if (it.contentEquals(packets[0])) pod.cmdQueue.addLast(byteArrayOf(0x04)) }

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        // Android lines 197-202 then 80-84: not the last packet, so it is a sending error.
        val inner = MessageSendErrorSending("Received SUCCESS before sending all the data. 0")
        assertEquals(MessageSendErrorSending(inner.toString()), result)
        assertEquals(listOf("CMD 00", "DATA ${packets[0].ioHex()}"), pod.writes)
    }

    @Test
    fun `pod answers FAIL while MessageIO waits for SUCCESS`() {
        val msg = message(44)
        val packets = packetsOf(msg)

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) }
        // Late: not seen by peekForNack, only by expectCommandType(SUCCESS).
        pod.onDataWrite = { if (it.contentEquals(packets.last())) pod.cmdLate.addLast(byteArrayOf(0x05)) }

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        // Android lines 96-101.
        assertEquals(MessageSendErrorSending("Received FAIL after sending message"), result)
    }

    @Test
    fun `pod answers FAIL right after the last packet`() {
        val msg = message(44)
        val packets = packetsOf(msg)

        val pod = FakePod()
        pod.onCmdWrite = { if (it.ioHex() == "00") pod.cmdQueue.addLast(byteArrayOf(0x01)) }
        pod.onDataWrite = { if (it.contentEquals(packets.last())) pod.cmdQueue.addLast(byteArrayOf(0x05)) }

        val result = MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(msg)

        // Android lines 205-206 then 80-82: peekForNack sees FAIL as an unexpected command, and
        // on the last packet that becomes a confirming error (not "Received FAIL").
        val inner = MessageSendErrorSending("Received unexpected command: 05")
        assertEquals(MessageSendErrorConfirming(inner.toString()), result)
    }

    @Test
    fun `RTS from the pod while sending reads that message and throws`() {
        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00)) // the pod wants to send

        assertFailsWith<IllegalStateException> {
            MessageIO(NoLogger, pod.cmd, pod.data).sendMessage(message(0))
        }
        // Android lines 52-56: receiveMessage(false) answers CTS (line 119) without reading RTS.
        // No DATA comes, so the first packet is tried maxMessageReadTries = 3 times (line 240,
        // loop at line 216), then it gives up. No RTS of our own is sent.
        assertEquals(listOf("CMD 01"), pod.writes)
        assertEquals(3, pod.dataReads)
    }

    // ---------------------------------------------------------------- receiveMessage

    @Test
    fun `receive a message split in several packets and answer SUCCESS`() {
        // 16 + 56 = 72 bytes: first (18) + 2 middle (19) + last (14) + extra (2).
        val msg = message(56)
        val packets = packetsOf(msg)
        assertEquals(5, packets.size)

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00)) // RTS
        pod.onCmdWrite = { if (it.ioHex() == "01") packets.forEach { p -> pod.dataQueue.addLast(p) } }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        val got = assertNotNull(received)
        assertEquals(msg.asByteArray().ioHex(), got.asByteArray().ioHex())
        // Android line 112 (RTS read with 5000 ms), 119 (CTS), 154 (SUCCESS).
        assertEquals(listOf(5000L), pod.cmdReadTimeouts)
        assertEquals(listOf("CMD 01", "CMD 04"), pod.writes)
    }

    @Test
    fun `receive the smallest message`() {
        val msg = message(0)
        val packets = packetsOf(msg)
        assertEquals(2, packets.size)

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00))
        pod.onCmdWrite = { if (it.ioHex() == "01") packets.forEach { p -> pod.dataQueue.addLast(p) } }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        val got = assertNotNull(received)
        assertEquals(msg.asByteArray().ioHex(), got.asByteArray().ioHex())
        assertEquals(listOf("CMD 01", "CMD 04"), pod.writes)
    }

    @Test
    fun `receive a packet out of order sends NACK for the missing index`() {
        val msg = message(44)
        val packets = packetsOf(msg) // 4 packets, no extra

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00))
        pod.onCmdWrite = {
            if (it.ioHex() == "01") listOf(0, 2, 1, 3).forEach { i -> pod.dataQueue.addLast(packets[i]) }
        }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        val got = assertNotNull(received)
        assertEquals(msg.asByteArray().ioHex(), got.asByteArray().ioHex())
        // Android lines 233-234: packet 2 is kept for later and NACK(1) is sent. Packet 2 is
        // then taken from receivedOutOfOrder (line 209) without a new read.
        assertEquals(listOf("CMD 01", "CMD 0201", "CMD 04"), pod.writes)
        assertEquals(4, pod.dataReads)
    }

    @Test
    fun `receive with the last packet missing sends NACK on timeout`() {
        val msg = message(44)
        val packets = packetsOf(msg) // 4 packets, the last one has index 3, no extra packet

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00))
        pod.onCmdWrite = {
            when (it.ioHex()) {
                "01"   -> packets.take(3).forEach { p -> pod.dataQueue.addLast(p) }
                "0203" -> pod.dataQueue.addLast(packets[3]) // the pod sends index 3 again
            }
        }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        val got = assertNotNull(received)
        assertEquals(msg.asByteArray().ioHex(), got.asByteArray().ioHex())
        // Android line 136: the last packet (no extra packet) is read with nackOnTimeout = true,
        // so a timeout sends NACK(3) (line 222) and the read is tried again.
        assertEquals(listOf("CMD 01", "CMD 0203", "CMD 04"), pod.writes)
        assertEquals(5, pod.dataReads)
    }

    @Test
    fun `receive with a bad CRC answers FAIL`() {
        val msg = message(44)
        val packets = packetsOf(msg).toMutableList()
        packets[1] = packets[1].copyOf().also { it[5] = (it[5].toInt() xor 0xff).toByte() }

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00))
        pod.onCmdWrite = { if (it.ioHex() == "01") packets.forEach { p -> pod.dataQueue.addLast(p) } }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        // Android lines 160-163.
        assertNull(received)
        assertEquals(listOf("CMD 01", "CMD 05"), pod.writes)
    }

    @Test
    fun `receive a packet that is too short answers ABORT`() {
        val msg = message(44)
        val packets = packetsOf(msg)

        val pod = FakePod()
        pod.cmdQueue.addLast(byteArrayOf(0x00))
        pod.onCmdWrite = {
            if (it.ioHex() == "01") {
                pod.dataQueue.addLast(packets[0])
                pod.dataQueue.addLast(byteArrayOf(0x01, 0x02, 0x03)) // index 1, but not 20 bytes
            }
        }

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        // A middle packet must be 20 bytes, so the joiner throws IncorrectPacketException.
        // Android lines 156-159.
        assertNull(received)
        assertEquals(listOf("CMD 01", "CMD 03"), pod.writes)
    }

    @Test
    fun `receive when the pod never sends RTS`() {
        val pod = FakePod()

        val received = MessageIO(NoLogger, pod.cmd, pod.data).receiveMessage()

        // Android lines 112-116: wait 5000 ms for RTS, then give up without writing.
        assertNull(received)
        assertEquals(listOf(5000L), pod.cmdReadTimeouts)
        assertTrue(pod.writes.isEmpty())
    }

    @Test
    fun `fake CMD characteristic sends the Android HELLO bytes`() {
        val pod = FakePod()
        pod.cmd.hello()
        // Android legacy CmdBleIO.hello: BleCommandHello(CONTROLLER_ID = 4242).
        assertEquals(listOf("CMD 06010400001092"), pod.writes)
    }
}

// -------------------------------------------------------------------- fakes

/** Both characteristics of a scripted pod. Writes are always confirmed. */
private class FakePod {

    val cmdQueue = ArrayDeque<ByteArray>()
    val dataQueue = ArrayDeque<ByteArray>()

    /** CMD packets that arrive only while a reader waits (receivePacket), not seen by peek. */
    val cmdLate = ArrayDeque<ByteArray>()

    /** Every write, in order, as "CMD <hex>" or "DATA <hex>". */
    val writes = mutableListOf<String>()

    /** Timeout of every CMD receivePacket call, in order. */
    val cmdReadTimeouts = mutableListOf<Long>()

    /** Number of DATA receivePacket calls. */
    var dataReads = 0

    var onCmdWrite: (ByteArray) -> Unit = {}
    var onDataWrite: (ByteArray) -> Unit = {}

    val cmd = FakeCmdBleIO(this)
    val data = FakeDataBleIO(this)
}

/** Copies the Android legacy `BleIO`, with a queue that never blocks. */
private abstract class FakeCharacteristicIO(
    private val name: String,
    private val queue: ArrayDeque<ByteArray>
) : BleCharacteristicIO {

    protected abstract fun onWrite(payload: ByteArray)
    protected open fun onRead(timeoutMs: Long) {}
    protected open fun lateArrival(): ByteArray? = null

    override fun receivePacket(timeoutMs: Long): ByteArray? {
        onRead(timeoutMs)
        return queue.removeFirstOrNull() ?: lateArrival()
    }

    override fun sendAndConfirmPacket(payload: ByteArray): BleSendResult {
        record("$name ${payload.ioHex()}")
        onWrite(payload)
        return BleSendSuccess
    }

    protected abstract fun record(line: String)

    // Same as Android legacy BleIO.flushIncomingQueue.
    override fun flushIncomingQueue(): Boolean {
        var foundRTS = false
        while (true) {
            val found = queue.removeFirstOrNull() ?: break
            if (found.isNotEmpty() && found[0] == BleCommandRTS.data[0]) {
                foundRTS = true
            }
        }
        return foundRTS
    }

    override fun readyToRead(): BleSendResult = BleSendSuccess
}

private class FakeCmdBleIO(private val pod: FakePod) : FakeCharacteristicIO("CMD", pod.cmdQueue), CmdBleIO {

    override fun onWrite(payload: ByteArray) = pod.onCmdWrite(payload)
    override fun onRead(timeoutMs: Long) {
        pod.cmdReadTimeouts.add(timeoutMs)
    }

    override fun lateArrival(): ByteArray? = pod.cmdLate.removeFirstOrNull()
    override fun record(line: String) {
        pod.writes.add(line)
    }

    override fun peekCommand(): ByteArray? = pod.cmdQueue.firstOrNull()

    // Same as Android legacy CmdBleIO.hello, with CONTROLLER_ID = 4242.
    override fun hello(): BleSendResult = sendAndConfirmPacket(BleCommandHello(4242).data)

    // Same as Android legacy CmdBleIO.expectCommandType.
    override fun expectCommandType(expected: BleCommand, timeoutMs: Long): BleConfirmResult {
        return receivePacket(timeoutMs)?.let {
            if (it.isNotEmpty() && it[0] == expected.data[0])
                BleConfirmSuccess
            else
                BleConfirmIncorrectData(it)
        }
            ?: BleConfirmError("Error reading packet")
    }
}

private class FakeDataBleIO(private val pod: FakePod) : FakeCharacteristicIO("DATA", pod.dataQueue), DataBleIO {

    override fun onWrite(payload: ByteArray) = pod.onDataWrite(payload)
    override fun onRead(timeoutMs: Long) {
        pod.dataReads++
    }

    override fun record(line: String) {
        pod.writes.add(line)
    }
}

private object NoLogger : AAPSLogger {

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

private fun ByteArray.ioHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
