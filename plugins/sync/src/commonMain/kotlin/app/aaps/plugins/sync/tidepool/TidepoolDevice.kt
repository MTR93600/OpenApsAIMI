package app.aaps.plugins.sync.tidepool

/** How AAPS names itself to Tidepool. */
object TidepoolDevice {

    /**
     * Dataset `deviceManufacturers`, `deviceModel` and `deviceId`, and the prefix of every record's
     * `deviceId` (`AAPS:<pump serial>`). Tidepool shows the device and picks the pump settings layout by
     * this exact string, so NEVER change it.
     * Before 4.0 this was "Tandem". Those old datasets have no `deviceId`, so `TidepoolUploader.startSession`
     * does not find them any more and opens a new dataset. The old data stays in Tidepool under the old name.
     */
    const val NAME = "AAPS"
}
