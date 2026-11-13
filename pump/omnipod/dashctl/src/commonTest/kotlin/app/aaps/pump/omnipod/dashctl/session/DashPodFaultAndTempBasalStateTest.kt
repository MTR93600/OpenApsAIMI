package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.AlarmStatusResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pod state part of step 8: the alarm status (Android `updateFromAlarmStatusResponse`), the bolus
 * a faulted pod ended (the state part of Android `checkPodKaput`) and the confirmed temp basal (Android
 * `podStateManager.tempBasal`, `tempBasalActive`). The store holds the encoded bytes, as the Keychain
 * does, so a restart decodes them.
 */
class DashPodFaultAndTempBasalStateTest {

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
    private var manager = DashPodStateManager(store)

    /** The app is killed and starts again: everything not in the store is gone. */
    private fun restart() {
        manager = DashPodStateManager(store)
    }

    private fun status(
        lastSequence: Int = 0x0f,
        pulsesLeft: Int = 0,
        podStatus: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        deliveryStatus: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE,
    ) = DashPodStatus(
        podStatus = podStatus,
        deliveryStatus = deliveryStatus,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1000,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = pulsesLeft,
        activeAlerts = emptySet(),
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = 1_000L,
    )

    /**
     * An alarm status: the Android vector of `AlarmStatusResponseTest` with pod status ALARM, delivery
     * SUSPENDED, the bolus pulses left, the last programming sequence and the alarm type set.
     */
    private fun alarmStatus(alarmType: AlarmType, lastSequence: Int, pulsesLeft: Int) = AlarmStatusResponse(
        parityHex(
            "021602" + "0d" + "00" +
                pulsesLeft.toString(16).padStart(4, '0') +
                lastSequence.toString(16).padStart(2, '0') +
                "01bd" + (alarmType.value.toInt() and 0xff).toString(16).padStart(2, '0') +
                "0000" + "03ff" + "0195" + "00" + "00000000" + "670a"
        )
    )

    /** A delivery command, sent, then decided by [statusAfter]. */
    private fun decided(
        kind: DashCommandKind,
        statusAfter: (sequence: Int) -> DashPodStatus,
        bolusUnits: Double? = null,
        rate: Double? = null,
        minutes: Int? = null,
        createdAtMs: Long = 500L,
    ): DashResolvedCommand {
        val command = manager.createActiveCommand(
            kind, historyId = 7L, createdAtMs = createdAtMs, requestedBolusUnits = bolusUnits, tempBasalRate = rate, tempBasalMinutes = minutes
        )
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(statusAfter(command.sequence))
        return manager.resolveActiveCommand(nowMs = 2_000L)
    }

    /** A bolus of [units] U the pod took, with all of it still to give (Android `createLastBolus`). */
    private fun bolusTaken(units: Double) {
        val resolved = decided(DashCommandKind.BOLUS, { status(lastSequence = it, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE) }, bolusUnits = units)
        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
    }

    // -- Alarm status ---------------------------------------------------------------

