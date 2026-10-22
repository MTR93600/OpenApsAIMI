package app.aaps.pump.medtrum.compose.steps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.pump.medtrum.R
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.compose.MedtrumPatchViewModel

@Composable
fun PrepareStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    val patchStep by viewModel.patchStep.collectAsStateWithLifecycle()
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    val reservoirLevel by viewModel.medtrumPump.reservoirFlow.collectAsStateWithLifecycle()

    val isConnecting = patchStep == PatchStep.PREPARE_PATCH_CONNECT
    val isFilled = setupStep == MedtrumPatchViewModel.SetupStep.FILLED
    val isError = setupStep == MedtrumPatchViewModel.SetupStep.ERROR

    var unexpectedStateMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (patchStep == PatchStep.PREPARE_PATCH_CONNECT) {
            viewModel.preparePatchConnect()
        } else {
            viewModel.preparePatch()
            viewModel.loadInsulins()
        }
    }

    // During connect phase, unexpected states (including ERROR) trigger OkDialog + cancel
    LaunchedEffect(setupStep) {
        if (!isConnecting) return@LaunchedEffect

        when (setupStep) {
            MedtrumPatchViewModel.SetupStep.INITIAL,
            MedtrumPatchViewModel.SetupStep.FILLED,
            MedtrumPatchViewModel.SetupStep.STOPPED -> Unit

            else                                    -> unexpectedStateMessage = setupStep.toString()
        }
    }

    val state = when {
        patchStep == PatchStep.PREPARE_PATCH -> PrepareState.INITIAL
        isFilled                             -> PrepareState.FILLED
        isConnecting                         -> PrepareState.CONNECTING
        isError                              -> PrepareState.ERROR
        else                                 -> PrepareState.INITIAL
    }

    PrepareStepContent(
        state = state,
        reservoirLevel = reservoirLevel,
        pumpState = viewModel.medtrumPump.pumpState.toString(),
        onNext = {
            viewModel.moveStep(PatchStep.PREPARE_PATCH_CONNECT)
        },
        onFilled = {
            if (viewModel.showInsulinStep) viewModel.moveStep(PatchStep.SELECT_INSULIN)
            else viewModel.moveStep(PatchStep.PRIME)
        },
        onRetry = {
            viewModel.updateSetupStep(MedtrumPatchViewModel.SetupStep.INITIAL)
            viewModel.moveStep(PatchStep.PREPARE_PATCH_CONNECT)
        },
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
