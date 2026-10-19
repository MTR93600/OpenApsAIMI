package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.core.interfaces.pump.ble.session.BleCentralEvent
import app.aaps.core.interfaces.pump.ble.session.BleRestoreIdentifier
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
 * The owner must build that central with [BleRestoreIdentifier.DEXCOM_ONE_PLUS], never with the
 * default: the default belongs to the shared pump transport, and two centrals on one identifier
 * are offered the same kept state, so one of the two silently loses restoration. The same central
 * is given here as [central], because this object is the one part of the ONE+ radio that always
 * exists, and a restored peripheral has to be hung up on even when no scan and no connection
 * attempt is running.
 *
 * ## A restored link is never taken over
 *
 * iOS can start the app again for a Bluetooth event and hand back the peripherals it kept. The
 * ONE+ driver releases every one of them and starts again from a scan. Four reasons, each on its
 * own enough:
 *
 * - The pairing handshake has no Apple build at all, so a restored and connected peripheral
 *   cannot lead to a usable session here.
 * - The handshake is per link. The stored shared key only lets the library skip rounds, it still
 *   runs over the live link, and there is no session object to resume.
 * - After a relaunch the central's characteristic map is empty unless iOS happened to restore it,
 *   so a client that adopted the link could not even write.
 * - Two sessions on one transmitter kill both inside the handshake. `OnePlusMacArbiter` records
 *   that this is certain rather than occasional, and names a resume path with no UI as what
 *   reproduced it. A restoration callback is exactly such a path.
 *
 * Worst of all, Control indications left over from the exchange before the app was ended would be
 * parsed as current EGV and time frames. The transmitter clock is kept as "this value, at that
 * moment", so a stale frame becomes a glucose reading stamped with a wrong time, which the loop
 * would use as if it were now.
 *
 * Nothing is dropped in silence: every restored peripheral is logged and released here, and both
 * listeners are told as well so a field trace shows what each of them decided.
 *
 * ⚠️ ASYNC IMPACT: [onEvent] runs on the central's serial queue. Both listeners only record what
 * they heard and wake whoever is waiting, and [BleCentral.release] does not block, so nothing here
 * holds that queue up.
 */
class OnePlusIosRadio(private val central: BleCentral) {

    var scanner: OnePlusBleScannerIos? = null

    var gatt: OnePlusGattClientIos? = null

    fun onEvent(event: BleCentralEvent) {
        scanner?.onEvent(event)
        gatt?.onEvent(event)
        // After both listeners, so a client that recognised one of these ids as its own link has
        // already given it up and woken its waiting threads before the peripheral goes away.
        if (event is BleCentralEvent.Restored) releaseRestored(event.ids)
    }

    /**
     * Hangs up on everything iOS handed back.
     *
     * An empty list is normal and is still logged: it means the relaunch happened, which is the
     * fact a field trace needs. A peripheral that was released is gone from the central's map, so
     * the next connection attempt goes through the normal scan and finds it again.
     */
    private fun releaseRestored(ids: List<String>) {
        OnePlusLog.i(
            "${OnePlusLogMarkers.SESSION}: iOS started the app again and handed back ${ids.size} " +
                "peripheral(s); the ONE+ driver takes none of them over",
        )
        ids.forEach { id ->
            OnePlusLog.i(
                "${OnePlusLogMarkers.SESSION}: releasing restored peripheral ***${id.takeLast(5)}",
            )
            central.release(id)
        }
    }
}
