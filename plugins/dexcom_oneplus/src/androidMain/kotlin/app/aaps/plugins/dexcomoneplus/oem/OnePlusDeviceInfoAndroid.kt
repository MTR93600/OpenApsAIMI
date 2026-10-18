package app.aaps.plugins.dexcomoneplus.oem

import android.os.Build

internal actual fun onePlusDeviceManufacturer(): String = Build.MANUFACTURER

internal actual fun onePlusDeviceModel(): String = Build.MODEL
