package app.aaps.pump.omnipod.dashctl.comm.io

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.message.MessageIO
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendErrorConfirming
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendErrorSending
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendSuccess
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.packet.PayloadSplitter
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * End to end: a real `MessageIO` on a real [DashBleLink], against [FakeDashPodCentral], a
 * simulated pod that answers with BLE events. Nothing in between is faked: the bytes the pod gets
 * are the bytes the phone wrote on the characteristics.
 *
 * Every scenario runs twice: [MessageIOOverBleLinkTest] gives the events from another thread
 * (like the CoreBluetooth queue), [MessageIOOverBleLinkInlineTest] gives them inside the call.
 *
 * "Android line N" is a line of
 * `pump/omnipod/common/src/main/kotlin/app/aaps/pump/omnipod/common/bledriver/comm/message/MessageIO.kt`.
 *
 * Note: a whole message never fits in ONE BLE packet. A message has a 16 byte header and a lone
 * first packet holds 13 bytes. The smallest case of `PayloadSplitter` ("one packet", up to 18
 * bytes) is a first packet plus one extra packet.
 */
abstract class MessageIOOverBleLinkScenarios(otherThread: Boolean) {

    protected val pod = FakeDashPodCentral(otherThread)
    protected val link = DashBleLink(pod, FakeDashPodCentral.POD_ID, SilentPodLogger).also { pod.listener = it::onEvent }
    protected val messageIO = MessageIO(SilentPodLogger, link.cmd, link.data)

    @AfterTest
    fun closePod() {
        pod.close()
    }

    /**
     * Link setup as after a real connect: [DashBleLink.prepare] must give Ready. Every scenario that
     * moves a message starts with it. The pod sends nothing on a characteristic whose indications
     * are off, so a scenario that forgets it fails.
     */
    protected fun ready() {
        assertEquals(DashLinkSetupResult.Ready, link.prepare())
        pod.log.clear()
    }

    // ---------------------------------------------------------------- link setup

    @Test
    fun `link setup runs every step against the pod`() {
        assertEquals(DashLinkSetupResult.Ready, link.prepare())
        assertEquals(
            listOf(
                "phone discoverServices",
                "phone discoverCharacteristics",
                "phone CMD 06010400001092",
                "phone enableNotify CMD indicate=true",
                "phone enableNotify DATA indicate=true"
            ),
            pod.log
        )
    }

