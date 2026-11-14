package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress

/**
 * The pages of the pod activation and deactivation wizard: the same list and order as Android
 * `OmnipodWizardStep` (`:pump:omnipod:common`).
 */
enum class DashWizardStep {

    // Shown only when no profile switch exists yet: part 2 needs a basal program.
    PROFILE_GATE,

    // Activation
    START_POD_ACTIVATION,
    SELECT_INSULIN,
    INITIALIZE_POD,
    SITE_LOCATION,
    ATTACH_POD,
    INSERT_CANNULA,
    POD_ACTIVATED,

    // Deactivation
    START_POD_DEACTIVATION,
    DEACTIVATE_POD,
    POD_DEACTIVATED,
    POD_DISCARDED
}

/**
 * Android `ActivationType`: LONG is the whole activation, SHORT goes on after the prime (attach the
 * pod and insert the cannula only).
 */
enum class DashActivationType { LONG, SHORT }

/**
 * Android `DashOverviewViewModel.onActivatePodClicked`: the stored activation progress says where the
 * wizard starts. From PRIME_COMPLETED on, part 1 is done (or as good as done) and only part 2 is left,
 * so the wizard is the SHORT one. This is how an activation goes on after the app was stopped or
 * crashed in the middle: the progress is stored by the driver after each step.
 */
fun dashActivationType(progress: ActivationProgress): DashActivationType =
    if (progress.isAtLeast(ActivationProgress.PRIME_COMPLETED)) DashActivationType.SHORT else DashActivationType.LONG

/**
 * Android `OmnipodWizardViewModel.initializeActivation`: the pages of an activation, in order.
 *
 * @param needsProfileGate no profile switch exists yet (Android `getRequestedProfile() == null`)
 * @param showInsulinStep more than one insulin is set up
 * @param showSiteLocationStep site rotation for the pump is on
 */
fun dashActivationSteps(
    type: DashActivationType,
    needsProfileGate: Boolean,
    showInsulinStep: Boolean,
    showSiteLocationStep: Boolean
): List<DashWizardStep> = buildList {
    if (needsProfileGate) add(DashWizardStep.PROFILE_GATE)
    if (type == DashActivationType.LONG) {
        add(DashWizardStep.START_POD_ACTIVATION)
        if (showInsulinStep) add(DashWizardStep.SELECT_INSULIN)
        add(DashWizardStep.INITIALIZE_POD)
    }
    if (showSiteLocationStep) add(DashWizardStep.SITE_LOCATION)
    add(DashWizardStep.ATTACH_POD)
    add(DashWizardStep.INSERT_CANNULA)
    add(DashWizardStep.POD_ACTIVATED)
}

/**
 * Android `OmnipodWizardViewModel.initializeDeactivation`. POD_DISCARDED is not in the list: it
 * replaces POD_DEACTIVATED when a deactivation failed and the person discarded the pod.
 */
val DASH_DEACTIVATION_STEPS: List<DashWizardStep> = listOf(
    DashWizardStep.START_POD_DEACTIVATION,
    DashWizardStep.DEACTIVATE_POD,
    DashWizardStep.POD_DEACTIVATED
)

/** The steps that send something to the pod as soon as they show (Android `getActionForStep`). */
val DashWizardStep.isActionStep: Boolean
    get() = this == DashWizardStep.INITIALIZE_POD || this == DashWizardStep.INSERT_CANNULA || this == DashWizardStep.DEACTIVATE_POD

/** Android `DashOmnipodWizardViewModel.getTitleForStep`. */
val DashWizardStep.title: TextRef
    get() = when (this) {
        DashWizardStep.PROFILE_GATE           -> CoreUiStrings.pump_wizard_profile_gate_title
        DashWizardStep.START_POD_ACTIVATION   -> DashStrings.dash_wizard_start_activation_title
        DashWizardStep.SELECT_INSULIN         -> CoreUiStrings.select_insulin
        DashWizardStep.INITIALIZE_POD         -> DashStrings.dash_wizard_initialize_pod_title
        DashWizardStep.SITE_LOCATION          -> CoreUiStrings.site_location
        DashWizardStep.ATTACH_POD             -> DashStrings.dash_wizard_attach_pod_title
        DashWizardStep.INSERT_CANNULA         -> DashStrings.dash_wizard_insert_cannula_title
        DashWizardStep.POD_ACTIVATED          -> DashStrings.dash_wizard_pod_activated_title
        DashWizardStep.START_POD_DEACTIVATION -> DashStrings.dash_wizard_start_deactivation_title
        DashWizardStep.DEACTIVATE_POD         -> DashStrings.dash_wizard_deactivating_pod_title
        DashWizardStep.POD_DEACTIVATED        -> DashStrings.dash_wizard_pod_deactivated_title
        DashWizardStep.POD_DISCARDED          -> DashStrings.dash_wizard_pod_discarded_title
    }

/**
 * Android `DashOmnipodWizardViewModel.getTextForStep`. Null for the pages that draw their own text
 * (the profile gate, the site location).
 */
val DashWizardStep.text: TextRef?
    get() = when (this) {
        DashWizardStep.PROFILE_GATE           -> null
        DashWizardStep.START_POD_ACTIVATION   -> DashStrings.dash_wizard_start_activation_text
        DashWizardStep.SELECT_INSULIN         -> CoreUiStrings.select_insulin_description
        DashWizardStep.INITIALIZE_POD         -> DashStrings.dash_wizard_initialize_pod_text
        DashWizardStep.SITE_LOCATION          -> null
        DashWizardStep.ATTACH_POD             -> DashStrings.dash_wizard_attach_pod_text
        DashWizardStep.INSERT_CANNULA         -> DashStrings.dash_wizard_insert_cannula_text
        DashWizardStep.POD_ACTIVATED          -> DashStrings.dash_wizard_pod_activated_text
        DashWizardStep.START_POD_DEACTIVATION -> DashStrings.dash_wizard_start_deactivation_text
        DashWizardStep.DEACTIVATE_POD         -> DashStrings.dash_wizard_deactivating_pod_text
        DashWizardStep.POD_DEACTIVATED        -> DashStrings.dash_wizard_pod_deactivated_text
        DashWizardStep.POD_DISCARDED          -> DashStrings.dash_wizard_pod_discarded_text
    }

/** Android `ActionState`: what an action step shows. */
sealed interface DashWizardActionState {

    data object Idle : DashWizardActionState
    data object Executing : DashWizardActionState
    data object Success : DashWizardActionState
    data class Error(val message: String) : DashWizardActionState
}
