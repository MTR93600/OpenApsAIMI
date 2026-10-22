package app.aaps.pump.medtrum.session

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumRecordSync
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.CommandType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.code.PatchStep
import app.aaps.pump.medtrum.session.MedtrumPatchSession.PatchResult
import app.aaps.pump.medtrum.util.MedtrumTimeUtil
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the whole patch machine over the fake transport, the way a screen would.
 *
 * Every test checks two things: where the machine ended up, and what it put on the wire. A step
 * that must not send a command is checked by the absence of its opcode, not only by the result.
 */
class MedtrumPatchSessionTest {

    private val startMs = 1_700_000_000_000L
    private var clockMs = startMs
    private val clock = MedtrumClock { clockMs }

    /** The profile the pump should run. A test can take it away to check the activation refusal. */
    private var profileValues: List<Pair<Double, Int>>? = listOf(1.0 to 0, 0.8 to 43_200)

    private var stateSavedCount = 0

    private val fake = FakeMedtrumBleTransport()

    private val state = MedtrumSessionState(MedtrumLogger.NO_OP, clock).apply {
        pumpSN = 0x12345678L
        patchSessionToken = 0xABCDEF01L
    }

    private val session = MedtrumSession(
        transport = fake,
        state = state,
        recordSync = MedtrumRecordSync.NO_OP,
        logger = MedtrumLogger.NO_OP,
        clock = clock,
        timeUtil = MedtrumTimeUtil(clock::nowMillis),
        timeZoneOffsetMinutes = { 0 }
    ).also { fake.setCallback(it) }

    private val patch = MedtrumPatchSession(
        session = session,
        basalProfile = { profileValues },
        clock = clock,
        logger = MedtrumLogger.NO_OP,
        onStateChanged = { stateSavedCount++ },
        // Virtual time: a wait moves the clock instead of sleeping, so a timeout is reached at once.
        sleeper = { ms -> clockMs += ms },
        newSessionToken = { 0x11223344L }
    )

    // -- helpers -------------------------------------------------------------

    /** Script the auth handshake. [reported] is the pump state the pump answers with. */
    private fun scriptAuth(reported: MedtrumPumpState) {
        val pumpSeconds = ((clockMs - MedtrumTimeUtil.PUMP_EPOCH_MILLIS) / 1000).toInt()
        val pumpTimeBytes = byteArrayOf(
            pumpSeconds.toByte(), (pumpSeconds shr 8).toByte(),
            (pumpSeconds shr 16).toByte(), (pumpSeconds shr 24).toByte()
        )
        fake.responses[CommandType.AUTH_REQ.code] = successResponse(CommandType.AUTH_REQ.code, 0, 0, 1, 2, 3)
        fake.responses[CommandType.GET_DEVICE_TYPE.code] =
            successResponse(CommandType.GET_DEVICE_TYPE.code, 1, 0x12, 0x34, 0x56, 0x78)
        fake.responses[CommandType.GET_TIME.code] = successResponse(CommandType.GET_TIME.code) + pumpTimeBytes
        fake.responses[CommandType.SET_TIME.code] = successResponse(CommandType.SET_TIME.code) + pumpTimeBytes
        fake.responses[CommandType.SET_TIME_ZONE.code] = successResponse(CommandType.SET_TIME_ZONE.code) + pumpTimeBytes
        fake.responses[CommandType.SUBSCRIBE.code] = successResponse(CommandType.SUBSCRIBE.code)
        reportPumpState(reported)
    }

    /** What the pump answers to the sync step of the handshake: its current state. */
    private fun reportPumpState(reported: MedtrumPumpState) {
        fake.responses[CommandType.SYNCHRONIZE.code] =
            successResponse(CommandType.SYNCHRONIZE.code, reported.state, 0, 0, 0)
    }

    private fun scriptPatchCommands() {
        fake.responses[CommandType.PRIME.code] = successResponse(CommandType.PRIME.code)
        fake.responses[CommandType.ACTIVATE.code] = successResponse(CommandType.ACTIVATE.code) + ByteArray(19)
        fake.responses[CommandType.CANCEL_TEMP_BASAL.code] = successResponse(CommandType.CANCEL_TEMP_BASAL.code) + ByteArray(11)
        fake.responses[CommandType.STOP_PATCH.code] = successResponse(CommandType.STOP_PATCH.code) + ByteArray(4)
    }

    private fun sentOpcodes(): List<Byte> = fake.sentMessages.mapNotNull { it.getOrNull(0) }

    private fun sent(command: CommandType): Boolean = sentOpcodes().contains(command.code)