    @Test
    fun `pod answers nothing before the link is set up`() {
        // No prepare: indications are off, so a real pod's CTS never reaches the phone.
        val result = messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR)))
        assertIs<MessageSendErrorSending>(result)
        assertEquals(listOf("phone CMD 00", "pod CMD 01 not delivered: indications off"), pod.log)
        assertTrue(pod.dataFromPhone.isEmpty())
    }

    @Test
    fun `message from the pod is lost when only CMD indications are on`() {
        link.cmd.readyToRead()
        pod.log.clear()
        pod.podSendsMessage(parityHex(VECTOR))
        assertNull(messageIO.receiveMessage())
        assertEquals(listOf("pod CMD 00", "phone CMD 01"), pod.log.take(2))
        val podData = pod.log.filter { it.startsWith("pod DATA") }
        assertTrue(podData.isNotEmpty(), "log: ${pod.log}")
        assertTrue(podData.all { it.endsWith("not delivered: indications off") }, "log: ${pod.log}")
        assertFalse("phone CMD 04" in pod.log)
    }

    // ---------------------------------------------------------------- phone sends

    @Test
    fun `message of the one packet kind arrives at the pod with the same bytes`() {
        ready()
        val msg = clearMessage(2) // 18 bytes: the largest "one packet" message
        val packets = packetsOf(msg)
        assertEquals(2, packets.size)

        assertEquals(MessageSendSuccess, messageIO.sendMessage(msg))

        // Android lines 60, 64, 76, 90: RTS, CTS, every DATA packet, SUCCESS.
        assertEquals(
            listOf("phone CMD 00", "pod CMD 01") + packets.map { "phone DATA ${it.toParityHex()}" } + "pod CMD 04",
            pod.log
        )
        assertSameMessage(msg, pod.messagesFromPhone.single())
    }

    @Test
    fun `multi packet message arrives at the pod with the same bytes`() {
        ready()
        val msg = MessagePacket.parse(parityHex(VECTOR))
        val packets = packetsOf(msg)
        assertEquals(3, packets.size) // first, middle, last

        assertEquals(MessageSendSuccess, messageIO.sendMessage(msg))

        assertEquals(packets.map { it.toParityHex() }, pod.dataFromPhone.map { it.toParityHex() })
        assertEquals(VECTOR, pod.messagesFromPhone.single().toParityHex())
        assertSameMessage(msg, pod.messagesFromPhone.single())
    }

    @Test
    fun `message with an extra packet after the last one arrives at the pod`() {
        ready()
        val msg = clearMessage(37) // 53 bytes: first, middle, last and the extra packet
        val packets = packetsOf(msg)
        assertEquals(4, packets.size)

        assertEquals(MessageSendSuccess, messageIO.sendMessage(msg))

        assertEquals(packets.map { it.toParityHex() }, pod.dataFromPhone.map { it.toParityHex() })
        assertSameMessage(msg, pod.messagesFromPhone.single())
    }

    @Test
    fun `two messages one after the other both arrive`() {
        ready()
        val first = MessagePacket.parse(parityHex(VECTOR))
        val second = clearMessage(37)
        assertEquals(MessageSendSuccess, messageIO.sendMessage(first))
        assertEquals(MessageSendSuccess, messageIO.sendMessage(second))
        assertEquals(2, pod.messagesFromPhone.size)
        assertSameMessage(first, pod.messagesFromPhone[0])
        assertSameMessage(second, pod.messagesFromPhone[1])
    }

    @Test
    fun `NACK on a middle packet sends that packet again and the message still arrives`() {
        ready()
        pod.nackOnce += 1
        val msg = MessagePacket.parse(parityHex(VECTOR))
        val packets = packetsOf(msg)

        assertEquals(MessageSendSuccess, messageIO.sendMessage(msg))

        // Android lines 187-193: the NACK is read, and packets[1] is written again.
        assertEquals(listOf(0, 1, 1, 2), pod.dataFromPhone.map { it[0].toInt() })
        assertContentEquals(packets[1], pod.dataFromPhone[2])
        assertTrue("pod CMD 0201" in pod.log, "log: ${pod.log}")
        assertSameMessage(msg, pod.messagesFromPhone.single())
    }

    @Test
    fun `SUCCESS seen by peek right after the last packet is a success`() {
        ready()
        pod.finalAnswerTiming = FakeDashPodCentral.AnswerTiming.BEFORE_CONFIRM
        val msg = MessagePacket.parse(parityHex(VECTOR))
        // Android line 198-200: SUCCESS peeked after the last packet. Line 90 then reads it.
        assertEquals(MessageSendSuccess, messageIO.sendMessage(msg))
        assertNull(link.cmd.peekCommand())
    }

    @Test
    fun `FAIL seen by peek after the last packet is an error confirming`() {
        ready()
        pod.finalAnswer = FakeDashPodCentral.FinalAnswer.FAIL
        pod.finalAnswerTiming = FakeDashPodCentral.AnswerTiming.BEFORE_CONFIRM
        // Android line 205 (unexpected command), then line 83-84 (last packet).
        val result = messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR)))
        assertIs<MessageSendErrorConfirming>(result)
        assertTrue(result.msg.contains("Received unexpected command: 05"), result.msg)
    }

    @Test
    fun `pod that never answers after the last packet is an error confirming`() {
        ready()
        pod.finalAnswer = FakeDashPodCentral.FinalAnswer.NONE
        val (result, took) = timed { messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR))) }
        // Android line 94-95, after one read timeout.
        assertIs<MessageSendErrorConfirming>(result)
        assertTrue(result.msg.startsWith("Error reading message confirmation"), result.msg)
        assertTrue(took >= 990.milliseconds && took < 3000.milliseconds, "took $took")
    }

    @Test
    fun `pod never sends CTS`() {
        ready()
        pod.answerCts = false
        val (result, took) = timed { messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR))) }
        // Android lines 64-66: one read timeout, then an error, and no DATA is written.
        assertIs<MessageSendErrorSending>(result)
        assertTrue(pod.dataFromPhone.isEmpty())
        assertEquals(listOf("phone CMD 00"), pod.log)
        assertTrue(took >= 990.milliseconds && took < 3000.milliseconds, "took $took")
    }

    @Test
    fun `pod disconnects in the middle of a message from the phone`() {
        ready()
        pod.disconnectOnWrite = { uuid, data -> sameUuid(uuid, FakeDashPodCentral.DATA) && data[0] == 1.toByte() }
        val (result, took) = timed { messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR))) }

        // The write of packet 1 is never confirmed: the lost link ends the wait at once
        // (Android line 178: not the last packet, so an error sending).
        assertIs<MessageSendErrorSending>(result)
        assertTrue(link.isLinkLost)
        assertEquals(listOf(0), pod.dataFromPhone.map { it[0].toInt() })
        assertTrue(took < QUICK, "took $took")
        assertTrue(pod.messagesFromPhone.isEmpty())
    }

    @Test
    fun `pod disconnects instead of answering RTS`() {
        ready()
        pod.disconnectOnWrite = { uuid, data -> sameUuid(uuid, FakeDashPodCentral.CMD) && data[0] == 0.toByte() }
        val (result, took) = timed { messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR))) }
        // Android line 61: the RTS write was started, so it is not an error sending. Line 64-66:
        // the CTS wait ends at once on the lost link.
        assertIs<MessageSendErrorSending>(result)
        assertTrue(took < QUICK, "took $took")
        assertTrue(pod.dataFromPhone.isEmpty())
    }

    @Test
    fun `no write after the link is lost`() {
        ready()
        pod.disconnect()
        val (result, took) = timed { messageIO.sendMessage(clearMessage(2)) }
        // Android lines 60-62: the RTS write cannot start.
        assertIs<MessageSendErrorSending>(result)
        assertTrue(took < QUICK, "took $took")
        assertEquals(listOf("link lost"), pod.log)
    }

    // ---------------------------------------------------------------- pod sends

    @Test
    fun `message from the pod arrives at the phone`() {
        ready()
        pod.podSendsMessage(parityHex(VECTOR))

        val received = assertNotNull(messageIO.receiveMessage())

        assertEquals(VECTOR, received.asByteArray().toParityHex())
        assertEquals(MessageType.ENCRYPTED, received.type)
        assertEquals(Id.fromLong(136326824), received.source)
        assertEquals(Id.fromLong(136326825), received.destination)
        assertEquals(7.toByte(), received.sequenceNumber)
        // Android lines 112, 119, 154: RTS read, CTS written, SUCCESS written at the end.
        val packets = PayloadSplitter(parityHex(VECTOR)).splitInPackets().map { "pod DATA ${it.toByteArray().toParityHex()}" }
        assertEquals(listOf("pod CMD 00", "phone CMD 01") + packets + "phone CMD 04", pod.log)
    }

    @Test
    fun `message from the pod with an extra packet arrives at the phone`() {
        ready()
        val msg = clearMessage(37)
        pod.podSendsMessage(msg.asByteArray())
        val received = assertNotNull(messageIO.receiveMessage())
        assertContentEquals(msg.asByteArray(), received.asByteArray())
    }

    @Test
    fun `pod loses a middle packet on the way and the phone gets it with a NACK`() {
        ready()
        pod.dropToPhoneOnce += 1
        pod.podSendsMessage(parityHex(VECTOR))

        val received = assertNotNull(messageIO.receiveMessage())

        // Android lines 233-234: packet 2 came while 1 was expected, so the phone keeps 2 and
        // sends NACK 1. The pod sends 1 again. Line 212-213 then takes 2 from the kept packets.
        assertEquals(VECTOR, received.asByteArray().toParityHex())
        assertTrue("phone CMD 0201" in pod.log, "log: ${pod.log}")
        assertEquals("phone CMD 04", pod.log.last())
    }

    @Test
    fun `pod disconnects in the middle of a message to the phone`() {
        ready()
        pod.disconnectAfterPacketsToPhone = 1
        pod.podSendsMessage(parityHex(VECTOR))

        val (received, took) = timed { messageIO.receiveMessage() }

        // Without the lost link, Android lines 216-219 would wait up to 3 read timeouts of 1 s.
        assertNull(received)
        assertTrue(took < QUICK, "took $took")
        assertFalse("phone CMD 04" in pod.log)
    }

    // ---------------------------------------------------------------- helpers

    protected fun clearMessage(payloadSize: Int) = MessagePacket(
        type = MessageType.CLEAR,
        source = Id.fromLong(136326824),
        destination = Id.fromLong(136326825),
        payload = ByteArray(payloadSize) { (it * 7 + 3).toByte() },
        sequenceNumber = 5
    )

    protected fun packetsOf(msg: MessagePacket): List<ByteArray> =
        PayloadSplitter(msg.asByteArray()).splitInPackets().map { it.toByteArray() }

    /** What the pod received is exactly the message: same bytes, and it parses to the same fields. */
    protected fun assertSameMessage(sent: MessagePacket, received: ByteArray) {
        assertEquals(sent.asByteArray().toParityHex(), received.toParityHex())
        val parsed = MessagePacket.parse(received)
        assertEquals(sent.type, parsed.type)
        assertEquals(sent.source, parsed.source)
        assertEquals(sent.destination, parsed.destination)
        assertEquals(sent.sequenceNumber, parsed.sequenceNumber)
        assertEquals(sent.ackNumber, parsed.ackNumber)
        assertContentEquals(sent.payload, parsed.payload)
    }

    protected fun <T> timed(block: () -> T): Pair<T, Duration> {
        val mark = TimeSource.Monotonic.markNow()
        val result = block()
        return result to mark.elapsedNow()
    }

    companion object {

        /** The Android `MessagePacketTest` vector: an ENCRYPTED message of 50 bytes, 3 BLE packets. */
        const val VECTOR =
            "545711010700034008202ea808202ea9ab35d831609bb8fe3a3bde5b1837249a16dbf8e4d305e975dc817c3707cc415faf8a"

        /** Well under one read timeout (1 s): the call did not wait for any timeout. */
        val QUICK = 700.milliseconds
    }
}

