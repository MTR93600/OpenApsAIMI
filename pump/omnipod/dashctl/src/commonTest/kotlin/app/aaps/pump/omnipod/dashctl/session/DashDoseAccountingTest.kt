package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * The pod state part of the dose accounting taken from OmnipodKit (Trio), deliberate differences from
 * Android: a command decided by the delivery status (`checkCommandAgainstStatus`), what is counted
 * while an outcome is not known (`resolveAnyPendingCommandWithUncertainty`), the fault time
 * (`setDeliveryStoppedAt`) and a bolus the app did not track (`updateDeliveryStatus`). The store holds
 * the encoded bytes, as the Keychain does, so a restart decodes them.
 *
 * Test names here carry no comma: Kotlin/Native rejects one inside a backtick quoted name.
 */
class DashDoseAccountingTest {

    private class BytesStore : DashPodStateStore {

        var bytes: ByteArray? = null
        override fun load(): DashPodState? = bytes?.let { DashPodStateCodec.decode(it) }
        override fun save(state: DashPodState) {
            bytes = DashPodStateCodec.encode(state)
        }

        override fun clear() {
            bytes = null
        }
    }

    private val store = BytesStore()
    private var manager = newManager()

    private fun newManager() = DashPodStateManager(store, deviceTimeZone = { TimeZone.UTC })

    /** The app is killed and starts again: everything not in the store is gone. */
    private fun restart() {
        manager = newManager()
    }

    /** A pod whose activation is completed, with a flat basal program of 1 U/h. */
    private fun activatedPod() {
        manager.updateActivationProgress(ActivationProgress.COMPLETED, BasalProgram(listOf(BasalProgram.Segment(0, 48, 100))))
    }

    private fun status(
        lastSequence: Int = 0x0f,
        pulsesLeft: Int = 0,
        deliveryStatus: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE,
        receivedAtMs: Long = 1_000L,
    ) = DashPodStatus(
        podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        deliveryStatus = deliveryStatus,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1000,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = pulsesLeft,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = receivedAtMs,
    )

