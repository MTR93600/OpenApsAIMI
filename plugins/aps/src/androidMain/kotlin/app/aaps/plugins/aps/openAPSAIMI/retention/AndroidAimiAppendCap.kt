package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Android half of [AimiAppendCap]: hands the call to [AimiAppendGuard].
 *
 * The guard's outcome is dropped on purpose, exactly as the production writer drops it: the caller is
 * about to append whatever happens, and a rotation is reported by the guard itself, not here.
 *
 * Building a `File` from [AimiPath.value] to read its name is what `AndroidAimiStorage` does too;
 * it is the Android half of the path's contract, not shared code taking a path apart: the shared
 * guard takes the file name as an explicit parameter because [AimiPath] is opaque to it.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiAppendCap @Inject constructor(
    private val storage: AimiStorage,
) : AimiAppendCap {

    override fun beforeAppend(path: AimiPath, bytes: Int) {
        AimiAppendGuard.beforeAppend(storage, path, File(path.value).name, bytes)
    }
}
