package app.aaps.core.interfaces.pump.ble.session

import app.aaps.core.interfaces.pump.ble.BleTransportListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * iOS state restoration: the part of it that is not CoreBluetooth.
 *
 * What a test binary can reach is the restore identifiers, the rule for turning one
 * `willRestoreState` into events and what [BleSession] does with the event. `willRestoreState`
 * itself cannot be raised here: only iOS raises it, on a real phone, after it has started the app
 * again for a Bluetooth event.
 */
class BleRestoreTest {

    /**
     * A golden test on purpose.
     *
     * iOS matches the kept Bluetooth state by this exact string. Change it and restoration simply
     * stops for every phone that already has the app, without an error anywhere. So the values are
     * written out a second time here: an edit has to be deliberate and has to touch this file too.
     */
    @Test
    fun `restore identifiers keep their stored value`() {
        assertEquals("app.aaps.ble.pump.transport", BleRestoreIdentifier.PUMP_TRANSPORT)
        assertEquals("app.aaps.ble.pump.medtrum", BleRestoreIdentifier.MEDTRUM)
        assertEquals("app.aaps.ble.cgm.dexcom.oneplus", BleRestoreIdentifier.DEXCOM_ONE_PLUS)
    }

    /** Two centrals on one identifier would be offered the same kept state. */
    @Test
    fun `restore identifiers differ from each other`() {
        val all = listOf(
            BleRestoreIdentifier.PUMP_TRANSPORT,
            BleRestoreIdentifier.MEDTRUM,
            BleRestoreIdentifier.DEXCOM_ONE_PLUS
        )
        assertEquals(all.size, all.toSet().size)
    }

    @Test
    fun `a restore with peripherals reports them in one event`() {
        val events = bleRestoreEvents(listOf("AA", "BB"))
        assertEquals(1, events.size)
        val restored = assertIs<BleCentralEvent.Restored>(events[0])
        assertEquals(listOf("AA", "BB"), restored.ids)
    }

    /**
     * An empty restore is still a restore. It says iOS started the app again for Bluetooth, which
     * is worth knowing even when nothing came back with it.
     */
    @Test
    fun `a restore with nothing in it is still reported`() {
        val events = bleRestoreEvents(emptyList())
        assertEquals(1, events.size)
        val restored = assertIs<BleCentralEvent.Restored>(events[0])
        assertTrue(restored.ids.isEmpty())
    }

    /**
     * A peripheral lost here is a device that stays connected at the radio while the app believes
     * it owns nothing. It is reported, and reported before the list of what was kept.
     */
    @Test
    fun `an entry that is not a peripheral is reported and not dropped`() {
        val events = bleRestoreEvents(listOf("AA"), dropped = 2)
        assertEquals(2, events.size)
        val failed = assertIs<BleCentralEvent.OperationFailed>(events[0])
        assertEquals("restore", failed.operation)
        assertTrue(failed.message.contains("2"))
        val restored = assertIs<BleCentralEvent.Restored>(events[1])
        assertEquals(listOf("AA"), restored.ids)
    }

    @Test
    fun `the session keeps the restored ids`() {
        val session = session()
        session.onEvent(BleCentralEvent.Restored(listOf("AA", "BB")))
        assertEquals(listOf("AA", "BB"), session.restoredIds)
    }

    @Test
    fun `the session starts with no restored ids`() {
        assertTrue(session().restoredIds.isEmpty())
    }

    /**
     * The session must not act on a restore by itself. After a relaunch it is a brand new object
     * with no link and nothing to re-attach to, so the owner decides whether to connect again.
     */
    @Test
    fun `a restore does not touch the radio and does not call the listener`() {
        val central = CountingCentral()
        val listener = CountingListener()
        val session = session(central)
        session.setListener(listener)
        session.onEvent(BleCentralEvent.Restored(listOf("AA")))
        assertTrue(central.calls.isEmpty())
        assertEquals(0, listener.calls)
    }

    private fun session(central: BleCentral = CountingCentral()): BleSession = BleSession(
        central = central,
        profile = BleProfile(
            notifyCharacteristicUuid = "0000fff1-0000-1000-8000-00805f9b34fb",
            writeCharacteristicUuid = "0000fff2-0000-1000-8000-00805f9b34fb"
        )
    )

    private class CountingListener : BleTransportListener {
        var calls: Int = 0
        override fun onConnectionStateChanged(connected: Boolean) {
            calls += 1
        }

        override fun onServicesDiscovered(success: Boolean) {
            calls += 1
        }

        override fun onDescriptorWritten() {
            calls += 1
        }

        override fun onCharacteristicChanged(data: ByteArray) {
            calls += 1
        }

        override fun onCharacteristicWritten() {
            calls += 1
        }
    }

    private class CountingCentral : BleCentral {
        val calls = mutableListOf<String>()
        override fun adapterState(): BleAdapterState = BleAdapterState.PoweredOn
        override fun startScan() {
            calls += "startScan"
        }

        override fun stopScan() {
            calls += "stopScan"
        }

        override fun connect(id: String): Boolean {
            calls += "connect:$id"
            return true
        }

        override fun cancel(id: String) {
            calls += "cancel:$id"
        }

        override fun release(id: String) {
            calls += "release:$id"
        }

        override fun discoverServices(id: String) {
            calls += "discover:$id"
        }

        override fun discoverCharacteristics(id: String, serviceUuid: String) {
            calls += "chars:$serviceUuid"
        }

        override fun write(id: String, characteristicUuid: String, data: ByteArray, withResponse: Boolean): Boolean {
            calls += "write"
            return true
        }

        override fun enableNotify(id: String, characteristicUuid: String, indicate: Boolean): Boolean {
            calls += "notify"
            return true
        }

        override fun cachedName(id: String): String? = null
    }
}
