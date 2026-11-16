package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot

/**
 * Walks the AIMI settings tree the way a person can browse it. Shared by the tree tests.
 *
 * The rules are the rules of the shared renderer in core/ui:
 * - the plugin card draws its own keys, and each of its sub screens as a header;
 * - inside a header, `AdaptivePreferenceList` draws the keys and skips every sub screen;
 * - a link row ([AimiScreenLink]) opens a page that draws the linked screen with the same rule.
 */
internal object AimiSettingsTreeWalk {

    /** Every platform entry present, as on Android (iOS has the same PK/PD and context entries). */
    val allPlatformItems: AimiPlatformPreferenceItems = object : AimiPlatformPreferenceItems {
        override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem = when (slot) {
            AimiPlatformPreferenceSlot.SUPPORT_PACKAGE            -> ApsIntentKey.AimiSupportPackage
            AimiPlatformPreferenceSlot.CONTEXT                    -> ApsIntentKey.AimiContext
            AimiPlatformPreferenceSlot.MEAL_ADVISOR               -> ApsIntentKey.AimiMealAdvisor
            AimiPlatformPreferenceSlot.MODE_SETTINGS              -> ApsIntentKey.AimiModeSettings
            AimiPlatformPreferenceSlot.PROFILE_ADVISOR            -> ApsIntentKey.AimiProfileAdvisor
            AimiPlatformPreferenceSlot.SOS_PERMISSIONS            -> ApsIntentKey.AimiSosPermissions
            AimiPlatformPreferenceSlot.HEALTH_CONNECT_PERMISSIONS -> ApsIntentKey.AimiHealthConnectPermissions
            AimiPlatformPreferenceSlot.HORMONITOR_VIEWER          -> ApsIntentKey.HormonitorViewer
            AimiPlatformPreferenceSlot.PKPD_SETUP                 -> ApsIntentKey.PkpdSetup
        }
    }

    /** No platform entry at all: the smallest tree a platform can produce. */
    val noPlatformItems: AimiPlatformPreferenceItems = object : AimiPlatformPreferenceItems {
        override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = null
    }

    private val emptyScreen = ComposeScreenContent { }

    fun tree(platformItems: AimiPlatformPreferenceItems): PreferenceSubScreenDef =
        AimiPreferenceTree(
            platformItems = platformItems,
            essentials = emptyScreen,
            learned = emptyScreen,
            settingsAudit = emptyScreen,
            nightGrowthState = emptyScreen,
        ).build(icon = null)

    /** Keys a person can reach by tapping, from the root of [root]. */
    fun reachableKeys(root: PreferenceSubScreenDef): Set<String> {
        val reached = mutableSetOf<String>()
        fun drawRow(key: PreferenceKey) {
            if (!reached.add(key.key)) return
            if (key is AimiScreenLink) key.screen.items.filterIsInstance<PreferenceKey>().forEach(::drawRow)
        }
        root.items.forEach { item ->
            when (item) {
                is PreferenceKey          -> drawRow(item)
                is PreferenceSubScreenDef -> item.items.filterIsInstance<PreferenceKey>().forEach(::drawRow)
            }
        }
        return reached
    }

    /**
     * Every key in the tree, at any depth, without the link rows. A sub screen link is followed by
     * its own screen in the tree, so it adds nothing; the Essentials link is not, so its keys are
     * read from the link.
     */
    fun keysInTree(screen: PreferenceSubScreenDef): Set<String> =
        screen.items.flatMap { item ->
            when (item) {
                is AimiSubScreenLink      -> emptyList()
                is AimiEssentialsLink     -> keysInTree(item.screen).toList()
                is PreferenceKey          -> listOf(item.key)
                is PreferenceSubScreenDef -> keysInTree(item).toList()
                else                      -> emptyList()
            }
        }.toSet()

    /**
     * Keys a person can reach: by browsing the tree, or on a screen of its own (PK/PD, context)
     * whose entry row can be reached by browsing.
     */
    fun reachableWithOwnScreens(root: PreferenceSubScreenDef): Set<String> {
        val browsed = reachableKeys(root)
        val onOwnScreens = AimiSettingsScreens.sections
            .filter { section -> AimiSettingsSearch.ENTRY_ROW_OF_OWN_SCREEN[section.content]?.let { it in browsed } == true }
            .flatMap { section -> section.keys.map { it.key } }
        return browsed + onOwnScreens
    }
}
