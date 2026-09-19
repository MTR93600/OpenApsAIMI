package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeNumericStats
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.HarmoniaRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservation
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationFamily
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationLevel
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cAdvisorObservationSignal
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cOwnershipTransition
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeHistorySummary
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeNumericStats
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeOwnershipCategory
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.T3cRuntimeTickStatus
import app.aaps.plugins.aps.openAPSAIMI.compose.formatControlCenterDoubleValue
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * The three read-only runtime-history cards of the Profile Advisor (sub-lot 3/5): the last
 * Recursive Belief unfold, and the 24h summaries of T3C and Harmonia.
 *
 * All of it is observation only - nothing here writes a preference or reaches the pump. The AIMI
 * Control Center shows the latest tick; these cards show the aggregate of the last 24 hours.
 */

/** Unit label of a basal rate, as the Control Center writes it. */
private const val UNIT_RATE = "U/h"

/** Unit label of an insulin amount, as the Control Center writes it. */
private const val UNIT_INSULIN = "U"

/** The dialog shows the export as it was written, so only the indentation is added. */
private val jsonPrettyPrint = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

@Composable
internal fun RecursiveBeliefUnfoldCard(
    preferences: Preferences,
    lastExport: JsonObject?,
) {
    val shadowEnabled = remember { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefShadow) }
    val authorityEnabled = remember { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefAuthority) }
    val waveletEnabled = remember { preferences.get(BooleanKey.OApsAIMIRecursiveBeliefWavelet) }
    var showUnfoldDialog by remember { mutableStateOf(false) }

    HistoryCard(
        title = stringResource(R.string.aimi_rbt_unfold_section_title),
        description = stringResource(R.string.aimi_rbt_unfold_section_desc),
    ) {
        Text(
            text = stringResource(
                R.string.aimi_rbt_unfold_mode,
                shadowEnabled.toString(),
                authorityEnabled.toString(),
                waveletEnabled.toString(),
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lastExport == null) {
            HistoryBodyText(stringResource(R.string.aimi_rbt_unfold_no_data))
        } else {
            HistoryBodyText(rbtSummaryLine(lastExport))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small, Alignment.End),
        ) {
            OutlinedButton(
                enabled = lastExport != null,
                onClick = { showUnfoldDialog = true },
            ) {
                Text(stringResource(R.string.aimi_rbt_unfold_view_btn))
            }
        }
    }

    if (showUnfoldDialog && lastExport != null) {
        RecursiveBeliefUnfoldDialog(
            export = lastExport,
            onDismiss = { showUnfoldDialog = false },
        )
    }
}

/**
 * The raw export, pretty-printed. It is deliberately not summarised: this dialog is the escape
 * hatch used to check what the loop really wrote for a tick.
 */
