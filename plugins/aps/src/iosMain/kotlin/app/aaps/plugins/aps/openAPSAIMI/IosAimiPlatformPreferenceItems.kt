package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.withCompose
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiPkpdSettingsScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorViewerScreen
import app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.IosDateFormatter
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import app.aaps.plugins.aps.openAPSAIMI.utils.iosAimiStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * iOS half of [AimiPlatformPreferenceItems].
 *
 * A slot is answered only when its screen already exists in shared code. Every other slot is
 * `null`, so the entry is left out of the settings tree and no dead row is shown.
 *
 * Answered:
 * - `HORMONITOR_VIEWER`: the viewer is shared code. It reads the same `Documents/AAPS` files the
 *   engine writes.
 * - `PKPD_SETUP`: the screen is shared code. Android also passes a loader for advisor
 *   recommendations, which is built on Android classes (`AimiAdvisorService`). iOS passes none,
 *   and the screen then shows its settings without the recommendations block. The profile insulin
 *   hint is passed, because `ProfileFunction` is shared.
 *
 * Not answered (`null`):
 * - `SUPPORT_PACKAGE`: the screen is shared, but building and sharing the zip is Android code.
 * - `CONTEXT`, `MEAL_ADVISOR`, `MODE_SETTINGS`, `PROFILE_ADVISOR`: the screens are Android code.
 * - `SOS_PERMISSIONS`, `HEALTH_CONNECT_PERMISSIONS`: Android permission screens. iOS asks for
 *   these permissions in its own system prompts.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAimiPlatformPreferenceItems @Inject constructor(
    private val preferences: Preferences,
    private val profileFunction: ProfileFunction,
) : AimiPlatformPreferenceItems {

    override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = when (slot) {
        AimiPlatformPreferenceSlot.HORMONITOR_VIEWER -> ApsIntentKey.HormonitorViewer.withCompose(
            ComposeScreenContent { onBack ->
                HormonitorViewerScreen(storage = iosAimiStorage(), dateFormatter = IosDateFormatter(), onBack = onBack)
            },
        )
        AimiPlatformPreferenceSlot.PKPD_SETUP        -> ApsIntentKey.PkpdSetup.withCompose(
            ComposeScreenContent { onBack ->
                AimiPkpdSettingsScreen(
                    preferences = preferences,
                    onBack = onBack,
                    loadProfileInsulin = {
                        val profile = profileFunction.getProfile() as? EffectiveProfile
                        profile?.iCfg?.dia to profile?.iCfg?.peak?.toDouble()
                    },
                )
            },
        )
        else                                         -> null
    }

    internal companion object {

        /** The slots this class answers. A test pins this, so a new slot is a decision. */
        val ANSWERED: Set<AimiPlatformPreferenceSlot> = setOf(
            AimiPlatformPreferenceSlot.HORMONITOR_VIEWER,
            AimiPlatformPreferenceSlot.PKPD_SETUP,
        )
    }
}
