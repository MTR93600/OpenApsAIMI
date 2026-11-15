package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Gives dosing the behaviour profile on every tick: the fixed display-level part of
 * [AimiBehaviorRuntimeProfile.NEUTRAL], with the autonomy mode from the real Autodrive switches.
 *
 * It used to read the Control Center levels from the stored settings on every tick. Those levels
 * are a display calculation: they also counted dose limits and two physio switches that nothing
 * else reads. So moving a dose limit, or a switch with no effect of its own, changed the PK/PD
 * ISF factors and the ML correction limit (step C1 of the settings plan). The levels are now
 * constants. The autonomy mode is not a display level: it follows the Autodrive switches again
 * since step T2, as before C1. Same on Android and iOS.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class NeutralAimiBehaviorProfileSource @Inject constructor() : AimiBehaviorProfileSource {

    override fun read(preferences: Preferences): AimiBehaviorRuntimeProfile =
        AimiBehaviorRuntimeProfile.NEUTRAL.copy(autonomyMode = autonomyModeFromAutodrive(preferences))
}