/** The scenarios with events given from another thread, like the CoreBluetooth queue. */
class MessageIOOverBleLinkTest : MessageIOOverBleLinkScenarios(otherThread = true) {

    @Test
    fun `SUCCESS that comes after peek is read by the confirmation wait`() {
        ready()
        pod.finalAnswerTiming = FakeDashPodCentral.AnswerTiming.AFTER_CONFIRM_LATE
        // Android line 183 finds nothing, line 90-92 then waits and reads SUCCESS.
        assertEquals(MessageSendSuccess, messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR))))
        assertSameMessage(MessagePacket.parse(parityHex(VECTOR)), pod.messagesFromPhone.single())
    }

    @Test
    fun `FAIL that comes after peek is an error sending`() {
        ready()
        pod.finalAnswer = FakeDashPodCentral.FinalAnswer.FAIL
        pod.finalAnswerTiming = FakeDashPodCentral.AnswerTiming.AFTER_CONFIRM_LATE
        // Android lines 97-101: FAIL means the pod's CRC check failed.
        val result = messageIO.sendMessage(MessagePacket.parse(parityHex(VECTOR)))
        assertIs<MessageSendErrorSending>(result)
        assertEquals("Received FAIL after sending message", result.msg)
    }
}

/** The same scenarios with every event given inside the call, on the caller's thread. */
class MessageIOOverBleLinkInlineTest : MessageIOOverBleLinkScenarios(otherThread = false)
