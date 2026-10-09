package app.aaps.plugins.aps.openAPSAIMI.sos

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiEmergencySos].
 *
 * Currently a loud no-op: it logs when the SOS condition would be evaluated
 * but does not start any alert. Full iOS SOS alerting (UserNotifications,
 * critical alerts entitlement) is future work.
 *
 * This is deliberately visible, not silent: a quiet stub would leave the
 * safety loop looking alive while it alerts nothing.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiEmergencySos @Inject constructor() : AimiEmergencySos {

    override fun evaluate(
        aapsLogger: AAPSLogger,
        bg: Double,
        delta: Double,
        iob: Double,
        preferences: Preferences,
        nowMs: Long,
    ) {
        aapsLogger.debug(
            LTag.APS,
            "IosAimiEmergencySos: SOS alerting not implemented on iOS yet " +
                "(bg=$bg delta=$delta iob=$iob)"
        )
    }
}
