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
fun AttachStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    var unexpectedStateMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(setupStep) {
        if (setupStep != MedtrumPatchViewModel.SetupStep.PRIMED && setupStep != MedtrumPatchViewModel.SetupStep.INITIAL) {
            unexpectedStateMessage = setupStep.toString()
        }
    }

    AttachStepContent(
        onNext = { viewModel.moveStep(PatchStep.ACTIVATE) },
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
