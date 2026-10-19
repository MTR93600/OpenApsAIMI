package app.aaps.plugins.dexcomoneplus.scan

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.plugins.dexcomoneplus.FakeBleCentral
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorIdentity
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS scanner driven through [FakeBleCentral].
 *
 * No test name here may contain a comma: these really run on Kotlin/Native and a backtick name
 * with a comma fails there while compiling fine on the JVM.
 */
class OnePlusBleScannerIosTest {

    private val advertisedService = OnePlusBluetoothUuids.Advertisement.lowercase()
    private var clock = 0L

    @BeforeTest
    fun resetBudget() {
        OnePlusScanBudget.reset()
        clock = 0L
    }

    @AfterTest
    fun clearBudget() {
        OnePlusScanBudget.reset()
    }

    private fun scanner(
        central: FakeBleCentral,
        hint: OnePlusStoredSession? = null,
    ) = OnePlusBleScannerIos(
        central = central,
        sessionHint = hint,
        nowMs = { clock },
        sleep = { millis ->
            clock += millis
            true
        },
    )

    private fun advertisement(
        name: String?,
        id: String,
        services: List<String> = emptyList(),
    ) = BleCentralEvent.ScanResult(
        name = name,
        id = id,
        advertisementBytes = null,
        serviceUuids = services,
    )

    // -- screen scan ------------------------------------------------------------------------

    @Test
    fun scanReportsAG7FamilyName() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }
        scanner.onEvent(advertisement(name = "DX02aS", id = "PERIPHERAL-1"))

        assertTrue(central.scanning)
        assertEquals(1, found.size)
        assertEquals("PERIPHERAL-1", found[0].address)
        assertTrue(scanner.isScanning())
    }

    @Test
    fun scanReportsANamelessSensorByItsAdvertisedService() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }
        scanner.onEvent(advertisement(name = null, id = "PERIPHERAL-2", services = listOf(advertisedService)))

        assertEquals(1, found.size)
        assertEquals("PERIPHERAL-2", found[0].address)
    }

    @Test
    fun scanIgnoresAG6MarketingNameEvenWithTheSameService() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }
        scanner.onEvent(advertisement(name = "Dexcom65", id = "PERIPHERAL-3", services = listOf(advertisedService)))
        scanner.onEvent(advertisement(name = "Some Headphones", id = "PERIPHERAL-4"))

        assertTrue(found.isEmpty())
    }

    @Test
    fun scanPrefersTheStoredSensorEvenWithAnOddName() {
        val central = FakeBleCentral()
        val hint = OnePlusStoredSession(
            identity = OnePlusSensorIdentity(pin = "1234"),
            lastMac = "PERIPHERAL-5",
        )
        val scanner = scanner(central, hint)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }
        scanner.onEvent(advertisement(name = "odd", id = "PERIPHERAL-5"))

        assertEquals(1, found.size)
    }

    @Test
    fun stopScanStopsTheRadioWhenNoWaitIsRunning() {
        val central = FakeBleCentral()
        val scanner = scanner(central)

        scanner.startScan { }
        scanner.stopScan()

        assertFalse(central.scanning)
        assertFalse(scanner.isScanning())
    }

    @Test
    fun scanIsRefusedWhileBluetoothIsOff() {
        val central = FakeBleCentral()
        central.adapter = BleAdapterState.PoweredOff
        val scanner = scanner(central)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }

        assertFalse(central.scanning)
        assertTrue(found.isEmpty())
    }

    // -- pre-connect wait --------------------------------------------------------------------

    @Test
    fun awaitTargetReturnsTheSensorItWasWaitingFor() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        central.onStartScan = {
            scanner.onEvent(advertisement(name = "DX02aS", id = "PERIPHERAL-6"))
        }

        val result = scanner.awaitTarget("PERIPHERAL-6", timeoutMs = 2_000L)

        val target = assertNotNull(result.target)
        assertEquals("PERIPHERAL-6", target.address)
        assertTrue(result.foreign.isEmpty())
        // The radio is given back when the screen is not scanning.
        assertFalse(central.scanning)
    }

    @Test
    fun awaitTargetReportsAnotherSensorWhenTheStoredOneStaysSilent() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        central.onStartScan = {
            scanner.onEvent(advertisement(name = "DX02zz", id = "PERIPHERAL-7"))
        }

        val result = scanner.awaitTarget("PERIPHERAL-8", timeoutMs = 20L)

        assertNull(result.target)
        assertEquals("PERIPHERAL-7", result.strongestForeign()?.address)
    }

    @Test
    fun awaitTargetIgnoresAG6CompanionAsAForeignSensor() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        central.onStartScan = {
            scanner.onEvent(advertisement(name = "DexcomONE", id = "PERIPHERAL-9"))
        }

        val result = scanner.awaitTarget("PERIPHERAL-8", timeoutMs = 20L)

        assertNull(result.target)
        assertTrue(result.foreign.isEmpty())
    }

    @Test
    fun awaitTargetIsRefusedWhileBluetoothIsOff() {
        val central = FakeBleCentral()
        central.adapter = BleAdapterState.PoweredOff
        val scanner = scanner(central)

        val result = scanner.awaitTarget("PERIPHERAL-6", timeoutMs = 20L)

        assertNull(result.target)
        assertEquals(0, central.scanStarts)
    }

    @Test
    fun awaitTargetBooksItsStartAgainstTheSharedScanBudget() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        central.onStartScan = {
            scanner.onEvent(advertisement(name = "DX02aS", id = "PERIPHERAL-6"))
        }

        scanner.awaitTarget("PERIPHERAL-6", timeoutMs = 2_000L)

        assertEquals(1, OnePlusScanBudget.startsInWindow(clock))
    }

    // -- state restoration -------------------------------------------------------------------

    /**
     * The pinned decision: a restored identifier is never turned into a sighting.
     *
     * iOS handing a peripheral back says the app was started again. It does not say the sensor is
     * advertising, and `OnePlusBleSessionCyclePolicy.allowConnection` refuses an advertisement-less
     * connect in restored-session mode for exactly that reason. Ending the wait here would buy
     * that connect with evidence that never arrived.
     */
    @Test
    fun awaitTargetIsNotEndedByARestoredIdentifier() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        central.onStartScan = {
            scanner.onEvent(BleCentralEvent.Restored(listOf("PERIPHERAL-6")))
        }

        val result = scanner.awaitTarget("PERIPHERAL-6", timeoutMs = 20L)

        assertNull(result.target)
        assertTrue(result.foreign.isEmpty())
    }

    /** The screen lists sensors it has heard. A restored identifier was not heard. */
    @Test
    fun theScreenIsNotToldAboutARestoredIdentifier() {
        val central = FakeBleCentral()
        val scanner = scanner(central)
        val found = mutableListOf<OnePlusScanResult>()

        scanner.startScan { found += it }
        scanner.onEvent(BleCentralEvent.Restored(listOf("PERIPHERAL-6", "PERIPHERAL-7")))

        assertTrue(found.isEmpty())
        assertTrue(scanner.isScanning())
    }
}