@Composable
private fun RecursiveBeliefUnfoldDialog(
    export: JsonObject,
    onDismiss: () -> Unit,
) {
    val prettyJson = remember(export) { jsonPrettyPrint.encodeToString(JsonObject.serializer(), export) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aimi_rbt_unfold_dialog_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = prettyJson,
                    style = MaterialTheme.typography.bodySmall,
                    // The dialog already bounds its text slot, so the scroll only handles overflow.
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}

@Composable
private fun rbtSummaryLine(export: JsonObject): String {
    val resolution = export["resolution"] as? JsonObject
    return stringResource(
        R.string.aimi_rbt_unfold_summary,
        rbtReleaseAuthority(resolution),
        rbtSmbDemandU(resolution),
        rbtParadoxCount(export),
        rbtShadowOnly(export).toString(),
    )
}

@Composable
internal fun T3cRuntimeHistoryCard(summary: T3cRuntimeHistorySummary?) {
    HistoryCard(
        title = stringResource(R.string.aimi_t3c_history_section_title),
        description = stringResource(R.string.aimi_t3c_history_section_desc),
    ) {
        if (summary == null) {
            // "Unavailable" here also covers "the file could not be read", not only "the loop never
            // exported": the writer can fall back to app-scoped storage, which this reader cannot see.
            HistoryBodyText(stringResource(R.string.aimi_t3c_history_unavailable))
            return@HistoryCard
        }
        HistoryPillRow(
            tickCount = summary.tickCount,
            dominantLabel = summary.dominantStatus?.let { t3cStatusLabel(it) },
        )
        if (summary.notEnoughData) {
            HistoryBodyText(stringResource(R.string.aimi_t3c_history_not_enough_data, summary.tickCount))
            T3cFamilySignalsSection(summary)
            return@HistoryCard
        }
        HistoryBodyText(t3cHistoryObservation(summary))
        HistoryCountRow(R.string.aimi_t3c_history_native_applied, summary.nativeAppliedCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_t3c_history_native_blocked, summary.nativeBlockedCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_t3c_history_legacy_fallback, summary.legacyFallbackCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_t3c_history_safety_terminal, summary.safetyTerminalCount, summary.tickCount)
        summary.dominantBlocker?.let { blocker ->
            HistoryBodyText(stringResource(R.string.aimi_history_blocker, blocker))
        }
        summary.demandStats?.let { stats ->
            HistoryBodyText(stringResource(R.string.aimi_history_demand, formatT3cRateStats(stats)))
        }
        summary.appliedRateStats?.let { stats ->
            HistoryBodyText(stringResource(R.string.aimi_history_applied_rate, formatT3cRateStats(stats)))
        }
        if (summary.transitionCount > 0) {
            HistoryBodyText(stringResource(R.string.aimi_t3c_history_transitions, summary.transitionCount))
            summary.dominantTransition?.let { transition ->
                HistoryBodyText(
                    stringResource(R.string.aimi_t3c_history_transition_detail, formatT3cTransition(transition)),
                )
            }
        }
        T3cFamilySignalsSection(summary)
    }
}

@Composable
internal fun HarmoniaRuntimeHistoryCard(summary: HarmoniaRuntimeHistorySummary?) {
    HistoryCard(
        title = stringResource(R.string.aimi_harmonia_history_section_title),
        description = stringResource(R.string.aimi_harmonia_history_section_desc),
    ) {
        if (summary == null) {
            HistoryBodyText(stringResource(R.string.aimi_harmonia_history_unavailable))
            return@HistoryCard
        }
        HistoryPillRow(
            tickCount = summary.tickCount,
            dominantLabel = summary.dominantStatus?.let { harmoniaStatusLabel(it) },
        )
        if (summary.notEnoughData) {
            HistoryBodyText(stringResource(R.string.aimi_harmonia_history_not_enough_data, summary.tickCount))
            return@HistoryCard
        }
        HistoryBodyText(harmoniaHistoryObservation(summary))
        HistoryCountRow(R.string.aimi_harmonia_history_native_applied, summary.nativeAppliedCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_native_ready, summary.nativeReadyCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_native_blocked, summary.nativeBlockedCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_t3c_priority, summary.t3cPriorityCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_smb_applied, summary.smbAppliedCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_smb_ready, summary.smbReadyCount, summary.tickCount)
        HistoryCountRow(R.string.aimi_harmonia_history_smb_blocked, summary.smbBlockedCount, summary.tickCount)
        summary.dominantBlocker?.let { blocker ->
            HistoryBodyText(stringResource(R.string.aimi_history_blocker, blocker))
        }
        summary.demandStats?.let { stats ->
            HistoryBodyText(stringResource(R.string.aimi_history_demand, formatHarmoniaRateStats(stats)))
        }
        summary.appliedRateStats?.let { stats ->
            HistoryBodyText(stringResource(R.string.aimi_history_applied_rate, formatHarmoniaRateStats(stats)))
        }
        summary.smbDemandStats?.let { stats ->
            HistoryBodyText(stringResource(R.string.aimi_harmonia_history_smb_demand, formatHarmoniaSmbStats(stats)))
        }
    }
}

// ----- shared card chrome -----

@Composable
private fun HistoryCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

@Composable
private fun HistoryBodyText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun HistoryCountRow(@StringRes labelRes: Int, count: Int, total: Int) {
    HistoryBodyText(stringResource(labelRes, count, percentOf(count, total)))
}

@Composable
private fun HistoryPillRow(tickCount: Int, dominantLabel: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        HistoryPill(stringResource(R.string.aimi_history_period))
        HistoryPill(stringResource(R.string.aimi_history_ticks, tickCount))
        dominantLabel?.let { HistoryPill(stringResource(R.string.aimi_history_dominant, it)) }
    }
}

