package app.aaps.pump.omnipod.dashctl.comm.connection

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.POD_ID
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.advertisement
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.podServiceUuids
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.scan.DashFaultAdvertisement
import app.aaps.pump.omnipod.dashctl.comm.scan.DashPodAdvertisement
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fault watch of [DashHeldConnection], as OmnipodKit (Trio) `BluetoothManager`: while the paired
 * pod is not connected, a scan filtered on C00A only ([DashFaultAdvertisement.FAULT_SERVICE_UUID]);
 * an advertisement of THIS pod with C00A calls the fault listener on a worker, once, and the scan
 * stops.
 */
class DashFaultWatchTest {

    private val pod = FakeDashPod()
    private val central = HeldPodCentral(pod)
    private val client = RecordingClient().apply { paired = true }
    private val worker = ArrayDeque<() -> Unit>()
    private var nowMs = 0L
    private var faults = 0

    private val held = DashHeldConnection(
        central = central,
        aapsLogger = SilentPodLogger,
        client = client,
        runOnWorker = { worker.addLast(it) },
        sleep = { nowMs += it },
        now = { nowMs },
        discoveryTimeoutMs = 200,
        watchForFaults = true,
    )

    private val faultScan = listOf(DashFaultAdvertisement.FAULT_SERVICE_UUID)

    init {
        central.listener = held::onEvent
        held.setFaultListener { faults++ }
    }

    @AfterTest
    fun closePod() {
        pod.close()
    }

    private fun runWorker() {
        while (worker.isNotEmpty()) worker.removeFirst().invoke()
    }

    /** The advertisement of an activated pod in fault: its 2nd service UUID is C00A (OmnipodKit capture). */
    private fun faultAdvertisement(id: String) = advertisement(
        id,
        podServiceUuids(podId = 0x17012345L).toMutableList().also { it[1] = DashFaultAdvertisement.FAULT_SERVICE_UUID }
    )

    @Test
    fun `the fault rule is the C00A service UUID only`() {
        assertTrue(DashFaultAdvertisement.isFault(faultAdvertisement(POD_ID).serviceUuids))
        assertTrue(DashFaultAdvertisement.isFault(listOf("C00A")))
        assertFalse(DashFaultAdvertisement.isFault(podServiceUuids(podId = 0x17012345L)), "a pod in normal use")
        assertFalse(
            DashFaultAdvertisement.isFault(listOf("0000c005-0000-1000-8000-00805f9b34fb")),
            "an alert (C005) is not a fault, OmnipodKit does not wake on it"
        )
        assertFalse(DashFaultAdvertisement.isFault(listOf("1234c00a-0000-1000-8000-00805f9b34fb")), "not the 16-bit UUID")
    }

    @Test
    fun `the fault scan runs on C00A only while the paired pod is not connected`() {
        central.podInRange = false

        held.setPod(POD_ID)
        assertEquals(listOf(faultScan), central.scans, "the pod is known and not connected")

        held.hold(POD_ID, "test")
        assertEquals(listOf(faultScan), central.scans, "no second scan while it runs")
        central.podComesInRange()
        runWorker()
        assertEquals(1, central.scanStops, "no scan while the link is up")

        central.podGoesAway()
        assertEquals(listOf(faultScan, faultScan), central.scans, "the scan again after the drop")

        held.forget("pod deactivated")
        assertEquals(2, central.scanStops, "no scan without a pod")
    }

    @Test
    fun `a fault advertisement of the pod reads the alarm at once off the radio queue`() {
        central.podInRange = false
        held.setPod(POD_ID)

        held.onEvent(faultAdvertisement(POD_ID))

        assertEquals(0, faults, "not on the radio queue")
        assertEquals(1, central.scanStops, "the scan stops once the fault is seen")
        runWorker()
        assertEquals(1, faults)

        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()
        assertEquals(1, faults, "once per pod")
        held.stopHolding("queue empty")
        assertEquals(listOf(faultScan), central.scans, "no new scan for this pod")
    }

    @Test
    fun `a fault report is given again after a link to the pod came and went`() {
        central.podInRange = false
        held.setPod(POD_ID)
        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()

        // The pod was not activated yet (the plugin ignored the report), or the read ran: a link came.
        held.hold(POD_ID, "activation")
        central.podComesInRange()
        runWorker()
        central.podGoesAway()

        assertEquals(listOf(faultScan, faultScan), central.scans, "the fault scan runs again after the link")
        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()
        assertEquals(2, faults)
    }

