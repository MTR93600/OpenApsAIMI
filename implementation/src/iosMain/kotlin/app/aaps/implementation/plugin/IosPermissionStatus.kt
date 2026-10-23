package app.aaps.implementation.plugin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBManager
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationNotDetermined
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/** What iOS says about one permission. */
enum class IosPermissionState {

    GRANTED,

    /** Refused, or blocked by a parent or a device policy. Only the Settings app can change it now. */
    DENIED,

    /** Never asked. iOS shows its own question the first time the app asks. */
    NOT_ASKED,

    /** Not read yet. Reported as granted, so a sheet does not open on a guess. */
    UNKNOWN
}

/**
 * The iOS permission states `IosPluginPermissions` shows, behind an interface so it can be tested
 * without the system.
 *
 * Bluetooth and location can be read at any time. Notifications can only be read with a callback,
 * so that state is kept from the last [refresh].
 */
interface IosPermissionStatus {

    fun state(permission: String): IosPermissionState

    /** Reads the states that need a callback again. [onDone] runs on the main queue. */
    fun refresh(onDone: () -> Unit)

    /**
     * Asks iOS for [permission] when it was never asked, otherwise opens this app's page in the
     * Settings app: iOS asks only once, so after a refusal that page is the only place left to change
     * it. [onDone] runs on the main queue once the state may have changed.
     */
    fun ask(permission: String, onDone: () -> Unit)
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class SystemIosPermissionStatus(
    private val aapsLogger: AAPSLogger
) : IosPermissionStatus {

    @Volatile private var notifications: IosPermissionState = IosPermissionState.UNKNOWN

    /** Kept, because the question is shown only while a manager exists. */
    private var bluetoothAsker: CBCentralManager? = null
    private var locationAsker: CLLocationManager? = null

    override fun state(permission: String): IosPermissionState = when (permission) {
        IosPluginPermissions.BLUETOOTH     -> bluetoothState()
        IosPluginPermissions.NOTIFICATIONS -> notifications
        IosPluginPermissions.LOCATION      -> locationState()
        else                               -> IosPermissionState.UNKNOWN
    }

    override fun refresh(onDone: () -> Unit) {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            notifications = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> IosPermissionState.GRANTED
                UNAuthorizationStatusNotDetermined                                                            -> IosPermissionState.NOT_ASKED
                null                                                                                          -> IosPermissionState.UNKNOWN
                else                                                                                          -> IosPermissionState.DENIED
            }
            dispatch_async(dispatch_get_main_queue()) { onDone() }
        }
    }

    override fun ask(permission: String, onDone: () -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            if (state(permission) != IosPermissionState.NOT_ASKED) {
                openSettings()
                onDone()
                return@dispatch_async
            }
            when (permission) {
                IosPluginPermissions.BLUETOOTH     -> {
                    // Creating a manager is what makes iOS ask. The drivers create their own later.
                    bluetoothAsker = CBCentralManager(delegate = null, queue = null)
                    onDone()
                }

                IosPluginPermissions.NOTIFICATIONS ->
                    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                        UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
                    ) { granted, error ->
                        if (error != null) aapsLogger.error(LTag.NOTIFICATION, "Notification permission failed: $error")
                        else aapsLogger.debug(LTag.NOTIFICATION, "Notification permission granted=$granted")
                        refresh(onDone)
                    }

                IosPluginPermissions.LOCATION      -> {
                    locationAsker = (locationAsker ?: CLLocationManager()).also { it.requestWhenInUseAuthorization() }
                    onDone()
                }

                else                               -> onDone()
            }
        }
    }

    private fun bluetoothState(): IosPermissionState = when (CBManager.authorization) {
        CBManagerAuthorizationAllowedAlways -> IosPermissionState.GRANTED
        CBManagerAuthorizationNotDetermined -> IosPermissionState.NOT_ASKED
        else                                -> IosPermissionState.DENIED
    }

    private fun locationState(): IosPermissionState = when ((locationAsker ?: CLLocationManager().also { locationAsker = it }).authorizationStatus) {
        kCLAuthorizationStatusAuthorizedAlways, kCLAuthorizationStatusAuthorizedWhenInUse -> IosPermissionState.GRANTED
        kCLAuthorizationStatusNotDetermined                                              -> IosPermissionState.NOT_ASKED
        else                                                                             -> IosPermissionState.DENIED
    }

    private fun openSettings() {
        val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any?>(), completionHandler = null)
    }
}
