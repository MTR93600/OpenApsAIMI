package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActionSlot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformActions
import app.aaps.plugins.aps.openAPSAIMI.ports.requestProfileScreen
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiPlatformActions]. Every slot is answered.
 *
 * - `PROFILE_SCREEN`: the iOS app host (`AapsAppHost`) collects the pending navigation request and
 *   hands it to its navigator, the same one the drawer uses. So the iOS profile screen (the shared
 *   profile management screen) opens after the same protection check as on Android.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiPlatformActions @Inject constructor() : AimiPlatformActions {

    override fun action(slot: AimiPlatformActionSlot): (() -> Unit)? = when (slot) {
        AimiPlatformActionSlot.PROFILE_SCREEN -> ::requestProfileScreen
    }
}
