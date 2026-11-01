package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.interfaces.aps.AimiAdaptationEvidenceType
import app.aaps.core.interfaces.aps.AimiAdaptationMetricId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.LocalDecimalFormatter
import app.aaps.core.ui.compose.dashboard.AimiAdaptationStatusScreen
import app.aaps.core.ui.compose.dashboard.AimiAdaptationStatusStrings
import app.aaps.core.ui.compose.formatKmp
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * The AIMI adaptation status screen, opened from the adaptation card of the AIMI dashboard. Same screen
 * on Android and iOS; only the learner status of the last AIMI run is shown.
 */
@Composable
fun AimiAdaptationStatusRoute(
    onBack: () -> Unit,
    viewModel: AimiAdaptationStatusViewModel = metroViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AimiAdaptationStatusScreen(
        state = state,
        strings = rememberAimiAdaptationStatusStrings(),
        onBack = onBack,
    )
}

@Composable
private fun rememberAimiAdaptationStatusStrings(): AimiAdaptationStatusStrings {
    val dateUtil = LocalDateUtil.current
    val decimalFormatter = LocalDecimalFormatter.current
    val countsTemplate = stringResource(UiStrings.aimi_adaptation_status_counts)
    val progressTemplate = stringResource(UiStrings.aimi_adaptation_progress_format)
    val minutesAgo = stringResource(UiStrings.aimi_adaptation_freshness_minutes)
    val hoursAgo = stringResource(UiStrings.aimi_adaptation_freshness_hours)
    val lastUpdate = stringResource(UiStrings.aimi_adaptation_last_update_format)
    val hours = stringResource(UiStrings.aimi_adaptation_metric_value_hours)
    val minutes = stringResource(UiStrings.aimi_adaptation_metric_value_minutes)
    val percent = stringResource(UiStrings.aimi_adaptation_metric_value_percent)
    val units = stringResource(UiStrings.aimi_adaptation_metric_value_units)
    val mgdl = stringResource(UiStrings.aimi_adaptation_metric_value_mgdl)
    val moduleNames = AimiAdaptationModuleId.entries.associateWith { stringResource(moduleName(it)) }
    val phaseNames = AimiAdaptationPhase.entries.associateWith { stringResource(phaseName(it)) }
    val reasonNames = AimiAdaptationReasonCode.entries.associateWith { stringResource(reasonName(it)) }
    val evidenceNames = AimiAdaptationEvidenceType.entries.associateWith { stringResource(evidenceName(it)) }
    val metricNames = AimiAdaptationMetricId.entries.associateWith { stringResource(metricName(it)) }
    return AimiAdaptationStatusStrings(
        title = stringResource(UiStrings.aimi_adaptation_status_title),
        back = stringResource(CoreUiStrings.back),
        noData = stringResource(UiStrings.aimi_adaptation_status_no_data),
        counts = { active, ready, waiting, attention -> countsTemplate.formatKmp(active, ready, waiting, attention) },
        noModuleUpdate = stringResource(UiStrings.aimi_adaptation_no_module_update),
        progressFormat = { evidence, completed, required -> progressTemplate.formatKmp(evidence, completed, required) },
        metricsTitle = stringResource(UiStrings.aimi_adaptation_metrics_title),
        freshnessNow = stringResource(UiStrings.aimi_adaptation_freshness_now),
        freshnessMinutes = { minutesAgo.formatKmp(it.toInt()) },
        freshnessHours = { hoursAgo.formatKmp(it.toInt()) },
        lastUpdateFormat = { timestamp, age -> lastUpdate.formatKmp(timestamp, age) },
        formatTimestamp = { dateUtil.dateAndTimeString(it) },
        moduleName = { moduleNames.getValue(it) },
        phaseName = { phaseNames.getValue(it) },
        reasonName = { reasonNames.getValue(it) },
        evidenceName = { evidenceNames.getValue(it) },
        metricName = { metricNames.getValue(it) },
        metricCount = { decimalFormatter.to0Decimal(it) },
        metricHours = { hours.formatKmp(decimalFormatter.to1Decimal(it)) },
        metricMinutes = { minutes.formatKmp(decimalFormatter.to0Decimal(it)) },
        metricPercent = { percent.formatKmp(decimalFormatter.to1Decimal(it)) },
        metricUnits = { units.formatKmp(decimalFormatter.to2Decimal(it)) },
        metricMgdl = { mgdl.formatKmp(decimalFormatter.to1Decimal(it)) },
        metricNumber = { decimalFormatter.to3Decimal(it) },
    )
}

