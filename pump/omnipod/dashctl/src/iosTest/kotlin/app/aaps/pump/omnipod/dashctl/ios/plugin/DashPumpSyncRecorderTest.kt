package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashCommandOutcome
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashLastBolus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The rules of [DashPumpSyncRecorder] one by one, on outcomes built by hand: which outcome writes
 * what to `PumpSync`, and which writes nothing. The same rules on the real driver are in
 * `IosDashPluginPumpTest`.
 */
class DashPumpSyncRecorderTest {

    private val pumpSync = RecordingDashPumpSync()
    private val recorder = DashPumpSyncRecorder(pumpSync, SilentPodLogger, serialNumber = { SERIAL }, now = { DECIDED_AT })

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

    private fun endedBolus(createdAt: Long?, asked: Double, left: Double) =
        DashLastBolus(startTimeMs = DECIDED_AT, requestedUnits = asked, bolusUnitsRemaining = left, deliveryComplete = true, createdAtMs = createdAt)

    @Test
    fun `refused never sent and still open commands write nothing`() = runTest {
        for (confirmation in listOf(DashCommandConfirmation.DENIED, DashCommandConfirmation.SENDING_FAILURE, DashCommandConfirmation.NOT_CONFIRMED)) {
            recorder.record(outcome(command(DashCommandKind.BOLUS, units = 1.0), confirmation))
            recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, rate = 1.0, minutes = 30), confirmation))
            recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY), confirmation))
            recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL), confirmation))
            recorder.record(outcome(command(DashCommandKind.BASAL_PROGRAM), confirmation))
        }

        assertTrue(pumpSync.calls.isEmpty(), pumpSync.calls.toString())
    }

    @Test
    fun `a bolus decided late after a restart is written as NORMAL with its amount and time`() = runTest {
        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 2.5), late = true))

        assertEquals(listOf(bolusLine(AT, 2.5, BS.Type.NORMAL)), pumpSync.calls)
    }

    @Test
    fun `the type given for the call is not taken by an older command decided late inside it`() = runTest {
        recorder.beginBolus(BS.Type.SMB)
        recorder.record(outcome(command(DashCommandKind.BOLUS, at = AT - 1, units = 1.0), late = true))
        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 0.3)))
        recorder.endCommand(null)

        assertEquals(listOf(bolusLine(AT - 1, 1.0, BS.Type.NORMAL), bolusLine(AT, 0.3, BS.Type.SMB)), pumpSync.calls)
    }

    @Test
    fun `the type of a command left open is kept for its late outcome`() = runTest {
        recorder.beginBolus(BS.Type.SMB)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 0.4))
        recorder.beginTempBasal(PumpSync.TemporaryBasalType.EMULATED_PUMP_SUSPEND)
        recorder.endCommand(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30))

        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 0.4), late = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30), late = true))

        assertEquals(
            listOf(
                bolusLine(AT, 0.4, BS.Type.SMB),
                "tempBasal(timestamp=${AT + 1}, rate=0.0, duration=1800000, isAbsolute=true, type=EMULATED_PUMP_SUSPEND, pumpId=${AT + 1}, pumpType=OMNIPOD_DASH, serial=$SERIAL)"
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a refused call does not change the type kept for the command still open`() = runTest {
        // Bolus A (NORMAL) is left open. Then an SMB call is refused, and A is still the open command.
        recorder.beginBolus(BS.Type.NORMAL)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 3.0))
        recorder.beginBolus(BS.Type.SMB)
        recorder.endCommand(command(DashCommandKind.BOLUS, units = 3.0))
        recorder.beginTempBasal(PumpSync.TemporaryBasalType.SUPERBOLUS)
        recorder.endCommand(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30))
        recorder.beginTempBasal(PumpSync.TemporaryBasalType.NORMAL)
        recorder.endCommand(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30))

        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 3.0), late = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 0.0, minutes = 30), late = true))

        assertEquals(
            listOf(
                bolusLine(AT, 3.0, BS.Type.NORMAL),
                "tempBasal(timestamp=${AT + 1}, rate=0.0, duration=1800000, isAbsolute=true, type=SUPERBOLUS, pumpId=${AT + 1}, pumpType=OMNIPOD_DASH, serial=$SERIAL)"
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a late bolus older than the pump registration is written at the decision time and corrected there`() = runTest {
        // PumpSync was told of a new pump (connectNewPump) after the bolus was sent: a bolus at its
        // send time is refused. It is then written at the time it was decided, under the same pump
        // id, so it is counted (later than it went in: more insulin on board, never less).
        pumpSync.bolusAllowedFrom = DECIDED_AT - 60_000L

        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 2.0), late = true))
        recorder.record(DashBolusEndedByFault(endedBolus(AT, asked = 2.0, left = 0.5), AlarmType.ALARM_OCCLUDED, DECIDED_AT))

        assertEquals(
            listOf(
                "bolusRefused(timestamp=$AT, pumpId=$AT)",
                "bolus(timestamp=$DECIDED_AT, amount=2.0, type=NORMAL, pumpId=$AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)",
                "bolus(timestamp=$DECIDED_AT, amount=1.5, type=null, pumpId=$AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)"
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a bolus refused for another reason is not written again`() = runTest {
        // Inside the registration window a false is not the registration rule (no profile, or the
        // record exists): writing it again at another time would only move it.
        val at = DECIDED_AT - 30_000L
        pumpSync.bolusAllowedFrom = DECIDED_AT

        recorder.record(outcome(command(DashCommandKind.BOLUS, at = at, units = 1.0)))

        assertEquals(listOf("bolusRefused(timestamp=$at, pumpId=$at)"), pumpSync.calls)
    }

    @Test
    fun `commands left open when the pod is discarded are counted as Android counts them`() = runTest {
        val notConfirmed = DashCommandConfirmation.NOT_CONFIRMED
        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 3.0), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = AT + 1, rate = 2.0, minutes = 60), notConfirmed, late = true, discarded = true))
        recorder.record(outcome(command(DashCommandKind.STOP_TEMP_BASAL, at = AT + 2), notConfirmed, late = true, discarded = true))

        assertEquals(
            listOf(
                bolusLine(AT, 3.0, BS.Type.NORMAL),
                "tempBasal(timestamp=${AT + 1}, rate=2.0, duration=3600000, isAbsolute=true, type=NORMAL, pumpId=${AT + 1}, pumpType=OMNIPOD_DASH, serial=$SERIAL)"
            ),
            pumpSync.calls,
            "a stop is never counted on a guess: the temp basal would end early and less insulin would be counted"
        )
    }

    @Test
    fun `a resume ends the suspend temp basal`() = runTest {
        recorder.record(outcome(command(DashCommandKind.RESUME_DELIVERY)))

        assertEquals(listOf("stopTempBasal(timestamp=$AT, endPumpId=$AT, pumpType=OMNIPOD_DASH, serial=$SERIAL)"), pumpSync.calls)
    }

    @Test
    fun `a bolus ended by a fault corrects the record of its command time`() = runTest {
        recorder.record(DashBolusEndedByFault(endedBolus(AT, asked = 2.0, left = 1.25), AlarmType.ALARM_OCCLUDED, DECIDED_AT))

        assertEquals(listOf(bolusLine(AT, 0.75, null)), pumpSync.calls)
    }

    @Test
    fun `a correction without the command time goes to the last bolus written`() = runTest {
        recorder.record(outcome(command(DashCommandKind.BOLUS, units = 1.0)))
        recorder.record(outcome(command(DashCommandKind.SUSPEND_DELIVERY, at = AT + 5), endedBolus = endedBolus(null, asked = 1.0, left = 0.4)))

        assertEquals(
            listOf(
                bolusLine(AT, 1.0, BS.Type.NORMAL),
                "tempBasal(timestamp=${AT + 5}, rate=0.0, duration=288000000, isAbsolute=true, type=PUMP_SUSPEND, pumpId=${AT + 5}, pumpType=OMNIPOD_DASH, serial=$SERIAL)",
                bolusLine(AT, 0.6, null)
            ),
            pumpSync.calls
        )
    }

    @Test
    fun `a correction with nothing known writes nothing and keeps the bolus counted in full`() = runTest {
        recorder.record(DashBolusEndedByFault(endedBolus(null, asked = 1.0, left = 0.5), null, DECIDED_AT))

        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `a command stored without its time writes nothing`() = runTest {
        recorder.record(outcome(command(DashCommandKind.BOLUS, at = null, units = 1.0)))
        recorder.record(outcome(command(DashCommandKind.TEMP_BASAL, at = null, rate = 1.0, minutes = 30)))

        assertTrue(pumpSync.calls.isEmpty())
    }

    @Test
    fun `a PumpSync error does not break the command that decided it`() {
        val failing = object : PumpSync by pumpSync {
            override suspend fun syncBolusWithPumpId(
                timestamp: Long, amount: PumpInsulin, type: BS.Type?, pumpId: Long,
                pumpType: PumpType, pumpSerial: String
            ): Boolean = error("database closed")
        }
        val r = DashPumpSyncRecorder(failing, SilentPodLogger, serialNumber = { SERIAL })

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
    }
}
