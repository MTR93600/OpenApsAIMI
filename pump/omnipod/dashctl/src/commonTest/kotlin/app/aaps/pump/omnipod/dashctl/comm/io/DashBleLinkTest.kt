package app.aaps.pump.omnipod.dashctl.comm.io

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleCharProperty
import app.aaps.core.interfaces.pump.ble.session.BleGattCharacteristic
import app.aaps.core.interfaces.pump.ble.session.BleGattService
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandCTS
import app.aaps.pump.omnipod.dashctl.comm.command.BleCommandRTS
import app.aaps.pump.omnipod.dashctl.comm.exceptions.ConnectException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Tests the [BleCharacteristicIO] contract on [DashBleLink], with a fake [BleCentral].
 *
 * The fake answers each call with events that it delivers LATER, from another thread
 * (Dispatchers.Default), the way CoreBluetooth answers on its own queue. So every wait in these
 * tests is a real wait across threads. Each call and each delivered event is written to one log,
 * in the order it happened, so the tests can check the order of calls and answers.
 */
class DashBleLinkTest {

    private val central = FakeCentral()
    private val link = DashBleLink(central, POD, NoLogger).also { central.link = it }

    // ---------------------------------------------------------------- incoming queues

    @Test
    fun `value on CMD goes to the CMD queue only`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(1, 2)))
        assertNull(link.data.receivePacket(0))
        assertContentEquals(bytes(1, 2), link.cmd.receivePacket(0))
        assertNull(link.cmd.receivePacket(0))
    }

    @Test
    fun `value on DATA goes to the DATA queue only`() {
        link.onEvent(BleCentralEvent.Value(POD, DATA, bytes(3)))
        assertNull(link.cmd.receivePacket(0))
        assertContentEquals(bytes(3), link.data.receivePacket(0))
    }

    @Test
    fun `characteristic uuid is compared without case`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD.uppercase(), bytes(7)))
        link.onEvent(BleCentralEvent.Value(POD, DATA.uppercase(), bytes(8)))
        assertContentEquals(bytes(7), link.cmd.receivePacket(0))
        assertContentEquals(bytes(8), link.data.receivePacket(0))
    }

    @Test
    fun `peripheral id in another case is ignored`() {
        // CoreBluetoothCentral looks its peripherals up with case. An id in another case would
        // get no call through, so its events must not be taken either.
        link.onEvent(BleCentralEvent.Value(POD.lowercase(), DATA, bytes(8)))
        link.onEvent(BleCentralEvent.Written(POD.lowercase(), CMD, 0))
        link.onEvent(BleCentralEvent.Link(POD.lowercase(), connected = false, status = 8))
        assertNull(link.data.receivePacket(0))
        assertFalse(link.isLinkLost)
        central.onWrite = { _, _ -> emptyList() }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `value of an unknown characteristic is ignored`() {
        link.onEvent(BleCentralEvent.Value(POD, "00002a19-0000-1000-8000-00805f9b34fb", bytes(9)))
        assertNull(link.cmd.receivePacket(0))
        assertNull(link.data.receivePacket(0))
    }

    @Test
    fun `packets come out in the order they came in`() {
        for (i in 1..5) link.onEvent(BleCentralEvent.Value(POD, DATA, bytes(i)))
        for (i in 1..5) assertContentEquals(bytes(i), link.data.receivePacket(0))
        assertNull(link.data.receivePacket(0))
    }

    @Test
    fun `bytes are stored as received`() {
        val packet = bytes(0, 0xff, 0x80, 0x7f)
        link.onEvent(BleCentralEvent.Value(POD, DATA, packet))
        assertContentEquals(bytes(0, 0xff, 0x80, 0x7f), link.data.receivePacket(0))
    }

    @Test
    fun `receivePacket returns null after its timeout`() {
        val (packet, took) = timed { link.cmd.receivePacket(150) }
        assertNull(packet)
        assertTrue(took >= 140.milliseconds, "took $took")
    }

    @Test
    fun `receivePacket returns a packet that arrives from another thread while it waits`() {
        central.later(listOf(BleCentralEvent.Value(POD, CMD, bytes(0x01))))
        val (packet, took) = timed { link.cmd.receivePacket(5000) }
        assertContentEquals(bytes(0x01), packet)
        assertTrue(took < 2000.milliseconds, "took $took")
    }

    @Test
    fun `many packets from another thread keep their order`() {
        central.later((0 until 50).map { BleCentralEvent.Value(POD, DATA, bytes(it)) })
        for (i in 0 until 50) assertContentEquals(bytes(i), link.data.receivePacket(5000))
    }

    @Test
    fun `values from another peripheral are ignored`() {
        link.onEvent(BleCentralEvent.Value(OTHER, CMD, bytes(1)))
        link.onEvent(BleCentralEvent.Value(OTHER, DATA, bytes(2)))
        assertNull(link.cmd.receivePacket(0))
        assertNull(link.data.receivePacket(0))
    }

    // ---------------------------------------------------------------- writes

    @Test
    fun `write is sent with response and confirmed`() {
        assertEquals(BleSendSuccess, link.cmd.sendAndConfirmPacket(bytes(0x00)))
        assertEquals(BleSendSuccess, link.data.sendAndConfirmPacket(bytes(0x01, 0x02)))
        assertEquals(listOf("write CMD 00 withResponse=true", "write DATA 0102 withResponse=true"), central.calls())
    }

    @Test
    fun `write that cannot be started is an error sending`() {
        central.writeStarts = false
        assertIs<BleSendErrorSending>(link.cmd.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `write that throws is an error sending`() {
        central.writeThrows = true
        assertIs<BleSendErrorSending>(link.data.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `write never confirmed is an error confirming after the default timeout`() {
        central.onWrite = { _, _ -> emptyList() }
        val (result, took) = timed { link.cmd.sendAndConfirmPacket(bytes(0x00)) }
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(took >= 990.milliseconds, "took $took")
        assertTrue(took < 3000.milliseconds, "took $took")
    }

    @Test
    fun `write confirmed with an error status is an error confirming`() {
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(POD, uuid, 133)) }
        val result = link.data.sendAndConfirmPacket(bytes(0x00))
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(result.msg.contains("133"))
    }

    @Test
    fun `old confirmation is dropped before a new write`() {
        // A confirmation with nobody waiting (Android: flushConfirmationQueue before each write).
        link.onEvent(BleCentralEvent.Written(POD, CMD, 0))
        central.onWrite = { _, _ -> emptyList() }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `confirmation for DATA does not confirm a CMD write`() {
        central.onWrite = { _, _ -> listOf(BleCentralEvent.Written(POD, DATA, 0)) }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `confirmation from another peripheral does not confirm a write`() {
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(OTHER, uuid, 0)) }
        assertIs<BleSendErrorConfirming>(link.data.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `late confirmation of a NACK does not confirm the next CMD write`() {
        // The NACK write is not confirmed within 1 s. Its confirmation comes after the next CMD
        // write has started, before the answer of that write (here an error, status 133).
        central.onWrite = { _, data ->
            if (data[0] == 0x02.toByte()) emptyList()
            else {
                central.later(listOf(BleCentralEvent.Written(POD, CMD, 0)), 30)
                central.later(listOf(BleCentralEvent.Written(POD, CMD, 133)), 200)
                emptyList()
            }
        }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x02, 0x01)))
        val result = link.cmd.sendAndConfirmPacket(bytes(0x00))
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(result.msg.contains("133"), result.msg)
    }

    @Test
    fun `late confirmation that comes before the next write is skipped`() {
        central.onWrite = { _, _ -> emptyList() }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x02, 0x01)))
        // The late confirmation comes while nothing waits for it.
        link.onEvent(BleCentralEvent.Written(POD, CMD, 0))
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(POD, uuid, 133)) }
        val result = link.cmd.sendAndConfirmPacket(bytes(0x00))
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(result.msg.contains("133"), result.msg)
        // Counted once only: the write after that is confirmed by its own answer.
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(POD, uuid, 0)) }
        assertEquals(BleSendSuccess, link.cmd.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `late CMD confirmation does not touch DATA writes`() {
        central.onWrite = { _, _ -> emptyList() }
        assertIs<BleSendErrorConfirming>(link.cmd.sendAndConfirmPacket(bytes(0x02, 0x01)))
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(POD, uuid, 0)) }
        assertEquals(BleSendSuccess, link.data.sendAndConfirmPacket(bytes(0x00)))
    }

    @Test
    fun `hello sends the Android HELLO bytes on CMD`() {
        assertEquals(BleSendSuccess, link.cmd.hello())
        assertEquals(listOf("write CMD 06010400001092 withResponse=true"), central.calls())
    }

    // ---------------------------------------------------------------- flush and peek

    @Test
    fun `flush without RTS empties the queue and returns false`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x01)))
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x04)))
        assertFalse(link.cmd.flushIncomingQueue())
        assertNull(link.cmd.receivePacket(0))
    }

    @Test
    fun `flush with RTS anywhere in the queue returns true`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x01)))
        link.onEvent(BleCentralEvent.Value(POD, CMD, BleCommandRTS.data))
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x04)))
        assertTrue(link.cmd.flushIncomingQueue())
        assertNull(link.cmd.receivePacket(0))
    }

    @Test
    fun `flush of an empty queue returns false`() {
        assertFalse(link.cmd.flushIncomingQueue())
        assertFalse(link.data.flushIncomingQueue())
    }

    @Test
    fun `empty packet in the flush is not RTS`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, ByteArray(0)))
        assertFalse(link.cmd.flushIncomingQueue())
    }

    @Test
    fun `flush of DATA also reports a first byte of zero`() {
        // Same code as Android BleIO for both characteristics. MessageIO ignores it on DATA.
        link.onEvent(BleCentralEvent.Value(POD, DATA, bytes(0x00, 0x05)))
        assertTrue(link.data.flushIncomingQueue())
        assertNull(link.data.receivePacket(0))
    }

    @Test
    fun `peekCommand does not remove the packet`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x02, 0x01)))
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x04)))
        assertContentEquals(bytes(0x02, 0x01), link.cmd.peekCommand())
        assertContentEquals(bytes(0x02, 0x01), link.cmd.peekCommand())
        assertContentEquals(bytes(0x02, 0x01), link.cmd.receivePacket(0))
        assertContentEquals(bytes(0x04), link.cmd.peekCommand())
        assertContentEquals(bytes(0x04), link.cmd.receivePacket(0))
        assertNull(link.cmd.peekCommand())
    }

    @Test
    fun `peekCommand does not wait`() {
        central.later(listOf(BleCentralEvent.Value(POD, CMD, bytes(0x04))))
        assertNull(link.cmd.peekCommand())
    }

    @Test
    fun `flush includes a packet seen by peekCommand`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, BleCommandRTS.data))
        assertContentEquals(BleCommandRTS.data, link.cmd.peekCommand())
        assertTrue(link.cmd.flushIncomingQueue())
        assertNull(link.cmd.peekCommand())
    }

    // ---------------------------------------------------------------- expectCommandType

    @Test
    fun `expectCommandType matches on the first byte`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x01, 0x99)))
        assertEquals(BleConfirmSuccess, link.cmd.expectCommandType(BleCommandCTS, 0))
        assertNull(link.cmd.receivePacket(0))
    }

    @Test
    fun `expectCommandType mismatch gives the whole packet and removes it`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x05, 0x01)))
        val result = link.cmd.expectCommandType(BleCommandCTS, 0)
        assertIs<BleConfirmIncorrectData>(result)
        assertContentEquals(bytes(0x05, 0x01), result.payload)
        assertNull(link.cmd.receivePacket(0))
    }

    @Test
    fun `expectCommandType on an empty packet is incorrect data`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, ByteArray(0)))
        assertIs<BleConfirmIncorrectData>(link.cmd.expectCommandType(BleCommandRTS, 0))
    }

    @Test
    fun `expectCommandType timeout is an error`() {
        val (result, took) = timed { link.cmd.expectCommandType(BleCommandCTS, 100) }
        assertIs<BleConfirmError>(result)
        assertTrue(took >= 90.milliseconds, "took $took")
    }

    @Test
    fun `expectCommandType takes the packet seen by peekCommand first`() {
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x01)))
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x04)))
        link.cmd.peekCommand()
        assertEquals(BleConfirmSuccess, link.cmd.expectCommandType(BleCommandCTS, 0))
    }

    // ---------------------------------------------------------------- indications

    @Test
    fun `readyToRead turns on indications and waits for the descriptor`() {
        assertEquals(BleSendSuccess, link.data.readyToRead())
        assertEquals(listOf("enableNotify DATA indicate=true"), central.calls())
    }

    @Test
    fun `readyToRead accepts the short CCC uuid`() {
        central.onEnableNotify = { listOf(BleCentralEvent.DescriptorWritten(POD, "2902", 0)) }
        assertEquals(BleSendSuccess, link.cmd.readyToRead())
    }

    @Test
    fun `readyToRead throws when indications cannot be started`() {
        central.notifyStarts = false
        assertFailsWith<ConnectException> { link.cmd.readyToRead() }
    }

    @Test
    fun `readyToRead throws on an error status`() {
        central.onEnableNotify = { listOf(BleCentralEvent.DescriptorWritten(POD, CCC, 3)) }
        assertFailsWith<ConnectException> { link.cmd.readyToRead() }
    }

    @Test
    fun `readyToRead throws when the descriptor is never confirmed`() {
        central.onEnableNotify = { emptyList() }
        val (_, took) = timed { assertFailsWith<ConnectException> { link.data.readyToRead() } }
        assertTrue(took >= 990.milliseconds, "took $took")
    }

    @Test
    fun `readyToRead ignores an old descriptor confirmation`() {
        link.onEvent(BleCentralEvent.DescriptorWritten(POD, CCC, 0))
        central.onEnableNotify = { emptyList() }
        assertFailsWith<ConnectException> { link.cmd.readyToRead() }
    }

    // ---------------------------------------------------------------- link lost

    @Test
    fun `disconnect wakes a reader at once`() {
        central.later(listOf(BleCentralEvent.Link(POD, connected = false, status = 8)))
        val (packet, took) = timed { link.cmd.receivePacket(5000) }
        assertNull(packet)
        assertTrue(took < 2000.milliseconds, "took $took")
        assertTrue(link.isLinkLost)
    }

    @Test
    fun `disconnect wakes a write waiting for its confirmation`() {
        central.onWrite = { _, _ -> listOf(BleCentralEvent.Link(POD, connected = false, status = 8)) }
        val (result, took) = timed { link.data.sendAndConfirmPacket(bytes(0x00)) }
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(took < 900.milliseconds, "took $took")
    }

    @Test
    fun `no write is started after the link is lost`() {
        link.onEvent(BleCentralEvent.Link(POD, connected = false, status = 0))
        assertIs<BleSendErrorSending>(link.cmd.sendAndConfirmPacket(bytes(0x00)))
        assertFailsWith<ConnectException> { link.cmd.readyToRead() }
        assertEquals(emptyList(), central.calls())
    }

    @Test
    fun `packets queued before the disconnect can still be read`() {
        link.onEvent(BleCentralEvent.Value(POD, DATA, bytes(1)))
        link.onEvent(BleCentralEvent.Link(POD, connected = false, status = 0))
        assertContentEquals(bytes(1), link.data.receivePacket(5000))
        assertNull(link.data.receivePacket(5000))
    }

    @Test
    fun `events after the link is lost are not taken`() {
        // The pod id is the same on every connection. Events of the next connection must not
        // reach this object, which belongs to the old one.
        link.onEvent(BleCentralEvent.Link(POD, connected = false, status = 8))
        link.onEvent(BleCentralEvent.Link(POD, connected = true, status = 0))
        link.onEvent(BleCentralEvent.Value(POD, CMD, bytes(0x00)))
        link.onEvent(BleCentralEvent.Value(POD, DATA, bytes(0x01)))
        val (packet, took) = timed { link.cmd.receivePacket(5000) }
        assertNull(packet)
        assertTrue(took < 500.milliseconds, "took $took")
        assertNull(link.data.receivePacket(0))
        assertNull(link.cmd.peekCommand())
    }

    @Test
    fun `disconnect of another peripheral is ignored`() {
        link.onEvent(BleCentralEvent.Link(OTHER, connected = false, status = 8))
        link.onEvent(BleCentralEvent.Link(POD, connected = true, status = 0))
        assertFalse(link.isLinkLost)
    }

    @Test
    fun `radio turned off ends the link`() {
        link.onEvent(BleCentralEvent.AdapterState(BleAdapterState.Unknown))
        assertFalse(link.isLinkLost)
        link.onEvent(BleCentralEvent.AdapterState(BleAdapterState.PoweredOff))
        assertTrue(link.isLinkLost)
    }

    // ---------------------------------------------------------------- prepare

    @Test
    fun `prepare runs every step in the Android order`() {
        assertEquals(DashLinkSetupResult.Ready, link.prepare(SHORT))
        assertEquals(
            listOf(
                "discoverServices",
                "event services status=0",
                "discoverCharacteristics $SERVICE",
                "event characteristics status=0",
                "write CMD 06010400001092 withResponse=true",
                "event written CMD status=0",
                "enableNotify CMD indicate=true",
                "event descriptor status=0",
                "enableNotify DATA indicate=true",
                "event descriptor status=0"
            ),
            central.log()
        )
    }

    @Test
    fun `prepare turns on DATA only after CMD is confirmed`() {
        // The CMD confirmation comes late. DATA must still wait for it.
        central.onEnableNotify = { uuid ->
            if (sameUuid(uuid, CMD)) central.later(listOf(BleCentralEvent.DescriptorWritten(POD, CCC, 0)), 300)
            else central.later(listOf(BleCentralEvent.DescriptorWritten(POD, CCC, 0)))
            emptyList()
        }
        assertEquals(DashLinkSetupResult.Ready, link.prepare(SHORT))
        val tail = central.log().dropWhile { !it.startsWith("enableNotify") }
        assertEquals(
            listOf(
                "enableNotify CMD indicate=true",
                "event descriptor status=0",
                "enableNotify DATA indicate=true",
                "event descriptor status=0"
            ),
            tail
        )
    }

    @Test
    fun `prepare accepts upper case uuids from the platform`() {
        central.onDiscoverServices = { listOf(BleCentralEvent.Services(POD, listOf(service(SERVICE.uppercase())), 0)) }
        central.onDiscoverCharacteristics = {
            listOf(BleCentralEvent.Characteristics(POD, SERVICE.uppercase(), listOf(characteristic(CMD.uppercase()), characteristic(DATA.uppercase())), 0))
        }
        assertEquals(DashLinkSetupResult.Ready, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when services never come`() {
        central.onDiscoverServices = { emptyList() }
        assertFailedAt(DashLinkSetupStep.DISCOVER_SERVICES, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when service discovery throws`() {
        central.discoverThrows = true
        assertFailedAt(DashLinkSetupStep.DISCOVER_SERVICES, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when service discovery has an error status`() {
        central.onDiscoverServices = { listOf(BleCentralEvent.Services(POD, emptyList(), 5)) }
        assertFailedAt(DashLinkSetupStep.DISCOVER_SERVICES, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when the Dash service is missing`() {
        central.onDiscoverServices = { listOf(BleCentralEvent.Services(POD, listOf(service("180a")), 0)) }
        assertFailedAt(DashLinkSetupStep.FIND_SERVICE, link.prepare(SHORT))
        assertFalse(central.calls().any { it.startsWith("discoverCharacteristics") })
    }

    @Test
    fun `prepare fails when characteristics never come`() {
        central.onDiscoverCharacteristics = { emptyList() }
        assertFailedAt(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, link.prepare(SHORT))
    }

    @Test
    fun `prepare ignores characteristics of another service`() {
        central.onDiscoverCharacteristics = {
            listOf(BleCentralEvent.Characteristics(POD, "180a", listOf(characteristic(CMD), characteristic(DATA)), 0))
        }
        assertFailedAt(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when characteristic discovery has an error status`() {
        central.onDiscoverCharacteristics = { listOf(BleCentralEvent.Characteristics(POD, it, emptyList(), 5)) }
        assertFailedAt(DashLinkSetupStep.DISCOVER_CHARACTERISTICS, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when CMD is missing`() {
        central.onDiscoverCharacteristics = { listOf(BleCentralEvent.Characteristics(POD, it, listOf(characteristic(DATA)), 0)) }
        val result = assertFailedAt(DashLinkSetupStep.FIND_CHARACTERISTICS, link.prepare(SHORT))
        assertTrue(result.reason.contains(CMD))
        assertEquals(emptyList(), central.calls().filter { it.startsWith("write") || it.startsWith("enableNotify") })
    }

    @Test
    fun `prepare fails when DATA is missing`() {
        central.onDiscoverCharacteristics = { listOf(BleCentralEvent.Characteristics(POD, it, listOf(characteristic(CMD)), 0)) }
        val result = assertFailedAt(DashLinkSetupStep.FIND_CHARACTERISTICS, link.prepare(SHORT))
        assertTrue(result.reason.contains(DATA))
    }

    @Test
    fun `prepare fails when CMD indications fail and does not touch DATA`() {
        central.onEnableNotify = { listOf(BleCentralEvent.DescriptorWritten(POD, CCC, 5)) }
        assertFailedAt(DashLinkSetupStep.ENABLE_CMD_INDICATIONS, link.prepare(SHORT))
        assertEquals(listOf("enableNotify CMD indicate=true"), central.calls().filter { it.startsWith("enableNotify") })
    }

    @Test
    fun `prepare fails when DATA indications fail`() {
        central.onEnableNotify = { uuid ->
            listOf(BleCentralEvent.DescriptorWritten(POD, CCC, if (sameUuid(uuid, DATA)) 5 else 0))
        }
        assertFailedAt(DashLinkSetupStep.ENABLE_DATA_INDICATIONS, link.prepare(SHORT))
    }

    @Test
    fun `prepare fails when DATA indications cannot be started`() {
        central.notifyStartsFor = { sameUuid(it, CMD) }
        assertFailedAt(DashLinkSetupStep.ENABLE_DATA_INDICATIONS, link.prepare(SHORT))
    }

    @Test
    fun `prepare goes on when HELLO is never confirmed like Android`() {
        central.onWrite = { _, _ -> emptyList() }
        assertEquals(DashLinkSetupResult.Ready, link.prepare(SHORT))
    }

    @Test
    fun `late HELLO confirmation during the CMD indications wait still gives Ready`() {
        // Android fails here with "Received incorrect writeConfirmation" (one shared queue).
        // On iOS the late confirmation goes to the CMD queue, so setup goes on (see the class KDoc).
        central.onWrite = { _, _ -> emptyList() }
        central.onEnableNotify = { uuid ->
            if (sameUuid(uuid, CMD)) {
                listOf(BleCentralEvent.Written(POD, CMD, 0), BleCentralEvent.DescriptorWritten(POD, CCC, 0))
            } else {
                listOf(BleCentralEvent.DescriptorWritten(POD, CCC, 0))
            }
        }
        assertEquals(DashLinkSetupResult.Ready, link.prepare(SHORT))
        // The late HELLO confirmation is skipped: the next CMD write gets its own answer.
        central.onWrite = { uuid, _ -> listOf(BleCentralEvent.Written(POD, uuid, 133)) }
        val result = link.cmd.sendAndConfirmPacket(bytes(0x00))
        assertIs<BleSendErrorConfirming>(result)
        assertTrue(result.msg.contains("133"), result.msg)
    }

    @Test
    fun `disconnect during discovery ends prepare at once`() {
        central.onDiscoverServices = { listOf(BleCentralEvent.Link(POD, connected = false, status = 19)) }
        val (result, took) = timed { link.prepare(5000) }
        assertFailedAt(DashLinkSetupStep.DISCOVER_SERVICES, result)
        assertTrue(took < 2000.milliseconds, "took $took")
    }

    @Test
    fun `prepare on a lost link starts nothing`() {
        link.onEvent(BleCentralEvent.Link(POD, connected = false, status = 0))
        assertFailedAt(DashLinkSetupStep.DISCOVER_SERVICES, link.prepare(SHORT))
        assertEquals(emptyList(), central.calls())
    }

    // ---------------------------------------------------------------- helpers

    private fun assertFailedAt(step: DashLinkSetupStep, result: DashLinkSetupResult): DashLinkSetupResult.Failed {
        assertIs<DashLinkSetupResult.Failed>(result)
        assertEquals(step, result.step, result.reason)
        return result
    }

    private fun <T> timed(block: () -> T): Pair<T, Duration> {
        val mark = TimeSource.Monotonic.markNow()
        val value = block()
        return value to mark.elapsedNow()
    }

    private companion object {

        const val POD = "0A1B2C3D-0000-4000-8000-000000000001"
        const val OTHER = "0A1B2C3D-0000-4000-8000-000000000002"
        val CMD = CharacteristicType.CMD.value
        val DATA = CharacteristicType.DATA.value
        const val SERVICE = DashBleLink.SERVICE_UUID
        const val CCC = DashBleLink.CCC_DESCRIPTOR_UUID
        const val SHORT = 300L
    }
}

private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun service(uuid: String) = BleGattService(uuid, emptyList())

private fun characteristic(uuid: String) =
    BleGattCharacteristic(uuid, setOf(BleCharProperty.Write, BleCharProperty.Indicate))

private fun shortName(uuid: String): String = when {
    sameUuid(uuid, CharacteristicType.CMD.value)  -> "CMD"
    sameUuid(uuid, CharacteristicType.DATA.value) -> "DATA"
    else                                          -> uuid
}

/**
 * A [BleCentral] that records the calls of the link and answers them later, from another thread.
 * Each `on...` function says what to answer; the defaults are a pod that answers everything well.
 */
private class FakeCentral : BleCentral {

    lateinit var link: DashBleLink

    /** Calls and delivered events, in the order they happened. A channel because two threads write it. */
    private val entries = Channel<String>(Channel.UNLIMITED)
    private val recorded = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    var writeStarts = true
    var writeThrows = false
    var notifyStarts = true
    var notifyStartsFor: (String) -> Boolean = { true }
    var discoverThrows = false

    var onWrite: (String, ByteArray) -> List<BleCentralEvent> = { uuid, _ -> listOf(BleCentralEvent.Written(POD_ID, uuid, 0)) }
    var onEnableNotify: (String) -> List<BleCentralEvent> = {
        listOf(BleCentralEvent.DescriptorWritten(POD_ID, DashBleLink.CCC_DESCRIPTOR_UUID, 0))
    }
    var onDiscoverServices: () -> List<BleCentralEvent> = {
        listOf(BleCentralEvent.Services(POD_ID, listOf(service("180a"), service(DashBleLink.SERVICE_UUID)), 0))
    }
    var onDiscoverCharacteristics: (String) -> List<BleCentralEvent> = { serviceUuid ->
        listOf(
            BleCentralEvent.Characteristics(
                POD_ID,
                serviceUuid,
                listOf(characteristic(CharacteristicType.CMD.value), characteristic(CharacteristicType.DATA.value)),
                0
            )
        )
    }

    /** Deliver [events] to the link after [delayMs], on another thread. */
    fun later(events: List<BleCentralEvent>, delayMs: Long = DELIVERY_DELAY_MS) {
        if (events.isEmpty()) return
        scope.launch {
            delay(delayMs)
            for (event in events) {
                entries.trySend("event ${describe(event)}")
                link.onEvent(event)
            }
        }
    }

    /** Everything logged so far. */
    fun log(): List<String> {
        while (true) recorded += entries.tryReceive().getOrNull() ?: break
        return recorded.toList()
    }

    /** Only the calls made by the link. */
    fun calls(): List<String> = log().filterNot { it.startsWith("event") }

    private fun call(line: String) {
        entries.trySend(line)
    }

    private fun describe(event: BleCentralEvent): String = when (event) {
        is BleCentralEvent.Services          -> "services status=${event.status}"
        is BleCentralEvent.Characteristics   -> "characteristics status=${event.status}"
        is BleCentralEvent.Written           -> "written ${shortName(event.characteristicUuid)} status=${event.status}"
        is BleCentralEvent.DescriptorWritten -> "descriptor status=${event.status}"
        is BleCentralEvent.Value             -> "value ${shortName(event.characteristicUuid)} ${event.data.hex()}"
        is BleCentralEvent.Link              -> "link connected=${event.connected}"
        else                                 -> event.toString()
    }

    override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
        call("write ${shortName(characteristicUuid)} ${data.hex()} withResponse=$withResponse")
        if (writeThrows) throw IllegalStateException("central is not open")
        if (!writeStarts) return false
        later(onWrite(characteristicUuid, data))
        return true
    }

    override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
        call("enableNotify ${shortName(characteristicUuid)} indicate=$indicate")
        if (!notifyStarts || !notifyStartsFor(characteristicUuid)) return false
        later(onEnableNotify(characteristicUuid))
        return true
    }

    override fun discoverServices(id: String) {
        call("discoverServices")
        if (discoverThrows) throw IllegalStateException("central is not open")
        later(onDiscoverServices())
    }

    override fun discoverCharacteristics(id: String, serviceUuid: String) {
        call("discoverCharacteristics $serviceUuid")
        later(onDiscoverCharacteristics(serviceUuid))
    }

    override fun adapterState(): BleAdapterState = BleAdapterState.PoweredOn
    override fun startScan() = Unit
    override fun stopScan() = Unit
    override fun connect(id: String): Boolean = true
    override fun cancel(id: String) = Unit
    override fun release(id: String) = Unit
    override fun cachedName(id: String): String? = null

    companion object {

        const val POD_ID = "0A1B2C3D-0000-4000-8000-000000000001"
        const val DELIVERY_DELAY_MS = 30L
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
