package app.aaps.plugins.sync.garmin

/**
 * The Connect IQ iPhone SDK, as the Kotlin plugin is allowed to see it.
 *
 * Swift owns the SDK (`SwiftConnectIqCompanion`). Payloads cross as text: a JSON object for a
 * glucose message, a JSON array for the device list. The plugin decides when to send; this only
 * moves the bytes.
 */
interface ConnectIqCompanion {

    /**
     * [appIdsCsv] is the watch app ids, comma separated, 32 hex characters each, the same ids
     * Android pushes to.
     */
    fun start(callback: ConnectIqCallback, appIdsCsv: String)

    /** The alert shown when Garmin Connect is not installed, in the phone's language. */
    fun setInstallPrompt(title: String, message: String, accept: String, decline: String)

    /**
     * Unregisters then registers device events for one watch. Used once when a restored watch stays
     * connected without `deviceCharacteristicsDiscovered`.
     */
    fun reregisterDevice(deviceUuid: String)

    fun showDeviceSelection()

    /**
     * The URL Garmin Connect opened to return the chosen watches. True when the scheme is ours.
     */
    fun handleOpenUrl(url: String): Boolean

    /** Puts the saved watches back into the SDK after a restart and listens to them again. */
    fun restoreDevices(encoded: String)

    fun send(
        deviceUuid: String,
        modelName: String,
        friendlyName: String,
        partNumber: String,
        appId: String,
        payloadJson: String,
        token: String,
    )

    /**
     * Drops every SDK listener and the Kotlin callback. A later completion must not reach the plugin.
     * Safe to call more than once.
     */
    fun stop()
}

/** What the SDK tells the plugin. Called on whatever thread the SDK uses. */
interface ConnectIqCallback {

    fun devicesReplaced(encoded: String)

    fun deviceStatus(deviceUuid: String, status: String)

    fun appStatus(deviceUuid: String, appId: String, installed: Boolean, version: Int)

    fun messageReceived(deviceUuid: String, appId: String, payloadJson: String)

    fun sendFinished(deviceUuid: String, appId: String, result: String, token: String)
}

/**
 * Set from the Swift app entry, before the graph starts the plugin. The same shape as the meal
 * advisor camera factory: Swift builds it, Kotlin cannot.
 */
object ConnectIqRegistry {
    var companion: ConnectIqCompanion? = null
}

fun installConnectIqCompanion(companion: ConnectIqCompanion) {
    ConnectIqRegistry.companion = companion
}
