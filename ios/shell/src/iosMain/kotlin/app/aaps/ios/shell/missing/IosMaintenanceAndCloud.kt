package app.aaps.ios.shell.missing

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.maintenance.ExportResult
import app.aaps.core.interfaces.maintenance.Maintenance
import app.aaps.implementation.logging.IosLogFiles
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorReadingForUploading
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import kotlin.coroutines.resume

/*
 * Maintenance on iOS: sending and deleting the log files that `AAPSLoggerIos` writes.
 *
 * Screen usage statistics used to be here too. They moved to `platform`, because the app does not
 * collect them on iOS at all, which makes an empty answer correct rather than unfinished.
 */

/**
 * Sends the logs with the iOS share sheet, and deletes old ones.
 *
 * ## Sending
 *
 * Android writes an e-mail with the zipped logs attached. iOS has a mail composer too, but it only
 * works when the Mail app is set up, and many people use another mail app or none. The share sheet
 * covers all of them - Mail, Gmail, Messages, AirDrop, "Save to Files" - and is the counterpart of the
 * Android chooser. The logs are zipped into one file first (iOS zips a folder when it is read "for
 * uploading"), because 20 files of up to 5 MB are too much for most mail. If zipping fails, the plain
 * files are shared instead, so a user can still send something.
 *
 * The result is "sent" only when the user really sent it from the sheet; closing the sheet is
 * "not sent". The files are also always reachable by hand in **Files > On My iPhone > AAPS**.
 */
@OptIn(ExperimentalForeignApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosMaintenance(
    private val aapsLogger: AAPSLogger,
    /** Overridden in a test, so it neither reads the real log nor deletes it. */
    private val logDirectory: String? = IosLogFiles.defaultDirectory()
) : Maintenance {

    override suspend fun executeSendLogs(): ExportResult {
        val directory = logDirectory
        val files = directory?.let { IosLogFiles.allNewestFirst(it) }.orEmpty()
        if (directory == null || files.isEmpty()) {
            aapsLogger.error(LTag.CORE, "There are no log files to send")
            return ExportResult(localSuccess = false)
        }
        val items: List<NSURL> = zipLogs(directory, files)?.let { listOf(it) }
            ?: files.map { NSURL.fileURLWithPath("$directory/$it") }
        val sent = withContext(Dispatchers.Main) { share(items) }
        return ExportResult(localSuccess = sent)
    }

    /**
     * Removes rotated log files, keeping the newest [keep] files, the live one included.
     *
     * The logger keeps 20 files itself, so the periodic maintenance (30) deletes nothing, and the
     * "delete logs" button (5) leaves the newest four old files. The live `aaps.log` is never touched:
     * something is writing to it.
     */
    override fun deleteLogs(keep: Int) {
        val directory = logDirectory
        if (directory == null) {
            aapsLogger.debug(LTag.CORE, "No log directory, so there is nothing to delete")
            return
        }
        val manager = NSFileManager.defaultManager
        IosLogFiles.rotatedNewestFirst(directory).drop((keep - 1).coerceAtLeast(0)).forEach { name ->
            manager.removeItemAtPath("$directory/$name", null)
            aapsLogger.debug(LTag.CORE, "Deleted the old log file $name")
        }
    }

    /**
     * Copies [files] into a new folder and lets iOS zip it, or null when that fails.
     *
     * Reading a folder with `NSFileCoordinatorReadingForUploading` hands back a zip of it, valid only
     * inside the block, so it is copied out to the temporary directory there.
     */
    private fun zipLogs(directory: String, files: List<String>): NSURL? {
        val manager = NSFileManager.defaultManager
        val name = "AAPS-logs-" + stamp()
        val folder = NSTemporaryDirectory() + name
        val zip = NSURL.fileURLWithPath(NSTemporaryDirectory() + "$name.zip")
        manager.removeItemAtPath(folder, null)
        manager.removeItemAtURL(zip, null)
        if (!manager.createDirectoryAtPath(folder, withIntermediateDirectories = true, attributes = null, error = null)) return null
        files.forEach { manager.copyItemAtPath("$directory/$it", toPath = "$folder/$it", error = null) }

        var copied = false
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
            NSURL.fileURLWithPath(folder),
            options = NSFileCoordinatorReadingForUploading,
            error = null
        ) { zipped ->
            if (zipped != null) copied = manager.copyItemAtURL(zipped, toURL = zip, error = null)
        }
        manager.removeItemAtPath(folder, null)
        if (!copied) aapsLogger.error(LTag.CORE, "Could not zip the logs, sending the plain files")
        return zip.takeIf { copied }
    }

    private fun stamp(): String = NSDateFormatter().apply { dateFormat = "yyyy-MM-dd_HHmmss" }.stringFromDate(NSDate())

    /** Shows the share sheet over the screen in front, and waits until the user is done with it. */
    private suspend fun share(items: List<NSURL>): Boolean {
        val host = topViewController()
        if (host == null) {
            aapsLogger.error(LTag.CORE, "No screen to show the share sheet over")
            return false
        }
        return suspendCancellableCoroutine { continuation ->
            val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
            sheet.completionWithItemsHandler = { _, completed, _, _ ->
                if (continuation.isActive) continuation.resume(completed)
            }
            // On an iPad a sheet with no anchor is a crash, not a cosmetic problem.
            sheet.popoverPresentationController?.let { popover ->
                val anchor = host.view
                popover.sourceView = anchor
                if (anchor != null) {
                    popover.sourceRect = anchor.bounds.useContents { CGRectMake(size.width / 2.0, size.height / 2.0, 0.0, 0.0) }
                }
                popover.permittedArrowDirections = NO_ARROW
            }
            host.presentViewController(sheet, animated = true, completion = null)
        }
    }

    /** The screen in front. Presenting on a controller that is itself covered does nothing at all. */
    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            controller = controller.presentedViewController ?: return controller
        }
    }

    private companion object {

        /** `UIPopoverArrowDirectionUnknown`: the sheet is centred on the anchor with no arrow. */
        const val NO_ARROW: ULong = 0u
    }
}
