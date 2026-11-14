package app.aaps.pump.omnipod.dashctl.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.banner.ErrorBanner
import app.aaps.core.ui.compose.dialogs.OkCancelDialog
import app.aaps.core.ui.compose.insulin.SelectInsulin
import app.aaps.core.ui.compose.pump.ProfileGateWizardStep
import app.aaps.core.ui.compose.pump.WizardButton
import app.aaps.core.ui.compose.pump.WizardScreen
import app.aaps.core.ui.compose.pump.WizardStepLayout
import app.aaps.core.ui.compose.siteRotation.SiteLocationWizardStep
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.omnipod.dashctl.DashStrings

/**
 * The pod activation and deactivation wizard, the counterpart of Android `OmnipodWizardScreen` and
 * its step composables (`InfoStep`, `ActionStep`, `AttachPodStep`, `DeactivatePodStep`,
 * `SelectInsulinStep`): the same pages, buttons and confirmations, drawn with the shared
 * [WizardScreen] and [WizardStepLayout] of `:core:ui`.
 *
 * [onClose] is called when the wizard has nothing more to show, or the person left it.
 */
@Composable
fun DashPodWizardScreen(
    model: DashPodWizardModel,
    onClose: () -> Unit,
    setToolbarConfig: ((ToolbarConfig) -> Unit)? = null
) {
    val currentStep by model.currentStep.collectAsStateWithLifecycle()
    val totalSteps by model.totalSteps.collectAsStateWithLifecycle()
    val currentStepIndex by model.currentStepIndex.collectAsStateWithLifecycle()
    val canGoBack by model.canGoBack.collectAsStateWithLifecycle()
    val finished by model.finished.collectAsStateWithLifecycle()

    LaunchedEffect(finished) {
        if (finished) onClose()
    }

    WizardScreen(
        currentStep = currentStep,
        totalSteps = totalSteps,
        currentStepIndex = currentStepIndex,
        canGoBack = canGoBack,
        onBack = onClose,
        cancelDialogTitle = stringResource(DashStrings.dash_wizard_exit_title),
        cancelDialogText = stringResource(DashStrings.dash_wizard_exit_text),
        title = currentStep?.let { stringResource(it.title) } ?: "",
        setToolbarConfig = setToolbarConfig
    ) { step, onCancel ->
        val text = step.text?.let { stringResource(it) } ?: ""
        when (step) {
            DashWizardStep.PROFILE_GATE           -> ProfileGateWizardStep(model)

            DashWizardStep.START_POD_ACTIVATION   -> InfoStep(text = text, isFinishStep = false, onNext = model::moveToNext, onCancel = onCancel)

            DashWizardStep.SELECT_INSULIN         -> SelectInsulinStep(model = model, onCancel = onCancel)

            DashWizardStep.INITIALIZE_POD,
            DashWizardStep.INSERT_CANNULA         -> ActionStep(model = model, text = text, onCancel = onCancel)

            DashWizardStep.SITE_LOCATION          -> SiteLocationWizardStep(model)

            DashWizardStep.ATTACH_POD             -> AttachPodStep(
                title = stringResource(step.title),
                text = text,
                onNext = model::moveToNext,
                onCancel = onCancel
            )

            DashWizardStep.POD_ACTIVATED          -> InfoStep(text = text, isFinishStep = true, onNext = model::completeActivation, onCancel = null)

            DashWizardStep.START_POD_DEACTIVATION -> {
                // The next page deactivates the pod as soon as it shows, which cannot be undone, so
                // Next asks first (Android does the same).
                var showConfirm by remember { mutableStateOf(false) }
                if (showConfirm) {
                    OkCancelDialog(
                        title = stringResource(DashStrings.dash_wizard_start_deactivation_title),
                        message = stringResource(DashStrings.dash_wizard_start_deactivation_confirmation),
                        onConfirm = {
                            showConfirm = false
                            model.moveToNext()
                        },
                        onDismiss = { showConfirm = false }
                    )
                }
                InfoStep(text = text, isFinishStep = false, onNext = { showConfirm = true }, onCancel = onCancel)
            }

            DashWizardStep.DEACTIVATE_POD         -> DeactivatePodStep(model = model, text = text, onCancel = onCancel)

            DashWizardStep.POD_DEACTIVATED,
            DashWizardStep.POD_DISCARDED          -> InfoStep(text = text, isFinishStep = true, onNext = model::finish, onCancel = null)
        }
    }
}

