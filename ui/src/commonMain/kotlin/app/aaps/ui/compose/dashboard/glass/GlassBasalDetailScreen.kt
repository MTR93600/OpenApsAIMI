package app.aaps.ui.compose.dashboard.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.glass.isGlassDarkMode
import app.aaps.core.ui.compose.metroViewModel
import app.aaps.core.ui.compose.navigation.label
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.stringResourceOrNull
import app.aaps.ui.compose.tempBasalDialog.TempBasalDialogUiState
import app.aaps.ui.compose.tempBasalDialog.TempBasalDialogViewModel

/**
 * The Glass temp basal screen. Port of `GlassBasalDetailScreen` on `dev_OAPSAIMI`.
 *
 * Only the look is new. It uses the same [TempBasalDialogViewModel] as
 * [app.aaps.ui.compose.tempBasalDialog.TempBasalDialogScreen]: OK asks the master to prepare the
 * temp basal (with its constraints), the master's lines are shown in the confirmation dialog, and only
 * "confirm" there calls [TempBasalDialogViewModel.commit]. OK is disabled while a prepare is running.
 */
@Composable
fun GlassBasalDetailScreen(
    viewModel: TempBasalDialogViewModel = metroViewModel(),
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // The master's prepared confirmation (bolusId + its lines), set by the ShowConfirmation side effect.
    var confirmation by remember { mutableStateOf<Pair<Long, List<ConfirmationLine>>?>(null) }
    var showNoAction by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is TempBasalDialogViewModel.SideEffect.ShowDeliveryError  -> onShowDeliveryError(effect.comment)
                is TempBasalDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true
                is TempBasalDialogViewModel.SideEffect.ShowConfirmation   -> confirmation = effect.bolusId to effect.lines
            }
        }
    }

    confirmation?.let { (bolusId, lines) ->
        ElementConfirmationDialog(
            elementType = ElementType.TEMP_BASAL,
            lines = lines,
            onConfirm = {
                viewModel.commit(bolusId)
                confirmation = null
                onNavigateBack()
            },
            onDismiss = { confirmation = null }
        )
    }

    if (showNoAction) {
        ElementConfirmationDialog(
            elementType = ElementType.TEMP_BASAL,
            message = stringResource(CoreUiStrings.no_action_selected),
            onConfirm = { showNoAction = false },
            onDismiss = { showNoAction = false }
        )
    }

    GlassBasalDetailContent(
        uiState = uiState,
        isDark = isGlassDarkMode(),
        onBasalPercentChange = viewModel::updateBasalPercent,
        onBasalAbsoluteChange = viewModel::updateBasalAbsolute,
        onDurationChange = viewModel::updateDuration,
        onNavigateBack = onNavigateBack,
        onConfirmClick = { viewModel.prepareAndConfirm() }
    )
}

/** The stateless body of [GlassBasalDetailScreen]: the same controls as `TempBasalDialogContent`, in a Glass card. */
@Composable
internal fun GlassBasalDetailContent(
    uiState: TempBasalDialogUiState,
    isDark: Boolean,
    onBasalPercentChange: (Double) -> Unit,
    onBasalAbsoluteChange: (Double) -> Unit,
    onDurationChange: (Double) -> Unit,
    onNavigateBack: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    val hasAction = if (uiState.isPercentPump) uiState.basalPercent != 100.0 else uiState.basalAbsolute > 0.0
    val confirmLabel = if (uiState.isPercentPump && uiState.basalPercent != 100.0) {
        stringResource(CoreUiStrings.units_format_percent_value, NumberFormat.INTEGER.format(uiState.basalPercent))
    } else if (!uiState.isPercentPump && uiState.basalAbsolute > 0.0) {
        stringResource(CoreUiStrings.pump_base_basal_rate, uiState.basalAbsolute)
    } else {
        stringResource(CoreUiStrings.ok)
    }
    GlassTreatmentDetailFrame(
        title = stringResourceOrNull(ElementType.TEMP_BASAL.label()) ?: "",
        isDark = isDark,
        onNavigateBack = onNavigateBack,
        confirmButton = {
            GlassConfirmButton(
                label = confirmLabel,
                enabled = (hasAction || uiState.durationMinutes > 0.0) && !uiState.isPreparing,
                onClick = onConfirmClick
            )
        }
    ) {
        GlassDetailCard(isDark = isDark) {
            if (uiState.isPercentPump) {
                NumberInputRow(
                    labelRef = CoreUiStrings.tempbasal_label,
                    value = uiState.basalPercent,
                    onValueChange = onBasalPercentChange,
                    valueRange = 0.0..uiState.maxTempPercent,
                    step = uiState.tempPercentStep,
                    valueFormat = NumberFormat.INTEGER,
                    unitLabel = TextRef.Literal("%")
                )
            } else {
                NumberInputRow(
                    labelRef = CoreUiStrings.tempbasal_label,
                    value = uiState.basalAbsolute,
                    onValueChange = onBasalAbsoluteChange,
                    valueRange = 0.0..uiState.maxTempAbsolute,
                    step = uiState.tempAbsoluteStep,
                    valueFormat = NumberFormat.DECIMAL_2,
                    unitLabel = InterfacesStrings.profile_ins_units_per_hour
                )
            }
            NumberInputRow(
                labelRef = CoreUiStrings.duration,
                value = uiState.durationMinutes,
                onValueChange = onDurationChange,
                valueRange = uiState.tempDurationStep..uiState.tempMaxDuration,
                step = uiState.tempDurationStep,
                valueFormat = NumberFormat.INTEGER,
                unitLabel = CoreUiStrings.units_min
            )
        }
    }
}