@Composable
private fun HistoryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = AapsSpacing.large, vertical = AapsSpacing.small),
        )
    }
}

/**
 * The T3C patterns of the window mapped onto the AIMI product families. Harmonia has no equivalent:
 * family observations are produced by the T3C reader only.
 */
@Composable
private fun T3cFamilySignalsSection(summary: T3cRuntimeHistorySummary) {
    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
        Text(
            text = stringResource(R.string.aimi_t3c_family_signals_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.aimi_t3c_family_signals_desc),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (summary.notEnoughData || summary.familyObservations.isEmpty()) {
            HistoryBodyText(stringResource(R.string.aimi_t3c_family_signals_not_enough))
        } else {
            summary.familyObservations.forEach { observation ->
                HistoryBodyText(formatT3cFamilyObservation(observation))
            }
        }
    }
}

// ----- text mapping -----

/** Default of `resolution.release_authority`, matching what the exporter writes when unset. */
private const val RELEASE_AUTHORITY_NONE = "NONE"

private fun rbtReleaseAuthority(resolution: JsonObject?): String =
    runCatching { resolution?.get("release_authority")?.jsonPrimitive?.content }
        .getOrNull()
        ?.takeIf { it.isNotBlank() && it != "null" }
        ?: RELEASE_AUTHORITY_NONE

private fun rbtSmbDemandU(resolution: JsonObject?): Double =
    runCatching { resolution?.get("smb_demand_u")?.jsonPrimitive?.content?.toDouble() }.getOrNull() ?: 0.0

private fun rbtParadoxCount(export: JsonObject): Int =
    runCatching { export["paradoxes"]?.jsonArray?.size }.getOrNull() ?: 0

private fun rbtShadowOnly(export: JsonObject): Boolean =
    runCatching { export["shadow_only"]?.jsonPrimitive?.content?.toBoolean() }.getOrNull() ?: true

@Composable
private fun t3cHistoryObservation(summary: T3cRuntimeHistorySummary): String {
    val unknownBlocker = stringResource(R.string.aimi_history_blocker_unknown)
    return when (summary.dominantStatus) {
        T3cRuntimeTickStatus.NATIVE_APPLIED  -> stringResource(
            R.string.aimi_t3c_history_observation_applied,
            percentOf(summary.nativeAppliedCount, summary.tickCount),
        )

        T3cRuntimeTickStatus.NATIVE_BLOCKED  -> stringResource(
            R.string.aimi_t3c_history_observation_blocked,
            summary.dominantBlocker ?: unknownBlocker,
        )

        T3cRuntimeTickStatus.LEGACY_FALLBACK -> stringResource(
            R.string.aimi_t3c_history_observation_legacy,
            percentOf(summary.legacyFallbackCount, summary.tickCount),
        )

        T3cRuntimeTickStatus.SAFETY_TERMINAL -> stringResource(R.string.aimi_t3c_history_observation_safety)
        else                                 -> stringResource(R.string.aimi_t3c_history_observation_mixed)
    }
}

