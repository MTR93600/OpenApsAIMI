package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAppendCap
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiAppendCap].
 *
 * Currently a no-op: iOS does not rotate telemetry files past their hard cap
 * yet. The Android half delegates to `AimiAppendGuard`; the iOS equivalent
 * (file rotation via `platform.Foundation`) is future work.
 *
 * Files can therefore grow unbounded on iOS until retention is ported. This is
 * a storage concern, not a dosing concern.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiAppendCap @Inject constructor() : AimiAppendCap {

    override fun beforeAppend(path: AimiPath, bytes: Int) {
        // No retention on iOS yet. See class KDoc.
    }
}
