package app.aaps.plugins.aps.openAPSAIMI.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * iOS half of [AimiStudyLocations]: one directory, inside the app's own container.
 *
 * iOS has no shared `Documents/AAPS` and no equivalent of Android's external storage, so the Android
 * list's reason for having two entries - a user visible folder plus an app-scoped fallback - does not
 * exist here. There is exactly one place an app may write, its container, and no permission to lose,
 * so the list is one long and never empty in practice.
 *
 * `Documents` rather than `Library` because this is the user's data: a sibling AID app (Trio) keeps
 * its own JSON artefacts there, iOS does not purge it, and it is the only container folder that can
 * be shown in the Files app.
 *
 * ## Why a subfolder and not `Documents` itself
 *
 * The container's `Documents` root is already in use - `AppDatabaseBuilder.ios.kt` puts the app
 * database there. Study exports are append-only JSONL that tools walk a whole directory to collect,
 * and pointing such a walk at the folder holding the live database is asking for a copy of it in
 * someone's support package. `AAPS` also keeps the folder name the same as on Android, so a file
 * moved between the two platforms needs no renaming.
 *
 * The directory is created here, unlike on Android. Inside the app's own container that costs nothing
 * and the user cannot see a difference; on shared Android storage it would be creating a folder in
 * the user's own file system, which naming a path must not do.
 *
 * **Visibility in the Files app is a build setting, not code.** Even in `Documents`, the folder only
 * appears to the user when the app's `Info.plist` carries `UIFileSharingEnabled` and
 * `LSSupportsOpeningDocumentsInPlace`. Without them the files are written and read normally but stay
 * invisible, and no Kotlin change can fix that.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiStudyLocations @Inject constructor() : AimiStudyLocations {

    private companion object {

        /** Same folder name as the Android shared directory, so a file reads the same on both. */
        const val DIRECTORY_NAME = "AAPS"
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun studyDirectories(): List<AimiPath> {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
            ?: return emptyList()
        val directory = "$documents/$DIRECTORY_NAME"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        return listOf(AimiPath(directory))
    }
}
