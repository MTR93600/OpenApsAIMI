package app.aaps.pump.apex.utils.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

/** Values the Apex driver stores for itself. They are not on the settings screen. */
enum class ApexStringNonKey(
    override val key: String,
    override val defaultValue: String,
) : StringNonPreferenceKey {

    LastConnectedSerialNumber("apex_last_connected_serial_number", ""),
    BluetoothAddress("apex_bt_address", ""),
}
