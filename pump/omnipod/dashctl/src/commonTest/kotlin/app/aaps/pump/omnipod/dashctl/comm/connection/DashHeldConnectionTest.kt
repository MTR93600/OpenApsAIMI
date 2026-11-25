package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.RESTORED_LINK_DROP_FALLBACK_MS
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.RESTORED_LINK_RECONNECT_DELAY_S
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
 * after the radio event that started it. The timers ([schedule] of the connection) are a list too, and
 * only a test that fires them ([fireTimers]) sees them run.
 */
class DashHeldConnectionTest {

    private val pod = FakeDashPod()
    private val central = HeldPodCentral(pod)
    private val client = RecordingClient().apply { paired = true }
    private val worker = ArrayDeque<() -> Unit>()
    private var nowMs = 0L
    private val sleeps = mutableListOf<Long>()
    private var onSleep: () -> Unit = {}

    /** The timers asked, in order: delay and block. */
    private val timers = ArrayDeque<Pair<Long, () -> Unit>>()

    private val held = newHeld(client)

    /** A connection as the app makes it; iOS tells if the peripheral is connected by the fake link. */
    private fun newHeld(client: RecordingClient) = DashHeldConnection(
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
        schedule = { ms, block -> timers.addLast(ms to block) },
        peripheralConnected = { id -> central.linkUp && id == POD_ID },
    )

    /**
     * iOS started the app again: a new connection and a new pump with no session, the pod known
     * from the store (as `IosDashBleHost` does). The link of before may still be up in the fake.
     */
    private fun relaunch(): Pair<DashHeldConnection, RecordingClient> {
        val newClient = RecordingClient().apply { paired = true }
        val again = newHeld(newClient)
        again.setPod(POD_ID)
        central.listener = again::onEvent
        return again to newClient
    }

    /** Fires every timer, also those a timer asks. */
    private fun fireTimers() {
        var guard = 0
        while (timers.isNotEmpty() && guard++ < 100) {
            val (ms, block) = timers.removeFirst()
            nowMs += ms
            block()
        }
    }

