package app.aaps.ui.compose.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData

data class GlassAimiRuntimeCardStrings(
    val trajectoryTitle: String,
    val trajectoryHybridLabel: String,
    val trajectoryIobLabel: String,
    val trajectoryCobLabel: String,
    val trajectoryUamLabel: String,
    val trajectoryUnavailable: String,
    val keyFactorsTitle: String,
    val keyFactorsContextBadge: String,
    val keyFactorsTargetLabel: String,
    val keyFactorsMaxIobLabel: String,
    val keyFactorsMaxSmbLabel: String,
    val keyFactorsIsfLabel: String,
    val keyFactorsStableBgLabel: String,
    val formatInsulin: @Composable (Double) -> String,
    val formatIsf: @Composable (Double) -> String,
    val formatStableMinutes: @Composable (Double) -> String,
    val safetyTitle: String,
    val safetyTddLabel: String,
    val safetyTirLabel: String,
    val safetyStepsLabel: String,
    val safetyTirValue: @Composable (String, String) -> String,
    val formatPercent: @Composable (Double) -> String,
    val formatInsulinPerHour: @Composable (Double) -> String,
    val formatSteps: @Composable (Int) -> String,
    val openAimiContextLabel: String,
    val physioTitle: String,
    val physioModeLabel: String,
    val physioIntentLabel: String,
    val physioThermalLabel: String,
    val physioUnavailable: String,
    val valueUnavailable: String,
    val mlTrainingTitle: String,
    val lastTrainedLabel: String,
    val samplesLabel: String,
    val circuitOpen: String,
    val trainingNotStarted: String,
    val neverTrained: String,
    val smbTrainingLabel: String,
    val smbNeverAttempted: String,
    val smbOutcomeTrained: String,
    val smbOutcomeNoCsv: String,
    val smbOutcomeRefusedHeader: String,
    val smbOutcomeTooFewSamples: String,
    val smbOutcomeRejected: String,
    val smbOutcomeError: String,
    val smbWaitingNotDue: String,
    val smbWaitingCircuitOpen: String,
    val smbDetailLabel: String,
    val smbRowsLabel: @Composable (Int, Long) -> String,
    val modelFilesTitle: String,
    val basalModelLabel: String,
    val smbModelLabel: String,
    val basalModelSizeLabel: String,
    val smbModelSizeLabel: String,
    val modelFileMissing: String,
    val modelFileSizeUnavailable: String,
    val formatFileSize: @Composable (Long) -> String,
)
/**
 * AIMI-only GLASS cards backed by the shared loop's published read-only snapshot.
 *
 * The trajectory values are rendered with the caller's profile-aware formatter; this composable
 * never converts units or calculates a forecast. Missing endpoints remain visibly unavailable.
 */
