package app.aaps.pump.medtrum.compose.patch

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.dialogs.OkDialog
import app.aaps.core.ui.compose.insulin.SelectInsulin
import app.aaps.core.ui.compose.pump.ProfileGateWizardStep
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardScreen
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.siteRotation.SiteLocationWizardStep
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.compose.steps.ActivateState
import app.aaps.pump.medtrum.compose.steps.ActivateStepContent
import app.aaps.pump.medtrum.compose.steps.AttachStepContent
import app.aaps.pump.medtrum.compose.steps.ConfirmDeactivateContent
import app.aaps.pump.medtrum.compose.steps.DeactivateCompleteContent
import app.aaps.pump.medtrum.compose.steps.DeactivatingContent
import app.aaps.pump.medtrum.compose.steps.PrepareState
import app.aaps.pump.medtrum.compose.steps.PrepareStepContent
import app.aaps.pump.medtrum.compose.steps.PrimeState
import app.aaps.pump.medtrum.compose.steps.PrimeStepContent
import app.aaps.pump.medtrum.compose.steps.RetryActivationContent

/**
 * The "change patch" wizard over [MedtrumPatchWizardModel]: the same pages as the Android
 * `MedtrumPatchScreen`, drawn by the same step contents.
 *
 * [scanContent] is the pump search page. The search itself is a platform radio, so the caller
 * supplies it and calls [MedtrumPatchWizardModel.pumpChosen] when a pump was picked.
 *
 * [onClose] is called when the wizard has nothing more to show: the activation is complete, or
 * the person cancelled or finished a deactivation.
 */
@Composable
fun MedtrumPatchWizardScreen(
    model: MedtrumPatchWizardModel,
    scanContent: @Composable (onCancel: () -> Unit) -> Unit,
    onClose: () -> Unit,
    setToolbarConfig: ((ToolbarConfig) -> Unit)? = null
) {
    val step by model.step.collectAsStateWithLifecycle()
    val failure by model.failure.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val pump by model.pump.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    var confirmDeactivation by remember { mutableStateOf(false) }

    LaunchedEffect(step) {
        if (step == PatchStep.COMPLETE || step == PatchStep.CANCEL) onClose()
    }

    WizardScreen(
        currentStep = step,
        totalSteps = progress.second,
        currentStepIndex = progress.first,
        canGoBack = step in stepsThatMayBeLeft,
        onBack = { model.cancel() },
        cancelDialogTitle = stringResource(MedtrumStrings.change_patch_label),
        cancelDialogText = stringResource(MedtrumStrings.cancel_sure),
        title = step?.let { stringResource(titleOf(it)) } ?: "",
        setToolbarConfig = setToolbarConfig,
        stepContent = { current, onCancel ->
            when (current) {
                PatchStep.PROFILE_GATE             -> ProfileGateWizardStep(model)
                PatchStep.BLE_SCAN                 -> scanContent(onCancel)

                PatchStep.PREPARE_PATCH            -> PrepareStepContent(
                    state = PrepareState.INITIAL,
                    onNext = model::connectAndWaitForFill,
                    onFilled = {},
                    onRetry = model::connectAndWaitForFill,
                    onCancel = onCancel
                )

                // The session moves on by itself once the pump says the patch is filled, so there is
                // no FILLED page here: the page waits, or shows why it stopped.
                PatchStep.PREPARE_PATCH_CONNECT    -> PrepareStepContent(
                    state = if (failure != null) PrepareState.ERROR else PrepareState.CONNECTING,
                    reservoirLevel = pump.reservoir,
                    pumpState = failure ?: pump.pumpState,
                    onNext = {},
                    onFilled = {},
                    onRetry = model::connectAndWaitForFill,
                    onCancel = onCancel
                )

                PatchStep.PRIME                    -> PrimeStepContent(state = PrimeState.READY, onStartPrime = model::prime, onNext = {}, onCancel = onCancel)
                PatchStep.PRIMING                  -> PrimeStepContent(state = PrimeState.PRIMING, primeProgress = pump.primeProgress, onStartPrime = {}, onNext = {}, onCancel = onCancel)
                PatchStep.PRIME_COMPLETE           -> PrimeStepContent(state = PrimeState.COMPLETE, onStartPrime = {}, onNext = model::afterPriming, onCancel = onCancel)
                PatchStep.SITE_LOCATION            -> SiteLocationWizardStep(model)
                PatchStep.ATTACH_PATCH             -> AttachStepContent(onNext = model::activate, onCancel = onCancel)
                PatchStep.ACTIVATE                 -> ActivateStepContent(state = ActivateState.ACTIVATING, reservoirLevel = pump.reservoir, onComplete = {}, onCancel = onCancel)
                PatchStep.ACTIVATE_COMPLETE        -> ActivateStepContent(state = ActivateState.COMPLETE, reservoirLevel = pump.reservoir, onComplete = model::complete, onCancel = onCancel)

                PatchStep.START_DEACTIVATION       -> ConfirmDeactivateContent(onNext = { confirmDeactivation = true }, onCancel = onCancel)

                PatchStep.DEACTIVATE,
                PatchStep.FORCE_DEACTIVATION       -> DeactivatingContent(isError = failure != null, onDiscard = model::forceDeactivate, onCancel = onCancel)

                PatchStep.DEACTIVATION_COMPLETE    -> DeactivateCompleteContent(onNewPatch = model::startActivation, onDone = onClose)

                PatchStep.RETRY_ACTIVATION,
                PatchStep.RETRY_ACTIVATION_CONNECT -> RetryActivationContent(
                    isConnecting = busy,
                    onRetry = model::retryActivation,
                    onDiscard = model::forceDeactivate,
                    onCancel = onCancel
                )

                PatchStep.SELECT_INSULIN           -> SelectInsulinPage(model, onCancel)

                // COMPLETE and CANCEL close the wizard.
                PatchStep.COMPLETE,
                PatchStep.CANCEL                   -> Unit
            }
        }
    )

    if (confirmDeactivation) {
        OkCancelDialog(
            title = stringResource(MedtrumStrings.step_deactivate),
            message = stringResource(MedtrumStrings.medtrum_deactivate_pump_confirm),
            onConfirm = {
                confirmDeactivation = false
                model.deactivate()
            },
            onDismiss = { confirmDeactivation = false }
        )
    }

    // A command that did not work stops the sequence. The pages that can recover from it show the
    // reason themselves; on the others, the person is told and the wizard ends, as on Android.
    val failureHandledOnPage = step == PatchStep.PREPARE_PATCH_CONNECT || step == PatchStep.DEACTIVATE
    failure?.takeUnless { failureHandledOnPage }?.let { reason ->
        OkDialog(
            title = stringResource(CoreUiStrings.error),
            message = stringResource(MedtrumStrings.unexpected_state, reason),
            onDismiss = { model.cancel() }
        )
    }

    message?.let { text ->
        OkDialog(
            title = stringResource(MedtrumStrings.change_patch_label),
            message = text,
            onDismiss = model::dismissMessage
        )
    }
}