    private val freshConnect = "$POD_ID delay=$RESTORED_LINK_RECONNECT_DELAY_S"

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
        assertTrue(central.cancelled.isEmpty(), "a restored pod that is not connected has no link to drop")
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), client.calls)
        assertTrue(client.hasSession())

        // After the restore the connection is held as usual: a drop is connected again.
        central.podGoesAway()
        assertEquals(listOf(POD_ID, POD_ID), central.connects)
    }

    @Test
    fun `a restore of a pod still connected drops that link first and sets up a fresh one`() {
        held.hold(POD_ID, "test")
        runWorker()
        val (again, newClient) = relaunch()

        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(POD_ID), central.cancelled, "the restored link is dropped first")
        assertEquals(listOf(POD_ID, freshConnect), central.connects, "then a fresh connect after a short gap")
        assertTrue(newClient.calls.isEmpty(), "no session on the restored link")
        assertTrue(again.isConnecting)
        assertFalse(again.isHandshakeInProgress)

        central.podComesInRange()
        runWorker()

        assertEquals(listOf("attach 1", "session 1"), newClient.calls, "one link and one session, on the fresh link")
        assertTrue(newClient.hasSession())
        fireTimers()
        assertEquals(listOf(POD_ID, freshConnect), central.connects, "the fallback timer asks nothing more")
        assertEquals(listOf("attach 1", "session 1"), newClient.calls)
    }

    @Test
    fun `a restore before Bluetooth is on drops and releases nothing until it is on`() {
        held.hold(POD_ID, "test")
        runWorker()
        // iOS gives the restore before the manager is powered on; it ignores commands until then.
        central.adapter = BleAdapterState.Unknown
        val (again, newClient) = relaunch()

        again.onEvent(BleCentralEvent.Restored(listOf("OLD-POD", POD_ID)))

        assertTrue(central.cancelled.isEmpty(), "no cancel before Bluetooth is on")
        assertTrue(central.released.isEmpty(), "no release before Bluetooth is on")
        assertEquals(listOf(POD_ID), central.connects)
        assertTrue(timers.isEmpty())

        central.radioOn()

        assertEquals(listOf("OLD-POD"), central.released, "the old pod is released once Bluetooth is on")
        assertEquals(listOf(POD_ID), central.cancelled)
        assertEquals(listOf(POD_ID, freshConnect), central.connects)
        central.podComesInRange()
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), newClient.calls)
    }

    @Test
    fun `when the drop event never comes the timer connects anyway and only once`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()

        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(POD_ID), central.cancelled)
        assertEquals(listOf(POD_ID), central.connects, "nothing is asked while the drop runs")
        assertEquals(listOf(RESTORED_LINK_DROP_FALLBACK_MS), timers.map { it.first })

        fireTimers()

        assertEquals(listOf(POD_ID, freshConnect), central.connects, "the timer asks the connect once")
        // iOS answers a connect on a link that never dropped at once: the set up runs on that link.
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), newClient.calls)
        fireTimers()
        assertEquals(listOf(POD_ID, freshConnect), central.connects)
        assertEquals(listOf(POD_ID), central.cancelled)
    }

    @Test
    fun `a connect event of the restored link during the drop starts no set up`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        again.onEvent(BleCentralEvent.Link(POD_ID, connected = true, status = 0))
        runWorker()

        assertTrue(newClient.calls.isEmpty(), "no session on the restored link")
        assertFalse(again.isHandshakeInProgress)

        // The drop event comes late.
        central.ignoreCancel = false
        central.podGoesAway()
        assertEquals(listOf(POD_ID, freshConnect), central.connects)
        central.podComesInRange()
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), newClient.calls)
    }

    @Test
    fun `awaiting the connection during the drop sends nothing and gives the session of the fresh link`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))
        var callsDuringDrop: List<String>? = null
        var waits = 0
        onSleep = {
            waits++
            if (waits == 2) {
                callsDuringDrop = newClient.calls.toList()
                // The drop event comes late.
                central.ignoreCancel = false
                central.podGoesAway()
            }
            if (waits == 4) central.podComesInRange()
        }

        val result = again.awaitConnection(POD_ID, 30_000)

        assertSame(DashConnectResult.SessionReady, result)
        assertEquals(emptyList(), callsDuringDrop, "nothing is sent while the drop runs")
        assertEquals(listOf(POD_ID, freshConnect), central.connects)
        assertEquals(listOf("attach 1", "session 1"), newClient.calls)
    }

    @Test
    fun `awaiting the connection during a drop that never ends gives up and sends nothing`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        val result = again.awaitConnection(POD_ID, 1_000)

        assertIs<DashConnectResult.NotConnected>(result)
        assertTrue(newClient.calls.isEmpty())
        assertEquals(listOf(POD_ID), central.connects)
    }

    @Test
    fun `a stop during the drop connects nothing afterwards`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        again.stopHolding("queue empty")
        central.ignoreCancel = false
        central.podGoesAway()
        fireTimers()
        central.podComesInRange()
        runWorker()

        assertEquals(listOf(POD_ID), central.connects)
        assertTrue(newClient.calls.isEmpty())
        assertFalse(again.isConnecting)
    }

    @Test
    fun `a pod forgotten during the drop connects nothing afterwards`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.ignoreCancel = true
        val (again, newClient) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        again.forget("pod deactivated")
        central.ignoreCancel = false
        central.podGoesAway()
        fireTimers()
        central.podComesInRange()
        runWorker()

        assertEquals(listOf(POD_ID), central.connects)
        assertTrue(newClient.calls.isEmpty())
        assertFalse(again.isConnecting)
    }

    @Test
    fun `a pod forgotten during the drop before Bluetooth is on is still released`() {
        held.hold(POD_ID, "test")
        runWorker()
        central.adapter = BleAdapterState.Unknown
        val (again, _) = relaunch()
        again.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        again.forget("pod deactivated")
        assertTrue(central.released.isEmpty())
        central.radioOn()

        assertEquals(listOf(POD_ID), central.released, "the link iOS kept is ended once Bluetooth is on")
        assertEquals(listOf(POD_ID), central.connects)
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