    /** A delivery command, sent, then a status that does not report its sequence, then resolved. */
    private fun sentThenOtherSequence(
        kind: DashCommandKind,
        deliveryStatus: DeliveryStatus,
        pulsesLeft: Int = 0,
        bolusUnits: Double? = null,
        rate: Double? = null,
        minutes: Int? = null,
    ): DashResolvedCommand {
        val command = manager.createActiveCommand(
            kind, historyId = 0L, createdAtMs = 500L, requestedBolusUnits = bolusUnits, tempBasalRate = rate, tempBasalMinutes = minutes
        )
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(lastSequence = (command.sequence + 3) and 0x0f, pulsesLeft = pulsesLeft, deliveryStatus = deliveryStatus))
        return manager.resolveActiveCommand(nowMs = 2_000L)
    }

    /** An alarm status (the layout of `AlarmStatusResponse`) with [alarmMinutes] as its alarm time and 0x0195 minutes since activation. */
    private fun alarmStatus(alarmMinutes: Int) = AlarmStatusResponse(
        parityHex(
            "021602" + "0d" + "00" + "0000" + "0f" + "01bd" + (AlarmType.ALARM_OCCLUDED.value.toInt() and 0xff).toString(16).padStart(2, '0') +
                alarmMinutes.toString(16).padStart(4, '0') + "03ff" + "0195" + "00" + "00000000" + "670a"
        )
    )

    // ---------------------------------------------------------------- item 2: decided by the delivery status

    @Test
    fun `a bolus with another sequence is taken when the status shows a bolus running`() {
        val resolved = sentThenOtherSequence(DashCommandKind.BOLUS, DeliveryStatus.BOLUS_AND_BASAL_ACTIVE, pulsesLeft = 20, bolusUnits = 2.0)

        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        assertEquals(2.0, manager.lastBolus?.requestedUnits, "the bolus is followed and counted")
    }

    @Test
    fun `a bolus with another sequence and no bolus running is denied as Android`() {
        val resolved = sentThenOtherSequence(DashCommandKind.BOLUS, DeliveryStatus.BASAL_ACTIVE, bolusUnits = 2.0)

        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)
        assertNull(manager.lastBolus)
    }

    @Test
    fun `a bolus seen running while another bolus is still followed is not this one`() {
        manager.createLastBolus(requestedUnits = 3.0, nowMs = 100L, createdAtMs = 100L)
        val resolved = sentThenOtherSequence(DashCommandKind.BOLUS, DeliveryStatus.BOLUS_AND_BASAL_ACTIVE, pulsesLeft = 40, bolusUnits = 1.0)

        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)
        assertEquals(3.0, manager.lastBolus?.requestedUnits, "the followed bolus stays the last bolus")
    }

    @Test
    fun `a temp basal with another sequence is taken when the status shows a temp basal running`() {
        val resolved = sentThenOtherSequence(DashCommandKind.TEMP_BASAL, DeliveryStatus.TEMP_BASAL_ACTIVE, rate = 0.0, minutes = 30)

        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        assertEquals(DashTempBasal(startTimeMs = 500L, rate = 0.0, durationInMinutes = 30), manager.tempBasal)
    }

    @Test
    fun `a resume with another sequence is taken when the status shows the basal running`() {
        val program = BasalProgram(listOf(BasalProgram.Segment(0, 48, 80)))
        val command = manager.createActiveCommand(DashCommandKind.RESUME_DELIVERY, historyId = 0L, createdAtMs = 500L, basalProgram = program)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(lastSequence = (command.sequence + 3) and 0x0f, deliveryStatus = DeliveryStatus.BASAL_ACTIVE))

        assertEquals(DashCommandConfirmation.SUCCESS, manager.resolveActiveCommand(nowMs = 2_000L).confirmation)
        assertEquals(program.segments.map { it.basalRateInHundredthUnitsPerHour }, manager.basalProgram?.segments?.map { it.basalRateInHundredthUnitsPerHour })
    }

    @Test
    fun `a stop a suspend and a basal program keep the sequence check only`() {
        for (kind in listOf(DashCommandKind.STOP_TEMP_BASAL, DashCommandKind.SUSPEND_DELIVERY, DashCommandKind.BASAL_PROGRAM)) {
            assertEquals(DashCommandConfirmation.DENIED, sentThenOtherSequence(kind, DeliveryStatus.BOLUS_AND_TEMP_BASAL_ACTIVE).confirmation, "$kind")
        }
    }

    @Test
    fun `a command that never left is never taken by the delivery status`() {
        val command = manager.createActiveCommand(DashCommandKind.BOLUS, historyId = 0L, createdAtMs = 500L, requestedBolusUnits = 1.0)
        manager.markActiveCommandNotSent()
        manager.updateFromDefaultStatus(status(lastSequence = (command.sequence + 3) and 0x0f, pulsesLeft = 20, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE))

        assertEquals(DashCommandConfirmation.DENIED, manager.resolveActiveCommand(nowMs = 2_000L).confirmation)
    }

    // ---------------------------------------------------------------- item 1: assume more insulin

    @Test
    fun `while unknown a bolus and a temp basal not below the schedule are taken`() {
        activatedPod()
        fun command(kind: DashCommandKind, rate: Double? = null) =
            DashActiveCommand(sequence = 1, kind = kind, historyId = 0L, statusCountAtCreation = 0L, sent = true, tempBasalRate = rate, tempBasalMinutes = 30, createdAtMs = 500L)

        assertTrue(manager.countAsTakenWhileUnknown(command(DashCommandKind.BOLUS)))
        assertTrue(manager.countAsTakenWhileUnknown(command(DashCommandKind.TEMP_BASAL, rate = 1.0)), "the scheduled rate itself")
        assertTrue(manager.countAsTakenWhileUnknown(command(DashCommandKind.TEMP_BASAL, rate = 2.5)))
        assertFalse(manager.countAsTakenWhileUnknown(command(DashCommandKind.TEMP_BASAL, rate = 0.95)))
        assertFalse(manager.countAsTakenWhileUnknown(command(DashCommandKind.TEMP_BASAL, rate = 0.0)))
        assertFalse(manager.countAsTakenWhileUnknown(command(DashCommandKind.SUSPEND_DELIVERY)))
    }

    @Test
    fun `while unknown a cancel is taken only when it stops a low temp basal`() {
        activatedPod()
        val cancel = DashActiveCommand(sequence = 1, kind = DashCommandKind.STOP_TEMP_BASAL, historyId = 0L, statusCountAtCreation = 0L, sent = true, createdAtMs = 600L)

        assertFalse(manager.countAsTakenWhileUnknown(cancel), "no temp basal known: nothing written")
        for ((rate, taken) in listOf(0.0 to true, 3.0 to false, 1.0 to false, 0.5 to true)) {
            val tbr = manager.createActiveCommand(DashCommandKind.TEMP_BASAL, historyId = 0L, createdAtMs = 500L, tempBasalRate = rate, tempBasalMinutes = 30)
            manager.markActiveCommandSent()
            manager.updateFromDefaultStatus(status(lastSequence = tbr.sequence))
            manager.resolveActiveCommand(nowMs = 550L)
            assertEquals(taken, manager.countAsTakenWhileUnknown(cancel), "cancel of $rate U/h")
        }
        assertFalse(
            manager.countAsTakenWhileUnknown(cancel.copy(createdAtMs = 500L + 31 * 60_000L)),
            "a temp basal that already ran out is not stopped by the cancel"
        )
    }

    @Test
    fun `while unknown a resume and a basal program are taken because the basal starts again`() {
        activatedPod()
        fun command(kind: DashCommandKind) =
            DashActiveCommand(sequence = 1, kind = kind, historyId = 0L, statusCountAtCreation = 0L, sent = true, createdAtMs = 500L)

        assertTrue(manager.countAsTakenWhileUnknown(command(DashCommandKind.RESUME_DELIVERY)))
        assertTrue(manager.countAsTakenWhileUnknown(command(DashCommandKind.BASAL_PROGRAM)))
        assertFalse(manager.countAsTakenWhileUnknown(command(DashCommandKind.STOP_BOLUS)), "a stop gives less insulin")
        assertFalse(manager.countAsTakenWhileUnknown(command(DashCommandKind.PROGRAM_ALERTS)), "no insulin")
    }

    @Test
    fun `while unknown with no basal program a low temp basal is counted as Android`() {
        val low = DashActiveCommand(sequence = 1, kind = DashCommandKind.TEMP_BASAL, historyId = 0L, statusCountAtCreation = 0L, sent = true, tempBasalRate = 0.3, tempBasalMinutes = 30, createdAtMs = 500L)

        assertTrue(manager.countAsTakenWhileUnknown(low))
        assertFalse(manager.countAsTakenWhileUnknown(low.copy(tempBasalRate = 0.0)), "a zero temp basal is always below the schedule")
    }

    @Test
    fun `the scheduled rate is read in the time zone of the pod`() {
        manager.updateActivationProgress(
            ActivationProgress.COMPLETED,
            BasalProgram(listOf(BasalProgram.Segment(0, 2, 50), BasalProgram.Segment(2, 48, 200)))
        )
        // 23:30 UTC is 00:30 at the pod (UTC+1): the first hour of the pod day, 0.5 U/h. One hour later it is 2 U/h.
        manager.overwriteForTest { it.copy(timeZoneOffsetMs = 3_600_000) }
        val at = 23 * 3_600_000L + 30 * 60_000L

        assertEquals(0.5, manager.scheduledBasalRateAt(at))
        assertEquals(2.0, manager.scheduledBasalRateAt(at + 60 * 60_000L))
    }

    // ---------------------------------------------------------------- item 6: the fault time

    @Test
    fun `the first alarm status keeps the fault time of the pod`() {
        val nowMs = 10_000_000L
        manager.updateFromAlarmStatus(alarmStatus(alarmMinutes = 0x0195 - 90), nowMs)

        assertEquals(nowMs - 90 * 60_000L, manager.faultTimeMs)
        manager.updateFromAlarmStatus(alarmStatus(alarmMinutes = 0x0195 - 1), nowMs + 60_000L)
        assertEquals(nowMs - 90 * 60_000L, manager.faultTimeMs, "a later alarm status does not change it")
        restart()
        assertEquals(nowMs - 90 * 60_000L, manager.faultTimeMs, "stored")
    }

    @Test
    fun `an alarm time that is not valid gives no fault time`() {
        for (minutes in listOf(0, 0xffff, 0x0195 + 1)) {
            manager.reset()
            manager.updateFromAlarmStatus(alarmStatus(alarmMinutes = minutes), 10_000_000L)
            assertNull(manager.faultTimeMs, "alarm time $minutes")
        }
    }

    // ---------------------------------------------------------------- item 7: a bolus the app did not track

    @Test
    fun `a bolus the pod reports and the app does not track becomes the last bolus to record`() {
        activatedPod()
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE, receivedAtMs = 5_000L))

        val bolus = assertNotNull(manager.untrackedBolusToRecord())
        assertEquals(1.5, bolus.requestedUnits, "the units left")
        assertEquals(5_000L, bolus.createdAtMs)
        assertEquals(bolus, manager.lastBolus)
    }

    @Test
    fun `an untracked bolus is found once and its mark survives a restart until recorded`() {
        activatedPod()
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE, receivedAtMs = 5_000L))
        restart()
        assertEquals(5_000L, manager.untrackedBolusToRecord()?.createdAtMs, "the mark is stored")

        manager.untrackedBolusWritten(pumpId = 4_000L)
        assertEquals(5_000L, manager.untrackedBolusToRecord()?.createdAtMs, "the record of another bolus does not clear the mark")
        manager.untrackedBolusWritten(pumpId = 5_000L)
        manager.updateFromDefaultStatus(status(pulsesLeft = 10, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE, receivedAtMs = 65_000L))
        restart()
        assertNull(manager.untrackedBolusToRecord(), "the same bolus is not found a second time")
        assertEquals(1.5, manager.lastBolus?.requestedUnits)
        assertEquals(0.5, manager.lastBolus?.bolusUnitsRemaining)
    }

    @Test
    fun `no untracked bolus while a bolus is followed or a bolus command is open or before the activation`() {
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE))
        assertNull(manager.untrackedBolusToRecord(), "the prime and the cannula insertion are boluses too")

        activatedPod()
        manager.createLastBolus(requestedUnits = 2.0, nowMs = 100L, createdAtMs = 100L)
        manager.updateFromDefaultStatus(status(pulsesLeft = 30, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE))
        assertNull(manager.untrackedBolusToRecord(), "the followed bolus")

        manager.markLastBolusComplete()
        manager.createActiveCommand(DashCommandKind.BOLUS, historyId = 0L, createdAtMs = 200L, requestedBolusUnits = 1.0)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(pulsesLeft = 20, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE))
        assertNull(manager.untrackedBolusToRecord(), "the open bolus command is decided by its own status")
    }

    // ---------------------------------------------------------------- the codec

    @Test
    fun `the dose accounting fields roundtrip and an empty part is not written`() {
        val empty = DashPodStateCodec.encode(DashPodState())
        for (state in listOf(DashPodState(faultTimeMs = 7L), DashPodState(untrackedBolusToRecordAt = 9L), DashPodState(faultTimeMs = 1L, untrackedBolusToRecordAt = 2L))) {
            val bytes = DashPodStateCodec.encode(state)
            assertEquals(state, DashPodStateCodec.decode(bytes))
            assertEquals(empty.toList(), bytes.copyOf(empty.size).toList(), "added at the end only")
        }
    }

    @Test
    fun `the acknowledged alerts roundtrip at the end and an empty set is not written`() {
        val empty = DashPodStateCodec.encode(DashPodState())
        assertEquals(empty.toList(), DashPodStateCodec.encode(DashPodState(acknowledgedAlerts = emptySet())).toList())
        val states = listOf(
            DashPodState(acknowledgedAlerts = setOf(AlertType.LOW_RESERVOIR)),
            DashPodState(acknowledgedAlerts = setOf(AlertType.USER_SET_EXPIRATION, AlertType.EXPIRATION, AlertType.EXPIRATION_IMMINENT)),
            DashPodState(faultTimeMs = 1L, untrackedBolusToRecordAt = 2L, acknowledgedAlerts = setOf(AlertType.SUSPEND_ENDED)),
        )
        for (state in states) {
            val bytes = DashPodStateCodec.encode(state)
            assertEquals(state, DashPodStateCodec.decode(bytes))
            assertEquals(empty.toList(), bytes.copyOf(empty.size).toList(), "added at the end only")
        }
        // Flag bit2, then the alert byte (LOW_RESERVOIR is 0x10).
        assertEquals(listOf<Byte>(0x04, 0x10), DashPodStateCodec.encode(states[0]).drop(empty.size))
    }

    @Test
    fun `an acknowledged alert flag with no alert or an unknown flag fails closed`() {
        val empty = DashPodStateCodec.encode(DashPodState())
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(empty + byteArrayOf(0x04, 0x00)) }
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(empty + byteArrayOf(0x04)) }
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(empty + byteArrayOf(0x08, 0x10)) }
    }

    @Test
    fun `a bad or cut dose accounting part fails closed`() {
        val empty = DashPodStateCodec.encode(DashPodState())
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(empty + byteArrayOf(0)) }
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(empty + byteArrayOf(4)) }
        val full = DashPodStateCodec.encode(DashPodState(faultTimeMs = 1L, untrackedBolusToRecordAt = 2L))
        for (length in empty.size + 1 until full.size) {
            assertFailsWith<IllegalArgumentException>("length $length") { DashPodStateCodec.decode(full.copyOf(length)) }
        }
    }
}
