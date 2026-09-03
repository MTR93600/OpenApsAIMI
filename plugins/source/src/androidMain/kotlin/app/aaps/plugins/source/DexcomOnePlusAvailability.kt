package app.aaps.plugins.source

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.FileListProvider
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.utils.DateUtil
import dev.zacsweers.metro.Inject
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef

/**
 * Outcome of the Dexcom ONE+ availability check.
 *
 * [MarkerFileMissing] and the three access failures are deliberately distinct: only the access
 * failures mean "we could not determine anything", and only they raise the restore-access
 * notification. A plain missing marker file is a normal, silent "not for this build" state.
 */
sealed interface DexcomOnePlusAvailability {

    /** AAPS directory readable, `extra` readable, and the exact marker file is present. */
    data object Available : DexcomOnePlusAvailability

    /** Directory access is fine, the marker file simply is not there. Never notifies. */
    data object MarkerFileMissing : DexcomOnePlusAvailability

    /** A directory URI is stored but the persisted SAF read/write grant is gone. */
    data object FolderPermissionMissing : DexcomOnePlusAvailability

    /** No AAPS directory has been selected at all, or the stored tree URI no longer resolves. */
    data object AapsFolderUnavailable : DexcomOnePlusAvailability

    /** Anything else that stopped us from confirming presence — treated as unavailable. */
    data class TechnicalError(val reason: String) : DexcomOnePlusAvailability
}

/**
 * Single source of truth for "may the Dexcom ONE+ plugin be offered at all".
 *
 * The plugin is offered only when the engineering marker file exists at
 * `Documents/AAPS/extra/engineering_oneplus`. The file is never opened or parsed — only its
 * existence is tested, by exact name, in the `extra` subdirectory of the selected AAPS directory
 * and nowhere else.
 *
 * This reuses the project's existing marker-file convention (`extra` directory +
 * [FileListProvider.ensureExtraDirExists], the same storage path
 * `app.aaps.core.interfaces.configuration.Config.isEnabled` uses for
 * `app.aaps.core.interfaces.configuration.ExternalOptions`) but not `Config.isEnabled` itself:
 * that helper caches its answer for the whole process lifetime and collapses "file absent" and
 * "directory not reachable" into a single `false`, and ONE+ must tell those two apart.
 *
 * Consumed only by `DexcomOnePlusPlugin.specialShowInListCondition`, which is what
 * `app.aaps.core.interfaces.plugin.ActivePlugin.getSpecificPluginsVisibleInList` filters on — so
 * Config Builder, the Setup Wizard, search and Quick Launch all inherit the gate from one place.
 * No other component evaluates the marker file.
 */
