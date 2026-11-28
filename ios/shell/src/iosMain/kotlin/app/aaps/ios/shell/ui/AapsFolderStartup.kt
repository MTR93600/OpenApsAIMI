package app.aaps.ios.shell.ui

import app.aaps.core.interfaces.ios.IosAapsDirectories
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.NotificationAction
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ios.shell.config.IosAppRole
import platform.Foundation.NSFileManager

/**
 * The `Documents/AAPS` folder at app start: made, filled from older builds, and checked.
 *
 * [prepare] runs first thing in `startAapsApp`, before the logger writes a line, before `IosConfig`
 * reads `extra` and before the UAM model is installed. The graph does not exist yet then, so what
 * it did is kept and [report] writes it to the log and posts a notification once the graph is
 * built.
 */
internal object AapsFolderStartup {

    private var prepared: IosAapsDirectories.PrepareResult? = null
    private var failure: Throwable? = null

    /**
     * Makes the folders, moves the files of older builds in, and writes the README. Once per
     * process; a second call does nothing. Never throws: a file problem must not stop the app.
     */
    fun prepare() {
        if (prepared != null || failure != null) return
        try {
            prepared = IosAapsDirectories().prepare(isClient = IosAppRole.fromBundle() == IosAppRole.CLIENT)
        } catch (e: Exception) {
            failure = e
        }
    }

    /** Writes what [prepare] did to the log, and posts the notification when a folder is missing. */
    fun report(logger: AAPSLogger, notificationManager: NotificationManager) {
        failure?.let { logger.error(LTag.CORE, "The AAPS folder could not be prepared", it) }
        val result = prepared
        result?.logLines()?.forEach { logger.info(LTag.CORE, it) }
        if (failure != null || result?.ensure?.ok == false) postProblem(notificationManager)
    }

    /**
     * The master app only, after the plugins have started: the UAM model install step ran, and the
     * model is still missing or cannot be read.
     */
    fun reportMissingUamModel(modelMissing: Boolean, logger: AAPSLogger, notificationManager: NotificationManager) {
        if (!modelMissing) return
        logger.error(LTag.CORE, "The UAM model is still missing after the install step")
        postProblem(notificationManager)
    }

    /**
     * The folder the Files app is opened at: `Documents/AAPS` when it is there, else `Documents`
     * itself, so the button still leads somewhere when the AAPS folder could not be made.
     */
    fun filesAppFolder(): String? {
        val directories = IosAapsDirectories()
        val root = directories.root
        return if (root != null && NSFileManager.defaultManager.fileExistsAtPath(root)) root else directories.documents
    }

    /**
     * One notification for every case. The action does nothing itself: tapping it goes to
     * `ElementNavigator.handleNotificationAction`, which calls `onRequestDirectoryAccess`, and on iOS
     * that opens the Files app at [filesAppFolder].
     */
    private fun postProblem(notificationManager: NotificationManager) {
        notificationManager.post(
            id = NotificationId.AAPS_DIR_ACCESS_LOST,
            textRef = CoreUiStrings.aaps_folder_problem_ios,
            actions = listOf(NotificationAction(CoreUiStrings.aaps_folder_open_files_ios) {})
        )
    }
}