    private fun assertOk(result: PatchResult) = assertTrue(result is PatchResult.Ok, "expected Ok but was $result")

    private fun assertRefused(result: PatchResult) =
        assertTrue(result is PatchResult.Refused, "expected Refused but was $result")

    private fun assertFailed(result: PatchResult) = assertTrue(result is PatchResult.Failed, "expected Failed but was $result")

    /** Walk the machine up to the step where the patch is filled and the pump is connected. */
    private suspend fun connectAndFill() {
        scriptAuth(MedtrumPumpState.FILLED)
        scriptPatchCommands()
        assertOk(patch.start())
        assertEquals(PatchStep.PREPARE_PATCH, patch.step.value)
        assertOk(patch.connectForPatch())
        assertOk(patch.awaitPatchFilled())
        assertEquals(PatchStep.PRIME, patch.step.value)
    }

    // -- the happy path ------------------------------------------------------

    @Test
    fun happyPathPrimesThenActivatesInOrder() = runBlocking {
        connectAndFill()

        assertOk(patch.prime())
        assertEquals(PatchStep.PRIMING, patch.step.value)
        assertTrue(sent(CommandType.PRIME))

        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        assertEquals(PatchStep.PRIME_COMPLETE, patch.step.value)

        assertOk(patch.attachPatch())
        assertEquals(PatchStep.ATTACH_PATCH, patch.step.value)

        assertOk(patch.activate())
        assertEquals(PatchStep.ACTIVATE, patch.step.value)
        assertTrue(sent(CommandType.ACTIVATE))

        state.pumpState = MedtrumPumpState.ACTIVE
        assertOk(patch.awaitActivated())
        assertEquals(PatchStep.ACTIVATE_COMPLETE, patch.step.value)

        assertOk(patch.complete())
        assertEquals(PatchStep.COMPLETE, patch.step.value)
        assertNull(patch.failure.value)

        // The prime command went out before the activate command and each went out once.
        val opcodes = sentOpcodes()
        assertTrue(opcodes.indexOf(CommandType.PRIME.code) < opcodes.indexOf(CommandType.ACTIVATE.code))
        assertEquals(1, opcodes.count { it == CommandType.PRIME.code })
        assertEquals(1, opcodes.count { it == CommandType.ACTIVATE.code })
    }

    @Test
    fun activationWritesTheRealPatchStartTime() = runBlocking {
        connectAndFill()
        assertOk(patch.prime())
        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        assertOk(patch.attachPatch())
        assertOk(patch.activate())
        // Android stamps the new patch with the phone clock. A zero here would date the patch
        // and its cannula change event to 1970.
        assertEquals(clockMs, state.patchStartTime)
    }

    // -- nothing happens out of order ---------------------------------------

    @Test
    fun everyCommandIsRefusedBeforeTheSequenceStarts() = runBlocking {
        scriptAuth(MedtrumPumpState.FILLED)
        scriptPatchCommands()
        assertRefused(patch.connectForPatch())
        assertRefused(patch.prime())
        assertRefused(patch.awaitPrimingFinished())
        assertRefused(patch.attachPatch())
        assertRefused(patch.activate())
        assertRefused(patch.awaitActivated())
        assertRefused(patch.complete())
        assertRefused(patch.pumpChosen())
        assertRefused(patch.forceDeactivate())
        assertNull(patch.step.value)
        assertTrue(fake.sentMessages.isEmpty())
    }

    @Test
    fun primeIsRefusedWhileThePatchIsNotFilled() = runBlocking {
        scriptAuth(MedtrumPumpState.IDLE)
        scriptPatchCommands()
        assertOk(patch.start())
        assertOk(patch.connectForPatch())
        // The pump says IDLE, so the wait times out and the step stays where it was.
        assertRefused(patch.awaitPatchFilled())
        assertEquals(PatchStep.PREPARE_PATCH_CONNECT, patch.step.value)
        assertRefused(patch.prime())
        assertFalse(sent(CommandType.PRIME))
    }

    @Test
    fun activateIsRefusedBeforePrimingFinished() = runBlocking {
        connectAndFill()
        assertRefused(patch.activate())
        assertOk(patch.prime())
        assertRefused(patch.attachPatch())
        assertRefused(patch.activate())
        assertFalse(sent(CommandType.ACTIVATE))
    }

    @Test
    fun activateIsRefusedBeforeThePatchIsAttached() = runBlocking {
        connectAndFill()
        assertOk(patch.prime())
        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        // The person has not been told to put the patch on the body yet.
        assertRefused(patch.activate())
        assertFalse(sent(CommandType.ACTIVATE))
    }

