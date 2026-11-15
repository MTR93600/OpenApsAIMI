package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPlatformPreferenceItems
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks that every AIMI setting in the preference tree can be reached by browsing.
 *
 * The shared renderer in core/ui draws only two levels (the plugin card, and its sub screens as
 * headers); a sub screen one level deeper is skipped. The AIMI tree is deeper, so for months most
 * AIMI screens (women cycle, SOS, Physio, ...) could only be found through search. The walk
 * below follows the renderer's rules, plus the AIMI link rows ([AimiScreenLink]), and the tests
 * fail when a key is in the tree but no tap path leads to it.
 */
class AimiPreferenceTreeTest {

    private val allPlatformItems: AimiPlatformPreferenceItems = AimiSettingsTreeWalk.allPlatformItems

    private val noPlatformItems: AimiPlatformPreferenceItems = AimiSettingsTreeWalk.noPlatformItems

    private fun tree(platformItems: AimiPlatformPreferenceItems): PreferenceSubScreenDef = AimiSettingsTreeWalk.tree(platformItems)

    /** The contents [AimiPreferenceTree] draws. Each one must be reachable inside the tree. */
    private val contentsDrawnByTree: Set<AimiSettingsContentId> = setOf(
        AimiSettingsContentId.PreferencesRoot,
        AimiSettingsContentId.PreferencesEssentials,
        AimiSettingsContentId.PreferencesAiKeys,
        AimiSettingsContentId.PreferencesTpo,
        AimiSettingsContentId.PreferencesPatientContext,
        AimiSettingsContentId.PreferencesDoseLimits,
        AimiSettingsContentId.CardMeals,
        AimiSettingsContentId.CardActivity,
        AimiSettingsContentId.CardNight,
        AimiSettingsContentId.CardWomenCycle,
        AimiSettingsContentId.CardIllness,
        AimiSettingsContentId.CardPkpd,
        AimiSettingsContentId.CardPhysio,
        AimiSettingsContentId.CardAutodrive,
        AimiSettingsContentId.CardSafetyAlarms,
        AimiSettingsContentId.PreferencesManualModes,
        AimiSettingsContentId.PreferencesNightGrowth,
        AimiSettingsContentId.PreferencesThyroid,
        AimiSettingsContentId.PreferencesEndometriosis,
        AimiSettingsContentId.PreferencesT3c,
        AimiSettingsContentId.PreferencesAutodrivePrebolusVars,
        AimiSettingsContentId.ExpertFamilies,
        AimiSettingsContentId.PreferencesAdaptiveBasal,
        AimiSettingsContentId.PreferencesTrajectory,
        AimiSettingsContentId.PreferencesAiAuditor,
    )

    /** Contents drawn by their own screen, opened from one entry of the tree. One list, shared with the reachability test. */
    private val contentsDrawnElsewhere: Set<AimiSettingsContentId> = AimiSettingsSearch.ENTRY_ROW_OF_OWN_SCREEN.keys

    /** Keys a person can reach by tapping. See [AimiSettingsTreeWalk.reachableKeys]. */
    private fun reachableKeys(root: PreferenceSubScreenDef): Set<String> = AimiSettingsTreeWalk.reachableKeys(root)

    /** Every key in the tree, at any depth, without the link rows. */
    private fun keysInTree(screen: PreferenceSubScreenDef): Set<String> = AimiSettingsTreeWalk.keysInTree(screen)

    /** The same tree with every link row removed: what the renderer showed before the links. */
    private fun withoutLinks(screen: PreferenceSubScreenDef): PreferenceSubScreenDef =
        screen.copy(
            items = screen.items.mapNotNull { item ->
                when (item) {
                    is AimiScreenLink         -> null
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
        // The card's own sub screens are drawn as headers already: no link there. The one link of the
        // card is the Essentials page, which has no sub screen in the tree.
        assertTrue(root.items.none { it is AimiSubScreenLink })
        assertEquals(listOf(AimiEssentials.SCREEN_KEY), root.items.filterIsInstance<AimiEssentialsLink>().map { it.screen.key })
    }
}