private fun moduleName(id: AimiAdaptationModuleId): TextRef = when (id) {
    AimiAdaptationModuleId.BASAL_NEURAL_GOVERNANCE -> UiStrings.aimi_adaptation_module_basal_neural_governance
    AimiAdaptationModuleId.UNIFIED_REACTIVITY      -> UiStrings.aimi_adaptation_module_unified_reactivity
    AimiAdaptationModuleId.BASAL_MULTI_SCALE       -> UiStrings.aimi_adaptation_module_basal_multi_scale
    AimiAdaptationModuleId.PKPD                    -> UiStrings.aimi_adaptation_module_pkpd
    AimiAdaptationModuleId.ONLINE_LEARNER          -> UiStrings.aimi_adaptation_module_online_learner
    AimiAdaptationModuleId.NIGHT_GROWTH_RESISTANCE -> UiStrings.aimi_adaptation_module_night_growth_resistance
    AimiAdaptationModuleId.WCYCLE                  -> UiStrings.aimi_adaptation_module_wcycle
    AimiAdaptationModuleId.PEAK_GOVERNOR           -> UiStrings.aimi_adaptation_module_peak_governor
    AimiAdaptationModuleId.DIA_GOVERNOR            -> UiStrings.aimi_adaptation_module_dia_governor
}

private fun phaseName(phase: AimiAdaptationPhase): TextRef = when (phase) {
    AimiAdaptationPhase.DISABLED -> UiStrings.aimi_adaptation_phase_disabled
    AimiAdaptationPhase.WAITING  -> UiStrings.aimi_adaptation_phase_waiting
    AimiAdaptationPhase.LEARNING -> UiStrings.aimi_adaptation_phase_learning
    AimiAdaptationPhase.READY    -> UiStrings.aimi_adaptation_phase_ready
    AimiAdaptationPhase.ACTIVE   -> UiStrings.aimi_adaptation_phase_active
    AimiAdaptationPhase.STALE    -> UiStrings.aimi_adaptation_phase_stale
    AimiAdaptationPhase.BLOCKED  -> UiStrings.aimi_adaptation_phase_blocked
}

private fun reasonName(reason: AimiAdaptationReasonCode): TextRef = when (reason) {
    AimiAdaptationReasonCode.FEATURE_DISABLED         -> UiStrings.aimi_adaptation_reason_feature_disabled
    AimiAdaptationReasonCode.WAITING_FOR_SAMPLES      -> UiStrings.aimi_adaptation_reason_waiting_for_samples
    AimiAdaptationReasonCode.WAITING_FOR_EVENTS       -> UiStrings.aimi_adaptation_reason_waiting_for_events
    AimiAdaptationReasonCode.WAITING_FOR_FEEDBACKS    -> UiStrings.aimi_adaptation_reason_waiting_for_feedbacks
    AimiAdaptationReasonCode.WAITING_FOR_OBSERVATIONS -> UiStrings.aimi_adaptation_reason_waiting_for_observations
    AimiAdaptationReasonCode.WARMUP                   -> UiStrings.aimi_adaptation_reason_warmup
    AimiAdaptationReasonCode.LEARNING_IN_PROGRESS     -> UiStrings.aimi_adaptation_reason_learning_in_progress
    AimiAdaptationReasonCode.READY                    -> UiStrings.aimi_adaptation_reason_ready
    AimiAdaptationReasonCode.ACTIVE                   -> UiStrings.aimi_adaptation_reason_active
    AimiAdaptationReasonCode.DATA_STALE               -> UiStrings.aimi_adaptation_reason_data_stale
    AimiAdaptationReasonCode.SAFETY_HOLD              -> UiStrings.aimi_adaptation_reason_safety_hold
    AimiAdaptationReasonCode.CONTEXT_BLOCKED          -> UiStrings.aimi_adaptation_reason_context_blocked
    AimiAdaptationReasonCode.DEPENDENCY_DISABLED      -> UiStrings.aimi_adaptation_reason_dependency_disabled
    AimiAdaptationReasonCode.NO_RESULT                -> UiStrings.aimi_adaptation_reason_no_result
}

private fun evidenceName(type: AimiAdaptationEvidenceType): TextRef = when (type) {
    AimiAdaptationEvidenceType.SAMPLES      -> UiStrings.aimi_adaptation_evidence_samples
    AimiAdaptationEvidenceType.EVENTS       -> UiStrings.aimi_adaptation_evidence_events
    AimiAdaptationEvidenceType.FEEDBACKS    -> UiStrings.aimi_adaptation_evidence_feedbacks
    AimiAdaptationEvidenceType.OBSERVATIONS -> UiStrings.aimi_adaptation_evidence_observations
}

