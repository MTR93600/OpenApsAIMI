package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks that every AIMI setting in the preference tree can be reached by browsing.
 *
 * The shared renderer in core/ui draws only two levels (the plugin card, and its sub screens as
 * headers); a sub screen one level deeper is skipped. The AIMI tree is deeper, so for months most
 * AIMI screens (women cycle, Lab, SOS, Physio, ...) could only be found through search. The walk
 * below follows the renderer's rules, plus the AIMI link rows ([AimiSubScreenLink]), and the tests
 * fail when a key is in the tree but no tap path leads to it.
 */
class AimiPreferenceTreeTest {

    /** Every platform entry present, as on Android. */
    private val allPlatformItems = object : AimiPlatformPreferenceItems {
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
    private val noPlatformItems = object : AimiPlatformPreferenceItems {
        override fun item(slot: AimiPlatformPreferenceSlot): PreferenceItem? = null
    }

    private val emptyScreen = ComposeScreenContent { }

    private fun tree(platformItems: AimiPlatformPreferenceItems): PreferenceSubScreenDef =
        AimiPreferenceTree(platformItems, controlCenter = emptyScreen, settingsAudit = emptyScreen).build(icon = null)

    /** The contents [AimiPreferenceTree] draws. Each one must be reachable inside the tree. */
    private val contentsDrawnByTree: Set<AimiSettingsContentId> = setOf(
        AimiSettingsContentId.PreferencesRoot,
        AimiSettingsContentId.PreferencesAiKeys,
        AimiSettingsContentId.PreferencesTpo,
        AimiSettingsContentId.PreferencesPhysio,
        AimiSettingsContentId.PreferencesPatientContext,
        AimiSettingsContentId.PreferencesWomenCycle,
        AimiSettingsContentId.PreferencesInflammatory,
        AimiSettingsContentId.PreferencesThyroid,
        AimiSettingsContentId.PreferencesNightGrowth,
        AimiSettingsContentId.PreferencesLab,
        AimiSettingsContentId.PreferencesAdaptiveBasal,
        AimiSettingsContentId.PreferencesT3c,
        AimiSettingsContentId.PreferencesTrajectory,
        AimiSettingsContentId.PreferencesManualModes,
        AimiSettingsContentId.PreferencesAutodrive,
        AimiSettingsContentId.PreferencesAutodrivePrebolusVars,
        AimiSettingsContentId.PreferencesAiAuditor,
        AimiSettingsContentId.PreferencesEmergencySos,
    )

    /** Contents drawn by their own screen, opened from one entry of the tree. */
    private val contentsDrawnElsewhere: Set<AimiSettingsContentId> = setOf(
        AimiSettingsContentId.PkpdSimple,
        AimiSettingsContentId.PkpdAdvanced,
        AimiSettingsContentId.PkpdExpert,
        AimiSettingsContentId.ControlCenter,
        AimiSettingsContentId.ContextEditor,
        AimiSettingsContentId.SettingsAudit,
    )

    /**
     * Keys a person can reach by tapping, following the rules of the shared renderer:
     * - the card draws its own keys, and each of its sub screens as a header;
     * - inside a header, `AdaptivePreferenceList` draws the keys and skips every sub screen;
     * - a link row opens [AimiPreferenceSubScreen], which draws the linked screen with the same
     *   `AdaptivePreferenceList` rule.
     */
    private fun reachableKeys(root: PreferenceSubScreenDef): Set<String> {
        val reached = mutableSetOf<String>()
        fun drawRow(key: PreferenceKey) {
            if (!reached.add(key.key)) return
            if (key is AimiSubScreenLink) key.screen.items.filterIsInstance<PreferenceKey>().forEach(::drawRow)
        }
        root.items.forEach { item ->
            when (item) {
                is PreferenceKey          -> drawRow(item)
                is PreferenceSubScreenDef -> item.items.filterIsInstance<PreferenceKey>().forEach(::drawRow)
            }
        }
        return reached
    }

    /** Every key in the tree, at any depth, without the link rows. */
    private fun keysInTree(screen: PreferenceSubScreenDef): Set<String> =
        screen.items.flatMap { item ->
            when (item) {
                is AimiSubScreenLink      -> emptyList()
                is PreferenceKey          -> listOf(item.key)
                is PreferenceSubScreenDef -> keysInTree(item).toList()
                else                      -> emptyList()
            }
        }.toSet()

    /** The same tree with every link row removed: what the renderer showed before the links. */
    private fun withoutLinks(screen: PreferenceSubScreenDef): PreferenceSubScreenDef =
        screen.copy(
            items = screen.items.mapNotNull { item ->
                when (item) {
                    is AimiSubScreenLink      -> null
                    is PreferenceSubScreenDef -> withoutLinks(item)
                    else                      -> item
                }
            }
        )

    @Test
    fun `every key in the tree can be reached by browsing`() {
        for (platformItems in listOf(allPlatformItems, noPlatformItems)) {
            val root = tree(platformItems)
            val unreachable = keysInTree(root) - reachableKeys(root)
            assertEquals(emptySet(), unreachable)
        }
    }

    @Test
    fun `every key of a section the tree draws can be reached by browsing`() {
        for (platformItems in listOf(allPlatformItems, noPlatformItems)) {
            val reachable = reachableKeys(tree(platformItems))
            val missing = AimiSettingsScreens.sections
                .filter { it.content in contentsDrawnByTree }
                .flatMap { section -> section.keys.map { section.id to it.key } }
                .filter { (_, key) -> key !in reachable }
            assertEquals(emptyList(), missing)
        }
    }

    @Test
    fun `every settings content is either drawn by the tree or by its own screen`() {
        val unclassified = AimiSettingsContentId.entries.toSet() - contentsDrawnByTree - contentsDrawnElsewhere
        assertEquals(emptySet(), unclassified)
        assertEquals(emptySet(), contentsDrawnByTree.intersect(contentsDrawnElsewhere))
        assertTrue(AimiSettingsScreens.routedContents.containsAll(contentsDrawnByTree))
    }

    @Test
    fun `without the link rows the deep screens are not reachable`() {
        // Proves the walk models the renderer: this is exactly the defect the links fix.
        val reachable = reachableKeys(withoutLinks(tree(allPlatformItems)))
        assertFalse(BooleanKey.OApsAIMIwcycle.key in reachable)
        assertFalse(DoubleKey.OApsAIMIMaxSMB.key in reachable)
        assertFalse(DoubleKey.AimiTubeHypoFloorMgdl.key in reachable)
    }

    @Test
    fun `keys added from dev are on their screens`() {
        val reachable = reachableKeys(tree(noPlatformItems))
        listOf(
            DoubleKey.OApsAIMIMaxMultiplier.key,
            BooleanKey.OApsAIMIDynamicBasalStackCap.key,
            BooleanKey.OApsAIMIEngineHyperRateBound.key,
            DoubleKey.OApsAIMIHighBg.key,
            BooleanKey.OApsAIMITubeVetoIgnoreFloorArtefact.key,
            BooleanKey.OApsAIMITubeHyperClampPhysicalBound.key,
            BooleanKey.AimiHrvBaselinePerNight.key,
            ApsIntentKey.AimiSettingsAudit.key,
        ).forEach { key -> assertTrue(key in reachable, key) }
    }

    @Test
    fun `each nested screen has exactly one link row`() {
        val root = tree(allPlatformItems)
        val userPreferences = root.items.filterIsInstance<PreferenceSubScreenDef>()
            .single { it.key == AimiPreferenceTree.USER_PREFERENCES_KEY }

        fun check(screen: PreferenceSubScreenDef) {
            val links = screen.items.filterIsInstance<AimiSubScreenLink>().map { it.screen.key }
            val subScreens = screen.items.filterIsInstance<PreferenceSubScreenDef>().map { it.key }
            assertEquals(subScreens, links)
            screen.items.filterIsInstance<PreferenceSubScreenDef>().forEach(::check)
        }
        check(userPreferences)
        // The card's own sub screens are drawn as headers already: no link there.
        assertTrue(root.items.none { it is AimiSubScreenLink })
    }
}
