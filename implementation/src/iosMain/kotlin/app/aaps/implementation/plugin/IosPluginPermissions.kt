package app.aaps.implementation.plugin

import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PermissionGroup
import app.aaps.core.interfaces.plugin.PermissionProvider
import app.aaps.core.interfaces.plugin.PluginPermissions
import app.aaps.implementation.ImplementationStrings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The permissions an iOS master phone needs, as iOS reports them, so a refused one is shown.
 *
 * iOS has no list of permissions to enumerate the way Android has `Manifest.permission`, but the
 * ones that matter here can each be read:
 *
 * - **Bluetooth**, always listed. The pump and the sensor both talk over it, so without it the phone
 *   neither reads glucose nor doses insulin.
 * - **Notifications**, always listed. Alarms reach the user through them.
 * - **Location**, only when something declares a group with [LOCATION] - an automation rule that
 *   uses where the phone is.
 *
 * **Health is not listed, on purpose.** AAPS only reads from Health, and iOS never tells an app
 * whether it may read: a refusal looks exactly like having no data. A row that says "granted" or
 * "missing" there would claim a check that iOS does not allow.
 *
 * The ids are not Android permission strings, and nothing outside iOS ever sees them. A group with
 * any other id cannot be checked here, so it is left out and logged at error, as before: listing it
 * as missing would send the user looking for a switch iOS does not have, and listing it as granted
 * would claim a check that never happened.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosPluginPermissions(
    private val aapsLogger: AAPSLogger,
    private val activePlugin: ActivePlugin,
    private val status: IosPermissionStatus,
    private val permissionProviders: () -> Set<PermissionProvider>
) : PluginPermissions {

    override fun collectMissingPermissions(): List<PermissionGroup> =
        collectAllPermissions().filter { group -> group.permissions.any { isMissing(it) } }

    override fun collectAllPermissions(): List<PermissionGroup> = baseGroups() + declaredGroups()

    /**
     * Reads again what iOS can only answer with a callback, then calls [onDone] on the main queue.
     * The shell calls this when the app comes to the front, as Android refreshes in `onResume`.
     */
    fun refresh(onDone: () -> Unit) = status.refresh(onDone)

    /** Asks for the permissions in [group], or opens the Settings app when iOS will not ask again. */
    fun request(group: PermissionGroup, onDone: () -> Unit) {
        val permission = group.permissions.firstOrNull { it in KNOWN } ?: return onDone()
        status.ask(permission, onDone)
    }

    private fun isMissing(permission: String): Boolean = when (status.state(permission)) {
        IosPermissionState.DENIED, IosPermissionState.NOT_ASKED -> true
        IosPermissionState.GRANTED, IosPermissionState.UNKNOWN  -> false
    }

    private fun baseGroups(): List<PermissionGroup> = listOf(
        PermissionGroup(
            permissions = listOf(BLUETOOTH),
            rationaleTitle = InterfacesStrings.permission_bluetooth_title,
            rationaleDescription = InterfacesStrings.permission_bluetooth_description
        ),
        PermissionGroup(
            permissions = listOf(NOTIFICATIONS),
            rationaleTitle = ImplementationStrings.permission_notifications_title,
            rationaleDescription = ImplementationStrings.permission_notifications_description
        )
    )

    /** Groups from plugins and providers. Only those with an id this class can check are kept. */
    private fun declaredGroups(): List<PermissionGroup> {
        val declared = activePlugin.getPluginsList().filter { it.isEnabled() }.flatMap { it.requiredPermissions() } +
            permissionProviders().flatMap { it.requiredPermissions() }
        val (checkable, unknown) = declared.partition { group -> group.permissions.all { it in KNOWN } }
        if (unknown.isNotEmpty()) {
            aapsLogger.error(
                LTag.CORE,
                "A plugin declares permissions iOS cannot check: " + unknown.flatMap { it.permissions }.distinct().joinToString()
            )
        }
        // A plugin may ask for Bluetooth too; it is already in the list once.
        return checkable.filter { group -> group.permissions.none { it in BASE } }
    }

    companion object {

        const val BLUETOOTH = "ios.permission.BLUETOOTH"
        const val NOTIFICATIONS = "ios.permission.NOTIFICATIONS"
        const val LOCATION = "ios.permission.LOCATION"

        private val BASE = setOf(BLUETOOTH, NOTIFICATIONS)
        private val KNOWN = BASE + LOCATION
    }
}
