package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Delegates to [readAimiBehaviorRuntimeProfile], which stays here because it walks the Control
 * Center's draft/snapshot chain - two runtime history readers and the preference-derived autonomy
 * mode. No state, no I/O at construction: safe to bind.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiBehaviorProfileSource @Inject constructor() : AimiBehaviorProfileSource {

    override fun read(preferences: Preferences): AimiBehaviorRuntimeProfile =
        readAimiBehaviorRuntimeProfile(preferences)
}
