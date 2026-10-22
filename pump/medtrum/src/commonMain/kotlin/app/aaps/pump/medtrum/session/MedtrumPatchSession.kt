package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.code.ConnectionState
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.encryption.Crypt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a "put a new patch on" or "take this patch off" screen talks to. It has no UI of its own,
 * so a test can drive it exactly as a screen would.
 *
 * It is the headless twin of the Android `MedtrumPatchViewModel`: the same [PatchStep] order, the
 * same guards, and the same commands, sent through [MedtrumSession]. The parts of the Android
 * wizard that only pick something for the user interface - the insulin picker and the site
 * rotation page - are not here, because they send nothing to the pump.
 *
 * ## Nothing happens on its own
 *
 * Every command is sent by one call, and only by that call. There is no timer, no retry that
 * sends a command again, and no "carry on where we left off". Priming pushes insulin through the
 * cannula and activation starts basal delivery from a patch worn on the body, so each one needs a
 * person to ask for it. After a command fails, this object refuses every further step of the
 * sequence until the caller either gives up ([cancel], [startDeactivation]) or asks the pump what
 * really happened ([retryActivation]).
 *
 * ## The order
 *
 * ```
 * start()                 -> PROFILE_GATE (no basal profile) | BLE_SCAN (no serial) | PREPARE_PATCH
 * pumpChosen()            BLE_SCAN      -> PREPARE_PATCH
 * connectForPatch()       PREPARE_PATCH -> PREPARE_PATCH_CONNECT
 * awaitPatchFilled()      PREPARE_PATCH_CONNECT -> PRIME   (the pump says the patch is filled)
 * prime()                 PRIME         -> PRIMING         (sends the prime command)
 * awaitPrimingFinished()  PRIMING       -> PRIME_COMPLETE  (the pump says it is primed)
 * attachPatch()           PRIME_COMPLETE-> ATTACH_PATCH    (the person puts the patch on the body)
 * activate()              ATTACH_PATCH  -> ACTIVATE        (sends the activate command)
 * awaitActivated()        ACTIVATE      -> ACTIVATE_COMPLETE
 * complete()              ACTIVATE_COMPLETE -> COMPLETE
 * ```
 *
 * Taking a patch off is a second, separate entry point: [startDeactivation] then [deactivate].
 *
 * @param session the shared session that owns the link and sends the packets.
 * @param basalProfile the basal profile the new patch must run, as (rate in U/h, start time in
 *   seconds of the day) pairs. `null` means no profile is set, and then activation is refused -
 *   the same two refusals the Android `MedtrumService.startActivate` makes.
 * @param clock used for the wait deadlines only.
 * @param onStateChanged called after this object changed the stored pump state by itself, so the
 *   driver can save it and write the history it owes. It is not called for state the pump itself
 *   reported; the session already tells the driver about those.
 * @param sleeper the wait between two reads of the pump state. A test replaces it.
 * @param newSessionToken a fresh token for a new patch session, as Android does in
 *   `preparePatchConnect`.
 */
