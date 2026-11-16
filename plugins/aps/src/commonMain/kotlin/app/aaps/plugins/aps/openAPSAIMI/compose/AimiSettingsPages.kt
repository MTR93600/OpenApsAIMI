package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ComposeScreenContent
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.LocalNavigateToCompose
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import app.aaps.plugins.aps.openAPSAIMI.wcycle.EndometriosisFlareClock
import kotlinx.coroutines.delay

/*
 * The two AIMI settings pages that are more than a plain list (step S2): a feature card and the
 * Expert area. Both draw the same rows as `AimiPreferenceSubScreen`, with something above them.
 */

/**
 * A feature card page: the plain sentence of the card, then its rows. While the card switch is
 * off, the rows that only act while it is on are hidden.
 *
 * @param screen the card page with its link rows (see `aimiWithDrillInLinks`)
 * @param highlightKey the row search opened the page for, or null
 */
@Composable
internal fun AimiFeatureCardPage(
    card: AimiFeatureCard,
    screen: PreferenceSubScreenDef,
    onBack: () -> Unit,
    highlightKey: String? = null,
) {
    val masterOnNow = card.master?.let { masterIsOn(it) } ?: true
    AimiPreferenceSubScreen(
        screen = screen,
        onBack = onBack,
        highlightKey = highlightKey?.let { card.highlightFor(it, masterOnNow) },
        header = {
            Column {
                PageNote(
                    text = stringResource(card.sentence),
                    secondText = if (!masterOnNow && card.dependents.isNotEmpty()) stringResource(ApsStrings.aimi_card_off_hint) else null,
                    warning = false,
                )
                if (card.id == AimiFeatureCardId.Illness) EndometriosisFlareNote()
            }
        },
        visibleItems = { rows -> card.visibleItems(rows, masterOnNow) },
    )
}

/**
 * The time left of a declared endometriosis pain flare, or that it ended (step S3b, see
 * [EndometriosisFlareClock]). Drawn on the illness card while endometriosis and the flare are on, and
 * updated every minute.
 */
@Composable
private fun EndometriosisFlareNote() {
    val preferences = LocalPreferences.current
    val endoOn by remember { preferences.observe(BooleanKey.AimiEndometriosisEnable) }.collectAsState()
    val flareOn by remember { preferences.observe(BooleanKey.AimiEndometriosisPainFlare) }.collectAsState()
    val startedAtMs by remember { preferences.observe(AimiLongKey.EndometriosisFlareStartedAt) }.collectAsState()
    val durationHours by remember { preferences.observe(IntKey.AimiEndometriosisFlareDuration) }.collectAsState()
    var nowMs by remember { mutableLongStateOf(aimiWallClockMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(FLARE_NOTE_REFRESH_MS)
            nowMs = aimiWallClockMs()
        }
    }
    if (!endoOn) return
    when (val status = EndometriosisFlareClock.status(flareOn, startedAtMs, durationHours, nowMs)) {
        EndometriosisFlareClock.Status.Off       -> Unit
        is EndometriosisFlareClock.Status.Active -> PageNote(
            text = stringResource(
                ApsStrings.aimi_endo_flare_time_left,
                (status.remainingMinutes / 60).toInt(),
                (status.remainingMinutes % 60).toInt(),
            ),
            secondText = null,
            warning = false,
        )
        EndometriosisFlareClock.Status.Ended     -> PageNote(text = stringResource(ApsStrings.aimi_endo_flare_ended), secondText = null, warning = true)
    }
}

private const val FLARE_NOTE_REFRESH_MS = 60_000L

/** The live value of [key]: the page redraws when the switch row changes it. */
@Composable
private fun masterIsOn(key: BooleanPreferenceKey): Boolean {
    val preferences = LocalPreferences.current
    val on by remember(key) { preferences.observe(key) }.collectAsState()
    return on
}

/**
 * The Expert area: a warning, a search over every AIMI setting, then the family pages and the
 * technical pages. A search result opens the page that holds the setting and highlights its row.
 *
 * @param screen the Expert page with its link rows
 * @param searchEntries the search list, built from the whole AIMI settings tree when it is needed
 * @param openResult the page a result opens: the feature card page for a key on a card (so its
 *   sentence and its "turn it on" hint are drawn), else the plain page with the row highlighted
 */
@Composable
internal fun AimiExpertPage(
    screen: PreferenceSubScreenDef,
    searchEntries: () -> List<AimiSettingsSearchEntry>,
    openResult: (AimiSettingsSearchEntry) -> ComposeScreenContent,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    AimiPreferenceSubScreen(
        screen = screen,
        onBack = onBack,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
                PageNote(text = stringResource(ApsStrings.aimi_expert_warning), secondText = null, warning = true)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(ApsStrings.aimi_expert_search_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AapsSpacing.extraLarge),
                )
                if (query.isNotBlank()) SearchResults(query = query, entries = remember { searchEntries() }, openResult = openResult)
            }
        },
        // While a search is shown, the results replace the rows.
        visibleItems = { rows -> if (query.isBlank()) rows else emptyList() },
    )
}

@Composable
private fun SearchResults(
    query: String,
    entries: List<AimiSettingsSearchEntry>,
    openResult: (AimiSettingsSearchEntry) -> ComposeScreenContent,
) {
    val titles = entries.associate { it.key.key to stringResource(it.key.title) }
    val found = AimiSettingsSearch.filter(entries, query) { titles[it.key.key].orEmpty() }
    val navigate = LocalNavigateToCompose.current
    Column(modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge)) {
        if (found.isEmpty()) {
            Text(
                text = stringResource(ApsStrings.aimi_expert_search_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.forEach { entry ->
            // A setting on a screen of its own also names the row that opens that screen.
            val rowTitle = if (entry.row.key != entry.key.key) listOf(stringResource(entry.row.title)) else emptyList()
            val pagePath = (entry.path.drop(1).map { stringResource(it.title) } + rowTitle).joinToString(" › ")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { navigate?.invoke(openResult(entry)) }
                    .padding(vertical = AapsSpacing.medium),
            ) {
                Text(text = titles[entry.key.key].orEmpty(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = pagePath,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A card above the rows of a page: the card sentence, or the Expert warning. */
@Composable
private fun PageNote(text: String, secondText: String?, warning: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
        colors = if (warning) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
        },
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            secondText?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