/** The steps from which the person may leave, the same list as the Android wizard. */
private val stepsThatMayBeLeft = setOf(
    PatchStep.BLE_SCAN,
    PatchStep.PROFILE_GATE,
    PatchStep.PREPARE_PATCH,
    PatchStep.SELECT_INSULIN,
    PatchStep.SITE_LOCATION,
    PatchStep.START_DEACTIVATION,
    PatchStep.RETRY_ACTIVATION,
    PatchStep.COMPLETE,
    PatchStep.DEACTIVATION_COMPLETE
)

/** The page title, the same strings as the Android wizard. */
private fun titleOf(step: PatchStep): TextRef = when (step) {
    PatchStep.BLE_SCAN                 -> CoreUiStrings.step_ble_scan
    PatchStep.PROFILE_GATE             -> CoreUiStrings.pump_wizard_profile_gate_title
    PatchStep.PREPARE_PATCH            -> MedtrumStrings.step_prepare_patch
    PatchStep.PREPARE_PATCH_CONNECT    -> MedtrumStrings.step_prepare_patch_connect
    PatchStep.SELECT_INSULIN           -> MedtrumStrings.step_select_insulin
    PatchStep.PRIME                    -> MedtrumStrings.step_prime
    PatchStep.PRIMING                  -> MedtrumStrings.step_priming
    PatchStep.PRIME_COMPLETE           -> MedtrumStrings.step_priming_complete
    PatchStep.ATTACH_PATCH             -> MedtrumStrings.step_attach
    PatchStep.ACTIVATE                 -> MedtrumStrings.step_activate
    PatchStep.ACTIVATE_COMPLETE        -> MedtrumStrings.step_activate_complete
    PatchStep.SITE_LOCATION            -> CoreUiStrings.site_rotation
    PatchStep.START_DEACTIVATION       -> MedtrumStrings.step_deactivate
    PatchStep.DEACTIVATE,
    PatchStep.FORCE_DEACTIVATION       -> MedtrumStrings.step_deactivating
    PatchStep.DEACTIVATION_COMPLETE    -> MedtrumStrings.step_deactivate_complete
    PatchStep.RETRY_ACTIVATION,
    PatchStep.RETRY_ACTIVATION_CONNECT -> MedtrumStrings.step_retry_activation
    PatchStep.COMPLETE,
    PatchStep.CANCEL                   -> MedtrumStrings.change_patch_label
}

/** The Android SELECT_INSULIN page: which insulin is in the new reservoir. */
@Composable
private fun SelectInsulinPage(model: MedtrumPatchWizardModel, onCancel: () -> Unit) {
    val insulins by model.availableInsulins.collectAsStateWithLifecycle()
    val selected by model.selectedInsulin.collectAsStateWithLifecycle()
    val activeLabel by model.activeInsulinLabel.collectAsStateWithLifecycle()
    WizardStepLayout(
        primaryButton = WizardButton(text = stringResource(CoreUiStrings.next), onClick = model::insulinChosen),
        secondaryButton = WizardButton(text = stringResource(CoreUiStrings.cancel), onClick = onCancel)
    ) {
        Text(text = stringResource(CoreUiStrings.select_insulin_description), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        SelectInsulin(
            availableInsulins = insulins,
            selectedInsulin = selected,
            activeInsulinLabel = activeLabel,
            onInsulinSelect = model::selectInsulin,
            concentrationDropDownEnabled = model.concentrationEnabled
        )
    }
}
