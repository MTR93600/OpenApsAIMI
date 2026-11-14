package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.POD_ID
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.advertisement
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
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
 * [DashHeldConnection]: the held connection of OmniBLE on a fake central that behaves as
 * CoreBluetooth ([HeldPodCentral]), with the real [DashPodConnection], `DashBleLink` and `MessageIO`
 * against the simulated pod. The pump is a [RecordingClient] (the session is a flag); the real pump
 * on a held connection is in `IosDashPumpHeldConnectionTest` (iosTest).
 *
 * The worker of the link set up is a list that the test runs ([runWorker]), as the real worker runs
 * after the radio event that started it.
 */
class DashHeldConnectionTest {

    private val pod = FakeDashPod()
    private val central = HeldPodCentral(pod)
    private val client = RecordingClient().apply { paired = true }
    private val worker = ArrayDeque<() -> Unit>()
    private var nowMs = 0L
    private val sleeps = mutableListOf<Long>()
    private var onSleep: () -> Unit = {}

    private val held = DashHeldConnection(
        central = central,
        aapsLogger = SilentPodLogger,
        client = client,
        runOnWorker = { worker.addLast(it) },
        sleep = {
            sleeps += it
            nowMs += it
            onSleep()
        },
        now = { nowMs },
        discoveryTimeoutMs = 200,
    )

    init {
        central.listener = held::onEvent
    }

    @AfterTest
    fun closePod() {
        pod.close()
    }

    private fun runWorker() {
        while (worker.isNotEmpty()) worker.removeFirst().invoke()
    }

    @Test
    fun `hold asks one connect with no timeout and each link up gets a link and a session`() {
        central.podInRange = false

        held.hold(POD_ID, "test")
        held.hold(POD_ID, "test again")

        assertEquals(listOf(POD_ID), central.connects, "one held connect, no start delay")
        assertEquals(POD_ID, central.pendingConnect)
        assertTrue(held.isConnecting)
        assertFalse(held.isHandshakeInProgress)

        central.podComesInRange()
        assertTrue(held.isHandshakeInProgress, "the link is up, the session is not set up yet")
        runWorker()

        assertEquals(listOf("attach 1", "session 1"), client.calls)
        assertTrue(client.hasSession())
        assertFalse(held.isConnecting)
        assertFalse(held.isHandshakeInProgress)
    }

    @Test
    fun `a drop makes a new connect and a new link with a new session`() {
        held.hold(POD_ID, "test")
        runWorker()
        val first = assertNotNull(held.podConnection?.link)

        central.podGoesAway()

        assertFalse(first.isUsable, "the old link is lost at once")
        assertEquals(listOf(POD_ID, POD_ID), central.connects, "a new held connect right after the drop")
        assertEquals(POD_ID, central.pendingConnect)
        assertTrue(held.isConnecting)

        central.podComesInRange()
        runWorker()

        val second = assertNotNull(held.podConnection?.link)
        assertNotSame(first, second)
        assertEquals(2, second.number)
        assertTrue(second.isUsable)
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
    }

    @Test
    fun `every drop is followed by a new connect while the connection is wanted`() {
        held.hold(POD_ID, "test")
        runWorker()

        repeat(3) {
            central.podGoesAway()
            central.podComesInRange()
            runWorker()
        }

        assertEquals(4, central.connects.size)
        assertEquals(listOf("attach 4", "session 4"), client.calls.takeLast(2))
        assertTrue(client.hasSession())
    }

