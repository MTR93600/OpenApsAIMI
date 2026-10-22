package app.aaps.pump.medtrum.compose.patch

import app.aaps.core.data.model.TE
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.insulin.InsulinManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileRepository
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.pump.ProfileGateStepHost
import app.aaps.core.ui.compose.pump.activateProfileFromGate
import app.aaps.core.ui.compose.siteRotation.BodyType
import app.aaps.core.ui.compose.siteRotation.SiteLocationStepHost
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.session.MedtrumPatchSession
import app.aaps.pump.medtrum.session.MedtrumPatchSession.PatchResult
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What a "change patch" screen needs, built on the headless [MedtrumPatchSession].
 *
 * The session decides every step and sends every command; this class only turns a button press
 * into the matching session call, and waits where the session says "not yet". It never sends a
 * command by itself: the waits read the pump state and nothing else.
 *
 * Two steps of the Android wizard send nothing to the pump and are not in the session, so they live
 * here:
 * - the profile gate, through the shared [ProfileGateStepHost]: without a running profile there is
 *   no basal to activate the patch with;
 * - the site location, through the shared [SiteLocationStepHost], shown when site rotation for the
 *   pump is turned on. It is saved on the cannula change of the new patch, as Android does.
 *
 * The insulin picker of the Android wizard is not here. The patch runs the active insulin, which is
 * chosen in the insulin settings.
 */
