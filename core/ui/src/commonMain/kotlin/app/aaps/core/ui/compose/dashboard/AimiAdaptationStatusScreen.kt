package app.aaps.core.ui.compose.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.aps.AimiAdaptationEvidenceType
import app.aaps.core.interfaces.aps.AimiAdaptationMetric
import app.aaps.core.interfaces.aps.AimiAdaptationMetricId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleStatus
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar

/**
 * Adaptation status screen, ported from `plugins/main`.
 *
 * The Android `AimiAdaptationStatusViewModel.UiState` is replaced by
 * [AimiAdaptationStatusUiState] (same shape, pure Kotlin) and every
 * `R.string` lookup is replaced by [AimiAdaptationStatusStrings], which the
 * platform pre-resolves. Date formatting (`java.text.DateFormat`) is hoisted
 * as [AimiAdaptationStatusStrings.formatTimestamp].
 */

/** Hoisted UI state (mirrors the Android ViewModel's `UiState`). */
data class AimiAdaptationStatusUiState(
    val hasStatus: Boolean = false,
    val updatedAt: Long? = null,
    val snapshotAgeMillis: Long? = null,
    val modules: List<AimiAdaptationModuleUiState> = emptyList(),
    val activeCount: Int = 0,
    val readyCount: Int = 0,
    val waitingCount: Int = 0,
    val learningCount: Int = 0,
    val blockedCount: Int = 0,
    val staleCount: Int = 0,
    val disabledCount: Int = 0,
) {
    val waitingOrLearningCount: Int get() = waitingCount + learningCount
    val attentionCount: Int get() = blockedCount + staleCount
}

/** Hoisted per-module UI state. */
data class AimiAdaptationModuleUiState(
    val status: AimiAdaptationModuleStatus,
    val effectiveUpdatedAt: Long?,
    val ageMillis: Long?,
)

/**
 * All display strings for the adaptation status screen, pre-resolved by the
 * platform. Lambdas cover the formatted strings.
 */
data class AimiAdaptationStatusStrings(
    val title: String,
    val back: String,
    val noData: String,
    val counts: (active: Int, ready: Int, waitingOrLearning: Int, attention: Int) -> String,
    val noModuleUpdate: String,
    val progressFormat: (evidence: String, completed: Int, required: Int) -> String,
    val metricsTitle: String,
    val freshnessNow: String,
    val freshnessMinutes: (Long) -> String,
    val freshnessHours: (Long) -> String,
    val lastUpdateFormat: (timestamp: String, age: String) -> String,
    val formatTimestamp: (Long) -> String,
    val moduleName: (AimiAdaptationModuleId) -> String,
    val phaseName: (AimiAdaptationPhase) -> String,
    val reasonName: (AimiAdaptationReasonCode) -> String,
    val evidenceName: (AimiAdaptationEvidenceType) -> String,
    val metricName: (AimiAdaptationMetricId) -> String,
    val metricCount: (Double) -> String,
    val metricHours: (Double) -> String,
    val metricMinutes: (Double) -> String,
    val metricPercent: (Double) -> String,
    val metricUnits: (Double) -> String,
    val metricMgdl: (Double) -> String,
    val metricNumber: (Double) -> String,
)

