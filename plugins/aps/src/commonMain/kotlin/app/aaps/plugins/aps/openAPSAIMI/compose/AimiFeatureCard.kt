package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef

/**
 * The feature cards of the AIMI settings (step S2), in screen order. [screenKey] is the key of the
 * card page in the settings tree. It is not a stored preference.
 */
internal enum class AimiFeatureCardId(val screenKey: String) {
    Meals("aimi_card_meals"),
    Activity("aimi_card_activity"),
    Night("aimi_card_night"),
    WomenCycle("aimi_card_wcycle"),
    Illness("aimi_card_illness"),
    PkpdLearning("aimi_card_pkpd"),
    Physio("aimi_card_physio"),
    Autodrive("aimi_card_autodrive"),
    SafetyAlarms("aimi_card_sos"),
}

/**
 * One feature as one page: an optional on/off switch, one plain sentence about what the feature
 * changes in insulin delivery, and the settings of that feature.
 *
 * A row in [dependents] only acts while [master] is on, so the page hides it while the switch is
 * off. Only rows whose code really reads the switch first belong there: hiding a row that still
 * acts would hide a live setting. A card without [master] hides nothing.
 *
 * @param sentence one plain sentence, also the summary of the card row in the list
 * @param master the on/off switch, drawn as the first row, or null
 * @param items the other rows, in screen order (keys, platform rows and sub screens)
 * @param dependents keys of the rows in [items] that are hidden while [master] is off: the key of
 *   a preference, or the key of a sub screen
 */
internal data class AimiFeatureCard(
    val id: AimiFeatureCardId,
    val title: TextRef,
    val sentence: TextRef,
    val master: BooleanPreferenceKey?,
    val items: List<PreferenceItem>,
    val dependents: Set<String> = emptySet(),
) {

    /** The card page as the settings tree holds it. Search and the tree tests read it. */
    val screen: PreferenceSubScreenDef
        get() = PreferenceSubScreenDef(
            key = id.screenKey,
            title = title,
            items = listOfNotNull(master) + items,
            summary = sentence,
        )

    /**
     * The row to highlight when search opens this card for [key]. While the switch is off a
     * dependent row is hidden, so the switch row is highlighted instead, next to the "turn it on" hint.
     */
    fun highlightFor(key: String, masterOn: Boolean): String =
        if (master != null && !masterOn && key in dependents) master.key else key

    /**
     * The rows of [rows] the page shows. [rows] is the page with its link rows (see
     * `aimiWithDrillInLinks`): a link row of a hidden sub screen is hidden with it.
     */
    fun visibleItems(rows: List<PreferenceItem>, masterOn: Boolean): List<PreferenceItem> {
        if (master == null || masterOn) return rows
        val hidden = dependents + dependents.map { AimiSubScreenLink.linkKeyOf(it) }
        return rows.filterNot { row ->
            when (row) {
                is PreferenceKey          -> row.key in hidden
                is PreferenceSubScreenDef -> row.key in hidden
                else                      -> false
            }
        }
    }
}
