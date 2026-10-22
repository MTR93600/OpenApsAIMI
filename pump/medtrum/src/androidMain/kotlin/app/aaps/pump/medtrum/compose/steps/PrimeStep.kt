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
fun PrimeStep(
    viewModel: MedtrumPatchViewModel,
    onCancel: () -> Unit
) {
    val patchStep by viewModel.patchStep.collectAsStateWithLifecycle()
    val setupStep by viewModel.setupStep.collectAsStateWithLifecycle()
    val primeProgress by viewModel.medtrumPump.primeProgressFlow.collectAsStateWithLifecycle()

    val isPriming = patchStep == PatchStep.PRIMING
    val isPrimeComplete = patchStep == PatchStep.PRIME_COMPLETE

    var unexpectedStateMessage by remember { mutableStateOf<String?>(null) }

    // Trigger startPrime when entering PRIMING step
    // Note: use Unit as key to only trigger once when entering the step, not on every patchStep change
    // To avoid sending repeated commands and triggering errors and other unintended consequences
    LaunchedEffect(Unit) {
        if (patchStep == PatchStep.PRIMING) {
            viewModel.startPrime()
        }
    }

    // Auto-navigate on primed and handle unexpected states
    LaunchedEffect(setupStep) {
        when (patchStep) {
            PatchStep.PRIME          -> {
                if (setupStep != MedtrumPatchViewModel.SetupStep.FILLED && setupStep != MedtrumPatchViewModel.SetupStep.INITIAL)
                    unexpectedStateMessage = setupStep.toString()
            }

            PatchStep.PRIMING        -> {
                when (setupStep) {
                    MedtrumPatchViewModel.SetupStep.INITIAL,
                    MedtrumPatchViewModel.SetupStep.FILLED,
                    MedtrumPatchViewModel.SetupStep.PRIMING -> Unit

                    MedtrumPatchViewModel.SetupStep.PRIMED  -> viewModel.moveStep(PatchStep.PRIME_COMPLETE)
                    else                                    -> unexpectedStateMessage = setupStep.toString()
                }
            }

            PatchStep.PRIME_COMPLETE -> {
                if (setupStep != MedtrumPatchViewModel.SetupStep.PRIMED)
                    unexpectedStateMessage = setupStep.toString()
            }

            else                     -> Unit
        }
    }

    val state = when {
        patchStep == PatchStep.PRIME -> PrimeState.READY
        isPriming                    -> PrimeState.PRIMING
        isPrimeComplete              -> PrimeState.COMPLETE
        else                         -> PrimeState.READY
    }

    PrimeStepContent(
        state = state,
        primeProgress = primeProgress,
        onStartPrime = { viewModel.moveStep(PatchStep.PRIMING) },
        onNext = { viewModel.moveAfterPriming() },
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
