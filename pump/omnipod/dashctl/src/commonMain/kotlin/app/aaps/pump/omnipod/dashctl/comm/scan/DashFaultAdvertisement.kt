package app.aaps.pump.omnipod.dashctl.comm.scan

/**
 * The advertisement of a Dash pod in fault, as OmnipodKit (Trio) reads it in `BluetoothManager`
 * (`alarmServiceUUIDs`, `startScanning`, `didDiscover`).
 *
 * The 2nd service UUID of a Dash pod advertisement shows its state: C001 in normal use, C005 while an
 * alert is set (a reminder is almost always set), C00A in fault (OmnipodKit captured C001 -> C005 ->
 * C00A on an occlusion). Only a pod in fault lists C00A.
 *
 * Not Android: Android has no scan while the pod is not connected. Deliberate difference from
 * Android (insulin counted wrong): a pod that faults stops all delivery, and without this the app
 * learns it only at the next status read, up to one loop later; meanwhile the loop counts basal and
 * temp basal that the pod does not give. See `DashHeldConnection` (the fault watch).
 */
object DashFaultAdvertisement {

    /**
     * OmnipodKit `BluetoothManager.alarmServiceUUIDs` = [C00A], as the 128-bit text form the central
     * gives and takes. The scan filters on this UUID ONLY. OmnipodKit measured why: the iOS filter is
     * an OR, and with C005 in it the pod matches all the time, so iOS treats it as found already and
     * does not report (or wake the app for) the change to C00A (about 7 minutes late). With C00A only,
     * the pod does not match in normal use, so a fault is a new discovery, and iOS wakes a suspended
     * app for it.
     */
    const val FAULT_SERVICE_UUID = "0000c00a-0000-1000-8000-00805f9b34fb"

    /** The 16-bit form of [FAULT_SERVICE_UUID], as CoreBluetooth may print it. */
    const val FAULT_SERVICE_UUID16 = "c00a"

    /**
     * True when the advertisement lists the fault UUID. The whole UUID is compared (16-bit or the
     * 128-bit form of the Bluetooth base), never the characters 4 to 8 of any UUID: a custom 128-bit
     * UUID that has "c00a" at that place is not the fault.
     */
    fun isFault(serviceUuids: List<String>): Boolean =
        serviceUuids.any { it.equals(FAULT_SERVICE_UUID, ignoreCase = true) || it.equals(FAULT_SERVICE_UUID16, ignoreCase = true) }
}
