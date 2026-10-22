package app.aaps.pump.medtrum.compose.steps

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * @see `PreviewRetryPrompt`
 * @see `PreviewRetryConnecting`
 */
@Composable
internal fun RetryActivationContent(
    isConnecting: Boolean,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = if (isConnecting) {
            null
        } else {
            WizardButton(text = stringResource(CoreUiStrings.next), onClick = onRetry)
        },
        secondaryButton = if (isConnecting) {
            WizardButton(text = stringResource(CoreUiStrings.cancel), onClick = onCancel)
        } else {
            WizardButton(text = stringResource(CoreUiStrings.discard), onClick = onDiscard)
        }
    ) {
        if (isConnecting) {
            Text(
                text = stringResource(MedtrumStrings.reading_activation_status),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.CenterHorizontally)
            )
        } else {
            Text(
                text = stringResource(MedtrumStrings.activation_in_progress),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(MedtrumStrings.press_retry_or_discard).stripHtml(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