    @Test
    fun `no reconnect after the connection is stopped`() {
        held.hold(POD_ID, "test")
        runWorker()

        held.stopHolding("user disconnect")

        assertTrue(POD_ID in central.cancelled)
        assertFalse(central.linkUp, "the link is cancelled")
        assertFalse(client.hasSession())
        assertEquals(listOf(POD_ID), central.connects, "the drop made by the stop is not connected again")
        assertFalse(held.isConnecting)

        central.podComesInRange()
        runWorker()
        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf("attach 1", "session 1"), client.calls)
    }

    @Test
    fun `stopping cancels a connect that iOS still keeps`() {
        central.podInRange = false
        held.hold(POD_ID, "test")

        held.stopHolding("queue gave up")

        assertEquals(null, central.pendingConnect)
        central.podComesInRange()
        assertFalse(central.linkUp)
        assertTrue(client.calls.isEmpty())
    }

    @Test
    fun `no reconnect after the pod is forgotten and a later restore releases it`() {
        held.hold(POD_ID, "test")
        runWorker()

        held.forget("pod deactivated")
        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf(POD_ID), central.released, "a forgotten pod is not taken back")
        assertFalse(held.isConnecting)
    }

    @Test
    fun `a hold after a stop connects again`() {
        held.hold(POD_ID, "test")
        runWorker()
        held.stopHolding("queue empty")

        held.hold(POD_ID, "connection needed")
        runWorker()

        assertEquals(listOf(POD_ID, POD_ID), central.connects)
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
    }

    @Test
    fun `a state restore re-attaches the pod with a new session and releases other peripherals`() {
        held.setPod(POD_ID)
        assertTrue(central.connects.isEmpty(), "knowing the pod connects nothing")

        held.onEvent(BleCentralEvent.Restored(listOf("OLD-POD", POD_ID)))

        assertEquals(listOf("OLD-POD"), central.released)
        assertEquals(listOf(POD_ID), central.connects, "the restored pod is connected again")
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), client.calls)
        assertTrue(client.hasSession())

        // After the restore the connection is held as usual: a drop is connected again.
        central.podGoesAway()
        assertEquals(listOf(POD_ID, POD_ID), central.connects)
    }

    @Test
    fun `a restore of a pod still connected gets a new session on the same peripheral`() {
        held.hold(POD_ID, "test")
        runWorker()
        val first = assertNotNull(held.podConnection?.link)
        client.attached = null // the app was started again: no session of before is known

        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))
        runWorker()

        assertEquals(listOf(POD_ID, POD_ID), central.connects)
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
        assertFalse(first.isUsable, "the link of before is replaced")
    }

    @Test
    fun `a restore without the pod connects nothing and releases everything`() {
        held.setPod(POD_ID)

        held.onEvent(BleCentralEvent.Restored(listOf("OLD-POD")))

        assertEquals(listOf("OLD-POD"), central.released)
        assertTrue(central.connects.isEmpty())
    }

    @Test
    fun `a restore before any pod is known releases everything`() {
        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(POD_ID), central.released)
        assertTrue(central.connects.isEmpty())
    }

    @Test
    fun `with Bluetooth off nothing is asked and Bluetooth on asks the connect`() {
        central.radioOff()

        held.hold(POD_ID, "test")
        assertTrue(central.connects.isEmpty())
        assertTrue(held.isConnecting)

        central.radioOn()
        runWorker()

        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf("attach 1", "session 1"), client.calls)
    }

    @Test
    fun `Bluetooth off makes the link lost and Bluetooth on gives a new link and session`() {
        held.hold(POD_ID, "test")
        runWorker()
        val first = assertNotNull(held.podConnection?.link)

        central.radioOff()

        assertFalse(first.isUsable, "no drop event comes from iOS, the link is marked lost anyway")
        assertFalse(client.hasSession())

        central.radioOn()
        runWorker()

        assertEquals(listOf(POD_ID, POD_ID), central.connects)
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
    }

    @Test
    fun `a set up refused as Busy is tried again until the command ends`() {
        client.locked = true
        onSleep = { if (sleeps.size == 3) client.locked = false }

        held.hold(POD_ID, "test")
        runWorker()

        assertEquals(listOf(1_000L, 1_000L, 1_000L), sleeps)
        assertEquals(listOf("attach 1", "session 1"), client.calls)
    }

    @Test
    fun `a set up stops trying when the link drops while Busy`() {
        client.locked = true
        onSleep = { if (sleeps.size == 2) central.podGoesAway() }

        held.hold(POD_ID, "test")
        runWorker()

        assertEquals(2, sleeps.size)
        assertTrue(client.calls.isEmpty())
        assertEquals(POD_ID, central.pendingConnect, "the next held connect waits for the pod")
    }

    @Test
    fun `a failed session cancels the link and the next connect starts later`() {
        client.failSession = true

        held.hold(POD_ID, "test")
        runWorker()

        assertTrue(POD_ID in central.cancelled, "the failed link is cancelled")
        assertEquals(listOf(POD_ID, "$POD_ID delay=${DashHeldConnection.FAILED_SETUP_CONNECT_DELAY_S}"), central.connects)
        assertEquals(POD_ID, central.pendingConnect)

        client.failSession = false
        central.podComesInRange()
        runWorker()
        central.podGoesAway()

        assertEquals(POD_ID, central.connects.last(), "after a good set up the next connect has no delay")
        assertTrue(client.hasSession().not())
        assertEquals(listOf("attach 1", "session 1", "attach 2", "session 2"), client.calls)
    }

    @Test
    fun `an unpaired pod gets no set up from the held connection`() {
        client.paired = false

        held.hold(POD_ID, "test")
        runWorker()

        assertTrue(client.calls.isEmpty(), "the activation connects and pairs on its own")
    }

    @Test
    fun `awaiting the connection gives the session when the pod is in range`() {
        val result = held.awaitConnection(POD_ID, 30_000)
        runWorker()

        assertSame(DashConnectResult.SessionReady, result)
        assertEquals(listOf("attach 1", "session 1"), client.calls, "the set up of the worker found it done")
    }

    @Test
    fun `awaiting the connection gives up after the timeout and keeps the held connect`() {
        central.podInRange = false

        val result = held.awaitConnection(POD_ID, 1_000)

        assertIs<DashConnectResult.NotConnected>(result)
        assertTrue(nowMs >= 1_000)
        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(POD_ID, central.pendingConnect)
    }

    @Test
    fun `awaiting the connection waits while a command holds the lock`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.podGoesAway()
        client.locked = true
        central.podComesInRange()
        onSleep = { if (sleeps.size == 2) client.locked = false }

        val result = held.awaitConnection(POD_ID, 30_000)

        assertSame(DashConnectResult.SessionReady, result)
        assertEquals(listOf(DashHeldConnection.POLL_MS, DashHeldConnection.POLL_MS), sleeps)
    }

    @Test
    fun `a new pod is found by the scan then connected and its link is ready for pairing`() {
        client.paired = false
        central.advertisements += advertisement(POD_ID)
        val found = mutableListOf<String>()

        val result = held.connectNewPod(30_000) { found += it }
        runWorker()

        assertSame(DashConnectResult.LinkReady, result)
        assertEquals(listOf(POD_ID), found, "the peripheral is given to be stored before the connect")
        assertEquals(1, central.scans.size)
        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf("attach 1"), client.calls)
    }

    @Test
    fun `no pod to pair connects nothing`() {
        client.paired = false
        central.advertisements += advertisement("A")
        central.advertisements += advertisement("B")
        val found = mutableListOf<String>()

        val result = held.connectNewPod(30_000) { found += it }

        assertIs<DashConnectResult.NotConnected>(result)
        assertTrue(found.isEmpty())
        assertTrue(central.connects.isEmpty())
    }
}
