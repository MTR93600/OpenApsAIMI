package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.preference.AdaptiveDoublePreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveIntPreferenceItem
import app.aaps.core.ui.compose.preference.AdaptivePreferenceItem
import app.aaps.core.ui.compose.preference.AdaptiveSwitchPreferenceItem
import app.aaps.core.ui.compose.preference.LocalPreferenceTheme
import app.aaps.core.ui.compose.preference.LocalVisibilityContext
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings

/**
 * One AIMI setting, the Trio way: label, one-line hint, a "?" button and, when known, the value in use
 * now with a badge that says why it is not the stored value.
 *
 * The control itself is the shared one from core/ui, so a number can only be set between the key's
 * min and max, a hidden key stays hidden (simple mode, client role) and the value is written the same
 * way as on every other settings screen. The "?" sheet starts with "Default: X" and ends with the
 * [warnings]; see [aimiSettingSheetLines].
 *
 * Shared code: the same row runs on Android and iOS.
 *
 * @param key the setting
 * @param hint one line under the label
 * @param warnings last lines of the "?" sheet; empty when the setting cannot change insulin delivery
 * @param unit unit label for a key that has none of its own
 * @param title label to show instead of the key's title
 * @param inUse the value in use (it may depend on the basal mode), or null when it is not known
 * @param extra read-only content shown under the row, for example a state the value turns on
 */
@Composable
internal fun AimiSettingRow(
    key: PreferenceKey,
    hint: TextRef,
    warnings: List<TextRef>,
    unit: TextRef? = null,
    title: TextRef? = null,
    inUse: AimiInUse? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    val theme = LocalPreferenceTheme.current
    val preferences = LocalPreferences.current
    var showHelp by remember { mutableStateOf(false) }
    val effectiveTitle = title ?: key.title
    // The shared control draws nothing for a key simple mode computes; the label must stay.
    val hiddenBySimpleMode = preferences.simpleMode && (key.defaultedBySM || (key as? DoublePreferenceKey)?.calculatedBySM == true)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                if (hiddenBySimpleMode) {
                    Text(
                        text = stringResource(effectiveTitle),
                        style = theme.titleTextStyle,
                        color = theme.titleColor,
                        modifier = Modifier.padding(theme.padding),
                    )
                } else {
                    AimiSettingControl(key = key, title = title)
                }
            }
            IconButton(onClick = { showHelp = true }) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(ApsStrings.aimi_setting_help),
                )
            }
        }
        Column(
            modifier = Modifier.padding(theme.padding),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall),
        ) {
            Text(text = stringResource(hint), style = theme.summaryTextStyle, color = theme.summaryColor)
            inUse?.let { AimiInUseLine(key = key, unit = unit, inUse = it) }
            extra?.invoke()
        }
    }

    if (showHelp) {
        AimiSettingHelpSheet(
            title = effectiveTitle,
            lines = aimiSettingSheetLines(key, unit, warnings),
            onDismiss = { showHelp = false },
        )
    }
}

/** The shared control of [key], with the label replaced by [title] where the control allows it. */
@Composable
private fun AimiSettingControl(key: PreferenceKey, title: TextRef?) {
    val visibilityContext = LocalVisibilityContext.current
    when {
        title != null && key is IntPreferenceKey     -> AdaptiveIntPreferenceItem(intKey = key, title = title, visibilityContext = visibilityContext)
        title != null && key is DoublePreferenceKey  -> AdaptiveDoublePreferenceItem(doubleKey = key, title = title, visibilityContext = visibilityContext)
        title != null && key is BooleanPreferenceKey -> AdaptiveSwitchPreferenceItem(booleanKey = key, title = title, visibilityContext = visibilityContext)
        else                                         -> AdaptivePreferenceItem(key = key, onShowMessage = {}, visibilityContext = visibilityContext)
    }
}

/**
 * "In use now: X" (or the value of a mode that is not on now, or the note of a limit the loop sets on
 * every decision), and the badge when it is not the stored value.
 */
@Composable
private fun AimiInUseLine(key: PreferenceKey, unit: TextRef?, inUse: AimiInUse) {
    val theme = LocalPreferenceTheme.current
    val perDecisionNote = aimiPerDecisionNote(key)
    Text(
        text = when {
            inUse.kind == AimiInUseKind.PER_DECISION && perDecisionNote != null -> stringResource(perDecisionNote)
            inUse.kind == AimiInUseKind.WHEN_ACTIVE                            ->
                stringResource(ApsStrings.aimi_setting_when_active, aimiValueText(aimiValueOf(key, inUse.value, unit)))

            else                                                               ->
                stringResource(ApsStrings.aimi_setting_in_use, aimiValueText(aimiValueOf(key, inUse.value, unit)))
        },
        style = theme.summaryTextStyle,
        color = theme.titleColor,
    )
    when (inUse.badge) {
        AimiInUseBadge.TPO     -> {
            val userValue = inUse.userValue?.let { aimiValueText(aimiValueOf(key, it, unit)) }
            Text(
                text = if (userValue != null) stringResource(ApsStrings.aimi_setting_badge_tpo, userValue)
                else stringResource(ApsStrings.aimi_setting_badge_tpo_no_value),
                style = theme.summaryTextStyle,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }

        AimiInUseBadge.LIMITED -> Text(
            text = stringResource(ApsStrings.aimi_setting_badge_limited),
            style = theme.summaryTextStyle,
            color = MaterialTheme.colorScheme.tertiary,
        )

        AimiInUseBadge.RAISED  -> Text(
            text = stringResource(ApsStrings.aimi_setting_badge_raised),
            style = theme.summaryTextStyle,
            color = MaterialTheme.colorScheme.tertiary,
        )

        null                   -> Unit
    }
}

/** A value as text, with its unit, in the units of the person for glucose. */
@Composable
internal fun aimiValueText(value: AimiSettingValue): String =
    when (value) {
        is AimiSettingValue.Number  -> {
            val number = NumberFormat.withDecimals(value.decimals).format(value.value)
            value.unit?.let { stringResource(ApsStrings.aimi_setting_value_unit, number, stringResource(it)) } ?: number
        }

        is AimiSettingValue.Glucose -> LocalProfileUtil.current.fromMgdlToStringWithUnits(value.mgdl)
        is AimiSettingValue.Switch  -> stringResource(if (value.on) ApsStrings.aimi_setting_on else ApsStrings.aimi_setting_off)
        is AimiSettingValue.Text    -> value.text.ifEmpty { stringResource(ApsStrings.aimi_setting_not_set) }
    }

/** The "?" sheet: default first, warnings last. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AimiSettingHelpSheet(title: TextRef, lines: List<AimiSettingSheetLine>, onDismiss: () -> Unit) {
    val theme = LocalPreferenceTheme.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AapsSpacing.extraLarge)
                .padding(bottom = AapsSpacing.xxLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(text = stringResource(title), style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                when (line) {
                    is AimiSettingSheetLine.Default -> Text(
                        text = stringResource(ApsStrings.aimi_setting_default, aimiValueText(line.value)),
                        style = theme.titleTextStyle,
                    )

                    is AimiSettingSheetLine.Detail  -> Text(text = stringResource(line.text), style = theme.summaryTextStyle)
                    is AimiSettingSheetLine.Note    -> Text(text = stringResource(line.text), style = theme.summaryTextStyle)
                    is AimiSettingSheetLine.Warning -> Text(
                        text = stringResource(line.text),
                        style = theme.summaryTextStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