class MedtrumPatchSession(
    private val session: MedtrumSession,
    private val basalProfile: suspend () -> List<Pair<Double, Int>>?,
    private val clock: MedtrumClock,
    private val logger: MedtrumLogger = MedtrumLogger.NO_OP,
    private val onStateChanged: () -> Unit = {},
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val newSessionToken: () -> Long = { Crypt().generateRandomToken() }
) {

    companion object {

        /** How long to wait between two reads of the pump state. */
        const val POLL_INTERVAL_MS: Long = 500

        /** Waiting for the person to fill the patch with insulin. */
        const val FILLED_TIMEOUT_MS: Long = 120_000

        /** Waiting for priming to finish. Android waits for ever with the person watching. */
        const val PRIMING_TIMEOUT_MS: Long = 300_000

        /** Waiting for the pump to report that it is running. */
        const val ACTIVATION_TIMEOUT_MS: Long = 60_000

        /** Waiting for the pump to report that it stopped, after it answered the stop command. */
        const val STOPPED_TIMEOUT_MS: Long = 10_000

        /** Waiting for the link to go down before a reconnect. */
        const val LINK_DOWN_TIMEOUT_MS: Long = 10_000

        /** Android sleeps one second between the disconnect and the forced stop. */
        const val FORCE_DEACTIVATION_SETTLE_MS: Long = 1_000
    }

    /** What a call did. */
    sealed interface PatchResult {

        /** The step was taken. */
        data object Ok : PatchResult

        /** Nothing was sent and nothing changed, because the call does not fit the current step. */
        data class Refused(val reason: String) : PatchResult

        /** The pump did not do what was asked. The sequence stops here. */
        data class Failed(val reason: String) : PatchResult
    }

    /** Where the sequence stands. `null` before [start]. */
    private val _step = MutableStateFlow<PatchStep?>(null)
    val step: StateFlow<PatchStep?> = _step.asStateFlow()

    /**
     * Why the sequence stopped, or `null` while it is healthy. While this is set, every step of
     * the sequence is refused. [cancel], [startDeactivation], [forceDeactivate], [retryActivation]
     * and [start] clear it, because each of them asks the pump again or gives up.
     */
    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private val state: MedtrumSessionState get() = session.state

    /** Steps in which a command is in flight or a patch is being made ready. [start] refuses then. */
    private val busySteps = setOf(
        PatchStep.PRIME, PatchStep.PRIMING, PatchStep.PRIME_COMPLETE,
        PatchStep.ATTACH_PATCH, PatchStep.ACTIVATE, PatchStep.ACTIVATE_COMPLETE,
        PatchStep.START_DEACTIVATION, PatchStep.DEACTIVATE, PatchStep.FORCE_DEACTIVATION,
        PatchStep.RETRY_ACTIVATION, PatchStep.RETRY_ACTIVATION_CONNECT
    )

    /** Steps from which Android does not drop the link when the person cancels. */
    private val stepsThatKeepTheLinkOnCancel = setOf(
        PatchStep.PROFILE_GATE, PatchStep.PREPARE_PATCH, PatchStep.START_DEACTIVATION,
        PatchStep.DEACTIVATE, PatchStep.FORCE_DEACTIVATION, PatchStep.DEACTIVATION_COMPLETE
    )

    // ------------------------------------------------------------------
    // Putting a new patch on
    // ------------------------------------------------------------------

    /**
     * Opens a new activation and says where it starts. Mirrors the Android
     * `checkAndPreparePatchStart`: without a basal profile the person has to pick one first,
     * without a serial number a pump has to be found first.
     *
     * Refused while a patch is running: that patch has to come off first (see [startDeactivation]).
     */
    suspend fun start(): PatchResult {
        val current = _step.value
        if (current in busySteps) return refuse("a patch sequence is already running at $current")
        if (patchIsOnTheBody()) return refuse("a patch is still running (${state.pumpState}) - deactivate it first")
        _failure.value = null
        val next = when {
            buildProfileBytes() == null -> PatchStep.PROFILE_GATE
            state.pumpSN == 0L          -> PatchStep.BLE_SCAN
            else                        -> PatchStep.PREPARE_PATCH
        }
        moveTo(next)
        return PatchResult.Ok
    }

    /**
     * A pump was found and its serial number saved, so the patch can be prepared.
     *
     * Refused while the serial is still missing, because the next connect would have nothing to
     * connect to.
     */
    fun pumpChosen(): PatchResult {
        if (_step.value != PatchStep.BLE_SCAN) return refuse("pumpChosen is only allowed at BLE_SCAN")
        if (state.pumpSN == 0L) return refuse("no serial number was saved")
        moveTo(PatchStep.PREPARE_PATCH)
        return PatchResult.Ok
    }

    /**
     * Connects to the patch the person just filled with insulin. A new patch gets a new session
     * token first, as Android does.
     *
     * Can be called again from PREPARE_PATCH_CONNECT to try the same connect once more; the token
     * is then kept, because it is the same patch session. A failed connect may be tried again
     * because nothing has been delivered at this point - the patch is not even primed yet.
     */
    suspend fun connectForPatch(): PatchResult {
        val from = _step.value
        if (from != PatchStep.PREPARE_PATCH && from != PatchStep.PREPARE_PATCH_CONNECT)
            return refuse("connectForPatch is only allowed at PREPARE_PATCH")
        _failure.value = null
        if (isLinkUp()) return refuse("already connected")
        if (from == PatchStep.PREPARE_PATCH) state.patchSessionToken = newSessionToken()
        moveTo(PatchStep.PREPARE_PATCH_CONNECT)
        val connected = session.connect("patch activation")
        if (!connected) return fail("could not connect to the pump")
        return PatchResult.Ok
    }

    /**
     * Waits until the pump says the patch is filled with insulin. Only then may it be primed.
     *
     * A timeout is not a failure: nothing was sent, the step does not move, and the caller can
     * wait again while the person keeps filling.
     */
    suspend fun awaitPatchFilled(timeoutMs: Long = FILLED_TIMEOUT_MS): PatchResult {
        if (_step.value != PatchStep.PREPARE_PATCH_CONNECT) return refuse("not waiting for a filled patch")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        return when (val outcome = awaitPumpState(timeoutMs) { pumpState ->
            when {
                pumpState == MedtrumPumpState.FILLED && !state.patchPrimed -> Progress.DONE
                pumpState == MedtrumPumpState.NONE ||
                    pumpState == MedtrumPumpState.IDLE ||
                    pumpState == MedtrumPumpState.STOPPED                  -> Progress.KEEP_WAITING

                else                                                      -> Progress.UNEXPECTED
            }
        }) {
            Progress.DONE         -> {
                moveTo(PatchStep.PRIME)
                PatchResult.Ok
            }

            Progress.KEEP_WAITING -> refuse("the patch is not filled yet (${state.pumpState})")
            Progress.UNEXPECTED,
            Progress.LINK_LOST    -> fail(outcome.toReason("waiting for a filled patch"))
        }
    }

    /**
     * Sends the prime command. The person must have been told not to put the patch on the body
     * yet: priming pushes insulin through the cannula.
     *
     * When the pump is already priming nothing is sent, exactly as Android does.
     */
    suspend fun prime(): PatchResult {
        if (_step.value != PatchStep.PRIME) return refuse("prime is only allowed at PRIME")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        if (!isReady()) return refuse("the pump is not connected")
        if (state.pumpState == MedtrumPumpState.PRIMING) {
            logger.debug("PUMP", "prime: the pump is already priming, nothing sent")
            moveTo(PatchStep.PRIMING)
            return PatchResult.Ok
        }
        if (state.pumpState != MedtrumPumpState.FILLED)
            return refuse("the pump is ${state.pumpState}, not a filled patch")
        moveTo(PatchStep.PRIMING)
        if (!session.prime()) return fail("the pump did not answer the prime command")
        return PatchResult.Ok
    }

    /**
     * Waits until the pump says the patch is primed.
     *
     * A timeout is not a failure and nothing is sent again: the step stays at PRIMING and the
     * caller can wait once more.
     */
    suspend fun awaitPrimingFinished(timeoutMs: Long = PRIMING_TIMEOUT_MS): PatchResult {
        if (_step.value != PatchStep.PRIMING) return refuse("not priming")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        return when (val outcome = awaitPumpState(timeoutMs) { pumpState ->
            when (pumpState) {
                MedtrumPumpState.PRIMED, MedtrumPumpState.EJECTED  -> Progress.DONE
                MedtrumPumpState.FILLED, MedtrumPumpState.PRIMING,
                MedtrumPumpState.NONE, MedtrumPumpState.IDLE       -> Progress.KEEP_WAITING

                else                                               -> Progress.UNEXPECTED
            }
        }) {
            Progress.DONE         -> {
                moveTo(PatchStep.PRIME_COMPLETE)
                PatchResult.Ok
            }

            Progress.KEEP_WAITING -> refuse("still priming (${state.pumpState})")
            Progress.UNEXPECTED,
            Progress.LINK_LOST    -> fail(outcome.toReason("waiting for the end of priming"))
        }
    }

    /**
     * The screen now tells the person to put the patch on the body. Nothing is sent.
     *
     * Only allowed once priming finished, because the patch must not be worn while it primes.
     */
    fun attachPatch(): PatchResult {
        if (_step.value != PatchStep.PRIME_COMPLETE) return refuse("the patch is not primed yet")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        moveTo(PatchStep.ATTACH_PATCH)
        return PatchResult.Ok
    }

    /**
     * Sends the activate command: the patch starts giving basal insulin from the body.
     *
     * Refused without a basal profile, and refused when the profile cannot be turned into pump
     * bytes - the two refusals of the Android `MedtrumService.startActivate`. No profile is ever
     * invented here.
     */
    suspend fun activate(): PatchResult {
        if (_step.value != PatchStep.ATTACH_PATCH) return refuse("activate is only allowed once the patch is attached")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        if (!isReady()) return refuse("the pump is not connected")
        if (state.pumpState != MedtrumPumpState.PRIMED && state.pumpState != MedtrumPumpState.EJECTED)
            return refuse("the pump is ${state.pumpState}, not a primed patch")
        val values = basalProfile() ?: return refuse("no basal profile is set, cannot activate the patch")
        val bytes = state.buildMedtrumProfileArray(values) ?: return refuse("the basal profile cannot be sent to this pump")
        moveTo(PatchStep.ACTIVATE)
        if (!session.activate(bytes)) return fail("the pump did not answer the activate command")
        return PatchResult.Ok
    }

    /** Waits until the pump says it is running. A timeout leaves the step at ACTIVATE. */
    suspend fun awaitActivated(timeoutMs: Long = ACTIVATION_TIMEOUT_MS): PatchResult {
        if (_step.value != PatchStep.ACTIVATE) return refuse("not activating")
        _failure.value?.let { return refuse("the sequence stopped: $it") }
        return when (val outcome = awaitPumpState(timeoutMs) { pumpState ->
            when (pumpState) {
                MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT -> Progress.DONE
                MedtrumPumpState.PRIMED, MedtrumPumpState.EJECTED,
                MedtrumPumpState.NONE                                -> Progress.KEEP_WAITING

                else                                                 -> Progress.UNEXPECTED
            }
        }) {
            Progress.DONE         -> {
                moveTo(PatchStep.ACTIVATE_COMPLETE)
                PatchResult.Ok
            }

            Progress.KEEP_WAITING -> refuse("the pump has not started yet (${state.pumpState})")
            Progress.UNEXPECTED,
            Progress.LINK_LOST    -> fail(outcome.toReason("waiting for the patch to start"))
        }
    }

    /** Ends the activation and drops the link, as the Android `handleComplete` does. */
    fun complete(): PatchResult {
        if (_step.value != PatchStep.ACTIVATE_COMPLETE) return refuse("the patch is not activated")
        session.disconnect("patch activation complete")
        moveTo(PatchStep.COMPLETE)
        return PatchResult.Ok
    }

    /**
     * Gives up the current sequence. Nothing is sent to the pump, so a patch that is already
     * primed or running stays as it is - [startDeactivation] is how it comes off.
     */
    fun cancel() {
        val from = _step.value
        if (from != null && from !in stepsThatKeepTheLinkOnCancel) session.disconnect("patch sequence cancelled")
        _failure.value = null
        moveTo(PatchStep.CANCEL)
    }

    /**
     * Asks the pump where the activation really stands and carries on from there. This is the
     * only way back in after a failure, and it is what the Android RETRY_ACTIVATION step is for:
     * the app was stopped, or the link was lost, somewhere between filling and activation, and
     * only the pump knows what was done.
     *
     * It sends no prime and no activate. It reconnects, reads the state the pump reports, and
     * puts the sequence on the matching step. The next command is still asked for by a person.
     */
    suspend fun retryActivation(): PatchResult {
        _failure.value = null
        moveTo(PatchStep.RETRY_ACTIVATION)
        session.disconnect("retry activation")
        if (!awaitLinkDown()) return fail("the old connection did not close")
        moveTo(PatchStep.RETRY_ACTIVATION_CONNECT)
        // Android clears the state first so that what the pump reports is the only truth left.
        state.pumpState = MedtrumPumpState.NONE
        if (!session.connect("retry activation")) return fail("could not connect to the pump")
        val resumed = when (state.pumpState) {
            MedtrumPumpState.FILLED                              -> PatchStep.PRIME
            MedtrumPumpState.PRIMING                             -> PatchStep.PRIMING
            MedtrumPumpState.PRIMED, MedtrumPumpState.EJECTED    -> PatchStep.PRIME_COMPLETE
            MedtrumPumpState.ACTIVE, MedtrumPumpState.ACTIVE_ALT -> PatchStep.ACTIVATE_COMPLETE
            else                                                 -> return fail("the pump reports ${state.pumpState}, the activation cannot be resumed")
        }
        moveTo(resumed)
        return PatchResult.Ok
    }

    // ------------------------------------------------------------------
    // Taking the patch off
    // ------------------------------------------------------------------

    /** Opens the deactivation. Nothing is sent; the screen asks the person to confirm. */
    fun startDeactivation(): PatchResult {
        if (_step.value == PatchStep.DEACTIVATE) return refuse("a deactivation is already running")
        _failure.value = null
        moveTo(PatchStep.START_DEACTIVATION)
        return PatchResult.Ok
    }

    /**
     * Stops the patch. The shared session cancels a running temp basal first, syncs the records of
     * this patch, and only then sends the stop command - that order is the Android one and is kept
     * in [MedtrumSession.deactivatePatch].
     *
     * When the pump is in a fault state, or has nothing running at all, the stop command would not
     * work. Android then forces the end: drop the link, wait a second, and write the patch off
     * locally. That path sends nothing to the pump and is reproduced here.
     */
    suspend fun deactivate(): PatchResult {
        if (_step.value != PatchStep.START_DEACTIVATION) return refuse("deactivate is only allowed once deactivation was started")
        if (needsForcedEnd()) return forceDeactivateFrom(PatchStep.START_DEACTIVATION)
        if (!isReady() && !session.connect("deactivate patch")) return fail("could not connect to the pump")
        moveTo(PatchStep.DEACTIVATE)
        if (!session.deactivatePatch()) return fail("the pump did not answer the stop command")
        // The pump answered the stop command, so the patch is off. Android waits for the pump to
        // report it; if that report does not come, write it down anyway, as the forced path does,
        // or the driver would keep the sequence numbers of a patch that no longer exists.
        awaitPumpState(STOPPED_TIMEOUT_MS) { if (it == MedtrumPumpState.STOPPED) Progress.DONE else Progress.KEEP_WAITING }
        if (state.pumpState != MedtrumPumpState.STOPPED) {
            logger.warn("PUMP", "deactivate: the pump did not report STOPPED, writing it down anyway")
            state.pumpState = MedtrumPumpState.STOPPED
            onStateChanged()
        }
        moveTo(PatchStep.DEACTIVATION_COMPLETE)
        return PatchResult.Ok
    }

    /**
     * Writes the patch off without asking the pump. This is the Android "discard" button: the
     * patch is in a fault state, or was never started, so no stop command can work and the person
     * throws it away.
     *
     * Also allowed after a stop command failed: Android offers "discard" on the deactivating page
     * when the deactivation ends in an error. Not while a stop command is still running - only
     * once [failure] says it ended.
     */
    suspend fun forceDeactivate(): PatchResult {
        val from = _step.value
        val failedStop = from == PatchStep.DEACTIVATE && _failure.value != null
        if (from != PatchStep.START_DEACTIVATION && from != PatchStep.RETRY_ACTIVATION && from != PatchStep.RETRY_ACTIVATION_CONNECT && !failedStop)
            return refuse("forceDeactivate is only allowed from the deactivation or retry step, or after a failed stop")
        return forceDeactivateFrom(from)
    }

    private suspend fun forceDeactivateFrom(from: PatchStep?): PatchResult {
        logger.debug("PUMP", "deactivate: forced end from $from, state ${state.pumpState}")
        _failure.value = null
        moveTo(PatchStep.FORCE_DEACTIVATION)
        session.disconnect("forced deactivation")
        sleeper(FORCE_DEACTIVATION_SETTLE_MS)
        state.pumpState = MedtrumPumpState.STOPPED
        onStateChanged()
        moveTo(PatchStep.DEACTIVATION_COMPLETE)
        return PatchResult.Ok
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /** Android: a fault state, or nothing running yet. The stop command does not work then. */
    private fun needsForcedEnd(): Boolean {
        val pumpState = state.pumpState
        return (pumpState >= MedtrumPumpState.OCCLUSION && pumpState <= MedtrumPumpState.NO_CALIBRATION) ||
            pumpState <= MedtrumPumpState.FILLED
    }

    /** True when a patch is on the body: anything from running to a fault, but not stopped. */
    private fun patchIsOnTheBody(): Boolean =
        state.pumpState >= MedtrumPumpState.ACTIVE && state.pumpState != MedtrumPumpState.STOPPED

    private fun isLinkUp(): Boolean =
        state.connectionState == ConnectionState.CONNECTED || state.connectionState == ConnectionState.CONNECTING

    private fun isReady(): Boolean =
        session.phase == MedtrumSession.Phase.READY && state.connectionState == ConnectionState.CONNECTED

    private suspend fun awaitLinkDown(): Boolean {
        val deadline = clock.nowMillis() + LINK_DOWN_TIMEOUT_MS
        while (isLinkUp()) {
            if (clock.nowMillis() >= deadline) return false
            sleeper(POLL_INTERVAL_MS)
        }
        return true
    }

    private enum class Progress { DONE, KEEP_WAITING, UNEXPECTED, LINK_LOST }

    private fun Progress.toReason(what: String): String = when (this) {
        Progress.LINK_LOST -> "the connection was lost while $what"
        else               -> "the pump reports ${state.pumpState} while $what"
    }

    /**
     * Reads the pump state until [classify] is happy, the link drops or [timeoutMs] is over.
     * Nothing is sent: this only watches what the pump reports by itself.
     */
    private suspend fun awaitPumpState(timeoutMs: Long, classify: (MedtrumPumpState) -> Progress): Progress {
        val deadline = clock.nowMillis() + timeoutMs
        while (true) {
            val verdict = classify(state.pumpState)
            if (verdict != Progress.KEEP_WAITING) return verdict
            if (!isLinkUp()) return Progress.LINK_LOST
            if (clock.nowMillis() >= deadline) return Progress.KEEP_WAITING
            sleeper(POLL_INTERVAL_MS)
        }
    }

    /** The profile bytes the pump would get, or null when there is no profile or it does not fit. */
    private suspend fun buildProfileBytes(): ByteArray? = basalProfile()?.let { state.buildMedtrumProfileArray(it) }

    private fun moveTo(next: PatchStep) {
        logger.debug("PUMP", "patch step: ${_step.value} -> $next")
        _step.value = next
    }

    private fun refuse(reason: String): PatchResult {
        logger.debug("PUMP", "patch step refused at ${_step.value}: $reason")
        return PatchResult.Refused(reason)
    }

    private fun fail(reason: String): PatchResult {
        logger.error("PUMP", "patch sequence stopped at ${_step.value}: $reason")
        _failure.value = reason
        return PatchResult.Failed(reason)
    }
}