    @Test
    fun startIsRefusedWhileAPatchIsRunning() = runBlocking {
        state.pumpState = MedtrumPumpState.ACTIVE
        assertRefused(patch.start())
        assertNull(patch.step.value)
        assertTrue(fake.sentMessages.isEmpty())
    }

    // -- a failure stops the sequence ---------------------------------------

    @Test
    fun aFailedPrimeStopsTheSequence() = runBlocking {
        connectAndFill()
        fake.responses[CommandType.PRIME.code] = errorResponse(CommandType.PRIME.code)

        assertFailed(patch.prime())
        assertEquals(PatchStep.PRIMING, patch.step.value)
        assertNotNull(patch.failure.value)

        // Nothing moves on, and above all nothing is primed or activated again.
        assertRefused(patch.awaitPrimingFinished())
        assertRefused(patch.attachPatch())
        assertRefused(patch.activate())
        assertEquals(1, sentOpcodes().count { it == CommandType.PRIME.code })
        assertFalse(sent(CommandType.ACTIVATE))
    }

    @Test
    fun aFailedActivateStopsTheSequence() = runBlocking {
        connectAndFill()
        assertOk(patch.prime())
        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        assertOk(patch.attachPatch())
        fake.responses[CommandType.ACTIVATE.code] = errorResponse(CommandType.ACTIVATE.code)

        assertFailed(patch.activate())
        assertEquals(PatchStep.ACTIVATE, patch.step.value)
        assertNotNull(patch.failure.value)

        assertRefused(patch.awaitActivated())
        assertRefused(patch.complete())
        assertEquals(1, sentOpcodes().count { it == CommandType.ACTIVATE.code })
    }

    // -- no profile means no activation -------------------------------------

    @Test
    fun theSequenceStartsAtTheProfileGateWithoutABasalProfile() = runBlocking {
        profileValues = null
        assertOk(patch.start())
        assertEquals(PatchStep.PROFILE_GATE, patch.step.value)
        assertTrue(fake.sentMessages.isEmpty())
    }

    @Test
    fun activationIsRefusedWhenTheProfileIsGone() = runBlocking {
        connectAndFill()
        assertOk(patch.prime())
        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        assertOk(patch.attachPatch())

        profileValues = null
        assertRefused(patch.activate())
        assertEquals(PatchStep.ATTACH_PATCH, patch.step.value)
        assertFalse(sent(CommandType.ACTIVATE))
        assertNull(patch.failure.value)
    }

    @Test
    fun activationIsRefusedWhenTheProfileDoesNotFitThePump() = runBlocking {
        connectAndFill()
        assertOk(patch.prime())
        state.pumpState = MedtrumPumpState.PRIMED
        assertOk(patch.awaitPrimingFinished())
        assertOk(patch.attachPatch())

        // 300 U/h is far over what the three byte slot can carry.
        profileValues = listOf(300.0 to 0)
        assertRefused(patch.activate())
        assertEquals(PatchStep.ATTACH_PATCH, patch.step.value)
        assertFalse(sent(CommandType.ACTIVATE))
    }

    // -- taking the patch off ------------------------------------------------

    @Test
    fun deactivationCancelsARunningTempBasalBeforeStoppingThePatch() = runBlocking {
        scriptAuth(MedtrumPumpState.ACTIVE)
        scriptPatchCommands()
        assertTrue(session.connect("test"))
        state.lastBasalType = BasalType.ABSOLUTE_TEMP
        assertTrue(state.tempBasalInProgress)

        assertOk(patch.startDeactivation())
        assertEquals(PatchStep.START_DEACTIVATION, patch.step.value)
        assertOk(patch.deactivate())

        val opcodes = sentOpcodes()
        assertTrue(sent(CommandType.CANCEL_TEMP_BASAL))
        assertTrue(sent(CommandType.STOP_PATCH))
        assertTrue(opcodes.indexOf(CommandType.CANCEL_TEMP_BASAL.code) < opcodes.indexOf(CommandType.STOP_PATCH.code))
        assertEquals(PatchStep.DEACTIVATION_COMPLETE, patch.step.value)
        assertEquals(MedtrumPumpState.STOPPED, state.pumpState)
    }

