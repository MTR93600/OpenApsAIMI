package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No setting is lost when the settings are reorganised (owner requirement, `docs/aimi-settings/PLAN.md`).
 *
 * Every kept AIMI key (in the manifest, not dead, not internal state, and owned by the person) must
 * be reachable by browsing the real settings tree: on a page, in a sub screen, or on a screen of its
 * own (PK/PD, context) that the tree opens. The walk follows the renderer's rules, so this test fails
 * the day a kept key loses its screen, even when the manifest sections still name it.
 *
 * The keys that have no screen today are the reachability debt. That list may only shrink.
 */
class AimiSettingsReachabilityTest {

    /** Keys the person owns: the ones that must have a screen. Dead keys and internal state have no entry. */
    private val keptKeys: Set<String> =
        AimiSettingsManifest.entries.filter { it.userOwned }.map { it.key.key }.toSet()

    @Test
    fun `every kept key is reachable from the settings tree`() {
        for (platformItems in listOf(AimiSettingsTreeWalk.allPlatformItems)) {
            val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(AimiSettingsTreeWalk.tree(platformItems))
            val lost = keptKeys - AimiSettingsManifest.REACHABILITY_DEBT - reachable
            assertEquals(emptySet(), lost, "kept keys without a screen")
        }
    }

    @Test
    fun `a key in the reachability debt really has no screen in the tree`() {
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems))
        assertEquals(emptySet(), AimiSettingsManifest.REACHABILITY_DEBT.intersect(reachable))
    }

    @Test
    fun `the reachability debt does not grow`() {
        // 42 when step S1 landed, 23 after step S2 (dead keys left the debt, keys with a reader got an
        // Expert or card row). Lower it when a key gets a screen, never raise it.
        assertTrue(AimiSettingsManifest.REACHABILITY_DEBT.size <= 23, "debt grew: ${AimiSettingsManifest.REACHABILITY_DEBT.size}")
    }

    @Test
    fun `dead keys and internal state are not kept keys`() {
        assertEquals(emptySet(), keptKeys.intersect(AimiSettingsManifest.DEAD_KEYS))
        assertEquals(emptySet(), keptKeys.intersect(AimiSettingsManifest.INTERNAL_STATE))
    }

    @Test
    fun `the essentials page is the first row of the AIMI settings`() {
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val first = root.items.first()
        assertTrue(first is AimiEssentialsLink, "first row is $first")
        assertEquals(AimiEssentials.SCREEN_KEY, first.screen.key)
    }

    @Test
    fun `every essentials row can be reached by browsing`() {
        for (platformItems in listOf(AimiSettingsTreeWalk.allPlatformItems, AimiSettingsTreeWalk.noPlatformItems)) {
            val reachable = AimiSettingsTreeWalk.reachableKeys(AimiSettingsTreeWalk.tree(platformItems))
            val missing = AimiEssentials.rows.map { it.key.key }.filterNot { it in reachable }
            assertEquals(emptyList(), missing)
        }
    }

    @Test
    fun `the essentials link opens exactly the essentials rows`() {
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val link = root.items.filterIsInstance<AimiEssentialsLink>().single()
        assertEquals(
            AimiEssentials.rows.map { it.key.key },
            link.screen.items.filterIsInstance<PreferenceKey>().map { it.key },
        )
    }

    @Test
    fun `a key removed from its only screen is detected`() {
        // Proves the walk can fail: drop the Expert area (the ML training switch has no other home)
        // and the training switch must be reported lost.
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val withoutExpert = withoutScreen(root, AimiPreferenceTree.EXPERT_KEY)
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(withoutExpert)
        val lost = keptKeys - AimiSettingsManifest.REACHABILITY_DEBT - reachable
        assertTrue(BooleanKey.OApsAIMIMLtraining.key in lost, "lost: $lost")
    }

    @Test
    fun `a key removed from a feature card is detected`() {
        // The same proof for a card: without the women cycle card, its master switch is lost.
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val withoutCard = withoutScreen(root, AimiFeatureCardId.WomenCycle.screenKey)
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(withoutCard)
        val lost = keptKeys - AimiSettingsManifest.REACHABILITY_DEBT - reachable
        assertTrue(BooleanKey.OApsAIMIwcycle.key in lost, "lost: $lost")
    }

    @Test
    fun `no dead key has a row in the settings tree`() {
        // A row for a key nothing reads promises an effect that does not exist.
        for (platformItems in listOf(AimiSettingsTreeWalk.allPlatformItems, AimiSettingsTreeWalk.noPlatformItems)) {
            val inTree = AimiSettingsTreeWalk.keysInTree(AimiSettingsTreeWalk.tree(platformItems))
            assertEquals(emptySet(), inTree.intersect(AimiSettingsManifest.DEAD_KEYS))
        }
    }

    @Test
    fun `search finds every kept key on a page that holds it`() {
        // The AIMI search opens the page of a result and highlights the row, so the page must hold it.
        // A key drawn by a screen of its own (PK/PD, context) opens the page of the row that opens
        // that screen. Every kept key a person can reach must be found, also those.
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val index = AimiSettingsSearch.index(root)
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(root)
        val missing = keptKeys.filter { it in reachable }.filter { key -> index.none { it.key.key == key } }
        assertEquals(emptyList(), missing)
        assertEquals(emptySet(), keptKeys - AimiSettingsManifest.REACHABILITY_DEBT - index.map { it.key.key }.toSet())
        index.forEach { entry ->
            assertTrue(entry.screen.items.any { it is PreferenceKey && it.key == entry.row.key }, "${entry.row.key} not on ${entry.screen.key}")
        }
    }

    @Test
    fun `search opens the PK PD card for a PK PD setting`() {
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val index = AimiSettingsSearch.index(root)
        val governor = index.single { it.key.key == BooleanKey.OApsAIMIDiaGovernorEnabled.key }
        assertEquals(AimiFeatureCardId.PkpdLearning.screenKey, governor.screen.key)
        assertEquals(ApsIntentKey.PkpdSetup.key, governor.row.key)
    }

    private fun withoutScreen(screen: PreferenceSubScreenDef, key: String): PreferenceSubScreenDef =
        screen.copy(
            items = screen.items.mapNotNull { item ->
                when {
                    item is AimiSubScreenLink && item.screen.key == key -> null
                    item is PreferenceSubScreenDef && item.key == key   -> null
                    item is PreferenceSubScreenDef                      -> withoutScreen(item, key)
                    else                                                -> item
                }
            }
        )
}
