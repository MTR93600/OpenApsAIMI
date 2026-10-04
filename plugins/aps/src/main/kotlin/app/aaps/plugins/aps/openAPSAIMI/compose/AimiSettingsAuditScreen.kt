package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.ProvidePreferenceTheme
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.R
import kotlinx.coroutines.launch

/**
 * The settings audit screen.
 *
 * It answers one question: which of the person's AIMI settings are not what the app ships, and who
 * may have changed them? All the reading and comparing happens in [AimiSettingsAudit], a plain
 * object with no Compose in it; this file only renders the rows that object returns.
 *
 * The secret rule from [AimiSettingsAuditRow] holds here too: a row whose [AimiSettingsAuditRow.secret]
 * is true is rendered as "set" or "not set" only. Its real value never reaches a `Text` composable on
 * this screen, so it can never end up in a screenshot either.
 *
 * Two rules protect the reset button, because this list holds real therapy values by construction —
 * body weight, 7-day TDD and the maximum SMB are all on it:
 * - every reset asks first, naming the setting and the value it is about to write;
 * - a row the loop alone writes ([AimiSettingsAuditRow.runtimeState]) offers no reset at all.
 */
@Composable
fun AimiSettingsAuditScreen(
    preferences: Preferences,
    onBack: () -> Unit,
) {
    var revision by remember { mutableIntStateOf(0) }
    val report = remember(revision) { AimiSettingsAudit.build(preferences) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resetDoneMessage = stringResource(R.string.aimi_settings_audit_reset_done)
    var rowAwaitingConfirmation by remember { mutableStateOf<AimiSettingsAuditRow?>(null) }

    rowAwaitingConfirmation?.let { pending ->
        AimiSettingsAuditResetDialog(
            row = pending,
            onDismiss = { rowAwaitingConfirmation = null },
            onConfirm = {
                pending.resetToDefault(preferences)
                rowAwaitingConfirmation = null
                revision++
                scope.launch { snackbarHostState.showSnackbar(resetDoneMessage) }
            },
        )
    }

    ProvidePreferenceTheme {
        CompositionLocalProvider(LocalPreferences provides preferences) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    AapsTopAppBar(
                        title = { Text(stringResource(R.string.aimi_settings_audit_title)) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(CoreUiR.string.back),
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                // A lazy list, not a scrolling Column: every differing setting is a row, and on a
                // device that has been through a few presets that is a long list to compose at once.
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(
                        start = AapsSpacing.extraLarge,
                        end = AapsSpacing.extraLarge,
                        top = AapsSpacing.medium,
                        bottom = AapsSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
                ) {
                    item { AimiSettingsAuditSummaryCard(report = report) }
                    if (report.rows.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.aimi_settings_audit_empty),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        items(report.rows, key = { it.keyString }) { row ->
                            AimiSettingsAuditRowCard(
                                row = row,
                                onResetRequested = { rowAwaitingConfirmation = row },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AimiSettingsAuditSummaryCard(report: AimiSettingsAuditReport) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Text(
            text = stringResource(
                R.string.aimi_settings_audit_summary,
                report.changedCount.toString(),
                report.scopedCount.toString(),
            ),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(AapsSpacing.extraLarge),
        )
    }
}

@Composable
private fun AimiSettingsAuditRowCard(
    row: AimiSettingsAuditRow,
    onResetRequested: () -> Unit,
) {
    Card {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
        ) {
            Text(
                text = row.titleResId?.let { stringResource(it) } ?: row.keyString,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.aimi_settings_audit_row_tags, levelLabel(row.level), familyLabel(row.family)),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = stringResource(row.changedBy.labelResId),
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.secret) {
                Text(
                    text = stringResource(
                        if (row.isSet) R.string.aimi_settings_audit_secret_set else R.string.aimi_settings_audit_secret_not_set,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AapsSpacing.xxLarge),
                ) {
                    AimiSettingsAuditValueColumn(
                        labelResId = R.string.aimi_settings_audit_stored_label,
                        value = row.storedValue,
                    )
                    AimiSettingsAuditValueColumn(
                        labelResId = R.string.aimi_settings_audit_default_label,
                        value = row.defaultValue,
                    )
                }
            }
            if (row.storedValueIgnored) {
                Text(
                    text = stringResource(R.string.aimi_settings_audit_stored_ignored),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (row.runtimeState) {
                Text(
                    text = stringResource(R.string.aimi_settings_audit_runtime_state),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                TextButton(onClick = onResetRequested) {
                    Text(stringResource(R.string.aimi_settings_audit_reset_button))
                }
            }
        }
    }
}

/**
 * Asks before a reset writes anything.
 *
 * The message names the value that is about to be written, except for a secret row, where naming it
 * would defeat the whole point of [AimiSettingsAuditRow.secret].
 */
@Composable
private fun AimiSettingsAuditResetDialog(
    row: AimiSettingsAuditRow,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = row.titleResId?.let { stringResource(it) } ?: row.keyString
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aimi_settings_audit_reset_confirm_title)) },
        text = {
            Text(
                text = if (row.secret) {
                    stringResource(R.string.aimi_settings_audit_reset_confirm_message_secret, name)
                } else {
                    stringResource(
                        R.string.aimi_settings_audit_reset_confirm_message,
                        name,
                        descriptorText(row.defaultValue),
                    )
                },
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(CoreUiR.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(CoreUiR.string.cancel)) }
        },
    )
}

@Composable
private fun descriptorText(value: AimiValueDescriptor?): String =
    value?.valueResId?.let { stringResource(it) } ?: value?.valueText.orEmpty()

@Composable
private fun AimiSettingsAuditValueColumn(
    @StringRes labelResId: Int,
    value: AimiValueDescriptor?,
) {
    Column {
        Text(
            text = stringResource(labelResId),
            style = MaterialTheme.typography.labelSmall,
        )
        Text(
            text = descriptorText(value),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun levelLabel(level: AimiSettingsLevel): String = when (level) {
    AimiSettingsLevel.SIMPLE   -> stringResource(R.string.aimi_pkpd_level_simple)
    AimiSettingsLevel.ADVANCED -> stringResource(R.string.aimi_pkpd_level_advanced)
    AimiSettingsLevel.EXPERT   -> stringResource(R.string.aimi_pkpd_level_expert)
}

@Composable
private fun familyLabel(family: AimiBehaviorFamilyId): String = stringResource(family.titleResId())
