package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.pump.ble.session.BleAdapterState
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.pump.medtrum.session.MedtrumClock
import app.aaps.pump.medtrum.session.MedtrumSessionState
import platform.Foundation.NSUserDefaults
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Finding a Medtrum pump on iOS and keeping its serial number.
 *
 * Driven through the same kind of fake radio as [IosMedtrumDriverTest]: scan results are pushed
 * into [IosMedtrumBleTransport.onEvent], which is where CoreBluetooth would deliver them.
 */
class IosMedtrumDiscoveryTest {

    private val nowMs = 1_700_000_000_000L

    private class FakeCentral : BleCentral {

        var adapterState: BleAdapterState = BleAdapterState.PoweredOn
        var scanning = false
        var scanStarts = 0

        override fun adapterState(): BleAdapterState = adapterState
        override fun startScan() { scanning = true; scanStarts++ }
        override fun stopScan() { scanning = false }
        override fun connect(id: String): Boolean = true
        override fun cancel(id: String) = Unit
        override fun release(id: String) = Unit
        override fun discoverServices(id: String) = Unit
        override fun discoverCharacteristics(id: String, serviceUuid: String) = Unit
        override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean = true
        override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean = true
        override fun cachedName(id: String): String? = null
    }

    /** iOS manufacturer payload: 2-byte little endian company id + 4-byte serial + type + version. */
    private fun medtrumAdvertisement(serial: Long, companyId: Int = IosMedtrumBleTransport.MANUFACTURER_ID): ByteArray {
        val out = ByteArray(8)
        out[0] = companyId.toByte()
        out[1] = (companyId shr 8).toByte()
        out[2] = serial.toByte()
        out[3] = (serial shr 8).toByte()
        out[4] = (serial shr 16).toByte()
        out[5] = (serial shr 24).toByte()
        out[6] = 1
        out[7] = 2
        return out
    }

    private class Rig(suite: String) {

        val central = FakeCentral()
        val transport = IosMedtrumBleTransport(central)
        val defaults = NSUserDefaults(suiteName = suite)!!
        val store = IosMedtrumStateStore(defaults, prefix = "t.")
        val state = MedtrumSessionState(clock = MedtrumClock.fixed(1_700_000_000_000L))
        val session = IosMedtrumDiscoverySession(transport.scanner, store, state)

        fun advertise(name: String?, id: String, bytes: ByteArray?) {
            transport.onEvent(BleCentralEvent.ScanResult(name = name, id = id, advertisementBytes = bytes))
        }
    }

    private fun rig(name: String) = Rig("medtrum-discovery-$name-$nowMs")

    // -- Finding ----------------------------------------------------------------------

