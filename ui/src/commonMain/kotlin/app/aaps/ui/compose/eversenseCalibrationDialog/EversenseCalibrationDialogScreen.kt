package app.aaps.ui.compose.eversenseCalibrationDialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.AapsTopAppBar
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.bottomBarSafeArea
import app.aaps.core.ui.compose.clearFocusOnTap
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.UiStrings

@Composable
fun EversenseCalibrationDialogScreen(
    viewModel: EversenseCalibrationDialogViewModel = metroViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showConfirmation by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                EversenseCalibrationDialogViewModel.SideEffect.CalibrationAccepted  -> onNavigateBack()
                is EversenseCalibrationDialogViewModel.SideEffect.CalibrationFailed -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    if (showConfirmation) {
        val bgWithUnit = stringResource(CoreUiStrings.value_with_unit, formatBg(uiState), uiState.unitLabel)
        ElementConfirmationDialog(
            elementType = ElementType.EVERSENSE_CALIBRATION,
            message = stringResource(InterfacesStrings.confirmation_line, stringResource(CoreUiStrings.bg_label), bgWithUnit),
            onConfirm = {
                viewModel.submit()
                showConfirmation = false
            },
            onDismiss = { showConfirmation = false }
        )
    }

    EversenseCalibrationDialogContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onBgChange = viewModel::updateBg,
        onBgErrorChange = viewModel::updateBgInputError,
        onNavigateBack = onNavigateBack,
        onConfirmClick = { showConfirmation = true }
    )
}

@Composable
internal fun EversenseCalibrationDialogContent(
    uiState: EversenseCalibrationDialogUiState,
    snackbarHostState: SnackbarHostState,
    onBgChange: (Double) -> Unit,
    onBgErrorChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onConfirmClick: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Scaffold(
        topBar = {
            AapsTopAppBar(
                title = { Text(stringResourceOrNull(ElementType.EVERSENSE_CALIBRATION.label()) ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(CoreUiStrings.close)
                        )
                    }
                },
                actions = {}
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // One value for both the visible label and the spoken name, so they can never drift apart.
            val confirmLabel =
                if (uiState.hasValidBg) stringResource(CoreUiStrings.value_with_unit, formatBg(uiState), uiState.unitLabel)
                else stringResource(CoreUiStrings.ok)
            val confirmDescription = stringResource(CoreUiStrings.confirm)
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onConfirmClick()
                },
                enabled = uiState.canSubmit,
                modifier = Modifier
                    .fillMaxWidth()
                    .bottomBarSafeArea()
                    .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)
                    .semantics { contentDescription = confirmDescription }
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(AapsSpacing.medium))
                Text(confirmLabel)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .clearFocusOnTap(focusManager)
                .padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AapsSpacing.medium)
        ) {
            if (uiState.notReadyMessage.isNotEmpty()) {
                PreflightWarningCard(message = uiState.notReadyMessage)
            }

            if (uiState.notConnected) {
                ReconnectHintCard()
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.medium)) {
                    NumberInputRow(
                        labelRef = CoreUiStrings.bg_label,
                        value = uiState.bg,
                        onValueChange = onBgChange,
                        valueRange = uiState.bgRange,
                        step = uiState.bgStep,
                        unitLabel = TextRef.Literal(uiState.unitLabel),
                        decimalPlaces = uiState.bgDecimalPlaces,
                        onErrorChange = onBgErrorChange
                    )
                }
            }

            Spacer(modifier = Modifier.height(AapsSpacing.medium))
        }
    }
}

/** The transmitter says it will not take a calibration yet, so nothing can be sent. */
@Composable
private fun PreflightWarningCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(AapsSpacing.medium))
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Information only: the calibration can still be sent, it just has to connect first. */
@Composable
private fun ReconnectHintCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AapsSpacing.extraLarge, vertical = AapsSpacing.large),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(AapsSpacing.medium))
            Text(
                text = stringResource(UiStrings.eversense_calibration_dialog_reconnect_hint),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

/** The typed value, with as many decimals as the user's unit needs. Carries no unit label itself. */
private fun formatBg(uiState: EversenseCalibrationDialogUiState): String {
    val bgFormat = if (uiState.isMgdl) NumberFormat.INTEGER else NumberFormat.DECIMAL_1
    return bgFormat.format(uiState.bg)
}
