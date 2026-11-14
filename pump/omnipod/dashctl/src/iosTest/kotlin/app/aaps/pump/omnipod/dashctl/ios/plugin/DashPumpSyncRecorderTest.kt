package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashCommandOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.DashUntrackedBolus
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import app.aaps.pump.omnipod.dashctl.session.DashTempBasal
import app.aaps.pump.omnipod.dashctl.session.dashCountAsTakenWhileUnknown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The rules of [DashPumpSyncRecorder] one by one, on commands built by hand: what is written when a
 * command is sent, and what each outcome adds, corrects or takes back, as Android
 * `OmnipodDashPumpPlugin`. The same rules on the real driver are in `IosDashPluginPumpTest`.
 */
class DashPumpSyncRecorderTest {

    private val pumpSync = RecordingDashPumpSync()

    /** The temp basal that runs on the pod when a command is made: the one a cancel stops. */
    private var runningTempBasal: DashTempBasal? = null

    /** The rule of the phone (`DashPodStateManager.countAsTakenWhileUnknown`) with a scheduled basal of [SCHEDULED_RATE]. */
    private fun countAsTaken(command: DashActiveCommand): Boolean = dashCountAsTakenWhileUnknown(command, SCHEDULED_RATE, runningTempBasal)
    private val recorder = DashPumpSyncRecorder(pumpSync, SilentPodLogger, ::countAsTaken, serialNumber = { SERIAL })

    private fun command(kind: DashCommandKind, at: Long? = AT, units: Double? = null, rate: Double? = null, minutes: Int? = null) =
        DashActiveCommand(
            sequence = 1, kind = kind, historyId = 0L, statusCountAtCreation = 0L, sent = true,
            requestedBolusUnits = units, tempBasalRate = rate, tempBasalMinutes = minutes, createdAtMs = at,
        )

    private fun outcome(
        command: DashActiveCommand,
        confirmation: DashCommandConfirmation = DashCommandConfirmation.SUCCESS,
        late: Boolean = false,
        endedBolus: DashLastBolus? = null,
        discarded: Boolean = false,
    ) = DashCommandOutcome(confirmation, command, late, DECIDED_AT, endedBolus, discarded)

    private fun bolusLine(at: Long, units: Double, type: BS.Type?) =
        "bolus(timestamp=$at, amount=$units, type=$type, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)"

    private fun tempBasalLine(at: Long, rate: Double, durationMs: Long, type: PumpSync.TemporaryBasalType?) =
        "tempBasal(timestamp=$at, rate=$rate, duration=$durationMs, isAbsolute=true, type=$type, pumpId=$at, pumpType=OMNIPOD_DASH, serial=$SERIAL)"

    private fun endedBolus(createdAt: Long?, asked: Double, left: Double) =
        DashLastBolus(startTimeMs = DECIDED_AT, requestedUnits = asked, bolusUnitsRemaining = left, deliveryComplete = true, createdAtMs = createdAt)

    /** A command of [kind] sent inside a call of the given types, as `IosDashPluginPump` wraps the driver. */
    private suspend fun send(command: DashActiveCommand, bolusType: BS.Type? = null, tbrType: PumpSync.TemporaryBasalType? = null) {
        bolusType?.let { recorder.beginBolus(it) }
        tbrType?.let { recorder.beginTempBasal(it) }
        recorder.recordSent(command)
    }

    // ---------------------------------------------------------------- written when sent, as Android

    @Test
    fun `a basal correction is never written as Android never syncs a BASAL_CORRECTION`() = runTest {
        val correction = command(DashCommandKind.BOLUS, units = 0.05).copy(basalCorrection = true)
        recorder.recordSent(correction)
        recorder.record(outcome(correction))
        recorder.record(outcome(correction, DashCommandConfirmation.DENIED))
        recorder.record(outcome(command(DashCommandKind.STOP_BOLUS), endedBolus = endedBolus(AT, 0.05, 0.05).copy(basalCorrection = true)))
        recorder.record(DashBolusEndedByFault(endedBolus(AT, 0.05, 0.05).copy(basalCorrection = true), null, DECIDED_AT))

        assertTrue(pumpSync.calls.isEmpty(), "${pumpSync.calls}")
    }