class DexcomOnePlusAvailabilityProvider @Inject constructor(
    private val aapsLogger: AAPSLogger,
    // Lazy, mirroring ConfigImpl: FileListProvider pulls in Config/Preferences/Storage, and this
    // provider is constructed as part of a BG source plugin that is itself in the plugin graph.
    private val fileListProvider: () -> FileListProvider,
    private val preferences: Preferences,
    private val notificationManager: NotificationManager,
    private val dateUtil: DateUtil
) {

    companion object {

        /**
         * Exact, extension-less name of the marker file. Defined here once; no other file may
         * repeat the literal.
         */
        const val ONE_PLUS_ACCESS_FILE_NAME = "engineering_oneplus"

        /**
         * Re-evaluation interval. Long enough that repeated `showInList` calls while a list is
         * being built do not each hit SAF, short enough that dropping the file in (or losing the
         * grant) is picked up without an app restart. A directory-URI change bypasses this
         * entirely, so re-selecting the folder in Maintenance takes effect immediately.
         */
        const val CACHE_TTL_MS = 60_000L
    }

    private data class CacheEntry(val uri: String?, val at: Long, val value: DexcomOnePlusAvailability)

    @Volatile private var cache: CacheEntry? = null

    /** True only while the restore-access notification we posted is still up (dedup latch). */
    @Volatile private var accessNotificationPosted = false

    /** Convenience gate for the plugin. */
    fun isAvailable(): Boolean = evaluateAvailability() is DexcomOnePlusAvailability.Available

    /**
     * Current availability, served from the cache unless the AAPS directory changed or the entry
     * aged out. Side effect: posts / dismisses the restore-access notification on transitions.
     */
    @Synchronized
    fun evaluateAvailability(): DexcomOnePlusAvailability {
        val uri = storedDirectoryUri()
        val now = dateUtil.now()
        cache?.let { if (it.uri == uri && now - it.at < CACHE_TTL_MS) return it.value }
        return evaluateNow(uri, now)
    }

    /**
     * Drop the cached answer and re-check immediately. For callers that know the storage state
     * just changed (directory re-selected, permission re-granted).
     */
    @Synchronized
    fun invalidate(): DexcomOnePlusAvailability = evaluateNow(storedDirectoryUri(), dateUtil.now())

    private fun evaluateNow(uri: String?, now: Long): DexcomOnePlusAvailability {
        val result = compute(uri)
        cache = CacheEntry(uri, now, result)
        syncAccessNotification(result)
        aapsLogger.debug(LTag.CORE, "Dexcom ONE+ availability: $result")
        return result
    }

    private fun storedDirectoryUri(): String? = preferences.getIfExists(StringKey.AapsDirectoryUri)

    /**
     * Never returns [DexcomOnePlusAvailability.Available] unless presence was positively
     * confirmed — every failure path falls through to an unavailable state.
     */
    private fun compute(uri: String?): DexcomOnePlusAvailability {
        if (uri.isNullOrEmpty()) return DexcomOnePlusAvailability.AapsFolderUnavailable
        return try {
            if (!fileListProvider().isDirectoryAccessGranted()) return DexcomOnePlusAvailability.FolderPermissionMissing
            // Resolving `extra` also creates it when missing — the project's standard behaviour for
            // this directory (MainApp password-reset checks and ConfigImpl rely on it). A freshly
            // created `extra` simply has no marker file, which is MarkerFileMissing, not an error.
            val extraDir = fileListProvider().ensureExtraDirExists()
                ?: return DexcomOnePlusAvailability.TechnicalError("extra directory not resolvable")
            // Existence only — the file is never opened, read or interpreted.
            if (extraDir.findFile(ONE_PLUS_ACCESS_FILE_NAME) != null) DexcomOnePlusAvailability.Available
            else DexcomOnePlusAvailability.MarkerFileMissing
        } catch (_: SecurityException) {
            DexcomOnePlusAvailability.FolderPermissionMissing
        } catch (e: Exception) {
            DexcomOnePlusAvailability.TechnicalError(e.javaClass.simpleName)
        }
    }

    /**
     * Posts the restore-access notification once per loss, and clears it once access comes back —
     * so a later loss can notify again. A missing marker file never touches the notification.
     */
    private fun syncAccessNotification(result: DexcomOnePlusAvailability) {
        val accessLost = when (result) {
            DexcomOnePlusAvailability.Available,
            DexcomOnePlusAvailability.MarkerFileMissing          -> false

            DexcomOnePlusAvailability.FolderPermissionMissing,
            DexcomOnePlusAvailability.AapsFolderUnavailable,
            is DexcomOnePlusAvailability.TechnicalError          -> true
        }
        if (accessLost) {
            if (!accessNotificationPosted) {
                accessNotificationPosted = true
                notificationManager.post(
                    id = NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST,
                    textRef = TextRef.AndroidRes(R.string.dexcom_oneplus_aaps_directory_access_lost),
                    actions = listOf(
                        NotificationAction(TextRef.AndroidRes(R.string.dexcom_oneplus_aaps_directory_select)) {}
                    )
                )
            }
        } else if (accessNotificationPosted) {
            accessNotificationPosted = false
            notificationManager.dismiss(NotificationId.DEXCOM_ONEPLUS_DIR_ACCESS_LOST)
        }
    }
}
