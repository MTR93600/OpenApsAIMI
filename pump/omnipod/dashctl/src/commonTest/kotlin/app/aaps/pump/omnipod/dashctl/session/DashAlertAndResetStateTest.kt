package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The alert part of the pod state (Android `suspendAlertsEnabled`, `updateExpirationAlertSettings`,
 * `updateLowReservoirAlertSettings`, `sameAlertSettings`, `activeAlerts`, `isPodKaput`), what a
 * confirmed resume or deactivation does to the state, and `reset`. The store holds the encoded bytes,
 * as the Keychain does.
 */
class DashAlertAndResetStateTest {

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
    private val manager = DashPodStateManager(store)

    private val expiration = DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 9, alarmEnabled = true, alarmHours = 7)
    private val lowReservoir = DashLowReservoirAlertSettings(enabled = true, units = 20)
    private val settings = DashAlertSettings(expiration, lowReservoir)

    private fun status(
        lastSequence: Int,
        podStatus: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        bolusPulsesRemaining: Int = 0,
        alerts: Set<AlertType> = emptySet(),
    ) = DashPodStatus(
        podStatus = podStatus,
        deliveryStatus = DeliveryStatus.BASAL_ACTIVE,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1000,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = bolusPulsesRemaining,
        activeAlerts = alerts,
        lastProgrammingCommandSequence = lastSequence,
        receivedAtMs = 1_000L,
    )

    private fun reloaded() = DashPodStateManager(store)

    // ---------------------------------------------------------------- alerts

    @Test
    fun `the alert settings are stored and survive a restart`() {
        manager.updateExpirationAlertSettings(expiration)
        manager.updateLowReservoirAlertSettings(lowReservoir)
        manager.updateSuspendAlertsEnabled(true)

        val again = reloaded()
        assertEquals(expiration, again.expirationAlertSettings)
        assertEquals(lowReservoir, again.lowReservoirAlertSettings)
        assertTrue(again.suspendAlertsEnabled)
    }

    @Test
    fun `same alert settings needs both parts stored and equal`() {
        assertFalse(manager.sameAlertSettings(settings), "nothing stored yet")
        manager.updateLowReservoirAlertSettings(lowReservoir)
        assertFalse(manager.sameAlertSettings(settings), "only the low reservoir part, as after activation part 1")
        manager.updateExpirationAlertSettings(expiration)
        assertTrue(manager.sameAlertSettings(settings))
        assertFalse(manager.sameAlertSettings(settings.copy(lowReservoir = lowReservoir.copy(units = 10))))
        assertFalse(manager.sameAlertSettings(settings.copy(expiration = expiration.copy(alarmEnabled = false))))
    }

    @Test
    fun `active alerts come from the last status and are null before one`() {
        assertNull(manager.activeAlerts)
        manager.updateFromDefaultStatus(status(0, alerts = setOf(AlertType.LOW_RESERVOIR, AlertType.USER_SET_EXPIRATION)))
        assertEquals(setOf(AlertType.LOW_RESERVOIR, AlertType.USER_SET_EXPIRATION), manager.activeAlerts)
    }

    @Test
    fun `a pod in alarm or deactivated is kaput as Android`() {
        assertFalse(manager.isPodKaput, "no status yet")
        manager.updateFromDefaultStatus(status(0))
        assertFalse(manager.isPodKaput)
        manager.updateFromStatus(status(0, podStatus = PodStatus.ALARM))
        assertTrue(manager.isPodKaput)
        manager.updateFromDefaultStatus(status(0, podStatus = PodStatus.DEACTIVATED))
        assertTrue(manager.isPodKaput)
    }

    // ---------------------------------------------------------------- resume and deactivation

    @Test
    fun `a confirmed resume makes its program the running one`() {
        val old = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
        val resumed = BasalProgram(listOf(BasalProgram.Segment(0, 48, 80)))
        manager.overwriteForTest { it.copy(basalProgram = old) }
        val command = manager.createActiveCommand(DashCommandKind.RESUME_DELIVERY, historyId = 1L, createdAtMs = 500L, basalProgram = resumed)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(command.sequence))

        val resolved = manager.resolveActiveCommand(nowMs = 2_000L)

        assertEquals(DashCommandConfirmation.SUCCESS, resolved.confirmation)
        assertEquals(resumed, manager.basalProgram)
        assertEquals(resumed, reloaded().basalProgram, "in the same write that closes the command")
    }

    @Test
    fun `a refused resume keeps the program before`() {
        val old = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100)))
        manager.overwriteForTest { it.copy(basalProgram = old) }
        val command = manager.createActiveCommand(
            DashCommandKind.RESUME_DELIVERY, historyId = 1L, createdAtMs = 500L,
            basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 80)))
        )
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status((command.sequence + 1) and 0x0f))

        assertEquals(DashCommandConfirmation.DENIED, manager.resolveActiveCommand(nowMs = 2_000L).confirmation)
        assertEquals(old, manager.basalProgram)
    }

    @Test
    fun `a confirmed deactivation ends a running bolus with what the answer left`() {
        manager.createLastBolus(requestedUnits = 3.0, nowMs = 100L)
        val command = manager.createActiveCommand(DashCommandKind.DEACTIVATE, historyId = 1L, createdAtMs = 500L)
        manager.markActiveCommandSent()
        manager.updateFromDefaultStatus(status(command.sequence, podStatus = PodStatus.DEACTIVATED, bolusPulsesRemaining = 20))

        val resolved = manager.resolveActiveCommand(nowMs = 2_000L)

        val ended = assertNotNull(resolved.endedBolus)
        assertEquals(2.0, assertNotNull(ended.deliveredUnits()), 1e-9)
    }

    // ---------------------------------------------------------------- reset

    @Test
    fun `reset clears every field and stores the empty state`() {
        manager.updateFromPairing(uniqueId = 37879809L, ltk = ByteArray(16) { 1 })
        manager.increaseMessageSequenceNumber()
        manager.updateActivationProgress(ActivationProgress.COMPLETED, BasalProgram(listOf(BasalProgram.Segment(0, 48, 100))))
        manager.updateFromDefaultStatus(status(3))
        manager.createLastBolus(requestedUnits = 1.0, nowMs = 100L)
        manager.updateExpirationAlertSettings(expiration)
        manager.updateLowReservoirAlertSettings(lowReservoir)
        manager.updateSuspendAlertsEnabled(true)
        manager.createActiveCommand(DashCommandKind.TEMP_BASAL, historyId = 1L, createdAtMs = 500L)

        manager.reset()

        assertEquals(DashPodState(), manager.currentState())
        assertEquals(DashPodState(), reloaded().currentState())
        assertNull(manager.ltk)
        assertEquals(1L, manager.eapAkaSequenceNumber)
        assertEquals(0, manager.messageSequenceNumber)
    }
}