@Composable
private fun harmoniaHistoryObservation(summary: HarmoniaRuntimeHistorySummary): String {
    val unknownBlocker = stringResource(R.string.aimi_history_blocker_unknown)
    return when (summary.dominantStatus) {
        HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> stringResource(
            R.string.aimi_harmonia_history_observation_applied,
            percentOf(summary.nativeAppliedCount, summary.tickCount),
        )

        HarmoniaRuntimeTickStatus.NATIVE_READY   -> stringResource(R.string.aimi_harmonia_history_observation_ready)
        HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> stringResource(
            R.string.aimi_harmonia_history_observation_blocked,
            summary.dominantBlocker ?: unknownBlocker,
        )

        HarmoniaRuntimeTickStatus.T3C_PRIORITY   -> stringResource(
            R.string.aimi_harmonia_history_observation_t3c_priority,
            percentOf(summary.t3cPriorityCount, summary.tickCount),
        )

        else                                     -> stringResource(R.string.aimi_harmonia_history_observation_mixed)
    }
}

@Composable
private fun formatT3cRateStats(stats: T3cRuntimeNumericStats): String =
    formatStatsRange(average = stats.average, min = stats.min, max = stats.max, unit = UNIT_RATE)

@Composable
private fun formatHarmoniaRateStats(stats: HarmoniaRuntimeNumericStats): String =
    formatStatsRange(average = stats.average, min = stats.min, max = stats.max, unit = UNIT_RATE)

@Composable
private fun formatHarmoniaSmbStats(stats: HarmoniaRuntimeNumericStats): String =
    formatStatsRange(average = stats.average, min = stats.min, max = stats.max, unit = UNIT_INSULIN)

@Composable
private fun formatStatsRange(average: Double, min: Double, max: Double, unit: String): String =
    stringResource(
        R.string.aimi_history_stats_range,
        formatControlCenterDoubleValue(average, unit),
        formatControlCenterDoubleValue(min, null),
        formatControlCenterDoubleValue(max, null),
    )

@Composable
private fun formatT3cTransition(transition: T3cOwnershipTransition): String =
    stringResource(
        R.string.aimi_t3c_history_transition_arrow,
        t3cOwnershipLabel(transition.from),
        t3cOwnershipLabel(transition.to),
    )

@Composable
private fun formatT3cFamilyObservation(observation: T3cAdvisorObservation): String =
    stringResource(
        R.string.aimi_t3c_family_signal_row,
        t3cObservationFamilyLabel(observation.family),
        t3cObservationLevelLabel(observation.level),
        t3cObservationSignalLabel(observation.signal),
    )

@Composable
private fun t3cObservationFamilyLabel(family: T3cAdvisorObservationFamily): String =
    when (family) {
        T3cAdvisorObservationFamily.STABILITY          -> stringResource(R.string.aimi_t3c_family_stability)
        T3cAdvisorObservationFamily.MEAL_CAPTURE       -> stringResource(R.string.aimi_t3c_family_meal_capture)
        T3cAdvisorObservationFamily.PHYSIO_AMBIGUITY   -> stringResource(R.string.aimi_t3c_family_physio_ambiguity)
        T3cAdvisorObservationFamily.POST_HYPO_RECOVERY -> stringResource(R.string.aimi_t3c_family_post_hypo)
        T3cAdvisorObservationFamily.ACTIVITY           -> stringResource(R.string.aimi_t3c_family_activity)
        T3cAdvisorObservationFamily.AUTONOMY           -> stringResource(R.string.aimi_t3c_family_autonomy)
        T3cAdvisorObservationFamily.NATIVE_RBT         -> stringResource(R.string.aimi_t3c_family_native_rbt)
    }

@Composable
private fun t3cObservationLevelLabel(level: T3cAdvisorObservationLevel): String =
    when (level) {
        T3cAdvisorObservationLevel.HIGH   -> stringResource(R.string.aimi_t3c_level_high)
        T3cAdvisorObservationLevel.MEDIUM -> stringResource(R.string.aimi_t3c_level_medium)
        T3cAdvisorObservationLevel.LOW    -> stringResource(R.string.aimi_t3c_level_low)
        T3cAdvisorObservationLevel.STABLE -> stringResource(R.string.aimi_t3c_level_stable)
    }

