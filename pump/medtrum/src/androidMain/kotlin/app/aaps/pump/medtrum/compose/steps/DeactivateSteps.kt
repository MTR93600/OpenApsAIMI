package app.aaps.pump.medtrum.compose.steps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.compose.MedtrumPatchViewModel

@Composable
fun ConfirmDeactivateStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.updateSetupStep(MedtrumPatchViewModel.SetupStep.START_DEACTIVATION)
    }

    ConfirmDeactivateStepContent(
        showConfirmDialog = showConfirmDialog,
        onShowDialog = { showConfirmDialog = true },
        onConfirm = {
            showConfirmDialog = false
            viewModel.moveStep(PatchStep.DEACTIVATE)
        },
        onDismissDialog = { showConfirmDialog = false },
        onCancel = onCancel
    )
}

@Composable
private fun ConfirmDeactivateStepContent(
    showConfirmDialog: Boolean,
    onShowDialog: () -> Unit,
    onConfirm: () -> Unit,
    onDismissDialog: () -> Unit,
    onCancel: () -> Unit
) {
    if (showConfirmDialog) {
        OkCancelDialog(
            title = stringResource(R.string.step_deactivate),
            message = stringResource(R.string.medtrum_deactivate_pump_confirm),
            onConfirm = onConfirm,
            onDismiss = onDismissDialog
        )
    }

    ConfirmDeactivateContent(
        onNext = onShowDialog,
        onCancel = onCancel
    )
}

@Composable
fun DeactivatingStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    val isError = setupStep == MedtrumPatchViewModel.SetupStep.ERROR

    var unexpectedStateMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.deactivatePatch()
    }

    LaunchedEffect(setupStep) {
        when (setupStep) {
            MedtrumPatchViewModel.SetupStep.INITIAL,
            MedtrumPatchViewModel.SetupStep.START_DEACTIVATION -> Unit

            MedtrumPatchViewModel.SetupStep.STOPPED            -> viewModel.moveStep(PatchStep.DEACTIVATION_COMPLETE)
            MedtrumPatchViewModel.SetupStep.ERROR              -> Unit // Handled by inline UI

            else                                               -> unexpectedStateMessage = setupStep.toString()
        }
    }

    DeactivatingContent(
        isError = isError,
        onDiscard = { viewModel.moveStep(PatchStep.FORCE_DEACTIVATION) },
        onCancel = onCancel
    )

    unexpectedStateMessage?.let { msg ->
        OkDialog(
            title = stringResource(app.aaps.core.ui.R.string.error),
            message = stringResource(R.string.unexpected_state, msg),
            onDismiss = {
                unexpectedStateMessage = null
                viewModel.moveStep(PatchStep.CANCEL)
            }
        )
    }
}

@Composable
fun DeactivateCompleteStep(
    viewModel: MedtrumPatchViewModel
) {
    DeactivateCompleteContent(
        onNewPatch = { viewModel.moveStep(PatchStep.PREPARE_PATCH) },
        onDone = { viewModel.moveStep(PatchStep.COMPLETE) }
    )
}
