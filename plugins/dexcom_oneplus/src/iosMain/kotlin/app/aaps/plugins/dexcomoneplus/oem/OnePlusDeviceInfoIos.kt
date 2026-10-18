package app.aaps.plugins.dexcomoneplus.oem

import platform.UIKit.UIDevice

/**
 * There is one maker of iPhones, so the OEM profile always lands on the generic one. That is the
 * right answer here: the per OEM profiles exist for the quirks of the various Android BLE stacks,
 * and CoreBluetooth has none of them.
 */
internal actual fun onePlusDeviceManufacturer(): String = "Apple"

internal actual fun onePlusDeviceModel(): String = UIDevice.currentDevice.model
