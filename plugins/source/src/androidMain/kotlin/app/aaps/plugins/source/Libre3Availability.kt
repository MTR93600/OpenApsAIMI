package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef

/**
 * Outcome of the Libre 3 availability check.
 *
 * [MarkerFileMissing] and the three access failures are kept apart on purpose: only the access
 * failures mean "we could not find out", and only they raise the restore access notification. A
 * plain missing marker file is a normal, silent "not for this build" state.
 */
sealed interface Libre3Availability {

    /** AAPS directory readable, `extra` readable, and the exact marker file is present. */
    data object Available : Libre3Availability

    /** Directory access is fine, the marker file simply is not there. Never notifies. */
    data object MarkerFileMissing : Libre3Availability

    /** A directory URI is stored but the saved read and write grant is gone. */
    data object FolderPermissionMissing : Libre3Availability

    /** No AAPS directory has been selected at all, or the stored tree URI no longer resolves. */
    data object AapsFolderUnavailable : Libre3Availability

    /** Anything else that stopped us from confirming the marker. Treated as unavailable. */
    data class TechnicalError(val reason: String) : Libre3Availability
}

/**
 * Single source of truth for "may the native Libre 3 plugin be offered at all".
 *
 * The plugin is offered only when the engineering marker file exists at
 * `Documents/AAPS/extra/engineering_libre3`. The file is never opened or parsed. Only its
 * existence is tested, by exact name, in the `extra` subdirectory of the selected AAPS directory
 * and nowhere else.
 *
 * This uses the project's marker file convention ([FileListProvider.ensureExtraDirExists]) but not
 * `app.aaps.core.interfaces.configuration.Config.isEnabled`: that helper caches its answer for the
 * whole process life and cannot tell "file absent" from "directory not reachable", and those two
 * cases need different handling here.
 *
 * Consumed only by `Libre3NativePlugin.specialShowInListCondition`, which is what
 * [app.aaps.core.interfaces.plugin.ActivePlugin.getSpecificPluginsVisibleInList] filters on, so
 * Config Builder, the Setup Wizard, search and Quick Launch all inherit the gate from one place.
 */
class Libre3AvailabilityProvider @Inject constructor(
    private val aapsLogger: AAPSLogger,
    // Lazy on purpose: FileListProvider pulls in Config, Preferences and Storage, and this provider
    // is built as part of a BG source plugin that is itself in the plugin graph.
    private val fileListProvider: Provider<FileListProvider>,
    private val preferences: Preferences,
    private val notificationManager: NotificationManager,
    private val dateUtil: DateUtil
) {

    companion object {

        /**
         * Exact name of the marker file, without extension. Defined here once. No other file may
         * repeat this literal.
         */
        const val LIBRE3_ACCESS_FILE_NAME = "engineering_libre3"

        /**
         * How long an answer is reused. Long enough that repeated `showInList` calls while a list
         * is drawn do not each hit storage, short enough that dropping the file in, or losing the
         * grant, is seen without an app restart. A change of the directory URI skips the cache.
         */
        const val CACHE_TTL_MS = 60_000L
    }

    private data class CacheEntry(val uri: String?, val at: Long, val value: Libre3Availability)

    @Volatile private var cache: CacheEntry? = null

    /** True only while the restore access notification we posted is still up. */
    @Volatile private var accessNotificationPosted = false

    /** Short gate for the plugin. */
    fun isAvailable(): Boolean = evaluateAvailability() is Libre3Availability.Available

    /**
     * Current availability, served from the cache unless the AAPS directory changed or the entry
     * is too old. Side effect: posts or clears the restore access notification on a change.
     */
    @Synchronized
    fun evaluateAvailability(): Libre3Availability {
        val uri = storedDirectoryUri()
        val now = dateUtil.now()
        cache?.let { if (it.uri == uri && now - it.at < CACHE_TTL_MS) return it.value }
        return evaluateNow(uri, now)
    }

    /**
     * Drop the cached answer and check again now. For callers that know the storage state just
     * changed, for example the directory was selected again.
     */
    @Synchronized
    fun invalidate(): Libre3Availability = evaluateNow(storedDirectoryUri(), dateUtil.now())

    private fun evaluateNow(uri: String?, now: Long): Libre3Availability {
        val result = compute(uri)
        cache = CacheEntry(uri, now, result)
        syncAccessNotification(result)
        aapsLogger.debug(LTag.CORE, "LIBRE3_AVAILABILITY: $result")
        return result
    }

    private fun storedDirectoryUri(): String? = preferences.getIfExists(StringKey.AapsDirectoryUri)

    /**
     * Never returns [Libre3Availability.Available] unless the marker was really found. Every
     * failure path ends in an unavailable state.
     */
    private fun compute(uri: String?): Libre3Availability {
        if (uri.isNullOrEmpty()) return Libre3Availability.AapsFolderUnavailable
        return try {
            if (!fileListProvider().isDirectoryAccessGranted()) return Libre3Availability.FolderPermissionMissing
            // Resolving `extra` also creates it when it is missing, which is the project's normal
            // behaviour for this directory. A fresh `extra` simply has no marker file, and that is
            // MarkerFileMissing, not an error.
            val extraDir = fileListProvider().ensureExtraDirExists()
                ?: return Libre3Availability.TechnicalError("extra directory not resolvable")
            // Existence only. The file is never opened, read or interpreted.
            if (extraDir.findFile(LIBRE3_ACCESS_FILE_NAME) != null) Libre3Availability.Available
            else Libre3Availability.MarkerFileMissing
        } catch (_: SecurityException) {
            Libre3Availability.FolderPermissionMissing
        } catch (e: Exception) {
            Libre3Availability.TechnicalError(e.javaClass.simpleName)
        }
    }

    /**
     * Posts the restore access notification once per loss, and clears it when access comes back, so
     * a later loss can notify again. A missing marker file never touches the notification.
     */
    private fun syncAccessNotification(result: Libre3Availability) {
        val accessLost = when (result) {
            Libre3Availability.Available,
            Libre3Availability.MarkerFileMissing     -> false

            Libre3Availability.FolderPermissionMissing,
            Libre3Availability.AapsFolderUnavailable,
            is Libre3Availability.TechnicalError     -> true
        }
        if (accessLost) {
            if (!accessNotificationPosted) {
                accessNotificationPosted = true
                notificationManager.post(
                    id = NotificationId.LIBRE3_DIR_ACCESS_LOST,
                    textRef = TextRef.AndroidRes(R.string.libre3_aaps_directory_access_lost),
                    actions = listOf(
                        NotificationAction(TextRef.AndroidRes(R.string.libre3_aaps_directory_select)) {}
                    )
                )
            }
        } else if (accessNotificationPosted) {
            accessNotificationPosted = false
            notificationManager.dismiss(NotificationId.LIBRE3_DIR_ACCESS_LOST)
        }
    }
}