    @Test
    fun aSecondDeactivateSendsNothing() = runBlocking {
        scriptAuth(MedtrumPumpState.ACTIVE)
        scriptPatchCommands()
        assertTrue(session.connect("test"))
        assertOk(patch.startDeactivation())
        assertOk(patch.deactivate())
        val afterFirst = sentOpcodes().count { it == CommandType.STOP_PATCH.code }

        assertRefused(patch.deactivate())
        assertEquals(PatchStep.DEACTIVATION_COMPLETE, patch.step.value)
        assertEquals(afterFirst, sentOpcodes().count { it == CommandType.STOP_PATCH.code })
    }

    @Test
    fun aFaultedPatchIsWrittenOffWithoutACommand() = runBlocking {
        scriptAuth(MedtrumPumpState.PATCH_FAULT)
        scriptPatchCommands()
        assertTrue(session.connect("test"))

        assertOk(patch.startDeactivation())
        assertOk(patch.deactivate())

        // The stop command does not work on a faulted patch, so Android does not send it either.
        assertFalse(sent(CommandType.STOP_PATCH))
        assertEquals(MedtrumPumpState.STOPPED, state.pumpState)
        assertEquals(PatchStep.DEACTIVATION_COMPLETE, patch.step.value)
        assertTrue(stateSavedCount > 0)
    }

    /** The Android "discard" after a failed stop: the patch is written off, nothing more is sent. */
    @Test
    fun aPatchWhoseStopFailedCanBeDiscarded() = runBlocking {
        scriptAuth(MedtrumPumpState.ACTIVE)
        scriptPatchCommands()
        fake.responses[CommandType.STOP_PATCH.code] = errorResponse(CommandType.STOP_PATCH.code)
        assertTrue(session.connect("test"))

        assertOk(patch.startDeactivation())
        assertFailed(patch.deactivate())
        assertEquals(PatchStep.DEACTIVATE, patch.step.value)
        val stopsSent = sentOpcodes().count { it == CommandType.STOP_PATCH.code }

        assertOk(patch.forceDeactivate())
        assertEquals(PatchStep.DEACTIVATION_COMPLETE, patch.step.value)
        assertEquals(MedtrumPumpState.STOPPED, state.pumpState)
        assertEquals(stopsSent, sentOpcodes().count { it == CommandType.STOP_PATCH.code })
    }

    // -- coming back after a failure ----------------------------------------

    @Test
    fun retryActivationResumesFromWhatThePumpReports() = runBlocking {
        connectAndFill()
        fake.responses[CommandType.PRIME.code] = errorResponse(CommandType.PRIME.code)
        assertFailed(patch.prime())

        // The pump had in fact finished priming.
        reportPumpState(MedtrumPumpState.PRIMED)
        val sentBefore = fake.sentMessages.size
        assertOk(patch.retryActivation())

        assertEquals(PatchStep.PRIME_COMPLETE, patch.step.value)
        assertNull(patch.failure.value)
        // The retry only reconnects and reads: no second prime and no activate.
        assertEquals(1, sentOpcodes().count { it == CommandType.PRIME.code })
        assertFalse(sent(CommandType.ACTIVATE))
        assertTrue(fake.sentMessages.size > sentBefore)
    }

    @Test
    fun retryActivationRefusesAPumpStateItCannotResume() = runBlocking {
        connectAndFill()
        reportPumpState(MedtrumPumpState.OCCLUSION)
        assertFailed(patch.retryActivation())
        assertNotNull(patch.failure.value)
        assertFalse(sent(CommandType.PRIME))
    }

    @Test
    fun aFailedConnectCanBeTriedAgain() = runBlocking {
        fake.connectResult = false
        assertOk(patch.start())
        assertFailed(patch.connectForPatch())
        assertEquals(PatchStep.PREPARE_PATCH_CONNECT, patch.step.value)
        assertNotNull(patch.failure.value)

        // Nothing was delivered yet, so asking for the connect again is allowed.
        fake.connectResult = true
        scriptAuth(MedtrumPumpState.FILLED)
        scriptPatchCommands()
        assertOk(patch.connectForPatch())
        assertNull(patch.failure.value)
        assertOk(patch.awaitPatchFilled())
        assertEquals(PatchStep.PRIME, patch.step.value)
    }

    @Test
    fun cancelSendsNothingAndLetsTheSequenceStartAgain() = runBlocking {
        connectAndFill()
        patch.cancel()
        assertEquals(PatchStep.CANCEL, patch.step.value)
        assertFalse(sent(CommandType.PRIME))
        assertFalse(sent(CommandType.ACTIVATE))

        scriptAuth(MedtrumPumpState.FILLED)
        assertOk(patch.start())
        assertEquals(PatchStep.PREPARE_PATCH, patch.step.value)
    }
}