@Composable
internal fun GlassAimiRuntimeCards(
    data: AimiGlassDashboardData,
    strings: GlassAimiRuntimeCardStrings,
    formatGlucose: (Double) -> String,
    formatDateTime: (Long) -> String,
    onOpenAimiContext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassRuntimeSection(
            title = strings.trajectoryTitle,
        ) {
            val trajectory = data.trajectory
            if (trajectory == null || trajectory.isStale) {
                Text(
                    text = strings.trajectoryUnavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassRuntimeMetric(
                            label = strings.trajectoryHybridLabel,
                            value = trajectory.hybridTerminalMgdl?.let(formatGlucose) ?: strings.valueUnavailable,
                            modifier = Modifier.weight(1f),
                        )
                        GlassRuntimeMetric(
                            label = strings.trajectoryIobLabel,
                            value = trajectory.iobTerminalMgdl?.let(formatGlucose) ?: strings.valueUnavailable,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassRuntimeMetric(
                            label = strings.trajectoryCobLabel,
                            value = trajectory.cobTerminalMgdl?.let(formatGlucose) ?: strings.valueUnavailable,
                            modifier = Modifier.weight(1f),
                        )
                        GlassRuntimeMetric(
                            label = strings.trajectoryUamLabel,
                            value = trajectory.uamTerminalMgdl?.let(formatGlucose) ?: strings.valueUnavailable,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        GlassRuntimeSection(
            title = strings.keyFactorsTitle,
            badge = strings.keyFactorsContextBadge,
        ) {
            val keyFactors = data.keyFactors
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassRuntimeMetric(
                        label = strings.keyFactorsTargetLabel,
                        value = keyFactors?.targetBgMgdl?.let(formatGlucose) ?: strings.valueUnavailable,
                        modifier = Modifier.weight(1f),
                    )
                    GlassRuntimeMetric(
                        label = strings.keyFactorsMaxIobLabel,
                        value = formatOptionalMetric(
                            keyFactors?.maxIobUnits,
                            strings.formatInsulin,
                            strings.valueUnavailable,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassRuntimeMetric(
                        label = strings.keyFactorsMaxSmbLabel,
                        value = formatOptionalMetric(
                            keyFactors?.maxSmbUnits,
                            strings.formatInsulin,
                            strings.valueUnavailable,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    GlassRuntimeMetric(
                        label = strings.keyFactorsIsfLabel,
                        value = formatOptionalMetric(
                            keyFactors?.isfMgdlPerUnit,
                            strings.formatIsf,
                            strings.valueUnavailable,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                GlassRuntimeMetric(
                    label = strings.keyFactorsStableBgLabel,
                    value = formatOptionalMetric(
                        keyFactors?.stableBgMinutes,
                        strings.formatStableMinutes,
                        strings.valueUnavailable,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlassRuntimeSection(title = strings.safetyTitle) {
            val safety = data.safety
            GlassRuntimeMetric(
                label = strings.safetyTddLabel,
                value = formatOptionalMetric(
                    safety?.tdd7DaysPerHour,
                    strings.formatInsulinPerHour,
                    strings.valueUnavailable,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            GlassRuntimeMetric(
                label = strings.safetyTirLabel,
                value = strings.safetyTirValue(
                    formatOptionalMetric(
                        safety?.lowTirLastHourPercent,
                        strings.formatPercent,
                        strings.valueUnavailable,
                    ),
                    formatOptionalMetric(
                        safety?.lowTirTodayPercent,
                        strings.formatPercent,
                        strings.valueUnavailable,
                    ),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            GlassRuntimeMetric(
                label = strings.safetyStepsLabel,
                value = safety?.stepsLast5Minutes
                    ?.let { steps -> strings.formatSteps(steps) }
                    ?: strings.valueUnavailable,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GlassRuntimeSection(title = strings.physioTitle) {
            val presentation = data.physio?.presentation
            if (presentation == null) {
                Text(
                    text = strings.physioUnavailable,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassRuntimeMetric(
                            label = strings.physioModeLabel,
                            value = presentation.modeHeadline,
                            modifier = Modifier.weight(1f),
                        )
                        GlassRuntimeMetric(
                            label = strings.physioIntentLabel,
                            value = presentation.intentSummary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GlassRuntimeMetric(
                        label = strings.physioThermalLabel,
                        value = presentation.thermalSummary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (presentation.updatedSummary.isNotBlank()) {
                        Text(
                            text = presentation.updatedSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (data.contextScreenAvailable) {
                        TextButton(onClick = onOpenAimiContext) {
                            Text(strings.openAimiContextLabel)
                        }
                    }
                }
            }
        }

        val training = data.training
        GlassRuntimeSection(title = strings.mlTrainingTitle) {
            val basal = training?.basal
            if (basal == null) {
                Text(
                    text = strings.trainingNotStarted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassRuntimeMetric(
                        label = strings.lastTrainedLabel,
                        value = basal.lastTrainedAtMs.takeIf { it > 0L }?.let(formatDateTime) ?: strings.neverTrained,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    GlassRuntimeMetric(
                        label = strings.samplesLabel,
                        value = basal.sampleCount.toString(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (basal.circuitOpen) {
                        Text(
                            text = strings.circuitOpen,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val smb = training?.smb
            val attempt = smb?.lastAttempt
            val attemptSummary = attempt?.let {
                when (it.outcome) {
                    AimiGlassDashboardData.SmbOutcome.TRAINED -> strings.smbOutcomeTrained
                    AimiGlassDashboardData.SmbOutcome.NO_CSV -> strings.smbOutcomeNoCsv
                    AimiGlassDashboardData.SmbOutcome.REFUSED_HEADER -> strings.smbOutcomeRefusedHeader
                    AimiGlassDashboardData.SmbOutcome.TOO_FEW_SAMPLES -> strings.smbOutcomeTooFewSamples
                    AimiGlassDashboardData.SmbOutcome.REJECTED_BY_GATES -> strings.smbOutcomeRejected
                    AimiGlassDashboardData.SmbOutcome.ERROR -> strings.smbOutcomeError
                    AimiGlassDashboardData.SmbOutcome.WAITING_NOT_DUE -> strings.smbWaitingNotDue
                    AimiGlassDashboardData.SmbOutcome.WAITING_CIRCUIT_OPEN -> strings.smbWaitingCircuitOpen
                }
            } ?: strings.smbNeverAttempted
            GlassRuntimeMetric(
                label = strings.smbTrainingLabel,
                value = attemptSummary,
                modifier = Modifier.fillMaxWidth(),
            )
            attempt?.let {
                Text(
                    text = formatDateTime(it.atMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (smb?.waiting) {
                AimiGlassDashboardData.SmbWaiting.NOT_DUE -> Text(
                    text = strings.smbWaitingNotDue,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AimiGlassDashboardData.SmbWaiting.CIRCUIT_OPEN -> Text(
                    text = strings.smbWaitingCircuitOpen,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                null -> Unit
            }
            val attemptDetail = attempt?.let {
                it.detail.takeIf(String::isNotBlank)
                    ?: if (it.samplesAfterFilter > 0 || it.rowsRejectedByFilter > 0L) {
                        strings.smbRowsLabel(it.samplesAfterFilter, it.rowsRejectedByFilter)
                    } else {
                        null
                    }
            }
            attemptDetail?.let {
                GlassRuntimeMetric(
                    label = strings.smbDetailLabel,
                    value = it,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        GlassRuntimeSection(title = strings.modelFilesTitle) {
            val basalFile = training?.basal?.modelFile
            GlassRuntimeMetric(
                label = strings.basalModelLabel,
                value = basalFile?.let { modelFileValue(it.exists, it.modifiedAtMs, strings, formatDateTime) }
                    ?: strings.modelFileMissing,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassRuntimeMetric(
                label = strings.basalModelSizeLabel,
                value = basalFile?.let { modelFileSizeValue(it, strings) } ?: strings.modelFileMissing,
                modifier = Modifier.fillMaxWidth(),
            )
            val smbFile = training?.smb?.modelFile
            GlassRuntimeMetric(
                label = strings.smbModelLabel,
                value = smbFile?.let { modelFileValue(it.exists, it.modifiedAtMs, strings, formatDateTime) }
                    ?: strings.modelFileMissing,
                modifier = Modifier.fillMaxWidth(),
            )
            GlassRuntimeMetric(
                label = strings.smbModelSizeLabel,
                value = smbFile?.let { modelFileSizeValue(it, strings) } ?: strings.modelFileMissing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun modelFileSizeValue(
    modelFile: AimiGlassDashboardData.ModelFile,
    strings: GlassAimiRuntimeCardStrings,
): String {
    if (!modelFile.exists) return strings.modelFileMissing
    val sizeBytes = modelFile.sizeBytes ?: return strings.modelFileSizeUnavailable
    return strings.formatFileSize(sizeBytes)
}

@Composable
private fun formatOptionalMetric(
    value: Double?,
    format: @Composable (Double) -> String,
    unavailable: String,
): String {
    if (value == null) return unavailable
    return format(value)
}

private fun modelFileValue(
    exists: Boolean,
    modifiedAtMs: Long?,
    strings: GlassAimiRuntimeCardStrings,
    formatDateTime: (Long) -> String,
): String = when {
    !exists -> strings.modelFileMissing
    modifiedAtMs == null -> strings.valueUnavailable
    else -> formatDateTime(modifiedAtMs)
}

@Composable
private fun GlassRuntimeSection(
    title: String,
    badge: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                if (badge != null) {
                    Text(
                        text = badge,
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            content()
        }
    }
}

@Composable
private fun GlassRuntimeMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
