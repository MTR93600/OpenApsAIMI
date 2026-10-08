package app.aaps.pump.ui.medtrum

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.pump.WizardScreen
import androidx.compose.ui.tooling.preview.Preview

/**
 * Patch activation/deactivation steps, ported from
 * `pump/medtrum/code/PatchStep.kt` (pure enum, no Android dependency).
 */
enum class MedtrumPatchStep {
    START_DEACTIVATION,
    DEACTIVATE,
    FORCE_DEACTIVATION,
    DEACTIVATION_COMPLETE,
    BLE_SCAN,
    PROFILE_GATE,
    PREPARE_PATCH,
    PREPARE_PATCH_CONNECT,
    SELECT_INSULIN,
    PRIME,
    PRIMING,
    PRIME_COMPLETE,
    ATTACH_PATCH,
    ACTIVATE,
    ACTIVATE_COMPLETE,
    SITE_LOCATION,
    RETRY_ACTIVATION,
    RETRY_ACTIVATION_CONNECT,
    CANCEL,
    COMPLETE;
}

/**
 * Hoisted UI state for the Medtrum patch wizard.
 * Replaces the Android `MedtrumPatchViewModel` flows.
 */
data class MedtrumPatchWizardState(
    val currentStep: MedtrumPatchStep?,
    val totalSteps: Int,
    val currentStepIndex: Int,
    val canGoBack: Boolean,
    val title: String,
    val cancelDialogTitle: String,
    val cancelDialogText: String
)

/**
 * Medtrum patch wizard screen, ported to commonMain.
 *
 * Ported from `pump/medtrum/.../compose/MedtrumPatchScreen.kt`.
 * The Android ViewModel is replaced by hoisted [state]; each wizard step's
 * UI is provided by the platform via [stepContent] (the Android step
 * composables stay Android-only).
 */
@Composable
fun MedtrumPatchScreen(
    state: MedtrumPatchWizardState,
    onBack: () -> Unit,
    setToolbarConfig: ((ToolbarConfig) -> Unit)? = null,
    stepContent: @Composable (step: MedtrumPatchStep, onCancel: () -> Unit) -> Unit
) {
    WizardScreen(
        currentStep = state.currentStep,
        totalSteps = state.totalSteps,
        currentStepIndex = state.currentStepIndex,
        canGoBack = state.canGoBack,
        onBack = onBack,
        cancelDialogTitle = state.cancelDialogTitle,
        cancelDialogText = state.cancelDialogText,
        title = state.title,
        setToolbarConfig = setToolbarConfig,
        stepContent = stepContent
    )
}

@Preview
@Composable
private fun MedtrumPatchPreview() {
    MedtrumPatchScreen(
        state = MedtrumPatchWizardState(
            currentStep = MedtrumPatchStep.PREPARE_PATCH,
            totalSteps = 8,
            currentStepIndex = 2,
            canGoBack = true,
            title = "Change patch",
            cancelDialogTitle = "Change patch",
            cancelDialogText = "Are you sure you want to cancel?"
        ),
        onBack = {},
        stepContent = { _, _ -> }
    )
}
