package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step S2: the Expert area and the AIMI search.
 *
 * Expert holds every EXPERT key that no card or page offers, grouped by family. The family pages
 * are generated from the manifest, so a new EXPERT key lands there on the day it is written. The
 * search is generated from the manifest too and opens the page that holds the key.
 */
class AimiExpertTest {

    private fun root(): PreferenceSubScreenDef = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)

    private fun expertScreen(root: PreferenceSubScreenDef = root()): PreferenceSubScreenDef =
        root.items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == AimiPreferenceTree.USER_PREFERENCES_KEY }
            .items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == AimiPreferenceTree.EXPERT_KEY }

    private fun familyPages(): List<PreferenceSubScreenDef> =
        expertScreen().items.filterIsInstance<PreferenceSubScreenDef>().filter { it.key.startsWith(AimiPreferenceTree.EXPERT_FAMILY_KEY_PREFIX) }

    @Test
    fun `the expert family pages hold only EXPERT keys of their own family`() {
        val pages = familyPages()
        assertTrue(pages.isNotEmpty())
        pages.forEach { page ->
            page.items.filterIsInstance<PreferenceKey>().forEach { key ->
                val entry = AimiSettingsManifest.entryForKeyString(key.key)!!
                assertEquals(AimiSettingsLevel.EXPERT, entry.level, key.key)
                assertEquals(AimiPreferenceTree.EXPERT_FAMILY_KEY_PREFIX + entry.family.name, page.key, key.key)
            }
        }
    }

    @Test
    fun `every EXPERT key that no other page offers is on an expert family page`() {
        val generated = AimiSettingsScreens.sections.filter { it.content == AimiSettingsContentId.ExpertFamilies }.flatMap { it.keys }.map { it.key }.toSet()
        val onHandPages = AimiSettingsScreens.sections.filter { it.content != AimiSettingsContentId.ExpertFamilies }.flatMap { it.keys }.map { it.key }.toSet()
        val expected = AimiSettingsManifest.entries
            .filter { it.level == AimiSettingsLevel.EXPERT && it.userOwned && it.key is PreferenceKey }
            .map { it.key.key }
            .filterNot { it in AimiSettingsManifest.REACHABILITY_DEBT || it in onHandPages }
            .toSet()
        assertEquals(expected, generated)
        assertEquals(emptySet(), generated.intersect(onHandPages))
        val inPages = familyPages().flatMap { page -> page.items.filterIsInstance<PreferenceKey>().map { it.key } }.toSet()
        assertEquals(generated, inPages)
    }

    @Test
    fun `the ML training switch is in the expert area`() {
        val keys = AimiSettingsTreeWalk.keysInTree(expertScreen())
        assertTrue(BooleanKey.OApsAIMIMLtraining.key in keys)
    }

    @Test
    fun `the search finds a setting by its title and opens the page that holds it`() {
        val index = AimiSettingsSearch.index(root())
        val titles = mapOf(
            DoubleKey.OApsAIMIweight.key to "Body weight",
            BooleanKey.OApsAIMIwcycle.key to "Cycle adjustments",
        )
        val found = AimiSettingsSearch.filter(index, "WEIGHT") { titles[it.key.key] ?: it.key.key }
        assertEquals(listOf(DoubleKey.OApsAIMIweight.key), found.map { it.key.key })
        assertTrue(found.single().screen.items.any { it is PreferenceKey && it.key == DoubleKey.OApsAIMIweight.key })
        assertEquals(emptyList(), AimiSettingsSearch.filter(index, "  ") { titles[it.key.key] ?: it.key.key })
    }

    @Test
    fun `the search lists only AIMI settings the person owns and each only once`() {
        val index = AimiSettingsSearch.index(root())
        val keys = index.map { it.key.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            val entry = AimiSettingsManifest.entryForKeyString(key)
            assertTrue(entry != null && entry.userOwned, key)
        }
    }

    @Test
    fun `the contraceptive is found once and opens the women cycle card`() {
        // Step D1 gave it a second row on the illness card; the search keeps the first page in tree
        // order, so it is listed once.
        val index = AimiSettingsSearch.index(root())
        val contraceptive = index.single { it.key.key == StringKey.OApsAIMIWCycleContraceptive.key }
        assertEquals(AimiFeatureCardId.WomenCycle.screenKey, contraceptive.screen.key)
    }

    @Test
    fun `the search prefers a card page over the expert area`() {
        val index = AimiSettingsSearch.index(root())
        val wcycle = index.single { it.key.key == BooleanKey.OApsAIMIwcycle.key }
        assertEquals(AimiFeatureCardId.WomenCycle.screenKey, wcycle.screen.key)
    }
}
