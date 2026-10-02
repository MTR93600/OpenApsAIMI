package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * The Android half of [AimiSharing]: `ACTION_SEND` inside `Intent.createChooser`.
 *
 * The intents are the ones the two call sites built for themselves before this class existed - same
 * action, same MIME types, same extras, same `FileProvider` authority (`<packageName>.fileprovider`)
 * and the same read permission grant on the attachment.
 *
 * **One thing did have to change.** Both call sites used to start the chooser from a Compose
 * `LocalContext`, which is the Activity. The graph gives this class the application context instead,
 * and `startActivity` from outside an Activity throws unless the intent carries
 * `FLAG_ACTIVITY_NEW_TASK`. So the flag is now set on both paths. It was already set on the support
 * package path, which is the one shipping today, so this is the behaviour that is already proven on
 * a device rather than a new one.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiSharing @Inject constructor(
    private val context: Context,
) : AimiSharing {

    override fun shareText(text: String, subject: String, chooserTitle: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TEXT
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startChooser(intent, chooserTitle)
    }

    override fun shareFile(path: AimiPath, mimeType: String, subject: String, text: String, chooserTitle: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, File(path.value))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startChooser(intent, chooserTitle)
    }

    private fun startChooser(intent: Intent, chooserTitle: String) {
        val chooser = Intent.createChooser(intent, chooserTitle)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private companion object {

        const val MIME_TEXT = "text/plain"
    }
}
