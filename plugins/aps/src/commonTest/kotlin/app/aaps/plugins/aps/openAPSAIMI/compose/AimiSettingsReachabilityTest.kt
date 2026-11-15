package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
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
        // The size on the day step S1 landed. Lower it when a key gets a screen, never raise it.
        assertTrue(AimiSettingsManifest.REACHABILITY_DEBT.size <= 42, "debt grew: ${AimiSettingsManifest.REACHABILITY_DEBT.size}")
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
        // Proves the walk can fail: drop the Lab screen (max SMB has a second home on the essentials
        // page, the ML training switch has none) and the training switch must be reported lost.
        val root = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)
        val withoutLab = withoutScreen(root, "aimi_compose_lab")
        val reachable = AimiSettingsTreeWalk.reachableWithOwnScreens(withoutLab)
        val lost = keptKeys - AimiSettingsManifest.REACHABILITY_DEBT - reachable
        assertTrue(BooleanKey.OApsAIMIMLtraining.key in lost, "lost: $lost")
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
