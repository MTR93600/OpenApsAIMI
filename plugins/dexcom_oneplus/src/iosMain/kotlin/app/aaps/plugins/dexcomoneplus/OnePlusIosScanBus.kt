package app.aaps.plugins.dexcomoneplus

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.pump.ble.session.BleCentral
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusBluetoothUuids

/**
 * The one CoreBluetooth scan, shared by every ONE+ slot.
 *
 * An app gets a single `scanForPeripherals` from one central: a second call replaces the first,
 * and `stopScan` ends it for everybody. Two pre-soak and production scanners therefore must not
 * each own the radio. They record what they need here, and this object is the only one that
 * talks to [BleCentral.startScan] and [BleCentral.stopScan].
 *
 * Connections are not scans. Stopping a scan does not cancel a peripheral, and a second
 * `connectPeripheral` does not drop the first. The sensor that feeds the loop stays connected
 * while the pre-soak slot scans, and the other way around.
 *
 * ## Which scan actually runs
 *
 * A filtered scan (the Dexcom advertisement service) is what iOS still delivers while the phone
 * is locked. An unfiltered scan is what the start screen wants, so it can list every nearby
 * sensor. While **any** slot is waiting on the filtered scan, the central stays filtered: switching
 * it to unfiltered would make a locked phone go silent and the production wait would miss its
 * sensor. An unfiltered screen still receives the filtered advertisements, because a ONE+ / G7
 * advertises that service. When nobody is waiting on a filter, a screen scan is unfiltered. When
 * nobody wants a scan, the radio stops.
 *
 * Asking again for a scan that is already running starts it once more. CoreBluetooth reports each
 * peripheral once per scan, so a slot that joins an ongoing scan would otherwise never hear a
 * sensor the first slot had already seen.
 *
 * ⚠️ ASYNC IMPACT: [request] and [release] may be called from a driver thread or the main thread.
 * The demand map and the central call share one lock. A `stopScan` decided earlier cannot land
 * after a `startScan` decided later: the central call runs before the lock is released, so the
 * calls stay in the order the decisions were made.
 */
class OnePlusIosScanBus(private val central: BleCentral) {

    enum class Mode { OFF, UNFILTERED, FILTERED }

    private val lock = AapsLock()
    private val demands = mutableMapOf<Any, Mode>()
    private var applied = Mode.OFF

    /**
     * [owner] now wants [mode]. [Mode.OFF] is [release].
     *
     * A filtered demand from one owner is never replaced by another owner's unfiltered demand.
     */
    fun request(owner: Any, mode: Mode) {
        if (mode == Mode.OFF) {
            release(owner)
            return
        }
        lock.withLock {
            demands[owner] = mode
            applyLocked(forceRestart = true)
        }
    }

    /** [owner] no longer wants a scan. The radio stays on while someone else does. */
    fun release(owner: Any) {
        lock.withLock {
            if (demands.remove(owner) == null && applied == effective()) return@withLock
            applyLocked(forceRestart = false)
        }
    }

    /** What the central is doing right now. Tests read it; the scanners do not need to. */
    fun appliedMode(): Mode = lock.withLock { applied }

    private fun effective(): Mode = when {
        demands.values.any { it == Mode.FILTERED }   -> Mode.FILTERED
        demands.values.any { it == Mode.UNFILTERED } -> Mode.UNFILTERED
        else                                         -> Mode.OFF
    }

    /** Applies the current demands. Caller holds [lock], and the central call does too. */
    private fun applyLocked(forceRestart: Boolean) {
        val next = effective()
        if (next == Mode.OFF) {
            val stop = applied != Mode.OFF
            applied = Mode.OFF
            if (stop) central.stopScan()
            return
        }
        val changed = next != applied
        applied = next
        if (!changed && !forceRestart) return
        when (next) {
            Mode.FILTERED   -> central.startScan(listOf(OnePlusBluetoothUuids.Advertisement))
            Mode.UNFILTERED -> central.startScan()
            Mode.OFF        -> central.stopScan()
        }
    }
}
