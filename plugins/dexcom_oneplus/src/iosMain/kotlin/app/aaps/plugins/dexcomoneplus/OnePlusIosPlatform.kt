package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.pump.ble.session.BleRestoreIdentifier
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientIos
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorPrefs
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorPrefsIos
import app.aaps.plugins.dexcomoneplus.identity.OnePlusStoredSession
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerIos
import app.aaps.plugins.dexcomoneplus.session.OnePlusCpuLease
import app.aaps.plugins.dexcomoneplus.session.OnePlusIosCpuLease

/**
 * [OnePlusPlatform] on iPhone: the same four favours, over CoreBluetooth and `NSUserDefaults`.
 *
 * This is the piece that was missing. Everything under it already existed - the scanner, the GATT
 * client, the store and the shared KEKS handshake - but nothing built them together, so
 * `OnePlusCgmDriverReal` had no iPhone host to attach to.
 *
 * ## One central, built here, opened here
 *
 * `OnePlusIosRadio` needs the central's events before the central is opened, because iOS delivers
 * `willRestoreState` while the manager is still being built. So this class builds the central,
 * wires the radio to it and opens it, in that order, and does it once.
 *
 * It must be built with [BleRestoreIdentifier.DEXCOM_ONE_PLUS]. The default identifier belongs to
 * the shared pump transport, and two centrals on one identifier are offered the same kept state, so
 * one of them silently loses restoration.
 *
 * ⚠️ Building this object **asks iOS for Bluetooth**: a `CBCentralManager` is what raises the
 * permission prompt. So it is built when the plugin starts, never while the dependency graph is
 * being assembled.
 *
 * ## One central, two slots
 *
 * Android runs a second driver for a pre-soak sensor, with its own scanner and its own GATT
 * client. CoreBluetooth still gives the app **one** scan, so the two scanners do not each call
 * `scanForPeripherals`. [OnePlusIosScanBus] is the only owner of that scan and hands every
 * advertisement to both slots. A `stopScan` from the pre-soak slot does not stop a scan the
 * production slot still needs, and a filtered wait (the one that works with the phone locked) is
 * not replaced by the start screen's unfiltered scan.
 *
 * Two peripheral connections are a different thing, and iOS allows them. Each slot gets its own
 * GATT client, both stay registered on [OnePlusIosRadio], and each client ignores events for the
 * other slot's identifier. The sensor that feeds the loop is never disconnected because the
 * pre-soak slot connected, scanned, or stopped scanning. Between readings both slots use the held
 * connect (`OemDeviceProfile.holdsPendingConnect`), which does not scan at all and is kept by iOS
 * while the app is suspended.
 */
class OnePlusIosPlatform : OnePlusPlatform {

    private val central = CoreBluetoothCentral(BleRestoreIdentifier.DEXCOM_ONE_PLUS)

    private val radio = OnePlusIosRadio(central)

    private val scanBus = OnePlusIosScanBus(central)

    init {
        central.events = radio::onEvent
        central.open()
    }

    override fun sensorPrefs(fileName: String): OnePlusSensorPrefs = OnePlusSensorPrefsIos(fileName)

    /**
     * The scanner for one driver slot, registered with the radio so advertisements reach it.
     *
     * A second call adds another scanner. It does not replace the first: the production driver and
     * the pre-soak driver each keep the one they were given.
     */
    override fun scanner(hint: OnePlusStoredSession?, slot: String): OnePlusBleScanner =
        openScanner(hint = hint, slot = slot, ephemeral = false)

    /**
     * The start screen's scanner. It is dropped when the screen stops scanning, so a closed screen
     * cannot keep a demand on the shared scan.
     */
    fun screenScanner(): OnePlusBleScanner =
        openScanner(hint = null, slot = OnePlusLogMarkers.SLOT_PRODUCTION, ephemeral = true)

    private fun openScanner(
        hint: OnePlusStoredSession?,
        slot: String,
        ephemeral: Boolean,
    ): OnePlusBleScanner {
        lateinit var created: OnePlusBleScannerIos
        created = OnePlusBleScannerIos(
            central = central,
            sessionHint = hint,
            slot = slot,
            scanBus = scanBus,
            isEphemeral = ephemeral,
            onRetired = { radio.detachScanner(created) },
        )
        radio.attachScanner(created)
        return created
    }

    /**
     * A fresh client for one connection attempt, registered beside any client that still has a link.
     *
     * The production connection and the pre-soak connection are both live. The client a finished
     * attempt left behind is dropped here, once it has disconnected.
     */
    override fun gattClient(profile: OemDeviceProfile): OnePlusGattClient {
        lateinit var created: OnePlusGattClientIos
        created = OnePlusGattClientIos(central, profile, onConnectAttempt = { radio.attachGatt(created) })
        radio.attachGatt(created)
        return created
    }

    override fun cpuLease(slot: String): OnePlusCpuLease = OnePlusIosCpuLease(slot)
}