    @Test
    fun `a bolus is written when it is sent and its SUCCESS adds nothing`() = runTest {
        val bolus = command(DashCommandKind.BOLUS, units = 0.5)
        send(bolus, bolusType = BS.Type.SMB)
        recorder.endCommand(null)
        recorder.record(outcome(bolus))

        assertEquals(listOf(bolusLine(AT, 0.5, BS.Type.SMB)), pumpSync.calls)
    }

    @Test
    fun `a temp basal is written when it is sent and a late SUCCESS adds nothing`() = runTest {
        val tbr = command(DashCommandKind.TEMP_BASAL, rate = 1.2, minutes = 30)
        send(tbr, tbrType = PumpSync.TemporaryBasalType.SUPERBOLUS)
        recorder.endCommand(tbr)
        recorder.record(outcome(tbr, late = true))

        assertEquals(listOf(tempBasalLine(AT, 1.2, 1_800_000L, PumpSync.TemporaryBasalType.SUPERBOLUS)), pumpSync.calls)
    }

    /**
     * Deliberate difference from Android (OmnipodKit `resolveAnyPendingCommandWithUncertainty`): a
     * suspend the pod never got would count the basal it gives as not given. Its SUCCESS writes it.
     */
    @Test
    fun `a suspend is not written when it is sent but when the pod confirms it`() = runTest {
        val suspend = command(DashCommandKind.SUSPEND_DELIVERY)
        send(suspend)
        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())

