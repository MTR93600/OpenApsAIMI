package app.aaps.ui.compose.dashboard.essential

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.statusLevelToColor
import app.aaps.core.ui.compose.statusLevelToDescription
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.DashboardV2ToolAction
import app.aaps.ui.compose.dashboard.glass.GlassUiState
import app.aaps.ui.compose.dashboard.glass.icon
import app.aaps.ui.compose.dashboard.glassSheetContainerColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One tap on − or + moves the Boost strength by this many percent. */
private const val BOOST_PERCENT_STEP = 5

/** One past decision, formatted: when, and what was delivered. */
data class EssentialDecision(val time: String, val what: String)

/**
 * The detail sheet that a tap on the shape opens. The Boost buttons here are the explicit path to the
 * same actions as the gesture: the gesture is never the only way.
 *
 * @param aimiTools the AIMI screens to offer right under the Boost (Context, Advisor, Meal Advisor), already
 *   filtered to what this phone can open. Empty hides the row.
 * @param onOpenTool opens one of [aimiTools]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EssentialDetailSheet(
    state: GlassUiState,
    boost: AimiBoostState,
    boostPercent: Int,
    loopTargetText: String?,
    onBoostPercentStep: (Int) -> Unit,
    commandsAllowed: Boolean,
    decisions: List<EssentialDecision>,
    iob: List<GraphDataPoint>,
    onStartBoost: () -> Unit,
    onCancelBoost: () -> Unit,
    onDismiss: () -> Unit,
    aimiTools: List<DashboardV2ToolAction> = emptyList(),
    onOpenTool: (DashboardV2ToolAction) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = glassSheetContainerColor()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AapsTheme.spacing.extraLarge, vertical = AapsTheme.spacing.medium)) {
            // Boost
            Text(stringResource(UiStrings.essential_boost_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(UiStrings.essential_boost_explain, boostPercent),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // The strength of the next Boost; a running one keeps the strength it started with
            Row(Modifier.fillMaxWidth().padding(top = AapsTheme.spacing.small), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(UiStrings.essential_boost_strength), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onBoostPercentStep(-BOOST_PERCENT_STEP) }, enabled = commandsAllowed) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(UiStrings.essential_boost_less))
                }
                Text(stringResource(UiStrings.essential_boost_percent, boostPercent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { onBoostPercentStep(BOOST_PERCENT_STEP) }, enabled = commandsAllowed) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(UiStrings.essential_boost_more))
                }
            }
            Spacer(Modifier.height(AapsTheme.spacing.small))
            when {
                boost.active -> {
                    Text(
                        loopTargetText?.let { stringResource(UiStrings.essential_boost_active_target, boost.remainingMinutes, it) }
                            ?: stringResource(UiStrings.essential_boost_active, boost.remainingMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(AapsTheme.spacing.small))
                    OutlinedButton(onClick = onCancelBoost, enabled = commandsAllowed, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(UiStrings.essential_boost_cancel))
                    }
                }

                else         -> {
                    boost.unavailableReason?.let { reason ->
                        Text(reason.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(AapsTheme.spacing.small))
                    }
                    Button(onClick = onStartBoost, enabled = commandsAllowed && boost.available, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(UiStrings.essential_boost_start))
                    }
                }
            }

            // Right under the Boost, so they are two taps away from the home: the shape, then the button
            if (aimiTools.isNotEmpty()) {
                SectionDivider()
                Text(stringResource(UiStrings.dashboard_v2_tools_aimi_section), style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.fillMaxWidth().padding(top = AapsTheme.spacing.small),
                    horizontalArrangement = Arrangement.spacedBy(AapsTheme.spacing.small),
                ) {
                    aimiTools.forEach { tool ->
                        AimiToolButton(tool, onClick = { onOpenTool(tool) }, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    stringResource(UiStrings.essential_tools_long_press_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AapsTheme.spacing.small),
                )
            }

            SectionDivider()
            Text(stringResource(UiStrings.essential_recent_decisions), style = MaterialTheme.typography.titleSmall)
            decisions.forEach { decision ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(decision.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(decision.what, style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionDivider()
            Text(stringResource(UiStrings.essential_insulin_on_board), style = MaterialTheme.typography.titleSmall)
            IobLine(iob)
            AgeRow(
                stringResource(UiStrings.essential_target),
                if (loopTargetText != null && loopTargetText != state.targetText) {
                    stringResource(UiStrings.essential_target_loop, state.targetText, loopTargetText)
                } else {
                    state.targetText
                },
            )
            AgeRow(stringResource(UiStrings.essential_activity_title), stringResource(UiStrings.essential_activity, state.stepsText, state.hrText))
            state.tir?.let { tir ->
                val vsYesterday = tir.deltaVsYesterday?.let { delta ->
                    stringResource(UiStrings.essential_tir_delta, if (delta > 0) "+$delta" else delta.toString())
                } ?: ""
                AgeRow(stringResource(UiStrings.essential_tir, tir.inRangePct.roundToInt().toString()), vsYesterday)
            }

            SectionDivider()
            Text(stringResource(UiStrings.essential_devices), style = MaterialTheme.typography.titleSmall)
            AgeRow(stringResource(UiStrings.essential_age_insulin), state.insulinAge, state.insulinAgeStatus)
            state.reservoirLevel?.let { AgeRow(stringResource(UiStrings.essential_reservoir), it, state.reservoirLevelStatus) }
            AgeRow(stringResource(UiStrings.essential_age_cannula), state.cannulaAge, state.cannulaAgeStatus)
            AgeRow(stringResource(UiStrings.essential_age_sensor), state.sensorAge, state.sensorAgeStatus)
            AgeRow(stringResource(UiStrings.essential_age_battery), state.batteryAge, state.batteryAgeStatus)
            state.batteryLevel?.let { AgeRow(stringResource(UiStrings.essential_pump_battery), it, state.batteryLevelStatus) }
            Spacer(Modifier.height(AapsTheme.spacing.xxLarge))
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = AapsTheme.spacing.large))
}

/**
 * One line of label and value. With a [status], the value takes the warning or critical colour of the
 * status lights, and a screen reader also hears the word, so colour is never the only sign.
 */