private fun metricName(id: AimiAdaptationMetricId): TextRef = when (id) {
    AimiAdaptationMetricId.SAMPLE_COUNT             -> UiStrings.aimi_adaptation_metric_sample_count
    AimiAdaptationMetricId.SHORT_BUFFER_COUNT       -> UiStrings.aimi_adaptation_metric_short_buffer_count
    AimiAdaptationMetricId.MEDIUM_BUFFER_COUNT      -> UiStrings.aimi_adaptation_metric_medium_buffer_count
    AimiAdaptationMetricId.FASTING_SAMPLE_COUNT     -> UiStrings.aimi_adaptation_metric_fasting_sample_count
    AimiAdaptationMetricId.SHORT_UPDATE_COUNT       -> UiStrings.aimi_adaptation_metric_short_update_count
    AimiAdaptationMetricId.MEDIUM_UPDATE_COUNT      -> UiStrings.aimi_adaptation_metric_medium_update_count
    AimiAdaptationMetricId.LONG_UPDATE_COUNT        -> UiStrings.aimi_adaptation_metric_long_update_count
    AimiAdaptationMetricId.SHORT_ANALYSIS_COUNT     -> UiStrings.aimi_adaptation_metric_short_analysis_count
    AimiAdaptationMetricId.LONG_ANALYSIS_COUNT      -> UiStrings.aimi_adaptation_metric_long_analysis_count
    AimiAdaptationMetricId.PENDING_PREDICTION_COUNT -> UiStrings.aimi_adaptation_metric_pending_prediction_count
    AimiAdaptationMetricId.EVALUATED_FEEDBACK_COUNT -> UiStrings.aimi_adaptation_metric_evaluated_feedback_count
    AimiAdaptationMetricId.RELEASE_COUNT            -> UiStrings.aimi_adaptation_metric_release_count
    AimiAdaptationMetricId.ACCEPTED_UPDATE_COUNT    -> UiStrings.aimi_adaptation_metric_accepted_update_count
    AimiAdaptationMetricId.SHORT_MULTIPLIER         -> UiStrings.aimi_adaptation_metric_short_multiplier
    AimiAdaptationMetricId.MEDIUM_MULTIPLIER        -> UiStrings.aimi_adaptation_metric_medium_multiplier
    AimiAdaptationMetricId.LONG_MULTIPLIER          -> UiStrings.aimi_adaptation_metric_long_multiplier
    AimiAdaptationMetricId.COMBINED_MULTIPLIER      -> UiStrings.aimi_adaptation_metric_combined_multiplier
    AimiAdaptationMetricId.GLOBAL_FACTOR            -> UiStrings.aimi_adaptation_metric_global_factor
    AimiAdaptationMetricId.SHORT_FACTOR             -> UiStrings.aimi_adaptation_metric_short_factor
    AimiAdaptationMetricId.SEGMENT_FACTOR           -> UiStrings.aimi_adaptation_metric_segment_factor
    AimiAdaptationMetricId.SENSITIVITY_FACTOR       -> UiStrings.aimi_adaptation_metric_sensitivity_factor
    AimiAdaptationMetricId.LAST_ERROR               -> UiStrings.aimi_adaptation_metric_last_error
    AimiAdaptationMetricId.DIA_HOURS                -> UiStrings.aimi_adaptation_metric_dia
    AimiAdaptationMetricId.PEAK_MINUTES             -> UiStrings.aimi_adaptation_metric_peak
    AimiAdaptationMetricId.TIR_PERCENT              -> UiStrings.aimi_adaptation_metric_tir
    AimiAdaptationMetricId.CV_PERCENT               -> UiStrings.aimi_adaptation_metric_cv
    AimiAdaptationMetricId.CONFIDENCE               -> UiStrings.aimi_adaptation_metric_confidence
    AimiAdaptationMetricId.SMB_MULTIPLIER           -> UiStrings.aimi_adaptation_metric_smb_multiplier
    AimiAdaptationMetricId.BASAL_MULTIPLIER         -> UiStrings.aimi_adaptation_metric_basal_multiplier
    AimiAdaptationMetricId.EXTRA_IOB_HEADROOM_UNITS -> UiStrings.aimi_adaptation_metric_extra_iob_headroom
    AimiAdaptationMetricId.DAY_IN_CYCLE             -> UiStrings.aimi_adaptation_metric_day_in_cycle
    AimiAdaptationMetricId.IC_MULTIPLIER            -> UiStrings.aimi_adaptation_metric_ic_multiplier
    AimiAdaptationMetricId.EFFECTIVE_VALUE          -> UiStrings.aimi_adaptation_metric_effective_value
    AimiAdaptationMetricId.PRIOR_VALUE              -> UiStrings.aimi_adaptation_metric_prior_value
    AimiAdaptationMetricId.LEARNED_VALUE            -> UiStrings.aimi_adaptation_metric_learned_value
}