    @Test
    fun offersAMedtrumPumpAndReadsItsSerial() {
        val rig = rig("found")
        try {
            assertTrue(rig.session.start())
            assertTrue(rig.central.scanning)
            rig.advertise(name = "MT", id = "pump-1", bytes = medtrumAdvertisement(0x2F4A1BL))

            val found = rig.session.pumps.value
            assertEquals(1, found.size)
            assertEquals("MT-2F4A1B", found[0].name)
            assertEquals("pump-1", found[0].id)
            assertEquals(0x2F4A1BL, found[0].serial)
            assertEquals("2F4A1B", found[0].serialHex)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun offersAPumpThatAdvertisesItsFullNameWithNoManufacturerData() {
        val rig = rig("namedonly")
        try {
            assertTrue(rig.session.start())
            rig.advertise(name = "MT-3039", id = "pump-2", bytes = null)

            assertEquals(1, rig.session.pumps.value.size)
            assertEquals(12345L, rig.session.pumps.value[0].serial)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun ignoresADeviceThatIsNotAMedtrumPump() {
        val rig = rig("notmedtrum")
        try {
            assertTrue(rig.session.start())
            // Another maker's advertisement with a name that is not a pump name.
            rig.advertise(name = "Dexcom ONE+", id = "cgm-1", bytes = medtrumAdvertisement(0x2F4A1BL, companyId = 0x00E0))
            rig.advertise(name = "Some phone", id = "phone-1", bytes = null)

            assertTrue(rig.session.pumps.value.isEmpty())
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun ignoresANameWithNothingAfterThePrefix() {
        val rig = rig("emptyserial")
        try {
            assertTrue(rig.session.start())
            rig.advertise(name = "MT-", id = "pump-3", bytes = null)
            // A Medtrum advertisement that carries a zero serial is no better than no serial.
            rig.advertise(name = "MT", id = "pump-4", bytes = medtrumAdvertisement(0L))

            assertTrue(rig.session.pumps.value.isEmpty())
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun listsEachPumpOnceEvenWhenItKeepsAdvertising() {
        val rig = rig("duplicates")
        try {
            assertTrue(rig.session.start())
            repeat(4) { rig.advertise(name = "MT", id = "pump-5", bytes = medtrumAdvertisement(0x55L)) }

            assertEquals(1, rig.session.pumps.value.size)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun findsNothingWhenNothingAnswers() {
        val rig = rig("nothing")
        try {
            assertTrue(rig.session.start())
            rig.session.stop()

            assertTrue(rig.session.pumps.value.isEmpty())
            assertNull(rig.session.selectedSerial())
            assertEquals(0L, rig.state.pumpSN)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun refusesToScanWhenBluetoothIsOff() {
        val rig = rig("btoff")
        try {
            rig.central.adapterState = BleAdapterState.PoweredOff
            assertFalse(rig.session.start())
            assertFalse(rig.central.scanning)
            assertEquals(0, rig.central.scanStarts)
            assertFalse(rig.session.scanning)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun ignoresAdvertisementsWhenNoScanIsRunning() {
        val rig = rig("notscanning")
        try {
            rig.advertise(name = "MT", id = "pump-6", bytes = medtrumAdvertisement(0x66L))
            assertTrue(rig.session.pumps.value.isEmpty())
        } finally {
            rig.store.clear()
        }
    }

    // -- Choosing ---------------------------------------------------------------------

    @Test
    fun theChosenSerialSurvivesAStoreRoundTrip() {
        val rig = rig("roundtrip")
        try {
            assertTrue(rig.session.start())
            rig.advertise(name = "MT", id = "pump-7", bytes = medtrumAdvertisement(0x2F4A1BL))
            val pump = rig.session.pumps.value.single()

            assertTrue(rig.session.select(pump))
            assertFalse(rig.central.scanning)
            assertFalse(rig.session.scanning)
            // The live state carries it at once, so no restart is needed before a connect.
            assertEquals(0x2F4A1BL, rig.state.pumpSN)
            assertEquals(0x2F4A1BL, rig.session.selectedSerial())

            // A fresh state reads back the same number, which is what Android gets from its hex text.
            val restarted = MedtrumSessionState(clock = MedtrumClock.fixed(nowMs))
            rig.store.load(restarted)
            assertEquals(0x2F4A1BL, restarted.pumpSN)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun theSerialIsReadBackEvenWhenNoPatchHasRunYet() {
        val rig = rig("serialonly")
        try {
            // Only the serial is saved: a pump was picked but nothing else has happened.
            rig.store.savePumpSerial("2F4A1B")
            val fresh = MedtrumSessionState(clock = MedtrumClock.fixed(nowMs))
            rig.store.load(fresh)
            assertEquals(0x2F4A1BL, fresh.pumpSN)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun refusesAPumpThatThisScanNeverOffered() {
        val rig = rig("unknown")
        try {
            assertTrue(rig.session.start())
            rig.advertise(name = "MT", id = "pump-8", bytes = medtrumAdvertisement(0x11L))
            val madeUp = MedtrumScannedPump(name = "MT-99", id = "pump-99", serial = 0x99L)

            assertFalse(rig.session.select(madeUp))
            assertNull(rig.session.selectedSerial())
            assertEquals(0L, rig.state.pumpSN)
        } finally {
            rig.store.clear()
        }
    }

    @Test
    fun clearingTheStoreForgetsTheSerial() {
        val rig = rig("cleared")
        rig.store.savePumpSerial("2F4A1B")
        rig.store.clear()
        assertNull(rig.store.loadPumpSerial())
    }
}
