package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step S2: the feature cards, the "What AIMI learned" row and the removed Control Center.
 *
 * A card is one page per feature: an optional on/off switch, one plain sentence about what it
 * changes in insulin delivery, and the settings of that feature. Settings that only act while the
 * switch is on are hidden while it is off.
 */
class AimiFeatureCardsTest {

    private val noScreen = ComposeScreenContent { }

    private fun root(): PreferenceSubScreenDef = AimiSettingsTreeWalk.tree(AimiSettingsTreeWalk.allPlatformItems)

    private fun userPreferences(root: PreferenceSubScreenDef = root()): PreferenceSubScreenDef =
        root.items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == AimiPreferenceTree.USER_PREFERENCES_KEY }

    private fun cardScreen(id: AimiFeatureCardId): PreferenceSubScreenDef =
        userPreferences().items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == id.screenKey }

    /** Every key in [screen], at any depth, link rows not counted. */
    private fun keysOf(screen: PreferenceSubScreenDef): Set<String> = AimiSettingsTreeWalk.keysInTree(screen)

    @Test
    fun `the user preferences start with the nine feature cards in order`() {
        val links = userPreferences().items.filterIsInstance<AimiSubScreenLink>().map { it.screen.key }
        assertEquals(AimiFeatureCardId.entries.map { it.screenKey }, links.take(AimiFeatureCardId.entries.size))
    }

    @Test
    fun `each card has one plain sentence and opens its own page`() {
        for (platformItems in listOf(AimiSettingsTreeWalk.allPlatformItems, AimiSettingsTreeWalk.noPlatformItems)) {
            val cards = AimiPreferenceTree.featureCards(platformItems, noScreen)
            assertEquals(AimiFeatureCardId.entries.toList(), cards.map { it.id })
            cards.forEach { card ->
                assertNotNull(card.screen.summary, "${card.id} has no sentence")
                assertTrue(card.screen.items.isNotEmpty(), "${card.id} is empty")
            }
        }
    }

    @Test
    fun `a card master switch is the first row of its page`() {
        AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).filter { it.master != null }.forEach { card ->
            assertEquals(card.master!!.key, (card.screen.items.first() as PreferenceKey).key, "${card.id}")
        }
    }

    @Test
    fun `settings that only act while the switch is on are hidden while it is off`() {
        val card = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).single { it.id == AimiFeatureCardId.WomenCycle }
        val items = aimiWithDrillInLinks(card.screen.items)
        val off = card.visibleItems(items, masterOn = false).keys()
        val on = card.visibleItems(items, masterOn = true).keys()
        assertEquals(listOf(BooleanKey.OApsAIMIwcycle.key, StringKey.OApsAIMIWCycleContraceptive.key), off)
        assertTrue(StringKey.OApsAIMIWCycleTrackingMode.key in on)
        assertEquals(items.keys(), on)
    }

    @Test
    fun `the contraceptive row stays shown while the cycle switch is off`() {
        // `EndometriosisAdjuster.calculateFactors` reads the contraceptive with only the endometriosis
        // switch on, so it changes insulin also while the cycle switch is off.
        val card = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).single { it.id == AimiFeatureCardId.WomenCycle }
        val off = card.visibleItems(aimiWithDrillInLinks(card.screen.items), masterOn = false).keys()
        assertTrue(StringKey.OApsAIMIWCycleContraceptive.key in off, "visible: $off")
        assertEquals(StringKey.OApsAIMIWCycleContraceptive.key, card.highlightFor(StringKey.OApsAIMIWCycleContraceptive.key, masterOn = false))
    }

    @Test
    fun `the contraceptive is also on the endometriosis page of the illness card`() {
        // Owner decision of step D1: the same key gets one more row next to endometriosis, because
        // `EndometriosisAdjuster` reads it with only the endometriosis switch on. It stays on the
        // women's cycle card too.
        val endometriosis = cardScreen(AimiFeatureCardId.Illness).items.filterIsInstance<PreferenceSubScreenDef>()
            .single { it.key == "aimi_compose_endo" }
        val keys = endometriosis.items.filterIsInstance<PreferenceKey>().map { it.key }
        assertTrue(StringKey.OApsAIMIWCycleContraceptive.key in keys, "endometriosis page: $keys")
        assertEquals(keys.indexOf(BooleanKey.AimiEndometriosisPainFlare.key) + 1, keys.indexOf(StringKey.OApsAIMIWCycleContraceptive.key))
        val cycle = cardScreen(AimiFeatureCardId.WomenCycle).items.filterIsInstance<PreferenceKey>().map { it.key }
        assertTrue(StringKey.OApsAIMIWCycleContraceptive.key in cycle, "women's cycle card: $cycle")
    }

    @Test
    fun `the night growth page starts with the read only state row`() {
        // The first row of the page says in full what the loop does and why. Since step A1 the switch
        // row shows the same state (`AimiNightGrowthSwitchRowTest`), but the age row still shows its key
        // default (14) when no age is stored.
        val ngr = cardScreen(AimiFeatureCardId.Night).items.filterIsInstance<PreferenceSubScreenDef>()
            .single { it.key == "aimi_compose_ngr" }
        val keys = ngr.items.filterIsInstance<PreferenceKey>().map { it.key }
        assertEquals(ApsIntentKey.AimiNightGrowthState.key, keys.first(), "night growth page: $keys")
        assertTrue(IntKey.OApsAIMINightGrowthAgeYears.key in keys)
        assertTrue(BooleanKey.OApsAIMINightGrowthEnabled.key in keys)
    }

    @Test
    fun `search on a hidden card row highlights the card switch`() {
        val card = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).single { it.id == AimiFeatureCardId.WomenCycle }
        val tracking = StringKey.OApsAIMIWCycleTrackingMode.key
        assertEquals(BooleanKey.OApsAIMIwcycle.key, card.highlightFor(tracking, masterOn = false))
        assertEquals(tracking, card.highlightFor(tracking, masterOn = true))
        val noSwitch = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).single { it.id == AimiFeatureCardId.Night }
        assertEquals(BooleanKey.OApsAIMInight.key, noSwitch.highlightFor(BooleanKey.OApsAIMInight.key, masterOn = false))
    }

    @Test
    fun `a search result on a card resolves to the card page`() {
        // The tree opens `AimiFeatureCardPage` for a result whose page is a card, found by its key.
        val index = AimiSettingsSearch.index(root())
        val cardKeys = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).map { it.id.screenKey }.toSet()
        val tracking = index.single { it.key.key == StringKey.OApsAIMIWCycleTrackingMode.key }
        assertEquals(AimiFeatureCardId.WomenCycle.screenKey, tracking.screen.key)
        assertTrue(tracking.screen.key in cardKeys)
        val sos = index.single { it.key.key == BooleanKey.AimiEmergencySosEnable.key }
        assertEquals(AimiFeatureCardId.SafetyAlarms.screenKey, sos.screen.key)
    }

    @Test
    fun `a dependent row is always a row of the same card`() {
        AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).forEach { card ->
            val rowKeys = card.screen.items.map { it.itemKey() }.toSet()
            assertTrue(rowKeys.containsAll(card.dependents), "${card.id}: ${card.dependents - rowKeys}")
            if (card.master == null) assertEquals(emptySet(), card.dependents, "${card.id} hides rows without a switch")
        }
    }

    @Test
    fun `a card without a switch shows everything`() {
        val card = AimiPreferenceTree.featureCards(AimiSettingsTreeWalk.allPlatformItems, noScreen).single { it.id == AimiFeatureCardId.Illness }
        val items = aimiWithDrillInLinks(card.screen.items)
        assertEquals(items, card.visibleItems(items, masterOn = false))
    }

    @Test
    fun `PK PD learning has one entry point`() {
        val root = root()
        val rows = allRows(root).filter { it is PreferenceKey && it.key == ApsIntentKey.PkpdSetup.key }
        assertEquals(1, rows.size, "PK/PD setup rows")
        assertTrue(ApsIntentKey.PkpdSetup.key in keysOf(cardScreen(AimiFeatureCardId.PkpdLearning)))
        assertTrue(BooleanKey.OApsAIMIPkpdEnabled.key in keysOf(cardScreen(AimiFeatureCardId.PkpdLearning)))
    }

    @Test
    fun `the meal mode values are on one page of the meals card`() {
        val meals = keysOf(cardScreen(AimiFeatureCardId.Meals))
        assertTrue(ApsIntentKey.AimiModeSettings.key in meals)
        val modeScreens = allRows(root()).filterIsInstance<PreferenceSubScreenDef>().filter { it.key.startsWith("aimi_compose_mode_") }
        assertEquals(7, modeScreens.size)
        val valuesPage = cardScreen(AimiFeatureCardId.Meals).items.filterIsInstance<PreferenceSubScreenDef>().single { it.key == AimiPreferenceTree.MEAL_MODE_VALUES_KEY }
        val onValuesPage = valuesPage.items.filterIsInstance<PreferenceSubScreenDef>().map { it.key }
        modeScreens.forEach { assertTrue(it.key in onValuesPage, "${it.key} is not on the meal mode values page") }
        val modeSettingsRows = allRows(root()).filter { it is PreferenceKey && it.key == ApsIntentKey.AimiModeSettings.key }
        assertEquals(1, modeSettingsRows.size)
    }

    @Test
    fun `the illness card holds thyroid endometriosis CFRD and Verneuil`() {
        val illness = keysOf(cardScreen(AimiFeatureCardId.Illness))
        listOf(
            BooleanKey.OApsAIMIThyroidEnabled.key,
            BooleanKey.AimiEndometriosisEnable.key,
            BooleanKey.OApsAIMIT3cCfrdMode.key,
            StringKey.OApsAIMIWCycleVerneuil.key,
        ).forEach { assertTrue(it in illness, it) }
    }

    @Test
    fun `the sleep and HRV switches are on the physio card and the flare duration on the illness card`() {
        // Step S3b: these three keys were dead and hidden. They now act, so they are shown.
        val physio = keysOf(cardScreen(AimiFeatureCardId.Physio))
        assertTrue(BooleanKey.AimiPhysioSleepDataEnable.key in physio)
        assertTrue(BooleanKey.AimiPhysioHRVDataEnable.key in physio)
        assertTrue(IntKey.AimiEndometriosisFlareDuration.key in keysOf(cardScreen(AimiFeatureCardId.Illness)))
    }

    @Test
    fun `the wired keys have a hint`() {
        listOf(BooleanKey.AimiPhysioSleepDataEnable, BooleanKey.AimiPhysioHRVDataEnable, IntKey.AimiEndometriosisFlareDuration, BooleanKey.AimiEndometriosisPainFlare)
            .forEach { assertNotNull(it.summary, it.key) }
    }

    @Test
    fun `the Control Center is gone and What AIMI learned can be reached`() {
        val root = root()
        val reachable = AimiSettingsTreeWalk.reachableKeys(root)
        assertTrue(ApsIntentKey.AimiLearned.key in reachable)
        assertTrue(ApsIntentKey.AimiSettingsAudit.key in reachable)
        assertTrue(allRows(root).none { it is PreferenceKey && it.key == "aimi_control_center_compose" })
        assertTrue(AimiSettingsContentId.entries.none { it.name == "ControlCenter" })
    }

    private fun List<PreferenceItem>.keys(): List<String> = map { it.itemKey() }

    private fun PreferenceItem.itemKey(): String = when (this) {
        is PreferenceKey          -> key
        is PreferenceSubScreenDef -> key
        else                      -> toString()
    }

    private fun allRows(screen: PreferenceSubScreenDef): List<PreferenceItem> =
        screen.items.flatMap { item -> if (item is PreferenceSubScreenDef) listOf(item) + allRows(item) else listOf(item) }
}