class MedtrumPatchWizardModel(
    private val patch: MedtrumPatchSession,
    private val state: MedtrumSessionState,
    private val profileFunction: ProfileFunction,
    private val profileRepository: ProfileRepository,
    private val insulinManager: InsulinManager,
    private val pumpSync: PumpSync,
    private val persistenceLayer: PersistenceLayer,
    private val preferences: Preferences,
    private val rh: TextResolver,
    private val rxBus: RxBus,
    private val dateUtil: DateUtil,
    private val aapsLogger: AAPSLogger,
    private val scope: CoroutineScope
) : ProfileGateStepHost, SiteLocationStepHost {

    /** What the pump reports, refreshed while the screen is open. */
    data class PumpReadout(val primeProgress: Int, val reservoir: Double, val pumpState: String)

    /** The wizard pages, for the progress indicator. */
    private enum class Page { PROFILE_GATE, BLE_SCAN, PREPARE, PRIME, SITE_LOCATION, ATTACH, ACTIVATE, COMPLETE, CONFIRM_DEACTIVATE, DEACTIVATING, DEACTIVATE_COMPLETE, RETRY }

    private val showingSiteLocation = MutableStateFlow(false)

    /** The retry prompt, shown before anything is asked of the pump - as on Android. */
    private val showingRetryPrompt = MutableStateFlow(false)

    /**
     * The step to show: the session's, SITE_LOCATION while the person picks a site, or
     * RETRY_ACTIVATION while the retry prompt waits for the person.
     */
    val step: StateFlow<PatchStep?> = combine(patch.step, showingSiteLocation, showingRetryPrompt) { sessionStep, site, retry ->
        when {
            retry -> PatchStep.RETRY_ACTIVATION
            site  -> PatchStep.SITE_LOCATION
            else  -> sessionStep
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Why the sequence stopped, from the session. Every further step is refused while it is set. */
    val failure: StateFlow<String?> = patch.failure

    /** A refusal to show once, for example "the patch is not filled yet". */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pump = MutableStateFlow(readPump())
    val pump: StateFlow<PumpReadout> = _pump.asStateFlow()

    private var pages: List<Page> = emptyList()
    private val _progress = MutableStateFlow(0 to 0)

    /** (current index, total pages) for the wizard progress indicator. */
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val mutex = Mutex()
    private var waitJob: Job? = null

    init {
        // The session state is plain fields, not flows, so the screen reads it on a timer.
        scope.launch {
            while (isActive) {
                _pump.value = readPump()
                delay(PUMP_READ_INTERVAL_MS)
            }
        }
        scope.launch { step.collect { updateProgress(it) } }
    }

    val showSiteLocationStep: Boolean get() = preferences.get(BooleanKey.SiteRotationManagePump)

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** "Change patch": opens the page that fits the pump state, as the Android overview does. */
    fun open() {
        when (patchWorkflowStart(state.pumpState, state.patchPrimed)) {
            PatchStep.PREPARE_PATCH    -> startActivation()
            PatchStep.RETRY_ACTIVATION -> openRetryActivation()
            else                       -> startDeactivation()
        }
    }

    /** Opens a new activation: the profile gate, the pump search, or the patch preparation. */
    fun startActivation() = action {
        pages = activationPages()
        loadSiteRotationEntries()
        loadAvailableProfiles()
        report(patch.start())
    }

    /** Opens the deactivation of the running patch. Nothing is sent until the person confirms. */
    fun startDeactivation() = action {
        pages = listOf(Page.CONFIRM_DEACTIVATE, Page.DEACTIVATING, Page.DEACTIVATE_COMPLETE)
        report(patch.startDeactivation())
    }

    /** Opens the retry of an interrupted activation. Nothing is sent until the person goes on. */
    fun openRetryActivation() {
        pages = listOf(Page.RETRY)
        showingRetryPrompt.value = true
    }

    /** Asks the pump where an interrupted activation stands, and carries on from there. */
    fun retryActivation() = action {
        showingRetryPrompt.value = false
        report(patch.retryActivation())
    }

    // ------------------------------------------------------------------
    // Putting a new patch on
    // ------------------------------------------------------------------

    /** A pump was chosen on the search page; its serial number is saved by the search. */
    fun pumpChosen() = action { report(patch.pumpChosen()) }

    /** Connects and waits until the person has filled the patch. */
    fun connectAndWaitForFill() = waitingAction {
        if (report(patch.connectForPatch()) != PatchResult.Ok) return@waitingAction
        waitWhileNotYet(PatchStep.PREPARE_PATCH_CONNECT) { patch.awaitPatchFilled() }
    }

    /** Sends the prime command and waits until priming is finished. */
    fun prime() = waitingAction {
        if (report(patch.prime()) != PatchResult.Ok) return@waitingAction
        waitWhileNotYet(PatchStep.PRIMING) { patch.awaitPrimingFinished() }
    }

    /** After priming: the site location page when site rotation is on, else attach the patch. */
    fun afterPriming() = action {
        if (showSiteLocationStep && patch.step.value == PatchStep.PRIME_COMPLETE) showingSiteLocation.value = true
        else report(patch.attachPatch())
    }

    /** Sends the activate command and waits until the pump runs. */
    fun activate() = waitingAction {
        if (report(patch.activate()) != PatchResult.Ok) return@waitingAction
        waitWhileNotYet(PatchStep.ACTIVATE) { patch.awaitActivated() }
    }

    /** Ends a successful activation and saves the site location on the new cannula change. */
    fun complete() = action {
        saveSiteLocation(state.patchStartTime)
        report(patch.complete())
    }

    // ------------------------------------------------------------------
    // Taking the patch off
    // ------------------------------------------------------------------

    fun deactivate() = waitingAction { report(patch.deactivate()) }

    /** The "discard" button: the patch is written off without asking the pump. */
    fun forceDeactivate() = waitingAction {
        if (showingRetryPrompt.value) {
            // From the retry prompt the session has not been asked anything yet, so open the
            // deactivation first; the forced end then needs no reply from the pump.
            showingRetryPrompt.value = false
            pages = listOf(Page.CONFIRM_DEACTIVATE, Page.DEACTIVATING, Page.DEACTIVATE_COMPLETE)
            report(patch.startDeactivation())
        }
        report(patch.forceDeactivate())
    }

    // ------------------------------------------------------------------
    // Leaving
    // ------------------------------------------------------------------

    /** Gives up. Nothing is sent; a primed or running patch stays as it is. */
    fun cancel() {
        waitJob?.cancel()
        waitJob = null
        showingSiteLocation.value = false
        showingRetryPrompt.value = false
        patch.cancel()
    }

    fun dismissMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------
    // ProfileGateStepHost
    // ------------------------------------------------------------------

    private val _availableProfiles = MutableStateFlow<List<String>>(emptyList())
    override val availableProfiles: StateFlow<List<String>> = _availableProfiles.asStateFlow()
    private val _selectedProfile = MutableStateFlow<String?>(null)
    override val selectedProfile: StateFlow<String?> = _selectedProfile.asStateFlow()

    private suspend fun loadAvailableProfiles() {
        val names = profileRepository.profiles.value.map { it.name }
        _availableProfiles.value = names
        if (_selectedProfile.value !in names) {
            val activeName = profileFunction.getOriginalProfileName()
            _selectedProfile.value = activeName.takeIf { it in names } ?: names.firstOrNull()
        }
    }

    override fun selectProfile(name: String) {
        _selectedProfile.value = name
    }

    /**
     * Activates the chosen profile, then waits until the loop has a running profile before the
     * session checks again. The running profile is written by the command queue after the switch,
     * so asking the session at once would still find none.
     */
    override fun activateSelectedProfile() = action {
        val name = _selectedProfile.value ?: return@action
        val store = profileRepository.profile.value ?: return@action
        val iCfg = insulinManager.insulins.firstOrNull() ?: return@action
        if (!activateProfileFromGate(profileFunction, rh, rxBus, store, name, iCfg, Sources.Medtrum, dateUtil.now())) return@action
        val deadline = dateUtil.now() + PROFILE_WAIT_MS
        while (pumpSync.expectedPumpState().profile == null && dateUtil.now() < deadline) delay(PUMP_READ_INTERVAL_MS)
        report(patch.start())
    }

    override fun cancelGate() = cancel()

    // ------------------------------------------------------------------
    // SiteLocationStepHost
    // ------------------------------------------------------------------

    private val _siteLocation = MutableStateFlow(TE.Location.NONE)
    override val siteLocation: StateFlow<TE.Location> = _siteLocation.asStateFlow()
    private val _siteArrow = MutableStateFlow(TE.Arrow.NONE)
    override val siteArrow: StateFlow<TE.Arrow> = _siteArrow.asStateFlow()
    private var siteRotationEntries: List<TE> = emptyList()

    override fun updateSiteLocation(location: TE.Location) {
        _siteLocation.value = location
    }

    override fun updateSiteArrow(arrow: TE.Arrow) {
        _siteArrow.value = arrow
    }

    override fun completeSiteLocation() = leaveSiteLocation()

    override fun skipSiteLocation() {
        _siteLocation.value = TE.Location.NONE
        _siteArrow.value = TE.Arrow.NONE
        leaveSiteLocation()
    }

    override fun bodyType(): BodyType = BodyType.fromPref(preferences.get(IntKey.SiteRotationUserProfile))

    override fun siteRotationEntries(): List<TE> = siteRotationEntries

    private fun leaveSiteLocation() = action {
        showingSiteLocation.value = false
        report(patch.attachPatch())
    }

    private suspend fun loadSiteRotationEntries() {
        if (!showSiteLocationStep) return
        siteRotationEntries = persistenceLayer.getTherapyEventDataFromTime(dateUtil.now() - T.days(45).msecs(), false)
            .filter { it.type == TE.Type.CANNULA_CHANGE || it.type == TE.Type.SENSOR_CHANGE }
    }

    /** Puts the chosen site on the cannula change written when the patch started, as Android does. */
    private suspend fun saveSiteLocation(activationTimestamp: Long) {
        val location = _siteLocation.value.takeIf { it != TE.Location.NONE }
        val arrow = _siteArrow.value.takeIf { it != TE.Arrow.NONE }
        if (location == null && arrow == null) return
        try {
            persistenceLayer.getTherapyEventDataFromToTime(activationTimestamp, activationTimestamp)
                .firstOrNull { it.type == TE.Type.CANNULA_CHANGE }
                ?.let { persistenceLayer.insertOrUpdateTherapyEvent(it.copy(location = location, arrow = arrow)) }
        } catch (e: Exception) {
            // The site is optional; the patch runs either way.
            aapsLogger.error(LTag.PUMP, "Could not save the site location", e)
        }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** One call at a time, so two quick presses cannot send two commands. */
    private fun action(block: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                _busy.value = true
                try {
                    block()
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /** Like [action], but [cancel] can stop it while it waits for the pump. */
    private fun waitingAction(block: suspend () -> Unit) {
        waitJob = scope.launch {
            mutex.withLock {
                _busy.value = true
                try {
                    block()
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    /**
     * Repeats a wait of the session while it answers "not yet" and the step has not moved. Each
     * wait has its own timeout and sends nothing, so repeating it only keeps reading the pump.
     */
    private suspend fun waitWhileNotYet(waitingStep: PatchStep, wait: suspend () -> PatchResult) {
        while (true) {
            val result = wait()
            if (result is PatchResult.Refused && patch.step.value == waitingStep && patch.failure.value == null) continue
            report(result)
            return
        }
    }

    /** Logs what a call did and shows a refusal once. A failure is shown through [failure]. */
    private fun report(result: PatchResult): PatchResult {
        when (result) {
            PatchResult.Ok          -> Unit
            is PatchResult.Refused -> {
                aapsLogger.debug(LTag.PUMP, "Medtrum patch: refused - ${result.reason}")
                _message.value = result.reason
            }

            is PatchResult.Failed  -> aapsLogger.error(LTag.PUMP, "Medtrum patch: failed - ${result.reason}")
        }
        return result
    }

    private fun readPump() = PumpReadout(
        primeProgress = state.primeProgress,
        reservoir = state.reservoir,
        pumpState = state.pumpState.toString()
    )

    private suspend fun activationPages(): List<Page> {
        val noProfile = pumpSync.expectedPumpState().profile == null
        return activationPages(noProfile)
    }

    private fun activationPages(noProfile: Boolean): List<Page> = buildList {
        if (noProfile) add(Page.PROFILE_GATE)
        if (state.pumpSN == 0L) add(Page.BLE_SCAN)
        add(Page.PREPARE)
        add(Page.PRIME)
        if (showSiteLocationStep) add(Page.SITE_LOCATION)
        add(Page.ATTACH)
        add(Page.ACTIVATE)
        add(Page.COMPLETE)
    }

    private fun updateProgress(step: PatchStep?) {
        val page = when (step) {
            PatchStep.PROFILE_GATE                                         -> Page.PROFILE_GATE
            PatchStep.BLE_SCAN                                             -> Page.BLE_SCAN
            PatchStep.PREPARE_PATCH, PatchStep.PREPARE_PATCH_CONNECT       -> Page.PREPARE
            PatchStep.PRIME, PatchStep.PRIMING, PatchStep.PRIME_COMPLETE   -> Page.PRIME
            PatchStep.SITE_LOCATION                                        -> Page.SITE_LOCATION
            PatchStep.ATTACH_PATCH                                         -> Page.ATTACH
            PatchStep.ACTIVATE, PatchStep.ACTIVATE_COMPLETE                -> Page.ACTIVATE
            PatchStep.COMPLETE                                             -> Page.COMPLETE
            PatchStep.START_DEACTIVATION                                   -> Page.CONFIRM_DEACTIVATE
            PatchStep.DEACTIVATE, PatchStep.FORCE_DEACTIVATION             -> Page.DEACTIVATING
            PatchStep.DEACTIVATION_COMPLETE                                -> Page.DEACTIVATE_COMPLETE
            PatchStep.RETRY_ACTIVATION, PatchStep.RETRY_ACTIVATION_CONNECT -> Page.RETRY
            PatchStep.SELECT_INSULIN, PatchStep.CANCEL, null               -> return
        }
        _progress.value = pages.indexOf(page).coerceAtLeast(0) to pages.size
    }

    companion object {

        private const val PUMP_READ_INTERVAL_MS = 500L

        /** How long to wait for the running profile after the gate switched it. */
        private const val PROFILE_WAIT_MS = 60_000L
    }
}