/** Android `InfoStep`: a text, Next (or Finish), and Cancel when the step may be left. */
@Composable
private fun InfoStep(text: String, isFinishStep: Boolean, onNext: () -> Unit, onCancel: (() -> Unit)?) {
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(if (isFinishStep) CoreUiStrings.finish else CoreUiStrings.next),
            onClick = onNext
        ),
        secondaryButton = onCancel?.let { WizardButton(text = stringResource(CoreUiStrings.cancel), onClick = it) }
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Android `AttachPodStep`: Next asks before the cannula is inserted. */
@Composable
private fun AttachPodStep(title: String, text: String, onNext: () -> Unit, onCancel: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }
    if (showConfirm) {
        OkCancelDialog(
            title = title,
            message = stringResource(DashStrings.dash_wizard_attach_pod_confirm_text),
            onConfirm = {
                showConfirm = false
                onNext()
            },
            onDismiss = { showConfirm = false }
        )
    }
    WizardStepLayout(
        primaryButton = WizardButton(text = stringResource(CoreUiStrings.next), onClick = { showConfirm = true }),
        secondaryButton = WizardButton(text = stringResource(CoreUiStrings.cancel), onClick = onCancel)
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Android `SelectInsulinStep`. */
@Composable
private fun SelectInsulinStep(model: DashPodWizardModel, onCancel: () -> Unit) {
    val availableInsulins by model.availableInsulins.collectAsStateWithLifecycle()
    val selectedInsulin by model.selectedInsulin.collectAsStateWithLifecycle()
    val activeInsulinLabel by model.activeInsulinLabel.collectAsStateWithLifecycle()
    WizardStepLayout(
        primaryButton = WizardButton(
            text = stringResource(CoreUiStrings.next),
            onClick = model::moveToNext,
            enabled = selectedInsulin != null
        ),
        secondaryButton = WizardButton(text = stringResource(CoreUiStrings.cancel), onClick = onCancel)
    ) {
        Text(text = stringResource(CoreUiStrings.select_insulin_description), style = MaterialTheme.typography.bodyLarge)
        SelectInsulin(
            availableInsulins = availableInsulins,
            selectedInsulin = selectedInsulin,
            activeInsulinLabel = activeInsulinLabel,
            onInsulinSelect = model::selectInsulin,
            concentrationDropDownEnabled = model.concentrationEnabled
        )
    }
}

/**
 * Android `ActionStep` (part 1 and part 2): the action runs when the page shows. While it runs only
 * the progress shows and Cancel is disabled; after a success Next; after a failure the reason, Retry
 * and Cancel. (Android also offers "Deactivate Pod" here for a pod in alarm or out of time, but its
 * Dash wizard answers false to both, so that button never shows on Android Dash either.)
 */
@Composable
private fun ActionStep(model: DashPodWizardModel, text: String, onCancel: () -> Unit) {
    val actionState by model.actionState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (actionState is DashWizardActionState.Idle) model.executeAction()
    }
    ActionLayout(
        actionState = actionState,
        text = text,
        failureButton = WizardButton(text = stringResource(CoreUiStrings.retry), onClick = model::executeAction),
        onNext = model::moveToNext,
        onCancel = onCancel
    )
}

/**
 * Android `DeactivatePodStep`: the deactivation runs when the page shows; after a failure the person
 * may discard the pod, which asks first and then shows "Pod Discarded".
 */
@Composable
private fun DeactivatePodStep(model: DashPodWizardModel, text: String, onCancel: () -> Unit) {
    val actionState by model.actionState.collectAsStateWithLifecycle()
    var showDiscard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (actionState is DashWizardActionState.Idle) model.executeAction()
    }
    if (showDiscard) {
        OkCancelDialog(
            title = stringResource(DashStrings.dash_wizard_discard_pod),
            message = stringResource(DashStrings.dash_wizard_discard_pod_confirmation),
            onConfirm = {
                showDiscard = false
                model.discardPod()
            },
            onDismiss = { showDiscard = false }
        )
    }
    ActionLayout(
        actionState = actionState,
        text = text,
        failureButton = WizardButton(text = stringResource(DashStrings.dash_wizard_discard_pod), onClick = { showDiscard = true }),
        onNext = model::moveToNext,
        onCancel = onCancel
    )
}

@Composable
private fun ActionLayout(
    actionState: DashWizardActionState,
    text: String,
    failureButton: WizardButton,
    onNext: () -> Unit,
    onCancel: () -> Unit
) {
    WizardStepLayout(
        primaryButton = when (actionState) {
            DashWizardActionState.Idle,
            DashWizardActionState.Executing -> null

            DashWizardActionState.Success   -> WizardButton(text = stringResource(CoreUiStrings.next), onClick = onNext)
            is DashWizardActionState.Error  -> failureButton
        },
        secondaryButton = when (actionState) {
            DashWizardActionState.Success -> null
            else                          -> WizardButton(
                text = stringResource(CoreUiStrings.cancel),
                onClick = onCancel,
                enabled = actionState !is DashWizardActionState.Executing
            )
        }
    ) {
        ActionContent(actionState, text)
    }
}

@Composable
private fun ColumnScope.ActionContent(actionState: DashWizardActionState, text: String) {
    when (actionState) {
        DashWizardActionState.Idle,
        DashWizardActionState.Executing -> {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(AapsSpacing.xxLarge))
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        DashWizardActionState.Success   -> Text(text = text, style = MaterialTheme.typography.bodyLarge)
        is DashWizardActionState.Error  -> ErrorBanner(message = actionState.message)
    }
}
