package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.keys.ApsIntentKey

/**
 * One search result: an AIMI setting and the page that holds its row.
 *
 * @param screen the page to open; its own items hold [row], so the row can be highlighted there
 * @param path the pages from the AIMI root down to [screen], for the result line
 * @param row the row to highlight on [screen]. It is [key] itself, or, for a setting drawn by a
 *   screen of its own (PK/PD, context), the row that opens that screen.
 */
internal data class AimiSettingsSearchEntry(
    val key: PreferenceKey,
    val screen: PreferenceSubScreenDef,
    val path: List<PreferenceSubScreenDef>,
    val row: PreferenceKey = key,
)

/**
 * The search over the AIMI settings on the Expert page (step S2).
 *
 * The list is generated from the manifest: every setting the person owns
 * ([AimiSettingEntry.userOwned]) that has a row in the tree, or that a screen of its own draws
 * (see [ENTRY_ROW_OF_OWN_SCREEN]). The app wide search of core/ui walks the same
 * `PreferenceSubScreenDef` pages, so both open the same page for a key.
 */
internal object AimiSettingsSearch {

    /**
     * The row that opens each content drawn by a screen of its own instead of the tree. The keys of
     * such a content have no row in the tree; search opens the page of this row and highlights it.
     */
    val ENTRY_ROW_OF_OWN_SCREEN: Map<AimiSettingsContentId, String> = mapOf(
        AimiSettingsContentId.PkpdSimple to ApsIntentKey.PkpdSetup.key,
        AimiSettingsContentId.PkpdAdvanced to ApsIntentKey.PkpdSetup.key,
        AimiSettingsContentId.PkpdExpert to ApsIntentKey.PkpdSetup.key,
        AimiSettingsContentId.ContextEditor to ApsIntentKey.AimiContext.key,
        AimiSettingsContentId.SettingsAudit to ApsIntentKey.AimiSettingsAudit.key,
    )

    /**
     * Every AIMI setting of [root] with the first page that holds it, in tree order. A card comes
     * before the Expert area in the tree, so a key on a card opens its card. Then the settings of the
     * screens of their own, each with the page of the row that opens its screen.
     */
    fun index(root: PreferenceSubScreenDef): List<AimiSettingsSearchEntry> {
        val found = LinkedHashMap<String, AimiSettingsSearchEntry>()
        // The first page of every row, also rows that are not settings (the PK/PD setup row).
        val firstPageOfRow = LinkedHashMap<String, Pair<PreferenceKey, List<PreferenceSubScreenDef>>>()
        fun walk(screen: PreferenceSubScreenDef, path: List<PreferenceSubScreenDef>) {
            val here = path + screen
            screen.items.forEach { item ->
                when (item) {
                    is AimiScreenLink         -> Unit
                    is PreferenceKey          -> {
                        if (item.key !in firstPageOfRow) firstPageOfRow[item.key] = item to here
                        if (isOwned(item.key) && item.key !in found) found[item.key] = AimiSettingsSearchEntry(item, screen, here)
                    }

                    is PreferenceSubScreenDef -> walk(item, here)
                }
            }
        }
        walk(root, emptyList())
        AimiSettingsScreens.sections.forEach { section ->
            val entryRow = ENTRY_ROW_OF_OWN_SCREEN[section.content] ?: return@forEach
            val (row, path) = firstPageOfRow[entryRow] ?: return@forEach
            // A key that is not a preference key has no title to search by.
            section.keys.filterIsInstance<PreferenceKey>().forEach { key ->
                if (isOwned(key.key) && key.key !in found) {
                    found[key.key] = AimiSettingsSearchEntry(key = key, screen = path.last(), path = path, row = row)
                }
            }
        }
        return found.values.toList()
    }

    private fun isOwned(key: String): Boolean = AimiSettingsManifest.entryForKeyString(key)?.userOwned == true

    /**
     * The entries whose title contains every word of [query], ignoring case. A blank query finds
     * nothing. [titleOf] gives the shown title of an entry (the screen resolves the string).
     */
    fun filter(
        entries: List<AimiSettingsSearchEntry>,
        query: String,
        titleOf: (AimiSettingsSearchEntry) -> String,
    ): List<AimiSettingsSearchEntry> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { entry ->
            val title = titleOf(entry).lowercase()
            words.all { it in title }
        }
    }
}
