package app.aaps.ui.compose.dashboard

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.interfaces.dashboard.AimiGlassDashboardData
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.ui.compose.LocalProfileUtil
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.ui.compose.overview.chips.ChipsViewModel
import app.aaps.ui.compose.overview.graphs.GraphViewModel
import app.aaps.ui.compose.overview.statusLights.StatusViewModel
import app.aaps.ui.UiStrings
import kotlinx.coroutines.flow.Flow

/**
 * GLASS home data adapter for existing Overview flows and existing app routes.
 *
 * No Glass-only clinical values are inferred here. Each metric is the text/value already exposed
 * by the Overview graph or chips ViewModel, and detail actions navigate only to registered routes.
 */
@Composable
fun GlassDashboardHome(
    graphViewModel: GraphViewModel,
    aimiDashboardData: Flow<AimiGlassDashboardData>,
    dateUtil: DateUtil,
    chipsViewModel: ChipsViewModel,
    statusViewModel: StatusViewModel,
    statusLightsDef: PreferenceSubScreenDef,
    commandsAllowed: Boolean,
    onNavigate: (NavigationRequest) -> Unit,
    onOpenSettings: () -> Unit,
    title: String,
    settingsLabel: String,
    modifier: Modifier = Modifier,
) {
    val bgInfoState by graphViewModel.bgInfoState.collectAsStateWithLifecycle()
    val iobState by chipsViewModel.iobUiState.collectAsStateWithLifecycle()
    val cobState by chipsViewModel.cobUiState.collectAsStateWithLifecycle()
    val sensitivityState by chipsViewModel.sensitivityUiState.collectAsStateWithLifecycle()
    val aimiData by aimiDashboardData.collectAsStateWithLifecycle(
        initialValue = AimiGlassDashboardData(),
    )
    val profileUtil = LocalProfileUtil.current

    GlassDashboardScreen(
        title = title,
        settingsLabel = settingsLabel,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
        metricsContent = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlassOverviewMetricsCard(
                    glucoseLabel = stringResource(CoreUiStrings.glucose),
                    bgInfo = bgInfoState.bgInfo,
                    timeAgoText = bgInfoState.timeAgoText,
                    iobLabel = stringResource(CoreUiStrings.iob),
                    iobText = iobState.text,
                    cobLabel = stringResource(CoreUiStrings.cob),
                    cobText = cobState.text,
                    sensitivityLabel = stringResource(CoreUiStrings.sensitivity),
                    sensitivityText = sensitivityState.asText,
                    statisticsLabel = stringResource(CoreUiStrings.statistics),
                    treatmentsLabel = stringResource(CoreUiStrings.treatments),
                    onStatistics = {
                        onNavigate(NavigationRequest.Element(ElementType.STATISTICS))
                    },
                    onTreatments = {
                        onNavigate(NavigationRequest.Element(ElementType.TREATMENTS))
                    },
                )
                GlassAimiRuntimeCards(
                    data = aimiData,
                    strings = GlassAimiRuntimeCardStrings(
                        trajectoryTitle = stringResource(UiStrings.dashboard_glass_loop_trajectory_title),
                        trajectoryHybridLabel = stringResource(UiStrings.dashboard_glass_loop_trajectory_hybrid_label),
                        trajectoryIobLabel = stringResource(UiStrings.dashboard_glass_loop_trajectory_iob_label),
                        trajectoryCobLabel = stringResource(UiStrings.dashboard_glass_loop_trajectory_cob_label),
                        trajectoryUamLabel = stringResource(UiStrings.dashboard_glass_loop_trajectory_uam_label),
                        trajectoryUnavailable = stringResource(UiStrings.dashboard_glass_loop_trajectory_stale),
                        keyFactorsTitle = stringResource(UiStrings.dashboard_glass_loop_key_factors_title),
                        keyFactorsTargetLabel = stringResource(UiStrings.dashboard_glass_loop_target_label),
                        keyFactorsMaxIobLabel = stringResource(UiStrings.dashboard_glass_loop_max_iob_label),
                        keyFactorsMaxSmbLabel = stringResource(UiStrings.dashboard_glass_loop_max_smb_label),
                        keyFactorsIsfLabel = stringResource(UiStrings.dashboard_glass_loop_isf_label),
                        keyFactorsStableBgLabel = stringResource(UiStrings.dashboard_glass_loop_stable_bg_label),
                        formatInsulin = { insulin ->
                            stringResource(InterfacesStrings.format_insulin_units, insulin)
                        },
                        formatIsf = { isf ->
                            val unit = if (profileUtil.units == GlucoseUnit.MGDL) {
                                InterfacesStrings.profile_isf_units_mgdl
                            } else {
                                InterfacesStrings.profile_isf_units_mmol
                            }
                            stringResource(
                                UiStrings.dashboard_glass_loop_isf_value,
                                profileUtil.fromMgdlToUnits(isf),
                                stringResource(unit),
                            )
                        },
                        formatStableMinutes = { minutes ->
                            stringResource(UiStrings.dashboard_glass_loop_stable_bg_minutes, minutes)
                        },
                        physioTitle = stringResource(UiStrings.dashboard_glass_loop_physio_title),
                        physioModeLabel = stringResource(UiStrings.dashboard_glass_loop_physio_mode_label),
                        physioIntentLabel = stringResource(UiStrings.dashboard_glass_loop_physio_intent_label),
                        physioThermalLabel = stringResource(UiStrings.dashboard_glass_loop_physio_thermal_label),
                        physioUnavailable = stringResource(UiStrings.dashboard_glass_loop_physio_stale),
                        valueUnavailable = stringResource(CoreUiStrings.value_unavailable_short),
                        mlTrainingTitle = stringResource(UiStrings.dashboard_glass_loop_ml_training_title),
                        lastTrainedLabel = stringResource(UiStrings.dashboard_glass_loop_ml_last_trained_label),
                        samplesLabel = stringResource(UiStrings.dashboard_glass_loop_ml_samples_label),
                        circuitOpen = stringResource(UiStrings.dashboard_glass_loop_ml_circuit_open),
                        trainingNotStarted = stringResource(UiStrings.dashboard_glass_loop_ml_training_stale),
                        neverTrained = stringResource(UiStrings.dashboard_glass_loop_ml_never_trained),
                        smbTrainingLabel = stringResource(UiStrings.dashboard_glass_loop_ml_smb_label),
                        smbNeverAttempted = stringResource(UiStrings.dashboard_glass_loop_ml_smb_never_attempted),
                        smbOutcomeTrained = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_trained),
                        smbOutcomeNoCsv = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_no_csv),
                        smbOutcomeRefusedHeader = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_refused_header),
                        smbOutcomeTooFewSamples = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_too_few_samples),
                        smbOutcomeRejected = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_rejected),
                        smbOutcomeError = stringResource(UiStrings.dashboard_glass_loop_ml_smb_outcome_error),
                        smbWaitingNotDue = stringResource(UiStrings.dashboard_glass_loop_ml_smb_waiting_not_due),
                        smbWaitingCircuitOpen = stringResource(UiStrings.dashboard_glass_loop_ml_smb_waiting_circuit_open),
                        smbDetailLabel = stringResource(UiStrings.dashboard_glass_loop_ml_smb_detail_label),
                        smbRowsLabel = { kept, dropped ->
                            stringResource(UiStrings.dashboard_glass_loop_ml_smb_rows, kept, dropped)
                        },
                        modelFilesTitle = stringResource(UiStrings.dashboard_glass_loop_ml_files_title),
                        basalModelLabel = stringResource(UiStrings.dashboard_glass_loop_ml_file_basal_label),
                        smbModelLabel = stringResource(UiStrings.dashboard_glass_loop_ml_file_smb_label),
                        basalModelSizeLabel = stringResource(UiStrings.dashboard_glass_loop_ml_file_basal_size_label),
                        smbModelSizeLabel = stringResource(UiStrings.dashboard_glass_loop_ml_file_smb_size_label),
                        modelFileMissing = stringResource(UiStrings.dashboard_glass_loop_ml_file_missing),
                        modelFileSizeUnavailable = stringResource(CoreUiStrings.value_unavailable_short),
                        formatFileSize = { size ->
                            stringResource(UiStrings.dashboard_glass_loop_ml_file_size_bytes, size)
                        },
                    ),
                    formatGlucose = profileUtil::fromMgdlToStringInUnits,
                    formatDateTime = dateUtil::dateAndTimeString,
                )
            }
        },
        dashboardContent = {
            DashboardV2GraphScreen(
                graphViewModel = graphViewModel,
                chipsViewModel = chipsViewModel,
                statusViewModel = statusViewModel,
                statusLightsDef = statusLightsDef,
                commandsAllowed = commandsAllowed,
                onIobClick = chipsViewModel::showIobInfo,
                onNavigate = onNavigate,
                showOverviewSummary = false,
            )
        },
    )
}
