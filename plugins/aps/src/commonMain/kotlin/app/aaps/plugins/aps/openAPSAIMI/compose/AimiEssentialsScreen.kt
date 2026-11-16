package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.MasterOfflineBanner
import app.aaps.core.ui.compose.masterEditingEnabled
import app.aaps.core.ui.compose.preference.LocalPreferenceTheme
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.compose.preference.verticalScrollIndicators
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

/**
 * The Essentials page ([AimiEssentials]): the person's own data and the dose limits, each with the
 * value in use now, and the profile targets read only.
 *
 * It writes only what the person sets in a row, exactly as the other settings screens do. Opening it
 * writes nothing. [loadLive] is called once when the page opens; until it answers, or when it fails,
 * every row shows its stored value.
 *
 * Not medical advice: the page says so at the top.
 *
 * Shared code: the same page runs on Android and iOS.
 *
 * @param preferences the store every row reads and writes
 * @param loadLive reads what the loop uses now (constraints, profile targets, active TPO session)
 * @param onBack closes the page
 */
@Composable
internal fun AimiEssentialsScreen(
    preferences: Preferences,
    loadLive: suspend () -> AimiEssentialsLive,
    onBack: () -> Unit,
) {
    val live by produceState(initialValue = AimiEssentialsLive()) {
        value = runCatching { loadLive() }.getOrDefault(AimiEssentialsLive())
    }
    val pregnancyOn by remember { preferences.observe(BooleanKey.OApsAIMIpregnancy) }.collectAsState()

    ProvidePreferenceTheme {
        Scaffold(
            topBar = {
                AapsTopAppBar(
                    title = { Text(stringResource(ApsStrings.aimi_essentials_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(CoreUiStrings.back),
                            )
                        }
                    },
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { paddingValues ->
            val listState = rememberLazyListState()
            val rows = AimiEssentials.rows.filter { row ->
                row.key != AimiStringKey.PregnancyDueDateString || pregnancyOn
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScrollIndicators(listState),
                state = listState,
            ) {
                item { MasterOfflineBanner(editingEnabled = masterEditingEnabled()) }
                item { AimiEssentialsDisclaimer() }
                AimiEssentialsGroup.entries.forEach { group ->
                    item(key = "header_${group.name}") { AimiEssentialsHeader(group) }
                    items(rows.filter { it.group == group }, key = { it.key.key }) { row ->
                        AimiEssentialsRowItem(preferences = preferences, row = row, live = live)
                    }
                }
                item(key = "targets") { AimiEssentialsTargets(live) }
            }
        }
    }
}

@Composable
private fun AimiEssentialsRowItem(preferences: Preferences, row: AimiEssentialsRow, live: AimiEssentialsLive) {
    val stored = rememberStoredValue(preferences, row.key)
    AimiSettingRow(
        key = row.key,
        hint = row.hint,
        warnings = row.warnings,
        unit = row.unit,
        title = row.title,
        // Only the dose limits get a value in use: the patient inputs are used as stored, and a
        // second line with the same value would only add noise.
        inUse = if (row.group == AimiEssentialsGroup.DOSE_LIMITS) stored?.let { aimiInUse(row.key, it, live) } else null,
        extra = if (row.key == IntKey.OApsAIMINightGrowthAgeYears) {
            { AimiNightGrowthState(preferences) }
        } else null,
    )
}

/**
 * The stored value of [key], kept up to date while the page is open, or null for a text key. A
 * glucose value is returned in mg/dL, as [aimiValueOf] expects.
 */
@Composable
private fun rememberStoredValue(preferences: Preferences, key: PreferenceKey): Any? {
    val profileUtil = LocalProfileUtil.current
    return when (key) {
        is UnitDoublePreferenceKey -> {
            val inUnits by remember(key) { preferences.observe(key) }.collectAsState()
            profileUtil.convertToMgdlDetect(inUnits)
        }

        is DoublePreferenceKey     -> remember(key) { preferences.observe(key) }.collectAsState().value
        is IntPreferenceKey        -> remember(key) { preferences.observe(key) }.collectAsState().value
        is BooleanPreferenceKey    -> remember(key) { preferences.observe(key) }.collectAsState().value
        is StringPreferenceKey     -> null
        else                       -> null
    }
}

/** Read-only: is night growth support on now, and why. The same rule the loop uses. */
@Composable
private fun AimiNightGrowthState(preferences: Preferences) {
    val theme = LocalPreferenceTheme.current
    // An age never set means an adult, so NGR is off unless its switch or T3C turns it on. See
    // rememberNightGrowthState for why the stored state is read again while the page is open.
    val state by rememberNightGrowthState(preferences)
    Text(
        text = stringResource(aimiNightGrowthText(state)),
        style = theme.summaryTextStyle,
        color = theme.titleColor,
    )
}

@Composable
private fun AimiEssentialsDisclaimer() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(AapsSpacing.medium),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Text(
            text = stringResource(ApsStrings.aimi_essentials_disclaimer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(AapsSpacing.large),
        )
    }
}

@Composable
private fun AimiEssentialsHeader(group: AimiEssentialsGroup) {
    val theme = LocalPreferenceTheme.current
    Text(
        text = stringResource(
            when (group) {
                AimiEssentialsGroup.PATIENT     -> ApsStrings.aimi_essentials_group_patient
                AimiEssentialsGroup.DOSE_LIMITS -> ApsStrings.aimi_essentials_group_limits
            }
        ),
        style = theme.categoryTextStyle,
        color = theme.categoryColor,
        modifier = Modifier.padding(theme.categoryPadding),
    )
}

/** The profile targets, read only: they are set in the profile, not here. */
@Composable
private fun AimiEssentialsTargets(live: AimiEssentialsLive) {
    val theme = LocalPreferenceTheme.current
    val profileUtil = LocalProfileUtil.current
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
        Text(
            text = stringResource(ApsStrings.aimi_essentials_group_targets),
            style = theme.categoryTextStyle,
            color = theme.categoryColor,
            modifier = Modifier.padding(theme.categoryPadding),
        )
        Column(modifier = Modifier.padding(theme.padding), verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
            val low = live.targetLowMgdl
            val high = live.targetHighMgdl
            Text(
                text = if (low != null && high != null) {
                    stringResource(
                        ApsStrings.aimi_essentials_targets_value,
                        profileUtil.fromMgdlToStringWithUnits(low),
                        profileUtil.fromMgdlToStringWithUnits(high),
                    )
                } else stringResource(ApsStrings.aimi_essentials_targets_unknown),
                style = theme.titleTextStyle,
                color = theme.titleColor,
            )
            Text(text = stringResource(ApsStrings.aimi_essentials_targets_hint), style = theme.summaryTextStyle, color = theme.summaryColor)
        }
    }
}
