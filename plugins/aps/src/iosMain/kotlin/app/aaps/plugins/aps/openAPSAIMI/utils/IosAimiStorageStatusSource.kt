package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStorageStatus
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiStorageStatusSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The iOS side of [AimiStorageStatusSource].
 *
 * Android has three storage tiers because Android may refuse the public `Documents/AAPS` folder
 * and then falls back to two worse places. iOS has no such choice. An app can only write inside its
 * own sandbox, and AIMI always keeps its files in the `Documents/AAPS` folder of that sandbox (see
 * `iosTickAimiRoot`). So there are only two honest answers:
 *
 * - `DOCUMENTS_AAPS`: that folder exists and can be written. The path is returned.
 * - `ERROR`: it cannot be made or written. The reason is returned as the error text.
 *
 * `APP_SCOPED_EXTERNAL` and `INTERNAL_ONLY` are never returned. Neither tier exists on iOS, and
 * reporting one of them would invent a fallback that did not happen.
 *
 * Note that `DOCUMENTS_AAPS` here means the sandbox folder. It is not shared with other apps, but
 * it is where the shared code expects its files, so the health log line stays comparable.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiStorageStatusSource @Inject constructor() : AimiStorageStatusSource {

    override fun getStorageStatus(): Triple<AimiStorageStatus, String?, String?> =
        iosStorageStatus(
            root = ::iosTickAimiRoot,
            canWrite = { path -> aimiLocalFiles().canWrite(path) },
        )
}

/**
 * The answer, with the two outside calls passed in so a test can make each of them fail.
 *
 * Never throws: `iosTickAimiRoot` throws when the app has no Documents directory, and this is
 * called from the plugin start, where a throw would hide the real reason.
 */
internal fun iosStorageStatus(
    root: () -> String,
    canWrite: (String) -> Boolean,
): Triple<AimiStorageStatus, String?, String?> {
    val path = try {
        root()
    } catch (e: Exception) {
        return Triple(AimiStorageStatus.ERROR, null, "Cannot find the Documents/AAPS folder: ${e.message}")
    }
    return if (canWrite(path)) {
        Triple(AimiStorageStatus.DOCUMENTS_AAPS, path, null)
    } else {
        Triple(AimiStorageStatus.ERROR, path, "Documents/AAPS is not writable")
    }
}