@Composable
fun AimiAdaptationStatusScreen(
    state: AimiAdaptationStatusUiState,
    strings: AimiAdaptationStatusStrings,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(strings.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = strings.back,
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        if (!state.hasStatus) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .padding(AapsSpacing.extraLarge),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = strings.noData,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentPadding = PaddingValues(AapsSpacing.extraLarge),
                verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(AapsSpacing.small)) {
                        Text(
                            text = strings.counts(
                                state.activeCount,
                                state.readyCount,
                                state.waitingOrLearningCount,
                                state.attentionCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.updatedAt != null && state.snapshotAgeMillis != null) {
                            Text(
                                text = lastUpdateText(strings, state.updatedAt, state.snapshotAgeMillis),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                items(
                    items = state.modules,
                    key = { it.status.moduleId },
                ) { module ->
                    AdaptationModuleCard(module, strings)
                }
            }
        }
    }
}

@Composable
private fun AdaptationModuleCard(
    module: AimiAdaptationModuleUiState,
    strings: AimiAdaptationStatusStrings,
) {
    val status = module.status
    val containerColor = when (status.phase) {
        AimiAdaptationPhase.ACTIVE -> MaterialTheme.colorScheme.primaryContainer
        AimiAdaptationPhase.READY -> MaterialTheme.colorScheme.secondaryContainer
        AimiAdaptationPhase.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        AimiAdaptationPhase.STALE -> MaterialTheme.colorScheme.tertiaryContainer
        AimiAdaptationPhase.DISABLED,
        AimiAdaptationPhase.WAITING,
        -> MaterialTheme.colorScheme.surfaceVariant
        AimiAdaptationPhase.LEARNING -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(
                text = strings.moduleName(status.moduleId),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
            ) {
                Text(
                    text = strings.phaseName(status.phase),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = strings.reasonName(status.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (module.effectiveUpdatedAt != null && module.ageMillis != null) {
                Text(
                    text = lastUpdateText(strings, module.effectiveUpdatedAt, module.ageMillis),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (status.phase != AimiAdaptationPhase.DISABLED) {
                Text(
                    text = strings.noModuleUpdate,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.progress?.let { progress ->
                Text(
                    text = strings.progressFormat(
                        strings.evidenceName(progress.type),
                        progress.completed,
                        progress.required,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (status.metrics.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = strings.metricsTitle,
                    style = MaterialTheme.typography.labelLarge,
                )
                status.metrics.forEach { metric ->
                    MetricRow(status.moduleId, metric, strings)
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    moduleId: AimiAdaptationModuleId,
    metric: AimiAdaptationMetric,
    strings: AimiAdaptationStatusStrings,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
    ) {
        Text(
            text = strings.metricName(metric.id),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = metricValue(strings, moduleId, metric),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun lastUpdateText(
    strings: AimiAdaptationStatusStrings,
    updatedAt: Long,
    ageMillis: Long,
): String {
    val timestamp = strings.formatTimestamp(updatedAt)
    val age = when {
        ageMillis < MINUTE_MS -> strings.freshnessNow
        ageMillis < HOUR_MS -> strings.freshnessMinutes(ageMillis / MINUTE_MS)
        else -> strings.freshnessHours(ageMillis / HOUR_MS)
    }
    return strings.lastUpdateFormat(timestamp, age)
}

private fun metricValue(
    strings: AimiAdaptationStatusStrings,
    moduleId: AimiAdaptationModuleId,
    metric: AimiAdaptationMetric,
): String = when (metric.id) {
    AimiAdaptationMetricId.SAMPLE_COUNT,
    AimiAdaptationMetricId.SHORT_BUFFER_COUNT,
    AimiAdaptationMetricId.MEDIUM_BUFFER_COUNT,
    AimiAdaptationMetricId.FASTING_SAMPLE_COUNT,
    AimiAdaptationMetricId.SHORT_UPDATE_COUNT,
    AimiAdaptationMetricId.MEDIUM_UPDATE_COUNT,
    AimiAdaptationMetricId.LONG_UPDATE_COUNT,
    AimiAdaptationMetricId.SHORT_ANALYSIS_COUNT,
    AimiAdaptationMetricId.LONG_ANALYSIS_COUNT,
    AimiAdaptationMetricId.PENDING_PREDICTION_COUNT,
    AimiAdaptationMetricId.EVALUATED_FEEDBACK_COUNT,
    AimiAdaptationMetricId.RELEASE_COUNT,
    AimiAdaptationMetricId.ACCEPTED_UPDATE_COUNT,
    AimiAdaptationMetricId.DAY_IN_CYCLE,
    -> strings.metricCount(metric.value)

    AimiAdaptationMetricId.DIA_HOURS ->
        strings.metricHours(metric.value)

    AimiAdaptationMetricId.PEAK_MINUTES ->
        strings.metricMinutes(metric.value)

    AimiAdaptationMetricId.TIR_PERCENT,
    AimiAdaptationMetricId.CV_PERCENT,
    -> strings.metricPercent(metric.value)

    AimiAdaptationMetricId.EXTRA_IOB_HEADROOM_UNITS ->
        strings.metricUnits(metric.value)

    AimiAdaptationMetricId.LAST_ERROR ->
        strings.metricMgdl(metric.value)

    AimiAdaptationMetricId.EFFECTIVE_VALUE,
    AimiAdaptationMetricId.PRIOR_VALUE,
    AimiAdaptationMetricId.LEARNED_VALUE,
    -> when (moduleId) {
        AimiAdaptationModuleId.PEAK_GOVERNOR -> strings.metricMinutes(metric.value)
        AimiAdaptationModuleId.DIA_GOVERNOR -> strings.metricHours(metric.value)
        else -> strings.metricNumber(metric.value)
    }

    else -> strings.metricNumber(metric.value)
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
