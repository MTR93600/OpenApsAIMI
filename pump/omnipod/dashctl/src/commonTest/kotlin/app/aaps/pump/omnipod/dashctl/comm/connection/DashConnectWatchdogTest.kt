package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.CONNECT_WATCHDOG_BUDGET_MS
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.CONNECT_WATCHDOG_MS
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.CONNECT_WATCHDOG_RESTART_DELAY_S
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.FAILED_SETUP_CONNECT_DELAY_S
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.RESTORED_LINK_DROP_FALLBACK_MS
import app.aaps.pump.omnipod.dashctl.comm.connection.DashHeldConnection.Companion.RESTORED_LINK_RECONNECT_DELAY_S
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.POD_ID
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The connect watchdog of [DashHeldConnection], as OmnipodKit (Trio) `BluetoothManager.armConnectWatchdog`:
 * a connect with no link after [CONNECT_WATCHDOG_MS] is cancelled and asked again at once with a start
 * delay of [CONNECT_WATCHDOG_RESTART_DELAY_S], for at most [CONNECT_WATCHDOG_BUDGET_MS]; then it stays
 * held with no limit, as before.
 *
 * The fake central keeps a connect open while the pod is "out of range", which is what a wedged connect
 * looks like from the app (no `didConnect`). The timers are a list the test fires ([fireNext]); firing a
 * timer moves the clock by its delay.
 */
class DashConnectWatchdogTest {

    private val pod = FakeDashPod()
    private val central = HeldPodCentral(pod)
    private val client = RecordingClient().apply { paired = true }
    private val worker = ArrayDeque<() -> Unit>()
    private var nowMs = 0L

    /** The timers asked, in order: delay and block. */
    private val timers = ArrayDeque<Pair<Long, () -> Unit>>()

    /** True: iOS has the link up, but its connect event did not come yet. */
    private var iosLinked = false

    private val held = DashHeldConnection(
        central = central,
        aapsLogger = SilentPodLogger,
        client = client,
        runOnWorker = { worker.addLast(it) },
        sleep = { nowMs += it },
        now = { nowMs },
        discoveryTimeoutMs = 200,
        schedule = { ms, block -> timers.addLast(ms to block) },
        peripheralConnected = { iosLinked },
    )

    private val restartConnect = "$POD_ID delay=$CONNECT_WATCHDOG_RESTART_DELAY_S"
    private val restartWatchdogMs = CONNECT_WATCHDOG_RESTART_DELAY_S * 1_000L + CONNECT_WATCHDOG_MS

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

    /** Fires the oldest timer, after moving the clock by its delay. Returns its delay. */
    private fun fireNext(): Long {
        val (ms, block) = timers.removeFirst()
        nowMs += ms
        block()
        return ms
    }

