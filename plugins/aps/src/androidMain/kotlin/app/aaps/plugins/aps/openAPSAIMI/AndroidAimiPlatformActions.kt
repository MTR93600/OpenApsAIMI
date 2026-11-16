package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActionSlot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActions
import app.aaps.plugins.aps.openAPSAIMI.ports.requestProfileScreen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android half of [AimiPlatformActions].
 *
 * - `PROFILE_SCREEN`: `ComposeMainActivity` collects the pending navigation request and hands it to
 *   its navigator, so the shared profile screen opens after the same protection check as the drawer.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiPlatformActions @Inject constructor() : AimiPlatformActions {

    override fun action(slot: AimiPlatformActionSlot): (() -> Unit)? = when (slot) {
        AimiPlatformActionSlot.PROFILE_SCREEN -> ::requestProfileScreen
    }
}
