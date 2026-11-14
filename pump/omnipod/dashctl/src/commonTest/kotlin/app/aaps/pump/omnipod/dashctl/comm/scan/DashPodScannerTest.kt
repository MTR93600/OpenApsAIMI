package app.aaps.pump.omnipod.dashctl.comm.scan

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.advertisement
import app.aaps.pump.omnipod.dashctl.comm.connection.HeldPodCentral.Companion.podServiceUuids
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The scan for a pod that can be paired: the rules of Android `BleDiscoveredDevice` (9 service
 * UUIDs, 4024 first, 000a third, the pod id ffff fffe) and of `PodScanner` / `ScanCollector` (filter
 * on the Dash service, 5 s, exactly one pod).
 *
 * The advertisements are built from the Android rules ([podServiceUuids]); there is no recorded pod
 * advertisement in the Android tests.
 */
class DashPodScannerTest {

    private val pod = FakeDashPod()
    private val central = HeldPodCentral(pod)
    private val sleeps = mutableListOf<Long>()
    private val scanner = DashPodScanner(central, SilentPodLogger, sleep = { sleeps += it })

    init {
        central.listener = scanner::onEvent
    }

    @AfterTest
    fun closePod() {
        pod.close()
    }

    @Test
    fun `a pod that can be paired is accepted with its lot and sequence`() {
        val checked = DashPodAdvertisement.check(podServiceUuids(), DashPodAdvertisement.POD_ID_NOT_ACTIVATED)

        val found = assertIs<DashAdvertisement.Pod>(checked)
        assertEquals(0xFFFFFFFEL, found.podId)
        assertEquals(0x0812345678L, found.lotNo)
        assertEquals(0x09abcd, found.sequenceNo)
    }

    @Test
    fun `an activated pod is not a pod to pair`() {
        val checked = DashPodAdvertisement.check(podServiceUuids(podId = 0x17000001L), DashPodAdvertisement.POD_ID_NOT_ACTIVATED)

        assertIs<DashAdvertisement.Invalid>(checked)
    }

    @Test
    fun `an activated pod is accepted when its own pod id is asked`() {
        val checked = DashPodAdvertisement.check(podServiceUuids(podId = 0x17000001L), 0x17000001L)

        assertEquals(0x17000001L, assertIs<DashAdvertisement.Pod>(checked).podId)
    }

    @Test
    fun `an advertisement without exactly 9 service UUIDs is refused`() {
        val nine = podServiceUuids()

        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(nine.dropLast(1), DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(nine + nine.last(), DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(emptyList(), DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `the first service UUID must be 4024 and the third 000a`() {
        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(podServiceUuids(first = "4025"), DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(podServiceUuids(third = "000b"), DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `upper case UUID text is read as Android reads it`() {
        val upper = podServiceUuids().map { it.uppercase() }

        assertIs<DashAdvertisement.Pod>(DashPodAdvertisement.check(upper, DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `a UUID text too short is refused and does not throw`() {
        val broken = podServiceUuids().toMutableList().also { it[3] = "ff" }

        assertIs<DashAdvertisement.Invalid>(DashPodAdvertisement.check(broken, DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `the scan filters on the Dash service and stops after 5 seconds`() {
        central.advertisements += advertisement("POD-A")

        val result = scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED)

        assertEquals("POD-A", assertIs<DashScanResult.Found>(result).id)
        assertEquals(listOf(listOf("00004024-0000-1000-8000-00805f9b34fb")), central.scans)
        assertEquals(listOf(5_000L), sleeps)
        assertEquals(1, central.scanStops)
    }

    @Test
    fun `only the pod that can be paired is found among other devices`() {
        central.advertisements += advertisement("ACTIVATED", podServiceUuids(podId = 0x17000001L))
        central.advertisements += advertisement("OTHER", listOf("00004024-0000-1000-8000-00805f9b34fb"))
        central.advertisements += advertisement("NEW-POD")

        val result = scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED)

        assertEquals("NEW-POD", assertIs<DashScanResult.Found>(result).id)
    }

    @Test
    fun `two pods that can be paired are too many and none is chosen`() {
        central.advertisements += advertisement("POD-A")
        central.advertisements += advertisement("POD-B")

        val result = scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED)

        assertEquals(listOf("POD-A", "POD-B"), assertIs<DashScanResult.TooMany>(result).ids)
    }

    @Test
    fun `the same pod seen twice counts once`() {
        central.advertisements += advertisement("POD-A")
        central.advertisements += advertisement("POD-A")

        assertIs<DashScanResult.Found>(scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `nothing seen is not found and a scan failure only logs as Android`() {
        central.advertisements += advertisement("ACTIVATED", podServiceUuids(podId = 0x17000001L))
        scanner.onEvent(BleCentralEvent.ScanFailed("scan error 2"))

        assertIs<DashScanResult.NotFound>(scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }

    @Test
    fun `no scan with Bluetooth off`() {
        central.adapter = BleAdapterState.PoweredOff
        central.advertisements += advertisement("POD-A")

        assertIs<DashScanResult.Failed>(scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
        assertTrue(central.scans.isEmpty())
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `an advertisement outside a scan is not kept for the next scan`() {
        scanner.onEvent(advertisement("EARLY"))

        assertIs<DashScanResult.NotFound>(scanner.scanForPod(DashPodAdvertisement.POD_ID_NOT_ACTIVATED))
    }
}
