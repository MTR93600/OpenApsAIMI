package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefAnalysisReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefUserInsightFormatter
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.Position
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

/**
 * Sub-lot 4/5 of the Profile Advisor Compose port: the brain (cognitive state), OREF analysis, and
 * AI Coach cards. All three sit at the end of the column, after the recommendation sections.
 *
 * None of this does its own IO or network work: [CognitiveBrainCard] reads a value already on the
 * report, [OrefAnalysisCard] formats a report field that is already computed, and the AI Coach's own
 * load (network call or deterministic fallback) lives in the screen, not here - this file only
 * renders whatever state it is given.
 */

/** Decorative only, not user-facing text that needs translation. */
private const val BRAIN_EMOJI = "🧠" // 🧠

/**
 * The unified reactivity factor as a protective/offensive/neutral state. Unconditional: the factor
 * always exists on the report's advisor context and is coerced to [0.5, 1.5] upstream, so there is
 * no "missing data" case to render here.
 */
@Composable
internal fun CognitiveBrainCard(reactivityFactor: Double) {
    val (stateRes, descRes, stateColor) = when {
        reactivityFactor < 0.95 -> Triple(
            R.string.aimi_adv_brain_protect,
            R.string.aimi_adv_brain_protect_desc,
            AapsTheme.generalColors.bgLow,
        )

        reactivityFactor > 1.05 -> Triple(
            R.string.aimi_adv_brain_offense,
            R.string.aimi_adv_brain_offense_desc,
            AapsTheme.generalColors.bgHigh,
        )

        else                    -> Triple(
            R.string.aimi_adv_brain_neutral,
            R.string.aimi_adv_brain_neutral_desc,
            AapsTheme.generalColors.bgInRange,
        )
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            horizontalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            Text(text = BRAIN_EMOJI, style = MaterialTheme.typography.headlineSmall)
            Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.extraSmall)) {
                Text(
                    text = stringResource(stateRes, reactivityFactor),
                    style = MaterialTheme.typography.titleMedium,
                    color = stateColor,
                )
                Text(
                    text = stringResource(descRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * On-device OREF-aligned analysis: a plain-language paragraph, the raw prompt-section block, and an
 * optional CGM time-in-ranges chart (collapsed by default, per the staged behaviour).
 *
 * [rh] is passed in only to resolve [OrefUserInsightFormatter.buildParagraph], which is not a
 * composable - every other string here goes through [stringResource].
 */
@Composable
internal fun OrefAnalysisCard(oref: OrefAnalysisReport, rh: ResourceHelper) {
    var showChart by remember { mutableStateOf(false) }
    val canShowChart = oref.timeBelow70Pct != null || oref.timeInRange70180Pct != null || oref.timeAbove180Pct != null

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_adv_oref_user_insight_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = OrefUserInsightFormatter.buildParagraph(rh, oref),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = oref.toPromptSection().trim(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canShowChart) {
                OutlinedButton(onClick = { showChart = !showChart }) {
                    Text(
                        stringResource(
                            if (showChart) R.string.aimi_adv_oref_hide_chart else R.string.aimi_adv_oref_show_chart,
                        ),
                    )
                }
                if (showChart) {
                    OrefCgmRangeChart(oref)
                }
            }
        }
    }
}

/**
 * Bar chart of the OREF-window CGM distribution (the same three percentages as the monospace
 * summary above it): time below 70, time in range 70-180, and time above 180.
 *
 * The only chart in this port. Built with Vico - the repo's KMP-compatible charting library, already
 * on this module's classpath via `:core:graph` (see `core/graph/build.gradle.kts` and
 * `plugins/aps/build.gradle.kts`) - copying the axis/host/zoom-state setup from
 * `core/graph/IsfProfileGraphCompose.kt`. That file draws a line chart; this one needs Vico's column
 * layer instead, which nothing in the repo used yet, so its API came from the library's own sources
 * (`ColumnCartesianLayer.kt`, `ColumnCartesianLayerModel.kt`) rather than from an existing example.
 *
 * The three categories are one series with three x-values, and the colour comes from a
 * [ColumnCartesianLayer.ColumnProvider] that keys on `entry.x`, so each bar still gets its own
 * colour and the bottom axis ends up with exactly one labeled x-position per category.
 */
@Composable
private fun OrefCgmRangeChart(oref: OrefAnalysisReport) {
    val belowPct = oref.timeBelow70Pct ?: 0.0
    val inRangePct = oref.timeInRange70180Pct ?: 0.0
    val abovePct = oref.timeAbove180Pct ?: 0.0

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(belowPct, inRangePct, abovePct) {
        modelProducer.runTransaction {
            columnModel {
                series(x = listOf(0, 1, 2), y = listOf(belowPct, inRangePct, abovePct))
            }
        }
    }

    val lowLabel = stringResource(R.string.aimi_adv_oref_bar_low)
    val inRangeLabel = stringResource(R.string.aimi_adv_oref_bar_in_range)
    val highLabel = stringResource(R.string.aimi_adv_oref_bar_high)
    val lowColor = AapsTheme.generalColors.bgLow
    val inRangeColor = AapsTheme.generalColors.bgInRange
    val highColor = AapsTheme.generalColors.bgHigh
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dataLabelColor = MaterialTheme.colorScheme.onSurface

    val columnProvider = remember(lowColor, inRangeColor, highColor) {
        object : ColumnCartesianLayer.ColumnProvider {
            private val columns = listOf(
                LineComponent(fill = Fill(lowColor), thickness = 32.dp),
                LineComponent(fill = Fill(inRangeColor), thickness = 32.dp),
                LineComponent(fill = Fill(highColor), thickness = 32.dp),
            )

            override fun getColumn(entry: ColumnCartesianLayerModel.Entry, extraStore: ExtraStore): LineComponent =
                columns[entry.x.toInt().coerceIn(0, columns.lastIndex)]

            override fun getWidestSeriesColumn(seriesKey: Any, seriesIndex: Int, extraStore: ExtraStore): LineComponent =
                columns[seriesIndex.coerceIn(0, columns.lastIndex)]
        }
    }
    val bottomLabelComponent = rememberTextComponent(style = TextStyle(color = axisLabelColor))
    val startLabelComponent = rememberTextComponent(style = TextStyle(color = axisLabelColor))
    val dataLabelComponent = rememberTextComponent(style = TextStyle(color = dataLabelColor))
    val categoryFormatter = remember(lowLabel, inRangeLabel, highLabel) {
        CartesianValueFormatter { _, value, _ ->
            when (value.toInt()) {
                0    -> lowLabel
                1    -> inRangeLabel
                else -> highLabel
            }
        }
    }
    val percentAxisFormatter = remember { CartesianValueFormatter.decimal(decimalCount = 0, suffix = "%") }
    val percentDataLabelFormatter = remember { CartesianValueFormatter.decimal(decimalCount = 1, suffix = "%") }
    val rangeProvider = remember { CartesianLayerRangeProvider.fixed(minY = 0.0, maxY = 100.0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = AapsSpacing.small),
        verticalArrangement = Arrangement.spacedBy(AapsSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.aimi_adv_oref_chart_title),
            style = MaterialTheme.typography.titleSmall,
        )
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberColumnCartesianLayer(
                    columnProvider = columnProvider,
                    dataLabel = dataLabelComponent,
                    dataLabelPosition = Position.Vertical.Top,
                    dataLabelValueFormatter = percentDataLabelFormatter,
                    rangeProvider = rangeProvider,
                ),
                startAxis = VerticalAxis.rememberStart(label = startLabelComponent, valueFormatter = percentAxisFormatter),
                bottomAxis = HorizontalAxis.rememberBottom(label = bottomLabelComponent, valueFormatter = categoryFormatter),
            ),
            modelProducer = modelProducer,
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp),
        )
    }
}

/**
 * The AI Coach card. Rendering only: the load (network call, or the deterministic fallback when no
 * API key is configured) runs in the screen so a slow or failed call cannot hide the rest of the
 * page. [loading] and [error] are mutually exclusive with a resolved [advice]; the screen clears
 * [error] before starting a new attempt.
 */
@Composable
internal fun AiCoachCard(loading: Boolean, advice: String?, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = stringResource(R.string.aimi_coach_header_title, stringResource(R.string.aimi_coach_title)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
            )
            val bodyText = when {
                error != null            -> error
                advice != null && !loading -> advice
                else                      -> stringResource(R.string.aimi_coach_loading)
            }
            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
