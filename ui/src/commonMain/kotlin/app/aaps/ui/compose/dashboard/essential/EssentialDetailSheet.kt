package app.aaps.ui.compose.dashboard.essential

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.aps.AimiBoostState
import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.core.interfaces.overview.graph.GraphDataPoint
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings
import app.aaps.ui.compose.dashboard.glass.GlassUiState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** One past decision, formatted: when, and what was delivered. */
data class EssentialDecision(val time: String, val what: String)

/**
 * The detail sheet that a tap on the shape opens. The Boost buttons here are the explicit path to the
 * same actions as the gesture: the gesture is never the only way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EssentialDetailSheet(
    state: GlassUiState,
    boost: AimiBoostState,
    commandsAllowed: Boolean,
    decisions: List<EssentialDecision>,
    iob: List<GraphDataPoint>,
    onStartBoost: () -> Unit,
    onCancelBoost: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AapsTheme.spacing.extraLarge, vertical = AapsTheme.spacing.medium)) {
            // Boost
            Text(stringResource(UiStrings.essential_boost_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(UiStrings.essential_boost_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AapsTheme.spacing.medium))
            when {
                boost.active -> {
                    Text(stringResource(UiStrings.essential_boost_active, boost.remainingMinutes), style = MaterialTheme.typography.bodyMedium)
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
            AgeRow(stringResource(UiStrings.essential_target), state.targetText)
            AgeRow(stringResource(UiStrings.essential_activity_title), stringResource(UiStrings.essential_activity, state.stepsText, state.hrText))
            state.tir?.let { tir ->
                val vsYesterday = tir.deltaVsYesterday?.let { delta ->
                    stringResource(UiStrings.essential_tir_delta, if (delta > 0) "+$delta" else delta.toString())
                } ?: ""
                AgeRow(stringResource(UiStrings.essential_tir, tir.inRangePct.roundToInt().toString()), vsYesterday)
            }

            SectionDivider()
            Text(stringResource(UiStrings.essential_ages), style = MaterialTheme.typography.titleSmall)
            AgeRow(stringResource(UiStrings.essential_age_insulin), state.insulinAge)
            AgeRow(stringResource(UiStrings.essential_age_cannula), state.cannulaAge)
            AgeRow(stringResource(UiStrings.essential_age_sensor), state.sensorAge)
            AgeRow(stringResource(UiStrings.essential_age_battery), state.batteryAge)
            Spacer(Modifier.height(AapsTheme.spacing.xxLarge))
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(Modifier.padding(vertical = AapsTheme.spacing.large))
}

@Composable
private fun AgeRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall)
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