    @Test
    fun `a status read that never reached the pod does not use up the fault report`() {
        central.podInRange = false
        held.setPod(POD_ID)
        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()

        // The status read of the plugin asks the connection, and the queue gives up with no link.
        held.hold(POD_ID, "connection needed")
        held.stopHolding("no link to the pod")

        assertEquals(listOf(faultScan, faultScan), central.scans, "the fault scan runs again")
        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()
        assertEquals(2, faults)
    }

    @Test
    fun `the fault scan stops while the connect watchdog runs and comes back after its budget`() {
        val timers = ArrayDeque<Pair<Long, () -> Unit>>()
        val watched = DashHeldConnection(
            central = central,
            aapsLogger = SilentPodLogger,
            client = client,
            runOnWorker = { worker.addLast(it) },
            sleep = { nowMs += it },
            now = { nowMs },
            discoveryTimeoutMs = 200,
            schedule = { ms, block -> timers.addLast(ms to block) },
            watchForFaults = true,
        )
        central.listener = watched::onEvent
        central.podInRange = false
        watched.setPod(POD_ID)
        assertEquals(listOf(faultScan), central.scans)

        watched.hold(POD_ID, "test")
        assertEquals(1, central.scanStops, "as OmnipodKit eagerConnect: a scan beside the connect slows it")
        repeat(3) {
            val (ms, block) = timers.removeFirst()
            nowMs += ms
            block()
        }
        assertEquals(listOf(faultScan), central.scans, "no scan between the watchdog cycles")

        var guard = 0
        while (timers.isNotEmpty() && guard++ < 1_000) {
            val (ms, block) = timers.removeFirst()
            nowMs += ms
            block()
        }
        assertEquals(
            listOf(faultScan, faultScan), central.scans,
            "after the budget the light scan runs beside the held connect, as OmnipodKit issueDelayedConnectProbe"
        )
        assertEquals(POD_ID, central.pendingConnect)
    }

    @Test
    fun `a fault advertisement of another pod or a normal one is ignored`() {
        central.podInRange = false
        held.setPod(POD_ID)

        held.onEvent(faultAdvertisement("ANOTHER-POD"))
        held.onEvent(advertisement(POD_ID, podServiceUuids(podId = 0x17012345L)))
        runWorker()

        assertEquals(0, faults)
        assertEquals(0, central.scanStops, "the scan goes on")
    }

    @Test
    fun `a new pod gets its own fault watch`() {
        central.podInRange = false
        held.setPod(POD_ID)
        held.onEvent(faultAdvertisement(POD_ID))
        runWorker()

        held.forget("pod deactivated")
        held.setPod("NEW-POD")
        held.onEvent(faultAdvertisement("NEW-POD"))
        runWorker()

        assertEquals(2, faults)
    }

    @Test
    fun `no fault scan for a pod that is not paired`() {
        client.paired = false

        held.setPod(POD_ID)
        held.hold(POD_ID, "test")

        assertTrue(central.scans.isEmpty())
    }

    @Test
    fun `no fault scan with Bluetooth off and the scan starts when it is on`() {
        central.podInRange = false
        central.radioOff()

        held.setPod(POD_ID)
        assertTrue(central.scans.isEmpty())

        central.radioOn()
        assertEquals(listOf(faultScan), central.scans)

        central.radioOff()
        central.radioOn()
        assertEquals(listOf(faultScan, faultScan), central.scans, "iOS stopped the scan with the radio: it starts again")
    }

    @Test
    fun `the pairing scan stops the fault scan and the fault scan comes back after it`() {
        central.podInRange = false
        held.setPod(POD_ID)
        val pairingScan = listOf(DashPodAdvertisement.SCAN_FOR_SERVICE_UUID)

        held.connectNewPod(1_000) {}

        assertEquals(listOf(faultScan, pairingScan, faultScan), central.scans)
    }

    @Test
    fun `a restore starts the fault scan of the new process`() {
        central.podInRange = false
        held.setPod(POD_ID)

        held.onEvent(BleCentralEvent.Restored(listOf(POD_ID)))

        assertEquals(listOf(faultScan, faultScan), central.scans)
    }
}
