package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.compose.pump.ProfileGateStepHost
import app.aaps.core.ui.compose.siteRotation.BodyType
import app.aaps.core.ui.compose.siteRotation.SiteLocationStepHost
import app.aaps.pump.omnipod.dashctl.DashStrings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The state of the pod activation and deactivation wizard, the counterpart of Android
 * `OmnipodWizardViewModel` with `DashOmnipodWizardViewModel` (`:pump:omnipod:common` and
 * `:pump:omnipod:dash`, Android only). The same pages, the same order and the same rules:
 *
 * - [startActivation]: the pages of [dashActivationSteps] for the type the stored progress gives
 *   ([dashActivationType]), so an activation stopped by a crash goes on where it was;
 * - an action step ([isActionStep]) runs its action once when it shows ([executeAction]); Next shows
 *   only after a success, Retry and Cancel after a failure, with the reason of the driver;
 * - a failed deactivation offers to discard the pod, which then shows [DashWizardStep.POD_DISCARDED]
 *   in place of [DashWizardStep.POD_DEACTIVATED];
 * - when the pod is activated, the insulin chosen in the wizard is switched to (when it is not the
 *   active one) and the site is saved on the cannula change ([completeActivation]).
 *
 * A plain class over a [CoroutineScope], as `MedtrumPatchWizardModel`. The pod calls go through
 * [DashWizardPod], the app calls through [DashWizardHost]. The scope must live longer than the
 * screen (on iOS the never-cancelled scope of `IosDashDriverHost`), as Android runs this work on
 * `viewModelScope`: a part of the activation, or the records written after it, must not stop
 * because the person left the screen.
 *
 * Fail closed: only one action runs at a time; a second press while one runs does nothing, so a
 * part is never sent twice at once.
 */
