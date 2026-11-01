package app.aaps.ui.compose.dashboard.aimi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.dashboard.DashboardAdjustmentCardUiState
import app.aaps.core.ui.compose.dashboard.DashboardAdjustmentComposeCard
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.stringResource
import app.aaps.ui.UiStrings

/**
 * Details of the last AIMI decision, opened from Adjust and from the "last AIMI run" card of the AIMI
 * dashboard. Port of `AdjustmentDetailsActivity` on `dev_OAPSAIMI`: the adjustments summary, the AIMI
 * parameters (peak, DIA, target, SMB, basal), the trajectory and the full reason.
 *
 * The "Run loop" button is shown only on a phone that loops.
 */
@Composable
fun AimiAdjustmentDetailsScreen(
    onBack: () -> Unit,
    viewModel: AimiDashboardViewModel = metroViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    val cardStrings = dashboardCardStrings()

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResource(UiStrings.dashboard_adjustments_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(CoreUiStrings.back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        val details = status.adjustment
        if (details == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.large),
        ) {
            DashboardAdjustmentComposeCard(
                state = DashboardAdjustmentCardUiState(
                    glycemiaLine = details.glycemiaLine,
                    predictionLine = details.predictionLine,
                    iobActivityLine = details.iobActivityLine,
                    decisionLine = details.decisionLine,
                    modeLine = details.modeLine,
                    pumpReservoirPlain = details.pumpReservoirText,
                    pumpSitePlain = details.pumpSiteText,
                    pumpSensorPlain = details.pumpSensorText,
                    safetyLine = details.safetyLine,
                    adjustments = details.adjustments,
                    onRunLoopRequested = if (details.canRunLoop) viewModel::runLoop else null,
                ),
                strings = cardStrings,
            )
            DetailsCard(title = stringResource(UiStrings.dashboard_adjustment_parameters_title)) {
                ValueRow(stringResource(UiStrings.dashboard_adjustment_peak_label), details.peakText)
                ValueRow(stringResource(UiStrings.dashboard_adjustment_dia_label), details.diaText)
                ValueRow(stringResource(UiStrings.dashboard_adjustment_target_label), details.targetText)
                ValueRow(stringResource(UiStrings.dashboard_adjustment_smb_label), details.smbText)
                ValueRow(stringResource(UiStrings.dashboard_adjustment_basal_label), details.basalText)
            }
            details.trajectoryTitle?.let { title ->
                DetailsCard(title = title) {
                    details.trajectoryMetrics?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            details.reason?.let { reason ->
                DetailsCard(title = stringResource(UiStrings.dashboard_adjustment_reason_title)) {
                    Text(text = reason, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AapsSpacing.extraLarge),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AapsSpacing.medium)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
