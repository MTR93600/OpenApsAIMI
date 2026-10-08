package app.aaps.pump.ui.omnipod

import androidx.compose.runtime.Composable
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.pump.WizardScreen
import androidx.compose.ui.tooling.preview.Preview

/**
 * Wizard steps for Omnipod pod activation and deactivation, ported from
 * `pump/omnipod/common/.../ui/wizard/compose/OmnipodWizardStep.kt`
 * (pure enum, no Android dependency). Used by both Eros and Dash.
 */
enum class OmnipodWizardStep {

    // Pre-activation gate (shown only when no profile switch exists yet)
    PROFILE_GATE,

    // Activation steps
    START_POD_ACTIVATION,
    SELECT_INSULIN,
    INITIALIZE_POD,
    SITE_LOCATION,
    ATTACH_POD,
    INSERT_CANNULA,
    POD_ACTIVATED,

    // Deactivation steps
    START_POD_DEACTIVATION,
    DEACTIVATE_POD,
    POD_DEACTIVATED,
    POD_DISCARDED
}

/**
 * Hoisted UI state for the Omnipod wizard.
 * Replaces the Android `OmnipodWizardViewModel` flows.
 */
data class OmnipodWizardState(
    val currentStep: OmnipodWizardStep?,
    val totalSteps: Int,
    val currentStepIndex: Int,
    val canGoBack: Boolean,
    val title: String,
    val cancelDialogTitle: String,
    val cancelDialogText: String
)

/**
 * Top-level Compose screen for the Omnipod activation/deactivation wizard,
 * ported to commonMain.
 *
 * Ported from `pump/omnipod/common/.../ui/wizard/compose/OmnipodWizardScreen.kt`.
 * The Android ViewModel (including `KeepScreenOnEffect` and the finish-event
 * flow) is replaced by hoisted [state] and the [onFinish] callback; each
 * wizard step's UI is provided by the platform via [stepContent].
 */
@Composable
fun OmnipodWizardScreen(
    state: OmnipodWizardState,
    onFinish: () -> Unit,
    setToolbarConfig: ((ToolbarConfig) -> Unit)? = null,
    stepContent: @Composable (step: OmnipodWizardStep, onCancel: () -> Unit) -> Unit
) {
    WizardScreen(
        currentStep = state.currentStep,
        totalSteps = state.totalSteps,
        currentStepIndex = state.currentStepIndex,
        canGoBack = state.canGoBack,
        onBack = onFinish,
        cancelDialogTitle = state.cancelDialogTitle,
        cancelDialogText = state.cancelDialogText,
        title = state.title,
        setToolbarConfig = setToolbarConfig,
        stepContent = stepContent
    )
}

@Preview
@Composable
private fun OmnipodWizardPreview() {
    OmnipodWizardScreen(
        state = OmnipodWizardState(
            currentStep = OmnipodWizardStep.START_POD_ACTIVATION,
            totalSteps = 7,
            currentStepIndex = 1,
            canGoBack = true,
            title = "Activate pod",
            cancelDialogTitle = "Exit wizard?",
            cancelDialogText = "Are you sure you want to exit?"
        ),
        onFinish = {},
        stepContent = { _, _ -> }
    )
}
