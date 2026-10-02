package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.UIKit.UIActivityItemSourceProtocol
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

/**
 * The iOS half of [AimiSharing]: `UIActivityViewController`, the direct counterpart of the Android
 * chooser.
 *
 * Both platforms show the same thing - the system list of places this can go - so the two features
 * behave the same way. There is no stub here and no early return on the normal path: iOS has a share
 * sheet, and a silent implementation would leave the support package screen looking as though it had
 * sent something.
 *
 * ## Details that differ from Android
 *
 * **Subject.** Android puts it in `Intent.EXTRA_SUBJECT` and lets each target decide. iOS has no
 * equivalent extra, so the subject arrives through [SubjectItemSource], a `UIActivityItemSource`
 * whose `subjectForActivityType` answers it. Mail picks it up as the message subject; the targets
 * that have no subject ignore it, exactly as on Android.
 *
 * **Text with a file.** Android sends the covering message as `EXTRA_TEXT` alongside the attachment.
 * Here both go in the item list, so a mail target gets the message in the body and the ZIP attached.
 *
 * **The file URL.** Android needs a `FileProvider` to lend another app read access to its own
 * sandbox. iOS does that itself when the item is a file URL, so the path goes in as
 * `NSURL.fileURLWithPath` and nothing else is needed.
 *
 * **iPad.** A sheet with no anchor is a hard crash there, not a cosmetic problem, so the popover is
 * anchored to the middle of the presenting view. On iPhone the anchor is ignored.
 *
 * ## When it cannot present
 *
 * The only early return is when there is no view controller to present from - the app is in the
 * background, or the window has gone. That is logged as an error rather than passed over in silence.
 * It is the same condition `IosAuthBrowser` guards, and in practice it cannot happen from these two
 * call sites, which are both buttons on a screen the user is looking at.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class IosAimiSharing @Inject constructor(
    private val aapsLogger: AAPSLogger,
) : AimiSharing {

    override fun shareText(text: String, subject: String, chooserTitle: String) {
        present(listOf(SubjectItemSource(text, subject)), chooserTitle)
    }

    override fun shareFile(path: AimiPath, mimeType: String, subject: String, text: String, chooserTitle: String) {
        // mimeType has no counterpart here: iOS reads the type from the file name through its own
        // uniform type identifiers, so passing it would change nothing.
        val url = NSURL.fileURLWithPath(path.value)
        present(listOf(SubjectItemSource(text, subject), url), chooserTitle)
    }

    /**
     * Shows the sheet over whatever is in front.
     *
     * [chooserTitle] becomes the sheet's own title. iOS shows it only when the sheet has room, which
     * is why it is set on the controller rather than inserted as an item - an item would appear in
     * the shared text itself.
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun present(items: List<Any>, chooserTitle: String) {
        val host = topViewController()
        if (host == null) {
            aapsLogger.error(LTag.APS, "$TAG no screen to show the share sheet over")
            return
        }
        val sheet = UIActivityViewController(activityItems = items, applicationActivities = null)
        sheet.setTitle(chooserTitle)
        sheet.popoverPresentationController?.let { popover ->
            val anchor = host.view
            popover.sourceView = anchor
            if (anchor != null) {
                popover.sourceRect = anchor.bounds.useContents {
                    CGRectMake(size.width / 2.0, size.height / 2.0, 0.0, 0.0)
                }
            }
            popover.permittedArrowDirections = NO_ARROW
        }
        host.presentViewController(sheet, animated = true, completion = null)
    }

    /**
     * The screen currently in front.
     *
     * Walking down from the root rather than presenting on the root itself: the AIMI screen is
     * already presented over it, and presenting on a controller that is itself covered does nothing
     * at all - silently. Same walk as `IosAuthBrowser`.
     */
    private fun topViewController(): UIViewController? {
        var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
        while (true) {
            controller = controller.presentedViewController ?: return controller
        }
    }

    private companion object {

        const val TAG = "IosAimiSharing:"

        /** `UIPopoverArrowDirectionUnknown`: the sheet is centred on the anchor with no arrow. */
        const val NO_ARROW: ULong = 0u
    }
}

/**
 * One shared item that also carries a subject.
 *
 * `UIActivityViewController` asks each item for its subject, and a plain `String` item has none, so
 * mail would go out with an empty subject line where Android fills one in from
 * `Intent.EXTRA_SUBJECT`. This is the supported way to answer that question.
 *
 * The placeholder is the text itself rather than an empty string: iOS uses it to decide which
 * targets to offer before it asks for the real item, and an empty placeholder makes it offer the
 * wrong ones.
 */
private class SubjectItemSource(
    private val text: String,
    private val subject: String,
) : NSObject(), UIActivityItemSourceProtocol {

    override fun activityViewControllerPlaceholderItem(activityViewController: UIActivityViewController): Any = text

    // Two Objective-C selectors, one Kotlin signature: both take a UIActivityViewController and a
    // nullable activity type, and Kotlin does not look at parameter names. @ObjCSignatureOverride is
    // what the compiler asks for here, and the selector still picks the right one at run time.
    @ObjCSignatureOverride
    override fun activityViewController(activityViewController: UIActivityViewController, itemForActivityType: String?): Any = text

    @ObjCSignatureOverride
    override fun activityViewController(activityViewController: UIActivityViewController, subjectForActivityType: String?): String = subject
}
