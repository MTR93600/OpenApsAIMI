package app.aaps.plugins.aps.openAPSAIMI.utils

import android.content.Context
import android.os.Environment
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Android half of [AimiStudyLocations].
 *
 * The two entries and their order are **not** a new decision. They are the list
 * `HormonitorViewerScreen` has always built inline, moved here unchanged so that every reader of the
 * study files uses the same one:
 *
 * ```
 * runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(File(it, "Documents/AAPS")) }
 * runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(File(it, "AAPS")) }
 * ```
 *
 * Both `runCatching` wrappers are kept. A device with no external volume mounted makes
 * `getExternalStorageDirectory` fail, and the viewer has always answered that by dropping the entry
 * and carrying on with the app-scoped one rather than by throwing.
 *
 * ## Why this does not call [AimiStorageHelper]
 *
 * The helper picks **one** directory and only after proving it can write to it - that is its job, and
 * it is the right answer for a learner. It is the wrong answer here twice over: a folder the app can
 * read but not write still holds the user's data and must stay in the list, and the helper's answer
 * collapses to a single entry once it has chosen. `getExternalFilesDir(null)` is the same call the
 * helper's tier 2 makes, so the two agree on that path without one asking the other.
 *
 * Nothing here touches the file system: no `exists`, no `mkdirs`. Shared storage is the user's, and
 * naming a folder must not create it.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiStudyLocations @Inject constructor(
    private val context: Context
) : AimiStudyLocations {

    override fun studyDirectories(): List<AimiPath> = buildList {
        runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { add(AimiPath(File(it, "Documents/AAPS").absolutePath)) }
        runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { add(AimiPath(File(it, "AAPS").absolutePath)) }
    }
}
