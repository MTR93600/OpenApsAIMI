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
import app.aaps.core.ui.compose.banner.ErrorBanner
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * @see `PreviewConfirmDeactivate`
 */
@Composable
internal fun ConfirmDeactivateContent(
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
            text = stringResource(MedtrumStrings.deactivate_sure),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MedtrumStrings.press_next_or_cancel).stripHtml(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * @see `PreviewDeactivating`
 * @see `PreviewDeactivatingError`
 */
@Composable
internal fun DeactivatingContent(
    isError: Boolean,
    onDiscard: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = if (isError) WizardButton(
            text = stringResource(CoreUiStrings.discard),
            onClick = onDiscard
        ) else null,
        secondaryButton = if (isError) WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        ) else null
    ) {
        if (isError) {
            ErrorBanner(message = stringResource(MedtrumStrings.deactivating_error).stripHtml())
        } else {
            Text(
                text = stringResource(MedtrumStrings.deactivating_pump),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(48.dp))
            CircularProgressIndicator(
                modifier = Modifier
                    .size(64.dp)
                    .align(Alignment.CenterHorizontally)
            )
        }
    }
}

/**
 * @see `PreviewDeactivateComplete`
 */
@Composable
internal fun DeactivateCompleteContent(
    onNewPatch: () -> Unit,
    onDone: () -> Unit
) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.next),
            onClick = onNewPatch
        ),
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.ok),
            onClick = onDone
        )
    ) {
        Text(
            text = stringResource(MedtrumStrings.retract_needle),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MedtrumStrings.remove_base_discard_patch),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(MedtrumStrings.press_next_or_OK).stripHtml(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
