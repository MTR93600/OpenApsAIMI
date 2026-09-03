package app.aaps.plugins.aps.openAPSAIMI.sos

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android half of [AimiEmergencySos].
 *
 * Holds the [Context] the port's own signature deliberately leaves out, and delegates straight to
 * [EmergencySosManager.evaluateSosCondition] - same object, same behaviour, no change to the SOS
 * logic itself.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiEmergencySos @Inject constructor(
    private val context: Context,
) : AimiEmergencySos {

    override fun evaluate(
        aapsLogger: AAPSLogger,
        bg: Double,
        delta: Double,
        iob: Double,
        preferences: Preferences,
        nowMs: Long,
    ) {
        EmergencySosManager.evaluateSosCondition(
            aapsLogger = aapsLogger,
            bg = bg,
            delta = delta,
            iob = iob,
            context = context,
            preferences = preferences,
            nowMs = nowMs,
        )
    }
}
