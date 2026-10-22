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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.banner.ErrorBanner
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

internal enum class PrepareState { INITIAL, CONNECTING, FILLED, ERROR }

/**
 * @see `PreviewInitial`
 * @see `PreviewFilled`
 * @see `PreviewError`
 */
@Composable
internal fun PrepareStepContent(
    state: PrepareState,
    reservoirLevel: Double = 0.0,
    pumpState: String = "",
    onNext: () -> Unit,
    onFilled: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = when (state) {
            PrepareState.INITIAL -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onNext)
            PrepareState.CONNECTING -> null
            PrepareState.FILLED -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onFilled)
            PrepareState.ERROR -> WizardButton(text = stringResource(CoreUiStrings.retry), onClick = onRetry)
        },
        secondaryButton = WizardButton(
            text = stringResource(CoreUiStrings.cancel),
            onClick = onCancel
        )
    ) {
        when (state) {
            PrepareState.INITIAL -> {
                Text(
                    text = stringResource(MedtrumStrings.patch_begin_activation).stripHtml(),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            PrepareState.CONNECTING,
            PrepareState.FILLED,
            PrepareState.ERROR   -> {
                Text(
                    text = stringResource(MedtrumStrings.connect_pump_base).stripHtml(),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.note_min_70_units),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (reservoirLevel > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(MedtrumStrings.reservoir_text_and_level, reservoirLevel),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(MedtrumStrings.do_not_attach_to_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
                if (state == PrepareState.ERROR) {
                    Spacer(Modifier.height(8.dp))
                    ErrorBanner(message = stringResource(MedtrumStrings.unexpected_state, pumpState))
                }
                if (state == PrepareState.CONNECTING) {
                    Spacer(Modifier.height(48.dp))
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(64.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            }
        }
    }
}