    @Test
    fun `an alarm status is stored as a status and keeps its alarm type`() {
        val before = manager.statusResponseCount
        assertTrue(manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 3, pulsesLeft = 8), nowMs = 9_000L))

        assertEquals(before + 1, manager.statusResponseCount, "counted, so it decides an open command")
        assertEquals(PodStatus.ALARM, manager.reportedPodStatus)
        assertEquals(3, manager.podStatus?.lastProgrammingCommandSequence)
        assertEquals(8, manager.podStatus?.bolusPulsesRemaining)
        assertEquals(9_000L, manager.lastUpdatedSystemMs)
        assertEquals(AlarmType.ALARM_OCCLUDED, manager.alarmType)
        assertTrue(manager.isPodKaput)
        assertNull(manager.activationTime, "an alarm status does not set the activation time, as Android")
        restart()
        assertEquals(AlarmType.ALARM_OCCLUDED, manager.alarmType, "stored")
    }

    @Test
    fun `only the first alarm type is kept`() {
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 3, pulsesLeft = 0), nowMs = 1L)
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_BOLUS_OVERFLOW, lastSequence = 3, pulsesLeft = 0), nowMs = 2L)

        assertEquals(AlarmType.ALARM_OCCLUDED, manager.alarmType)
        assertEquals(2L, manager.lastUpdatedSystemMs, "the status itself is the new one")
    }

    @Test
    fun `alarm synced is stored and cleared by a reset`() {
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 3, pulsesLeft = 0), nowMs = 1L)
        manager.updateAlarmSynced(true)
        restart()
        assertTrue(manager.alarmSynced)

        manager.reset()
        assertFalse(manager.alarmSynced)
        assertNull(manager.alarmType)
    }

    // -- Reservoir reading (Android pulsesRemaining) -----------------------------------

    @Test
    fun `a reservoir reading of 1023 or more keeps the last lower one`() {
        assertNull(manager.pulsesRemaining, "no reading yet")
        manager.updateFromStatus(status().copy(reservoirPulsesRemaining = 200))
        assertEquals(200, manager.pulsesRemaining)

        manager.updateFromStatus(status().copy(reservoirPulsesRemaining = 1023))
        assertEquals(200, manager.pulsesRemaining, "Android updatePodState stores only readings below 1023")
        // The alarm status of this vector reads 1023 (0x03ff) reservoir pulses.
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 3, pulsesLeft = 0), nowMs = 2L)
        assertEquals(200, manager.pulsesRemaining)
        restart()
        assertEquals(200, manager.pulsesRemaining, "stored")

        manager.updateFromStatus(status().copy(reservoirPulsesRemaining = 150))
        assertEquals(150, manager.pulsesRemaining)
        manager.reset()
        assertNull(manager.pulsesRemaining)
    }

    // -- checkPodKaput ----------------------------------------------------------------

    @Test
    fun `a running pod does not end the bolus`() {
        bolusTaken(1.0)
        manager.updateFromStatus(status(pulsesLeft = 8, deliveryStatus = DeliveryStatus.BOLUS_AND_BASAL_ACTIVE))

        assertNull(manager.completeBolusOnPodKaput())
        assertFalse(assertNotNull(manager.lastBolus).deliveryComplete)
    }

    @Test
    fun `a faulted pod ends the bolus with what it had left`() {
        bolusTaken(1.0)
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 0x0f, pulsesLeft = 8), nowMs = 3_000L)

        val ended = assertNotNull(manager.completeBolusOnPodKaput())
        assertTrue(ended.deliveryComplete)
        assertEquals(0.6, assertNotNull(ended.deliveredUnits()), 1e-9)
        assertEquals(500L, ended.createdAtMs, "recorded at the time of the bolus command")
        restart()
        assertTrue(assertNotNull(manager.lastBolus).deliveryComplete, "the end is stored")
        assertNull(manager.completeBolusOnPodKaput(), "never given twice")
    }

    @Test
    fun `a deactivated pod also ends the bolus`() {
        bolusTaken(1.0)
        manager.updateFromStatus(status(pulsesLeft = 4, podStatus = PodStatus.DEACTIVATED, deliveryStatus = DeliveryStatus.SUSPENDED))

        assertEquals(0.8, assertNotNull(manager.completeBolusOnPodKaput()?.deliveredUnits()), 1e-9)
    }

    @Test
    fun `a bolus that already ended is not given again by a fault`() {
        bolusTaken(1.0)
        manager.updateFromStatus(status(pulsesLeft = 0))
        assertTrue(assertNotNull(manager.lastBolus).deliveryComplete)
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 0x0f, pulsesLeft = 0), nowMs = 1L)

        assertNull(manager.completeBolusOnPodKaput())
        assertEquals(1.0, manager.lastBolus?.deliveredUnits())
    }

    @Test
    fun `no last bolus means nothing to end`() {
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 0x0f, pulsesLeft = 0), nowMs = 1L)

        assertNull(manager.completeBolusOnPodKaput())
    }

    @Test
    fun `a fault stored before a crash still ends the bolus after it`() {
        bolusTaken(2.0)
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = 0x0f, pulsesLeft = 10), nowMs = 1L)
        // Crash after the status write, before checkPodKaput.
        restart()

        assertEquals(1.5, assertNotNull(manager.completeBolusOnPodKaput()?.deliveredUnits()), 1e-9)
    }

    @Test
    fun `a bolus decided by the status of a faulted pod takes the pulses left from it`() {
        // Deliberate difference from Android: Android makes the last bolus with all of it left, and
        // checkPodKaput then records 0 U for a bolus the pod partly gave.
        val command = manager.createActiveCommand(DashCommandKind.BOLUS, historyId = 7L, createdAtMs = 500L, requestedBolusUnits = 1.0)
        manager.markActiveCommandSent()
        manager.updateFromResponse(alarmStatus(AlarmType.ALARM_OCCLUDED, lastSequence = command.sequence, pulsesLeft = 6), nowMs = 1L)
        val resolved = manager.resolveActiveCommand(nowMs = 2_000L)
        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)

        assertEquals(0.3, assertNotNull(manager.lastBolus).bolusUnitsRemaining, 1e-9)
        assertEquals(0.7, assertNotNull(manager.completeBolusOnPodKaput()?.deliveredUnits()), 1e-9)
    }

    @Test
    fun `a bolus decided by a running pod still starts with all of it left`() {
        // Android parity: the answer to the bolus does not set the pulses left.
        bolusTaken(2.0)

        assertEquals(2.0, manager.lastBolus?.bolusUnitsRemaining)
    }

    // -- Temp basal -------------------------------------------------------------------

    @Test
    fun `a confirmed temp basal is kept with the time of its command`() {
        val resolved = decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.5, minutes = 30, createdAtMs = 10_000L)
        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)

        assertEquals(DashTempBasal(startTimeMs = 10_000L, rate = 1.5, durationInMinutes = 30), manager.tempBasal)
        assertTrue(manager.tempBasalActive(10_000L + 29 * 60_000L))
        assertFalse(manager.tempBasalActive(10_000L + 30 * 60_000L), "ran out")
        restart()
        assertEquals(DashTempBasal(startTimeMs = 10_000L, rate = 1.5, durationInMinutes = 30), manager.tempBasal, "stored")
    }

    @Test
    fun `a refused temp basal is not kept`() {
        val resolved = decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = (it + 1) and 0x0f) }, rate = 1.5, minutes = 30)
        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)

        assertNull(manager.tempBasal)
        assertFalse(manager.tempBasalActive(500L))
    }

    @Test
    fun `a confirmed cancel suspend resume or stop of all clears the temp basal`() {
        listOf(
            DashCommandKind.STOP_TEMP_BASAL,
            DashCommandKind.SUSPEND_DELIVERY,
            DashCommandKind.RESUME_DELIVERY,
            DashCommandKind.STOP_ALL,
        ).forEach { kind ->
            decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.0, minutes = 60)
            assertNotNull(manager.tempBasal, "$kind: set first")

            val resolved = decided(kind, { status(lastSequence = it) })
            assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation, "$kind")
            assertNull(manager.tempBasal, "$kind clears it")
        }
    }

    @Test
    fun `a refused cancel keeps the temp basal`() {
        decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.0, minutes = 60)
        val resolved = decided(DashCommandKind.STOP_TEMP_BASAL, { status(lastSequence = (it + 1) and 0x0f) })
        assertEquals(DashCommandConfirmation.DENIED, resolved.confirmation)

        assertNotNull(manager.tempBasal)
    }

    @Test
    fun `a bolus or a basal program does not change the temp basal`() {
        decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.0, minutes = 60)
        val kept = manager.tempBasal
        decided(DashCommandKind.BOLUS, { status(lastSequence = it) }, bolusUnits = 0.5)
        decided(DashCommandKind.BASAL_PROGRAM, { status(lastSequence = it) })

        assertEquals(kept, manager.tempBasal)
    }

    @Test
    fun `no temp basal is active while delivery is suspended`() {
        decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.0, minutes = 60, createdAtMs = 0L)
        manager.updateFromStatus(status(deliveryStatus = DeliveryStatus.SUSPENDED))

        assertNotNull(manager.tempBasal)
        assertFalse(manager.tempBasalActive(1L), "Android: !isSuspended && ...")
    }

    @Test
    fun `a reset clears the temp basal`() {
        decided(DashCommandKind.TEMP_BASAL, { status(lastSequence = it) }, rate = 1.0, minutes = 60)
        manager.reset()

        assertNull(manager.tempBasal)
    }
}