@Composable
private fun t3cObservationSignalLabel(signal: T3cAdvisorObservationSignal): String =
    when (signal) {
        T3cAdvisorObservationSignal.SAFETY_GATES_OFTEN_BLOCK -> stringResource(R.string.aimi_t3c_signal_safety_gates_block)
        T3cAdvisorObservationSignal.MEAL_CONFLICTS_APPEAR    -> stringResource(R.string.aimi_t3c_signal_meal_conflicts)
        T3cAdvisorObservationSignal.POST_HYPO_GUARD_DOMINATES -> stringResource(R.string.aimi_t3c_signal_post_hypo)
        T3cAdvisorObservationSignal.ACTIVITY_LOCKOUT_VISIBLE -> stringResource(R.string.aimi_t3c_signal_activity_lockout)
        T3cAdvisorObservationSignal.LEGACY_FALLBACK_VISIBLE  -> stringResource(R.string.aimi_t3c_signal_legacy_fallback)
        T3cAdvisorObservationSignal.NATIVE_APPLIES_WHEN_CLEAR -> stringResource(R.string.aimi_t3c_signal_native_clear)
        T3cAdvisorObservationSignal.BLOCKERS_STAY_MIXED      -> stringResource(R.string.aimi_t3c_signal_blockers_mixed)
    }

@Composable
private fun t3cStatusLabel(status: T3cRuntimeTickStatus): String =
    when (status) {
        T3cRuntimeTickStatus.NATIVE_APPLIED  -> stringResource(R.string.aimi_control_center_t3c_status_native_applied)
        T3cRuntimeTickStatus.NATIVE_READY    -> stringResource(R.string.aimi_control_center_t3c_status_native_ready)
        T3cRuntimeTickStatus.NATIVE_BLOCKED  -> stringResource(R.string.aimi_control_center_t3c_status_native_blocked)
        T3cRuntimeTickStatus.LEGACY_FALLBACK -> stringResource(R.string.aimi_control_center_t3c_status_legacy_fallback)
        T3cRuntimeTickStatus.SAFETY_TERMINAL -> stringResource(R.string.aimi_control_center_t3c_status_safety_terminal)
        T3cRuntimeTickStatus.UNAVAILABLE     -> stringResource(R.string.aimi_control_center_t3c_status_unavailable)
    }

@Composable
private fun harmoniaStatusLabel(status: HarmoniaRuntimeTickStatus): String =
    when (status) {
        HarmoniaRuntimeTickStatus.NATIVE_APPLIED -> stringResource(R.string.aimi_control_center_harmonia_status_native_applied)
        HarmoniaRuntimeTickStatus.NATIVE_READY   -> stringResource(R.string.aimi_control_center_harmonia_status_native_ready)
        HarmoniaRuntimeTickStatus.NATIVE_BLOCKED -> stringResource(R.string.aimi_control_center_harmonia_status_native_blocked)
        HarmoniaRuntimeTickStatus.T3C_PRIORITY   -> stringResource(R.string.aimi_control_center_harmonia_status_t3c_priority)
        HarmoniaRuntimeTickStatus.UNAVAILABLE    -> stringResource(R.string.aimi_control_center_harmonia_status_unavailable)
    }

@Composable
private fun t3cOwnershipLabel(category: T3cRuntimeOwnershipCategory): String =
    when (category) {
        T3cRuntimeOwnershipCategory.NATIVE      -> stringResource(R.string.aimi_control_center_t3c_owner_native)
        T3cRuntimeOwnershipCategory.LEGACY      -> stringResource(R.string.aimi_control_center_t3c_owner_legacy)
        T3cRuntimeOwnershipCategory.SAFETY      -> stringResource(R.string.aimi_control_center_t3c_owner_safety)
        T3cRuntimeOwnershipCategory.UNAVAILABLE -> stringResource(R.string.aimi_control_center_t3c_owner_unavailable)
    }

/**
 * Share of [total] taken by [count], in whole percent. Both guards matter: a zero window would
 * divide by zero, and a negative count would print a share the card cannot mean.
 */
internal fun percentOf(count: Int, total: Int): Int =
    if (count <= 0 || total <= 0) 0 else ((count * 100.0) / total).roundToInt()
