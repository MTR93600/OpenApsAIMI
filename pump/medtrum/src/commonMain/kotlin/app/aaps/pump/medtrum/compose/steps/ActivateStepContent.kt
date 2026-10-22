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

internal enum class ActivateState { ACTIVATING, COMPLETE }

/**
 * @see `PreviewActivating`
 * @see `ActivateStepPreviewComplete`
 */
@Composable
internal fun ActivateStepContent(
    state: ActivateState,
    reservoirLevel: Double = 0.0,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = when (state) {
            ActivateState.ACTIVATING -> null
            ActivateState.COMPLETE   -> WizardButton(text = stringResource(CoreUiStrings.ok), onClick = onComplete)
        },
        secondaryButton = if (state != ActivateState.COMPLETE) WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        ) else null
    ) {
        when (state) {
            ActivateState.ACTIVATING -> {
                Text(
                    text = stringResource(MedtrumStrings.activating_pump),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(48.dp))
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(64.dp)
                        .align(Alignment.CenterHorizontally)
                )
            }

            ActivateState.COMPLETE   -> {
                Text(
                    text = stringResource(MedtrumStrings.activating_complete, reservoirLevel),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.can_export_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.press_OK_to_return).stripHtml(),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
