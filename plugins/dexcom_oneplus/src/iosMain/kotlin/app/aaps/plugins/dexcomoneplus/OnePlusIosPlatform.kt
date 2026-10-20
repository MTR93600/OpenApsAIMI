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
 * ## One slot only
 *
 * Android runs a second driver for a pre-soak sensor, with its own scanner and its own GATT client.
 * That cannot be copied here as it stands: an app gets **one** scan from CoreBluetooth, so a second
 * slot's `stopScan` would end the first slot's scan, and the radio holds one scanner and one client
 * at a time. A second slot would therefore work sometimes and stop working silently, which is the
 * one shape this driver must not take. The pre-soak slot stays on the stub driver on iPhone, and
 * `DexcomOnePlusIosHost` says so where a reader will look for it.
 */
class OnePlusIosPlatform : OnePlusPlatform {

    private val central = CoreBluetoothCentral(BleRestoreIdentifier.DEXCOM_ONE_PLUS)

    private val radio = OnePlusIosRadio(central)

    init {
        central.events = radio::onEvent
        central.open()
    }

    override fun sensorPrefs(fileName: String): OnePlusSensorPrefs = OnePlusSensorPrefsIos(fileName)

    /**
     * The scanner, which is also registered with the radio so advertisements reach it.
     *
     * A driver asks for this once per `attach`, so the registration replaces the previous scanner
     * rather than adding to it - the same single-scanner rule the radio is written for.
     */
    override fun scanner(hint: OnePlusStoredSession?, slot: String): OnePlusBleScanner =
        OnePlusBleScannerIos(central, sessionHint = hint, slot = slot).also { radio.scanner = it }

    /**
     * A fresh client for one connection attempt, registered with the radio in its place.
     *
     * The client the attempt before used is dropped here. It has already given up its link by the
     * time a new one is asked for, and every one of its handlers ignores an event for an id that is
     * not its own, so nothing it still held could act on what follows.
     */
    override fun gattClient(profile: OemDeviceProfile): OnePlusGattClient =
        OnePlusGattClientIos(central, profile).also { radio.gatt = it }

    override fun cpuLease(slot: String): OnePlusCpuLease = OnePlusIosCpuLease(slot)
}
