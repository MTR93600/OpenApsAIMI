package app.aaps.pump.medtrum.compose.steps

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.pump.announceWhenShown
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

private const val PRIME_MAX_PROGRESS = 150f

internal enum class PrimeState { READY, PRIMING, COMPLETE }

/**
 * @see `PreviewReady`
 * @see `PreviewPriming`
 * @see `PrimeStepPreviewComplete`
 */
@Composable
internal fun PrimeStepContent(
    state: PrimeState,
    primeProgress: Int = 0,
    onStartPrime: () -> Unit,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = when (state) {
            PrimeState.READY    -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onStartPrime)
            PrimeState.PRIMING  -> null
            PrimeState.COMPLETE -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onNext)
        },
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        when (state) {
            PrimeState.READY    -> {
                Text(
                    text = stringResource(MedtrumStrings.half_press_needle).stripHtml(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.do_not_attach_to_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            PrimeState.PRIMING  -> {
                val waitText = stringResource(MedtrumStrings.wait_for_priming)
                Text(
                    text = waitText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.announceWhenShown(waitText)
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { (primeProgress / PRIME_MAX_PROGRESS).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.do_not_attach_to_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }

            PrimeState.COMPLETE -> {
                // The Next button that appears now is announced by WizardStepLayout.
                Text(
                    text = stringResource(MedtrumStrings.press_next).stripHtml(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.do_not_attach_to_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