    @Test
    fun `a connect with no link after the bound is cancelled and asked again`() {
        central.podInRange = false

        held.hold(POD_ID, "test")

        assertEquals(listOf(CONNECT_WATCHDOG_MS), timers.map { it.first }, "the watchdog is armed for the connect")
        fireNext()
        assertEquals(listOf(POD_ID), central.cancelled, "the wedged connect is cancelled")
        assertEquals(listOf(POD_ID, restartConnect), central.connects, "a new connect at once, iOS counts the pause")
        assertEquals(POD_ID, central.pendingConnect)
        assertEquals(listOf(restartWatchdogMs), timers.map { it.first }, "the new connect is watched after its delay")

        central.podComesInRange()
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), client.calls)
        assertTrue(client.hasSession())
    }

    @Test
    fun `a connect is open at all times after a watchdog cancel`() {
        central.podInRange = false
        held.hold(POD_ID, "test")

        fireNext()

        assertEquals(listOf(POD_ID), central.cancelled)
        assertEquals(POD_ID, central.pendingConnect, "iOS may suspend the app before any timer runs: a connect must stay open")
    }

    @Test
    fun `a link that comes up in time is never cancelled`() {
        central.podInRange = false
        held.hold(POD_ID, "test")
        central.podComesInRange()
        runWorker()

        while (timers.isNotEmpty()) fireNext()

        assertTrue(central.cancelled.isEmpty())
        assertEquals(listOf(POD_ID), central.connects)
        assertTrue(client.hasSession())
    }

    @Test
    fun `a connect answered at once arms no watchdog`() {
        held.hold(POD_ID, "test")
        runWorker()

        assertTrue(timers.isEmpty())
        assertTrue(client.hasSession())
    }

    @Test
    fun `after the budget the connect stays held with no limit`() {
        central.podInRange = false
        held.hold(POD_ID, "test")

        var guard = 0
        while (timers.isNotEmpty() && guard++ < 1_000) fireNext()

        assertTrue(timers.isEmpty(), "the watchdog stops by itself")
        assertTrue(nowMs <= CONNECT_WATCHDOG_BUDGET_MS + CONNECT_WATCHDOG_MS, "it stopped within the budget: $nowMs ms")
        val cycles = central.cancelled.size
        assertTrue(cycles in 10..20, "about one cycle per $restartWatchdogMs ms: $cycles")
        assertEquals(cycles + 1, central.connects.size)
        assertEquals(POD_ID, central.pendingConnect, "the last connect stays held, as OmniBLE")

        // The pod comes later: the held connect still brings it.
        central.podComesInRange()
        runWorker()
        assertTrue(client.hasSession())
    }

    @Test
    fun `a link that came up gives the next connect a new budget`() {
        central.podInRange = false
        held.hold(POD_ID, "test")
        while (timers.isNotEmpty()) fireNext()
        central.podComesInRange()
        runWorker()
        val cancelsBefore = central.cancelled.size

        central.podGoesAway()

        assertEquals(listOf(CONNECT_WATCHDOG_MS), timers.map { it.first }, "the connect after the drop is watched again")
        fireNext()
        assertEquals(cancelsBefore + 1, central.cancelled.size)
    }

    @Test
    fun `a stop after a watchdog cancel leaves no connect and no watchdog`() {
        central.podInRange = false
        held.hold(POD_ID, "test")
        fireNext()

        held.stopHolding("queue empty")
        fireNext()

        assertEquals(listOf(POD_ID, restartConnect), central.connects, "nothing more is asked")
        assertEquals(null, central.pendingConnect)
        assertTrue(timers.isEmpty())
    }

    @Test
    fun `a hold after a watchdog cancel asks no second connect`() {
        central.podInRange = false
        held.hold(POD_ID, "test")
        fireNext()

        held.hold(POD_ID, "connection needed")

        assertEquals(listOf(POD_ID, restartConnect), central.connects, "the restarted connect is already open")
    }

    @Test
    fun `a link that iOS has up before its event comes is not cut`() {
        central.podInRange = false
        held.hold(POD_ID, "test")

        // iOS connected just before the timer; the connect event still waits on the radio queue.
        iosLinked = true
        fireNext()

        assertTrue(central.cancelled.isEmpty(), "the link that just came up is not cancelled")
        assertEquals(listOf(POD_ID), central.connects)
        assertEquals(listOf(CONNECT_WATCHDOG_MS), timers.map { it.first }, "it is looked at again")

        central.podComesInRange()
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), client.calls, "the set up runs on that link and works")
        while (timers.isNotEmpty()) fireNext()
        assertTrue(central.cancelled.isEmpty())
    }

    @Test
    fun `a stop ends the watchdog`() {
        central.podInRange = false
        held.hold(POD_ID, "test")

        held.stopHolding("queue empty")
        fireNext()

        assertEquals(listOf(POD_ID), central.cancelled, "only the cancel of the stop")
        assertEquals(listOf(POD_ID), central.connects)
        assertTrue(timers.isEmpty())
    }

    @Test
    fun `a delayed connect after a failed set up gets its delay before the watchdog`() {
        client.failSession = true
        held.hold(POD_ID, "test")
        runWorker()

        assertEquals(listOf(POD_ID, "$POD_ID delay=$FAILED_SETUP_CONNECT_DELAY_S"), central.connects)
        assertEquals(listOf(FAILED_SETUP_CONNECT_DELAY_S * 1_000L + CONNECT_WATCHDOG_MS), timers.map { it.first })
    }

    @Test
    fun `a restored pod that does not come up is cancelled and connected again`() {
        central.podInRange = false
        held.setPod(POD_ID)

        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))
        fireNext()

        assertEquals(listOf(POD_ID), central.cancelled)
        assertEquals(listOf(POD_ID, restartConnect), central.connects)
        assertEquals(POD_ID, central.pendingConnect, "the restored pod is still held")
        central.podComesInRange()
        runWorker()
        assertEquals(listOf("attach 1", "session 1"), client.calls)
    }

    @Test
    fun `the watchdog does not run while a restored link is dropped`() {
        held.hold(POD_ID, "test")
        runWorker()
        client.attached = null // the app was started again: no session of before is known
        // iOS kept the link, and the drop event of the cancel does not come.
        iosLinked = true
        central.ignoreCancel = true

        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(RESTORED_LINK_DROP_FALLBACK_MS), timers.map { it.first }, "only the fallback timer, no watchdog")
        assertEquals(listOf(POD_ID), central.cancelled)
        assertEquals(listOf(POD_ID), central.connects)

        fireNext()

        assertEquals(listOf(POD_ID, "$POD_ID delay=$RESTORED_LINK_RECONNECT_DELAY_S"), central.connects)
        runWorker()
        while (timers.isNotEmpty()) fireNext()
        assertEquals(listOf(POD_ID), central.cancelled, "no watchdog cancel")
        assertTrue(client.hasSession())
    }
}