@Composable
private fun AgeRow(label: String, value: String, status: StatusLevel = StatusLevel.UNSPECIFIED) {
    val statusWord = statusLevelToDescription(status)?.let { stringResource(it) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = statusLevelToColor(status),
            modifier = if (statusWord != null) Modifier.semantics { contentDescription = "$value, $statusWord" } else Modifier,
        )
    }
}

/** One AIMI screen as a quiet tile: its glyph over its name. */
@Composable
private fun AimiToolButton(tool: DashboardV2ToolAction, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = AapsTheme.spacing.medium, horizontal = AapsTheme.spacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(tool.icon(), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(AapsTheme.spacing.extraSmall))
            Text(
                stringResource(tool.label),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** The insulin on board of the chart window as one line. */
@Composable
private fun IobLine(iob: List<GraphDataPoint>) {
    val color = AapsTheme.generalColors.iobPrediction
    val grid = AapsTheme.generalColors.graphGrid
    Canvas(Modifier.fillMaxWidth().height(72.dp).padding(top = AapsTheme.spacing.small)) {
        val points = iob.sortedBy { it.timestamp }
        if (points.size < 2) return@Canvas
        val from = points.first().timestamp
        val to = points.last().timestamp
        val minV = min(points.minOf { it.value }, 0.0)
        val maxV = max(points.maxOf { it.value }, 0.1)
        fun x(t: Long) = if (to == from) 0f else ((t - from).toFloat() / (to - from)) * size.width
        fun y(v: Double) = (size.height * (1.0 - (v - minV) / (maxV - minV))).toFloat()
        drawLine(grid, Offset(0f, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 1.dp.toPx())
        points.zipWithNext().forEach { (a, b) ->
            drawLine(color, Offset(x(a.timestamp), y(a.value)), Offset(x(b.timestamp), y(b.value)), strokeWidth = 2.dp.toPx())
        }
    }
}

/** The one-line reason a Boost cannot start. */
@Composable
fun AimiBoostUnavailableReason.text(): String = stringResource(
    when (this) {
        AimiBoostUnavailableReason.NOT_AIMI        -> UiStrings.essential_boost_reason_not_aimi
        AimiBoostUnavailableReason.LOOP_NOT_CLOSED -> UiStrings.essential_boost_reason_loop
        AimiBoostUnavailableReason.NO_RECENT_TICK  -> UiStrings.essential_boost_reason_no_tick
        AimiBoostUnavailableReason.BELOW_TARGET    -> UiStrings.essential_boost_reason_below_target
        AimiBoostUnavailableReason.FALLING         -> UiStrings.essential_boost_reason_falling
        AimiBoostUnavailableReason.RECENT_LOW      -> UiStrings.essential_boost_reason_recent_low
        AimiBoostUnavailableReason.REBOUND_GUARD   -> UiStrings.essential_boost_reason_rebound
        AimiBoostUnavailableReason.ALREADY_ACTIVE  -> UiStrings.essential_boost_reason_already
        AimiBoostUnavailableReason.AT_LIMIT        -> UiStrings.essential_boost_reason_at_limit
    }
)
