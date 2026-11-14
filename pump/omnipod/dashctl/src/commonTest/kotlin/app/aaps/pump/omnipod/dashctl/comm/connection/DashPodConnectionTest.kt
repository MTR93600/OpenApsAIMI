package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.pump.omnipod.dashctl.comm.Id
import app.aaps.pump.omnipod.dashctl.comm.io.DashLinkSetupStep
import app.aaps.pump.omnipod.dashctl.comm.io.FakeDashPodCentral
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.message.MessagePacket
import app.aaps.pump.omnipod.dashctl.comm.message.MessageSendErrorSending
import app.aaps.pump.omnipod.dashctl.comm.message.MessageType
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [DashPodConnection]: one new link for each BLE connection, the session from 1 on each one, and
 * the BLE connection cancelled after a failure (Android `SharedBleManager.connect` and
 * `disconnect(false)`). The client here only records the calls; the real pump on a new link is in
 * `IosDashPumpReconnectTest` (iosTest).
 */
class DashPodConnectionTest {

    private val pod = FakeDashPod()
    private val central = PodCentralSwitch(pod)
    private val client = RecordingClient()
    private val connection = DashPodConnection(central, FakeDashPodCentral.POD_ID, SilentPodLogger, client, discoveryTimeoutMs = 200)

    init {
        pod.central.listener = connection::onEvent
    }

    @AfterTest
    fun closePod() {
        pod.close()
    }

    /** The pod goes out of range, then the phone connects to it again. */
    private fun dropAndComeBack() {
        pod.central.disconnect()
        pod.reconnect()
        pod.central.listener = connection::onEvent
    }

    @Test
    fun `a pod that is not paired gets a ready link and no session`() {
        val result = connection.connect()

        assertSame(DashConnectResult.LinkReady, result)
        assertEquals(listOf("attach 1"), client.calls)
        val link = assertNotNull(connection.link)
        assertTrue(link.isUsable)
        assertSame(link, client.attached)
        assertTrue(central.cancelled.isEmpty())
    }

    @Test
    fun `a paired pod gets a session on the new link`() {
        client.paired = true

        val result = connection.connect()

        assertSame(DashConnectResult.SessionReady, result)
        assertEquals(listOf("attach 1", "session 1"), client.calls, "the link first, then the session on it")
    }

    @Test
    fun `a usable link with a session is kept`() {
        client.paired = true
        connection.connect()
        val first = connection.link

        val result = connection.connect()

        assertSame(DashConnectResult.AlreadyConnected, result)
        assertSame(first, connection.link)
        assertEquals(listOf("attach 1", "session 1"), client.calls)
    }

    @Test
    fun `after a drop a new link and a new session are made`() {
        client.paired = true
        connection.connect()
        val first = assertNotNull(connection.link)

        pod.central.disconnect()
        assertFalse(first.isUsable, "the drop makes the old link unusable at once")

        pod.reconnect()
        pod.central.listener = connection::onEvent
        val result = connection.connect()

        assertSame(DashConnectResult.SessionReady, result)
        val second = assertNotNull(connection.link)
        assertNotSame(first, second)
        assertNotSame(first.messageIO, second.messageIO, "a new MessageIO for each connection")
        assertEquals(2, second.number)
        assertTrue(second.isUsable)
        assertFalse(first.isUsable)
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
        assertTrue(central.cancelled.isEmpty(), "a lost link needs no cancel")
    }

    @Test
    fun `a usable link without a session is replaced on the same connection`() {
        connection.connect()
        val first = assertNotNull(connection.link)

        val result = connection.connect()

        assertSame(DashConnectResult.LinkReady, result)
        assertFalse(first.isUsable, "the old link is replaced, never used again")
        assertTrue(assertNotNull(connection.link).isUsable)
        assertTrue(central.cancelled.isEmpty())
    }

    @Test
    fun `a link that cannot be set up cancels the connection`() {
        pod.central.disconnect()

        val result = connection.connect()

        val failed = assertIs<DashConnectResult.LinkFailed>(result)
        assertEquals(DashLinkSetupStep.DISCOVER_SERVICES, failed.step)
        assertEquals(listOf(FakeDashPodCentral.POD_ID), central.cancelled)
        assertTrue(client.calls.isEmpty(), "nothing is given to the pump")
    }

    @Test
    fun `a session that cannot be set up closes the link and cancels the connection`() {
        client.paired = true
        client.failSession = true

        val result = connection.connect()

        assertIs<DashConnectResult.SessionFailed>(result)
        assertFalse(assertNotNull(connection.link).isUsable)
        assertEquals(listOf(FakeDashPodCentral.POD_ID), central.cancelled)
    }

    @Test
    fun `closing an old link never cancels the newer connection`() {
        client.paired = true
        connection.connect()
        val first = assertNotNull(connection.link)
        dropAndComeBack()
        connection.connect()

        first.close("late error on the old link")

        assertTrue(central.cancelled.isEmpty())
        assertTrue(assertNotNull(connection.link).isUsable)
    }

    @Test
    fun `disconnect closes the current link and cancels the connection`() {
        client.paired = true
        connection.connect()

        connection.disconnect("user")

        assertFalse(assertNotNull(connection.link).isUsable)
        assertEquals(listOf(FakeDashPodCentral.POD_ID), central.cancelled)
        assertFalse(client.hasSession())
    }

    @Test
    fun `connect while a command holds the link lock is refused and changes nothing`() {
        // Android connect and sendCommand both take the busy flag: BusyException, never a wait.
        connection.connect()
        val first = assertNotNull(connection.link)
        val logBefore = pod.central.log.size
        client.locked = true

        val result = connection.connect()

        assertSame(DashConnectResult.Busy, result)
        assertSame(first, connection.link)
        assertTrue(first.isUsable, "the link of the running command is not replaced")
        assertEquals(logBefore, pod.central.log.size, "no second HELLO or set up on the same connection")
        assertEquals(listOf("attach 1"), client.calls)
        assertTrue(central.cancelled.isEmpty())
        assertTrue(client.locked, "the lock of the command is not taken away")
    }

    @Test
    fun `connect gives the link lock back on every path`() {
        connection.connect()
        assertFalse(client.locked)
        client.paired = true
        client.failSession = true
        connection.connect()
        assertFalse(client.locked)
        pod.central.disconnect()
        connection.connect()
        assertFalse(client.locked)
    }

    @Test
    fun `a replaced link writes nothing more to the pod`() {
        connection.connect()
        val first = assertNotNull(connection.link)
        connection.connect()
        val logBefore = pod.central.log.size

        val sent = first.messageIO.sendMessage(clearMessage())

        assertIs<MessageSendErrorSending>(sent)
        assertEquals(logBefore, pod.central.log.size, "nothing of the old link may reach the pod")
    }

    @Test
    fun `a closed link writes nothing more to the pod`() {
        connection.connect()
        val first = assertNotNull(connection.link)
        first.close("error in a command")
        val logBefore = pod.central.log.size

        val sent = first.messageIO.sendMessage(clearMessage())

        assertIs<MessageSendErrorSending>(sent)
        assertEquals(logBefore, pod.central.log.size, "nothing may be written after close")
    }

    private fun clearMessage() = MessagePacket(
        type = MessageType.CLEAR,
        source = Id.fromLong(136326824),
        destination = Id.fromLong(136326825),
        payload = ByteArray(10) { it.toByte() },
        sequenceNumber = 5
    )
}
