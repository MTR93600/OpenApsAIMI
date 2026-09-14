package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.ui.ConfirmationLine
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.ui.compose.NumberInputRow
import app.aaps.core.ui.compose.dialogs.ElementConfirmationDialog
import app.aaps.core.ui.compose.navigation.labelResId
import app.aaps.ui.compose.tempBasalDialog.TempBasalDialogViewModel
import app.aaps.core.keys.R as KeysR
import app.aaps.core.ui.R as CoreUiR

/**
 * Glass-branded re-skin of [app.aaps.ui.compose.tempBasalDialog.TempBasalDialogScreen]. Binds to the SAME
 * [TempBasalDialogViewModel] instance the shared screen uses — every user action here calls the exact same
 * ViewModel method the shared screen calls, so the batch-executor round trip and the confirmation flow are
 * inherited for free. This file only supplies the GlycoCalm-styled shell.
 */
@Composable
fun GlassBasalDetailScreen(
    viewModel: TempBasalDialogViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onShowDeliveryError: (String) -> Unit,
    isDark: Boolean,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val bg = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val card = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val primary = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)

    // The master's prepared confirmation (bolusId + its lines), set via the ShowConfirmation side effect.
    var confirmation by remember { mutableStateOf<Pair<Long, List<ConfirmationLine>>?>(null) }
    var showNoAction by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.sideEffect.collect { effect ->
            when (effect) {
                is TempBasalDialogViewModel.SideEffect.ShowDeliveryError -> onShowDeliveryError(effect.comment)
                is TempBasalDialogViewModel.SideEffect.ShowNoActionDialog -> showNoAction = true
                is TempBasalDialogViewModel.SideEffect.ShowConfirmation -> confirmation = effect.bolusId to effect.lines
            }
        }
    }

    // Confirmation dialog — renders the MASTER's prepared lines (set via ShowConfirmation after prepare()).
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
            message = stringResource(CoreUiR.string.no_action_selected),
            onConfirm = { showNoAction = false },
            onDismiss = { showNoAction = false }
        )
    }

    val focusManager = LocalFocusManager.current

    Box(modifier = Modifier.fillMaxSize().background(bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = primary)
                }
                Text(
                    text = stringResource(ElementType.TEMP_BASAL.labelResId()),
                    color = primary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }

            // Rate + duration card
            Column(
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.isPercentPump) {
                    NumberInputRow(
                        labelResId = CoreUiR.string.tempbasal_label,
                        value = uiState.basalPercent,
                        onValueChange = viewModel::updateBasalPercent,
                        valueRange = 0.0..uiState.maxTempPercent,
                        step = uiState.tempPercentStep,
                        valueFormat = NumberFormat.INTEGER,
                        unitLabel = "%"
                    )
                } else {
                    NumberInputRow(
                        labelResId = CoreUiR.string.tempbasal_label,
                        value = uiState.basalAbsolute,
                        onValueChange = viewModel::updateBasalAbsolute,
                        valueRange = 0.0..uiState.maxTempAbsolute,
                        step = uiState.tempAbsoluteStep,
                        valueFormat = NumberFormat.DECIMAL_2,
                        unitLabel = stringResource(CoreUiR.string.profile_ins_units_per_hour)
                    )
                }

                NumberInputRow(
                    labelResId = CoreUiR.string.duration,
                    value = uiState.durationMinutes,
                    onValueChange = viewModel::updateDuration,
                    valueRange = uiState.tempDurationStep..uiState.tempMaxDuration,
                    step = uiState.tempDurationStep,
                    valueFormat = NumberFormat.INTEGER,
                    unitLabelResId = KeysR.string.units_min
                )
            }

            val hasAction = if (uiState.isPercentPump) uiState.basalPercent != 100.0 else uiState.basalAbsolute > 0.0
            Button(
                onClick = {
                    focusManager.clearFocus()
                    viewModel.prepareAndConfirm()
                },
                enabled = (hasAction || uiState.durationMinutes > 0.0) && !uiState.isPreparing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (uiState.isPercentPump && uiState.basalPercent != 100.0) "${NumberFormat.INTEGER.format(uiState.basalPercent)}%"
                    else if (!uiState.isPercentPump && uiState.basalAbsolute > 0.0)
                        "${NumberFormat.DECIMAL_2.format(uiState.basalAbsolute)} ${stringResource(CoreUiR.string.profile_ins_units_per_hour)}"
                    else stringResource(CoreUiR.string.ok)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
