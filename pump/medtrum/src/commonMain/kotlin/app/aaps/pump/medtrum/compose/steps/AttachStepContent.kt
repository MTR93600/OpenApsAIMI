package app.aaps.pump.medtrum.compose.steps

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * @see `PreviewAttachStep`
 */
@Composable
internal fun AttachStepContent(
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.next),
            onClick = onNext
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        Text(
            text = stringResource(MedtrumStrings.remove_safety_lock),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MedtrumStrings.press_next_to_start_activation).stripHtml(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
