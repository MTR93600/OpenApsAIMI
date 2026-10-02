package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * Hands something to the operating system's share sheet.
 *
 * Two AIMI Advisor features end this way. The basal proposal dialog shares a generated report as
 * plain text, and the support package screen shares a ZIP. Everything before that hand-off - reading
 * the loop history, building the report, writing the archive - is ordinary code that runs anywhere;
 * only the last line is platform work, and this interface is that last line.
 *
 * It is an interface with bindings rather than an `expect fun` because the Android side needs an
 * injected `Context`, which an `expect` signature cannot name. That is the same shape as
 * [app.aaps.plugins.aps.openAPSAIMI.tpo.TpoNotifications].
 *
 * Nothing here reports success. The Android chooser and the iOS share sheet are both fire and
 * forget: the user may pick a target, or back out, and neither platform tells the caller which.
 * Both call sites already treat the share as the end of their flow, so there is no decision to feed
 * an answer back into.
 *
 * Implementations: `AndroidAimiSharing`, `IosAimiSharing`, `JvmAimiSharing`.
 */
interface AimiSharing {

    /**
     * Shares [text] as plain text, with [subject] offered to targets that have one (mail, mostly)
     * and [chooserTitle] on the picker.
     */
    fun shareText(text: String, subject: String, chooserTitle: String)

    /**
     * Shares the file at [path] as an attachment of type [mimeType], with [text] as the accompanying
     * message, [subject] offered to targets that have one, and [chooserTitle] on the picker.
     *
     * [path] must be a file the app itself wrote, because Android shares it through its own
     * `FileProvider`.
     */
    fun shareFile(path: AimiPath, mimeType: String, subject: String, text: String, chooserTitle: String)
}