        recorder.record(outcome(suspend))
        assertEquals(listOf(tempBasalLine(AT, 0.0, 288_000_000L, PumpSync.TemporaryBasalType.PUMP_SUSPEND)), pumpSync.calls)
    }

    @Test
    fun `a temp basal below the schedule is not written when it is sent but at the command time when confirmed`() = runTest {
        val low = command(DashCommandKind.TEMP_BASAL, rate = 0.4, minutes = 30)
        send(low, tbrType = PumpSync.TemporaryBasalType.NORMAL)
        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())

        // Decided inside the call that sent it: the record gets the type of the call.
        recorder.record(outcome(low))
        recorder.endCommand(null)
        assertEquals(listOf(tempBasalLine(AT, 0.4, 1_800_000L, PumpSync.TemporaryBasalType.NORMAL)), pumpSync.calls)
    }

    @Test
    fun `a zero temp basal is never written when it is sent and a high one always is`() = runTest {
        send(command(DashCommandKind.TEMP_BASAL, rate = 0.0, minutes = 30), tbrType = PumpSync.TemporaryBasalType.NORMAL)
        send(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = SCHEDULED_RATE, minutes = 30), tbrType = PumpSync.TemporaryBasalType.NORMAL)
        send(command(DashCommandKind.TEMP_BASAL, at = AT + 2, rate = 3.0, minutes = 30), tbrType = PumpSync.TemporaryBasalType.NORMAL)

        assertEquals(
            listOf(
                tempBasalLine(AT + 1, SCHEDULED_RATE, 1_800_000L, PumpSync.TemporaryBasalType.NORMAL),
                tempBasalLine(AT + 2, 3.0, 1_800_000L, PumpSync.TemporaryBasalType.NORMAL)
            ),
            pumpSync.calls,
            "the scheduled rate itself is not below the schedule"
        )
    }

    @Test
    fun `a low temp basal and a suspend left open when the pod is discarded are not counted`() = runTest {
        val notConfirmed = DashCommandConfirmation.NOT_CONFIRMED
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, rate = 0.0, minutes = 120), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.5, minutes = 60), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY, at = AT + 2), notConfirmed, late = true, discarded = true))

        assertTrue(pumpSync.calls.isEmpty(), "the basal goes on in AAPS, more insulin counted: ${pumpSync.calls}")
    }

    @Test
    fun `a cancel of a low temp basal left open when the pod is discarded ends it at the command time`() = runTest {
        runningTempBasal = DashTempBasal(startTimeMs = AT - 60_000L, rate = 0.2, durationInMinutes = 60)
        recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL), DashCommandConfirmation.NOT_CONFIRMED, late = true, discarded = true))

        assertEquals(listOf("stopTempBasal(timestamp=$AT, endPumpId=$AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)"), pumpSync.calls)
    }

    @Test
    fun `a cancel of a high temp basal left open when the pod is discarded writes nothing`() = runTest {
        runningTempBasal = DashTempBasal(startTimeMs = AT - 60_000L, rate = 2.5, durationInMinutes = 60)
        recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL), DashCommandConfirmation.NOT_CONFIRMED, late = true, discarded = true))

        assertTrue(pumpSync.calls.isEmpty(), "the high temp basal stays counted: ${pumpSync.calls}")
    }

    @Test
    fun `a resume or basal program left open when the pod is discarded ends the suspend at the command time`() = runTest {
        val notConfirmed = DashCommandConfirmation.NOT_CONFIRMED
        recorder.record(outcome(command(DashCommandKind.RESUME_DELIVERY), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.BASAL_PROGRAM, at = AT + 1), notConfirmed, late = true, discarded = true))

        assertEquals(
            listOf(AT, AT + 1).map { "stopTempBasal(timestamp=$it, endPumpId=$it, pumpType=OMNIPOD_DASH, serial=$SERIAL)" },
            pumpSync.calls,
            "the basal may run again: counted from the command time"
        )
    }

    @Test
    fun `an untracked bolus is written once with its time as the pump id`() = runTest {
        val untracked = DashLastBolus(startTimeMs = AT, requestedUnits = 1.25, bolusUnitsRemaining = 1.25, deliveryComplete = false, createdAtMs = AT)
        recorder.record(DashUntrackedBolus(untracked, AT))
        // After a crash before the mark was cleared, the same bolus comes again.
        recorder.record(DashUntrackedBolus(untracked, AT + 5_000L))

        assertEquals(mapOf(AT to (1.25 to BS.Type.NORMAL)), pumpSync.bolusRecords, "one record, never two")
    }

    @Test
    fun `stops programs and alerts write nothing when sent`() = runTest {
        for (kind in listOf(
            DashCommandKind.STOP_TEMP_BASAL, DashCommandKind.STOP_BOLUS, DashCommandKind.BASAL_PROGRAM, DashCommandKind.RESUME_DELIVERY,
            DashCommandKind.PROGRAM_ALERTS, DashCommandKind.SILENCE_ALERTS, DashCommandKind.DEACTIVATE, DashCommandKind.STOP_ALL
        )) send(command(kind))

        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())
    }

    // ---------------------------------------------------------------- the confirmation, as Android

    @Test
    fun `a bolus the pod refused is set to 0 U on the same record`() = runTest {
        val bolus = command(DashCommandKind.BOLUS, units = 2.0)
        send(bolus, bolusType = BS.Type.NORMAL)
        recorder.record(outcome(bolus, DashCommandConfirmation.DENIED))

        assertEquals(listOf(bolusLine(AT, 2.0, BS.Type.NORMAL), bolusLine(AT, 0.0, null)), pumpSync.calls)
        assertEquals(mapOf(AT to (0.0 to BS.Type.NORMAL)), pumpSync.bolusRecords, "one record, 0 U")
    }

    @Test
    fun `a temp basal or suspend the pod refused is invalidated`() = runTest {
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, rate = 1.0, minutes = 30), DashCommandConfirmation.DENIED))
        recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY, at = AT + 1), DashCommandConfirmation.DENIED))

        assertEquals(listOf("invalidateTemporaryBasalWithPumpId($AT)", "invalidateTemporaryBasalWithPumpId(${AT + 1})"), pumpSync.calls)
    }

    @Test
    fun `a send failure and a command still open write nothing more`() = runTest {
        for (confirmation in listOf(DashCommandConfirmation.SENDING_FAILURE, DashCommandConfirmation.NOT_CONFIRMED)) {
            recorder.record(outcome(command(DashCommandKind.BOLUS, units = 1.0), confirmation))
            recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, rate = 1.0, minutes = 30), confirmation))
            recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY), confirmation))
            recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL), confirmation))
            recorder.record(outcome(command(DashCommandKind.BASAL_PROGRAM), confirmation))
        }

        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())
    }

    @Test
    fun `a confirmed stop resume or program ends the temp basal at the command time`() = runTest {
        recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL)))
        recorder.record(outcome(command(DashCommandKind.RESUME_DELIVERY, at = AT + 1)))
        recorder.record(outcome(command(DashCommandKind.BASAL_PROGRAM, at = AT + 2)))

        assertEquals(
            listOf(AT, AT + 1, AT + 2).map { "stopTempBasal(timestamp=$it, endPumpId=$it, pumpType=OMNIPOD_DASH, serial=$SERIAL)" },
            pumpSync.calls
        )
    }

    // ---------------------------------------------------------------- the deliberate difference

    @Test
    fun `a SUCCESS whose send was not written in this run writes it once`() = runTest {
        // The write of the bolus threw (no record at send), or the app stopped after the write.
        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 2.5), late = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.5, minutes = 60), late = true))
        recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY, at = AT + 2), late = true))

        assertEquals(
            listOf(
                bolusLine(AT, 2.5, null),
                tempBasalLine(AT + 1, 0.5, 3_600_000L, null),
                tempBasalLine(AT + 2, 0.0, 288_000_000L, PumpSync.TemporaryBasalType.PUMP_SUSPEND)
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a late SUCCESS after a crash is recorded once`() = runTest {
        // Before the crash the bolus was written when it was sent.
        val bolus = command(DashCommandKind.BOLUS, units = 1.5)
        send(bolus, bolusType = BS.Type.SMB)
        // After the crash: a new recorder, the open bolus decided late by the first status.
        val afterRestart = DashPumpSyncRecorder(pumpSync, SilentPodLogger, ::countAsTaken, serialNumber = { SERIAL })
        afterRestart.record(outcome(bolus, late = true))

        assertEquals(mapOf(AT to (1.5 to BS.Type.SMB)), pumpSync.bolusRecords, "one record, its type kept")
    }

    @Test
    fun `the type of a command left open is used when its record must be written late`() = runTest {
        recorder.beginBolus(BS.Type.SMB)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 0.4))
        recorder.beginTempBasal(PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        recorder.endCommand(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30))

        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 0.4), late = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30), late = true))

        assertEquals(
            listOf(bolusLine(AT, 0.4, BS.Type.SMB), tempBasalLine(AT + 1, 0.0, 1_800_000L, PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)),
            pumpSync.calls
        )
    }

    @Test
    fun `a refused call does not change the type kept for the command still open`() = runTest {
        recorder.beginBolus(BS.Type.NORMAL)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 3.0))
        recorder.beginBolus(BS.Type.SMB)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 3.0))

        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 3.0), late = true))

        assertEquals(listOf(bolusLine(AT, 3.0, BS.Type.NORMAL)), pumpSync.calls)
    }

    @Test
    fun `commands left open when the pod is discarded keep what was written when sent`() = runTest {
        val notConfirmed = DashCommandConfirmation.NOT_CONFIRMED
        val bolus = command(DashCommandKind.BOLUS, units = 3.0)
        send(bolus, bolusType = BS.Type.NORMAL)
        recorder.record(outcome(bolus, notConfirmed, late = true, discarded = true))
        // Never written at send in this run: counted as Android counts it, at the asked amount and as started.
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 2.0, minutes = 60), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL, at = AT + 2), notConfirmed, late = true, discarded = true))

        assertEquals(
            listOf(bolusLine(AT, 3.0, BS.Type.NORMAL), tempBasalLine(AT + 1, 2.0, 3_600_000L, null)),
            pumpSync.calls,
            "a stop is never counted on a guess: the temp basal would end early and less insulin would be counted"
        )
    }

    // ---------------------------------------------------------------- corrections

    @Test
    fun `a bolus ended by a fault corrects the record of its command time`() = runTest {
        val bolus = command(DashCommandKind.BOLUS, units = 2.0)
        send(bolus, bolusType = BS.Type.NORMAL)
        recorder.record(outcome(bolus))
        recorder.record(DashBolusEndedByFault(endedBolus(AT, asked = 2.0, left = 1.25), AlarmType.ALARM_OCCLUDED, DECIDED_AT))

        assertEquals(listOf(bolusLine(AT, 2.0, BS.Type.NORMAL), bolusLine(AT, 0.75, null)), pumpSync.calls)
        assertEquals(mapOf(AT to (0.75 to BS.Type.NORMAL)), pumpSync.bolusRecords, "one record, never a second bolus")
    }

    @Test
    fun `a stopped bolus corrects the same record`() = runTest {
        val bolus = command(DashCommandKind.BOLUS, units = 2.0)
        send(bolus, bolusType = BS.Type.NORMAL)
        recorder.record(outcome(bolus))
        recorder.record(outcome(command(DashCommandKind.STOP_BOLUS, at = AT + 10), endedBolus = endedBolus(AT, asked = 2.0, left = 1.5)))

        assertEquals(mapOf(AT to (0.5 to BS.Type.NORMAL)), pumpSync.bolusRecords)
    }

    @Test
    fun `a correction without the command time never changes another bolus record`() = runTest {
        // A bolus of this run, sent and then refused: its record is 0 U.
        val refused = command(DashCommandKind.BOLUS, at = AT + 100, units = 1.0)
        send(refused, bolusType = BS.Type.NORMAL)
        recorder.record(outcome(refused, DashCommandConfirmation.DENIED))
        // The pod then faults; the bolus it ended was stored by codec version 3 (no command time).
        recorder.record(DashBolusEndedByFault(endedBolus(null, asked = 2.0, left = 1.5), AlarmType.ALARM_OCCLUDED, DECIDED_AT))

        assertEquals(mapOf(AT + 100 to (0.0 to BS.Type.NORMAL)), pumpSync.bolusRecords, "the refused bolus stays at 0 U")
        assertEquals(2, pumpSync.calls.size, "no correction is written: the old bolus stays counted in full")
    }

    @Test
    fun `a correction with nothing known writes nothing and keeps the bolus counted in full`() = runTest {
        recorder.record(DashBolusEndedByFault(endedBolus(null, asked = 1.0, left = 0.5), null, DECIDED_AT))

        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `a command stored without its time writes nothing`() = runTest {
        recorder.recordSent(command(DashCommandKind.BOLUS, at = null, units = 1.0))
        recorder.record(outcome(command(DashCommandKind.BOLUS, at = null, units = 1.0)))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = null, rate = 1.0, minutes = 30)))

        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `a PumpSync error does not break the command`() {
        val failing = object : PumpSync by pumpSync {
            override suspend fun syncBolusWithPumpId(
                timestamp: Long, amount: PumpInsulin, type: BS.Type?, pumpId: Long,
                pumpType: PumpType, pumpSerial: String
            ): Boolean = error("database closed")
        }
        val r = DashPumpSyncRecorder(failing, SilentPodLogger, ::countAsTaken, serialNumber = { SERIAL })

        r.onCommandSent(command(DashCommandKind.BOLUS, units = 1.0))
        r.onOutcome(outcome(command(DashCommandKind.BOLUS, units = 1.0)))

        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `the expected temp basal of the app decides the cancel and an error sends it`() {
        assertFalse(expectedTempBasalRunning(pumpSync, SilentPodLogger))
        pumpSync.expectedTemporaryBasal = PumpSync.PumpState.TemporaryBasal(
            timestamp = AT, duration = 1_800_000L, rate = 1.0, isAbsolute = true, type = PumpSync.TemporaryBasalType.NORMAL, id = 1L, pumpId = AT
        )
        assertTrue(expectedTempBasalRunning(pumpSync, SilentPodLogger))

        val failing = object : PumpSync by pumpSync {
            override suspend fun expectedPumpState(): PumpSync.PumpState = error("database closed")
        }
        assertTrue(expectedTempBasalRunning(failing, SilentPodLogger), "fail closed: the cancel is sent")
    }

    private companion object {

        const val AT = 1_794_564_000_000L
        const val DECIDED_AT = AT + 3_600_000L
        const val SERIAL = "37879809"

        /** The rate of the basal program at the command times, U/h. */
        const val SCHEDULED_RATE = 1.0
    }
}
