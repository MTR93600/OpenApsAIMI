package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
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
 * Building a `File` from [AimiPath.value] is what `AndroidAimiStorage` does too; it is the Android half
 * of the path's contract, not shared code taking a path apart.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiAppendCap @Inject constructor() : AimiAppendCap {

    override fun beforeAppend(path: AimiPath, bytes: Int) {
        AimiAppendGuard.beforeAppend(File(path.value), bytes)
    }
}
