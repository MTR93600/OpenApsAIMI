package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientIos
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerIos

/**
 * Sends one central's events to the scanner and to the GATT client.
 *
 * `CoreBluetoothCentral` has a single `events` slot, and the ONE+ driver has two listeners for it:
 * the scanner needs the advertisements and the client needs everything else. Wiring only one of
 * them costs nothing at compile time and shows up much later as a sensor that is never found, or a
 * connection that never becomes ready, so the wiring lives here once rather than in every caller.
 *
 * The owner builds the central, points it here with `central.events = radio::onEvent` **before**
 * calling `open()`, and then hands the same central to the scanner and to each GATT client. A
 * client is rebuilt for every connection attempt, which is why [gatt] is a `var`.
 *
 * ⚠️ ASYNC IMPACT: [onEvent] runs on the central's serial queue. Both listeners only record what
 * they heard and wake whoever is waiting, so nothing here blocks that queue.
 */
class OnePlusIosRadio {

    var scanner: OnePlusBleScannerIos? = null

    var gatt: OnePlusGattClientIos? = null

    fun onEvent(event: BleCentralEvent) {
        scanner?.onEvent(event)
        gatt?.onEvent(event)
    }
}
