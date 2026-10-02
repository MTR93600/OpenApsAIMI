package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The desktop JVM half of [AimiSharing]. **It does not share anything.**
 *
 * This is deliberate, and it is not the same decision as an empty iOS implementation would be. A
 * phone has a system share sheet, so a stub there would hide a capability the platform has. A
 * desktop JVM has no such thing: there is no list of apps to hand a file to, and `java.awt.Desktop`
 * offers only `MAIL`, which opens a `mailto:` address - it cannot attach the support ZIP at all, and
 * it truncates a long report at the URI length limit. Pretending that is the chooser would send a
 * cut-off support package, which is worse than sending none.
 *
 * So both methods log an error naming what was not shared, and return. Not silent: a user who
 * presses Export on a desktop build and sees nothing happen can find out why from the log. The
 * desktop answer, when there is one, is a save-file dialog rather than a share sheet, and that is a
 * different feature with a different interface.
 */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class JvmAimiSharing @Inject constructor(
    private val aapsLogger: AAPSLogger,
) : AimiSharing {

    override fun shareText(text: String, subject: String, chooserTitle: String) {
        aapsLogger.error(LTag.APS, "$TAG this platform has no share sheet, so \"$subject\" was not shared")
    }

    override fun shareFile(path: AimiPath, mimeType: String, subject: String, text: String, chooserTitle: String) {
        aapsLogger.error(LTag.APS, "$TAG this platform has no share sheet, so the $mimeType file was not shared")
    }

    private companion object {

        const val TAG = "JvmAimiSharing:"
    }
}