class DashPodWizardModel(
    private val pod: DashWizardPod,
    private val host: DashWizardHost,
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope
) : ProfileGateStepHost, SiteLocationStepHost {

    // ------------------------------------------------------------------ navigation

    private val _currentStep = MutableStateFlow<DashWizardStep?>(null)
    val currentStep: StateFlow<DashWizardStep?> = _currentStep.asStateFlow()

    private val _totalSteps = MutableStateFlow(0)
    val totalSteps: StateFlow<Int> = _totalSteps.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    /** Android `canGoBack`: never during the wizard; leaving asks first ("Exit"). */
    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /** True once [prepare] has read the insulins, the site entries and whether a profile is needed. */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** True when the wizard has nothing more to show (Android `OmnipodWizardEvent.Finish`). */
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private val _actionState = MutableStateFlow<DashWizardActionState>(DashWizardActionState.Idle)
    val actionState: StateFlow<DashWizardActionState> = _actionState.asStateFlow()

    private var pages: List<DashWizardStep> = emptyList()

    /** True while an action runs. Set before the launch, so two quick presses start one action only. */
    private var actionRunning = false

    // ------------------------------------------------------------------ insulin

    private val _availableInsulins = MutableStateFlow<List<ICfg>>(emptyList())
    val availableInsulins: StateFlow<List<ICfg>> = _availableInsulins.asStateFlow()

    private val _selectedInsulin = MutableStateFlow<ICfg?>(null)
    val selectedInsulin: StateFlow<ICfg?> = _selectedInsulin.asStateFlow()

    private val _activeInsulinLabel = MutableStateFlow<String?>(null)
    val activeInsulinLabel: StateFlow<String?> = _activeInsulinLabel.asStateFlow()

    /** Android `showInsulinStep`: more than one insulin to choose from. */
    val showInsulinStep: Boolean get() = _availableInsulins.value.size > 1

    val concentrationEnabled: Boolean get() = host.concentrationEnabled

    fun selectInsulin(iCfg: ICfg) {
        _selectedInsulin.value = iCfg
    }

    // ------------------------------------------------------------------ profile gate

    private var needsProfileGate = false

    private val _availableProfiles = MutableStateFlow<List<String>>(emptyList())
    override val availableProfiles: StateFlow<List<String>> = _availableProfiles.asStateFlow()

    private val _selectedProfile = MutableStateFlow<String?>(null)
    override val selectedProfile: StateFlow<String?> = _selectedProfile.asStateFlow()

    // ------------------------------------------------------------------ site location

    private val _siteLocation = MutableStateFlow(TE.Location.NONE)
    override val siteLocation: StateFlow<TE.Location> = _siteLocation.asStateFlow()

    private val _siteArrow = MutableStateFlow(TE.Arrow.NONE)
    override val siteArrow: StateFlow<TE.Arrow> = _siteArrow.asStateFlow()

    private var siteEntries: List<TE> = emptyList()

    // ------------------------------------------------------------------ set up

    /**
     * Android `initializeWizard`: reads the insulins, the site entries and whether a profile is
     * needed, then [ready] is true. Call it before [startActivation].
     */
    suspend fun prepare() {
        val insulins = host.insulins()
        val activeLabel = host.activeInsulinLabel(insulins)
        _availableInsulins.value = insulins
        _activeInsulinLabel.value = activeLabel
        _selectedInsulin.value = insulins.find { it.insulinLabel == activeLabel } ?: insulins.firstOrNull()
        siteEntries = if (host.showSiteLocationStep) host.siteRotationEntries() else emptyList()
        needsProfileGate = host.needsProfileGate()
        if (needsProfileGate) {
            val names = host.profileNames()
            _availableProfiles.value = names
            if (_selectedProfile.value !in names) {
                val active = host.activeProfileName()
                _selectedProfile.value = active.takeIf { it in names } ?: names.firstOrNull()
            }
        }
        _ready.value = true
    }

    /** "Activate Pod": the pages for the stored progress (LONG or SHORT). */
    fun startActivation(type: DashActivationType = dashActivationType(pod.activationProgress)) {
        showPages(dashActivationSteps(type, needsProfileGate, showInsulinStep, host.showSiteLocationStep))
    }

    /** "Deactivate Pod". */
    fun startDeactivation() {
        showPages(DASH_DEACTIVATION_STEPS)
    }

    private fun showPages(list: List<DashWizardStep>) {
        pages = list
        _finished.value = false
        _actionState.value = DashWizardActionState.Idle
        _totalSteps.value = list.size
        _currentStepIndex.value = 0
        _currentStep.value = list.first()
        _canGoBack.value = false
    }

    // ------------------------------------------------------------------ navigation

    /** Next page, or [finished] after the last one. Refused while an action runs. */
    fun moveToNext() {
        if (actionRunning) return
        val index = _currentStepIndex.value
        if (index < pages.size - 1) {
            _actionState.value = DashWizardActionState.Idle
            _currentStepIndex.value = index + 1
            _currentStep.value = pages[index + 1]
            _canGoBack.value = false
        } else {
            _finished.value = true
        }
    }

    /** Android `moveToStep`: replaces the page without moving the index (discard branch). */
    fun moveToStep(step: DashWizardStep) {
        _actionState.value = DashWizardActionState.Idle
        _currentStep.value = step
        _canGoBack.value = false
    }

    fun finish() {
        _finished.value = true
    }

    // ------------------------------------------------------------------ actions

    /**
     * Android `executeAction`: runs the action of the page shown, once. The screen calls it when an
     * action page shows (and again for Retry). Nothing happens on a page without an action, or while
     * an action runs.
     */
    fun executeAction() {
        val step = _currentStep.value ?: return
        if (!step.isActionStep || actionRunning) return
        actionRunning = true
        _actionState.value = DashWizardActionState.Executing
        scope.launch {
            val state = try {
                val result = when (step) {
                    DashWizardStep.INITIALIZE_POD -> pod.activatePart1()
                    DashWizardStep.INSERT_CANNULA -> pod.activatePart2()
                    else                          -> pod.deactivate()
                }
                stateOf(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "Error executing wizard action for step $step", e)
                DashWizardActionState.Error(e.message ?: rh.gs(DashStrings.dash_wizard_unexpected_error))
            } finally {
                actionRunning = false
            }
            _actionState.value = state
        }
    }

    private fun stateOf(result: DashWizardResult): DashWizardActionState =
        if (result.success) DashWizardActionState.Success
        else DashWizardActionState.Error(result.comment.ifBlank { rh.gs(DashStrings.dash_wizard_unexpected_error) })

    /**
     * Android `DeactivatePodStep` discard: the pod state is cleared (nothing is sent, insulin delivery
     * is not stopped), then the "Pod Discarded" page.
     */
    fun discardPod() {
        if (actionRunning) return
        actionRunning = true
        scope.launch {
            try {
                pod.discard()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refused (the stored pod state cannot be read now): nothing was cleared, so the pod
                // is not shown as discarded, and the reason is shown on the page.
                aapsLogger.error(LTag.PUMP, "Discard of the pod refused", e)
                _actionState.value = DashWizardActionState.Error(e.message ?: rh.gs(DashStrings.dash_wizard_unexpected_error))
                return@launch
            } finally {
                actionRunning = false
            }
            moveToStep(DashWizardStep.POD_DISCARDED)
        }
    }

    /**
     * The Finish button of "Pod Activated" (Android `executeInsulinProfileSwitch`, `saveSiteLocation`,
     * `finish`). The insulin switch and the site are done after the pod runs, so a refusal cannot stop
     * the activation; a refused switch says why.
     *
     * The wizard closes only when both are done, as `MedtrumPatchWizardModel.complete`: closing the
     * screen ends the composition, and a switch still running would then be lost, so AAPS would go on
     * with the old insulin. A failure is logged and the wizard still closes (the pod already runs).
     * One run at a time, so a second tap does not switch twice.
     */
    fun completeActivation() {
        if (actionRunning) return
        actionRunning = true
        val selected = _selectedInsulin.value
        val location = _siteLocation.value
        val arrow = _siteArrow.value.takeIf { it != TE.Arrow.NONE }
        scope.launch {
            try {
                if (selected != null && selected.insulinLabel != _activeInsulinLabel.value) {
                    try {
                        host.switchInsulin(selected)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.PUMP, "Failed to switch to the insulin chosen in the pod wizard", e)
                    }
                }
                if (location != TE.Location.NONE) {
                    try {
                        host.saveSiteLocation(location, arrow)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        aapsLogger.error(LTag.PUMP, "Failed to save pod site location", e)
                    }
                }
            } finally {
                actionRunning = false
            }
            finish()
        }
    }

    // ------------------------------------------------------------------ ProfileGateStepHost

    override fun selectProfile(name: String) {
        _selectedProfile.value = name
    }

    /**
     * The Activate button of the profile gate. One run at a time, as [executeAction]: the switch waits
     * for the queue to set the profile, and a second tap meanwhile would make a second profile switch
     * and move one page too far, past the "fill the pod" page.
     */
    override fun activateSelectedProfile() {
        val name = _selectedProfile.value ?: return
        if (actionRunning) return
        actionRunning = true
        scope.launch {
            val switched = try {
                host.activateProfile(name)
            } finally {
                actionRunning = false
            }
            if (switched) {
                needsProfileGate = false
                moveToNext()
            } else aapsLogger.error(LTag.PUMP, "ProfileGate: profile switch to $name failed")
        }
    }

    override fun cancelGate() = finish()

    // ------------------------------------------------------------------ SiteLocationStepHost

    override fun updateSiteLocation(location: TE.Location) {
        _siteLocation.value = location
    }

    override fun updateSiteArrow(arrow: TE.Arrow) {
        _siteArrow.value = arrow
    }

    override fun completeSiteLocation() = moveToNext()

    override fun skipSiteLocation() {
        _siteLocation.value = TE.Location.NONE
        _siteArrow.value = TE.Arrow.NONE
        moveToNext()
    }

    override fun bodyType(): BodyType = host.bodyType()

    override fun siteRotationEntries(): List<TE> = siteEntries
}
