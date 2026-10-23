package app.aaps.pump.medtrum.ios

import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.flow.StateFlow

/**
 * What a "choose your pump" screen talks to. It has no UI of its own, so it can be driven by a
 * test exactly as a screen would drive it.
 *
 * It is the iOS half of what the Android `MedtrumPatchViewModel` does in its BLE scan step:
 * start a scan, collect the pumps that answer, and on a choice save the serial number and stop
 * scanning. Android then moves the wizard on to filling and priming the patch; there is no such
 * wizard here yet, so this session ends at the saved serial.
 *
 * The serial is stored as hex text, the same text under the same meaning as the Android
 * `MedtrumStringNonKey.SnInput`, and it is read back as the same [Long]. Nothing here ever writes
 * a serial that was not read from a real advertisement.
 */
class IosMedtrumDiscoverySession(
    private val scanner: IosMedtrumPumpScanner,
    private val store: IosMedtrumStateStore,
    private val state: MedtrumSessionState,
    /**
     * Called when the chosen serial is not the saved one, after [MedtrumSessionState.pumpSN] holds
     * the new serial. The driver then does what the Android `MedtrumService` does on a new
     * `SnInput`: model from the serial, patch reset, `connectNewPump`. See `IosMedtrumPump.onNewPumpSerial`.
     */
    private val onNewPump: () -> Unit = {}
) {

    /** The pumps found so far. Empty means none was found, never a pump with an unknown serial. */
    val pumps: StateFlow<List<MedtrumScannedPump>> get() = scanner.pumps

    /** True while a scan is running. */
    val scanning: Boolean get() = scanner.scanning

    /**
     * Starts looking for pumps. Returns false when Bluetooth is off, so a screen can say that
     * instead of showing an empty list forever.
     */
    fun start(): Boolean = scanner.startScan()

    /** Stops looking. What was found stays on screen so a choice can still be made. */
    fun stop() = scanner.stopScan()

    /**
     * Keeps the chosen pump: the scan stops, the serial is saved, and the live state carries it
     * at once, so the next connect attempt uses it without a restart.
     *
     * When it is another pump than the saved one, the saved Bluetooth identifier is forgotten (it
     * belongs to the old pump) and [onNewPump] runs. Choosing the same pump again changes nothing.
     *
     * Returns false when the pump is not one that this scan offered. A serial from anywhere else
     * is never written.
     */
    fun select(pump: MedtrumScannedPump): Boolean {
        if (scanner.pumps.value.none { it.id == pump.id && it.serial == pump.serial }) return false
        val previous = store.loadPumpSerial()
        scanner.stopScan()
        store.savePumpSerial(pump.serialHex)
        state.pumpSN = pump.serial
        if (previous != pump.serial) {
            store.forgetPeripheralId()
            onNewPump()
        }
        store.save(state)
        return true
    }

    /** The saved serial, or `null` when no pump has been chosen yet. */
    fun selectedSerial(): Long? = store.loadPumpSerial()
}
