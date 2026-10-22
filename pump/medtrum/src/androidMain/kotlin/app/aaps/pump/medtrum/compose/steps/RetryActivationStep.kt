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
fun RetryActivationStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    val patchStep by viewModel.patchStep.collectAsStateWithLifecycle()
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()

    val isConnecting = patchStep == PatchStep.RETRY_ACTIVATION_CONNECT
    var showDiscardDialog by remember { mutableStateOf(false) }
    var unexpectedStateMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (patchStep == PatchStep.RETRY_ACTIVATION) {
            viewModel.preparePatch()
        } else if (patchStep == PatchStep.RETRY_ACTIVATION_CONNECT) {
            viewModel.retryActivationConnect()
        }
    }

    LaunchedEffect(setupStep) {
        if (patchStep == PatchStep.RETRY_ACTIVATION_CONNECT) {
            when (setupStep) {
                MedtrumPatchViewModel.SetupStep.INITIAL -> Unit
                MedtrumPatchViewModel.SetupStep.FILLED -> viewModel.forceMoveStep(PatchStep.SELECT_INSULIN)
                MedtrumPatchViewModel.SetupStep.PRIMING -> viewModel.forceMoveStep(PatchStep.PRIMING)
                MedtrumPatchViewModel.SetupStep.PRIMED -> viewModel.forceMoveStep(PatchStep.PRIME_COMPLETE)
                MedtrumPatchViewModel.SetupStep.ACTIVATED -> viewModel.forceMoveStep(PatchStep.ACTIVATE_COMPLETE)

                else -> unexpectedStateMessage = setupStep.toString()
            }
        }
    }

    if (showDiscardDialog) {
        OkCancelDialog(
            title = stringResource(R.string.step_retry_activation),
            message = stringResource(R.string.medtrum_deactivate_pump_confirm),
            onConfirm = {
                showDiscardDialog = false
                viewModel.moveStep(PatchStep.FORCE_DEACTIVATION)
            },
            onDismiss = { showDiscardDialog = false }
        )
    }

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

    RetryActivationContent(
        isConnecting = isConnecting,
        onRetry = { viewModel.moveStep(PatchStep.RETRY_ACTIVATION_CONNECT) },
        onDiscard = { showDiscardDialog = true },
        onCancel = onCancel
    )
}
