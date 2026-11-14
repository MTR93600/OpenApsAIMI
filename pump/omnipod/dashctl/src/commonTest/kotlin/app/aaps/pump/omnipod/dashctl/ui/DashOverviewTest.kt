package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStatus
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pod screen: which buttons show and can be pressed, the pod status text and the warning colours,
 * the rules of Android `DashOverviewViewModel`.
 */
class DashOverviewTest {

    private fun status(
        podStatus: PodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        delivery: DeliveryStatus = DeliveryStatus.BASAL_ACTIVE,
        alerts: Set<AlertType> = emptySet()
    ) = DashPodStatus(
        podStatus = podStatus,
        deliveryStatus = delivery,
        totalPulsesDelivered = 100,
        reservoirPulsesRemaining = 1023,
        minutesSinceActivation = 60,
        bolusPulsesRemaining = 0,
        activeAlerts = alerts,
        lastProgrammingCommandSequence = 1,
        receivedAtMs = NOW
    )

    private val running = DashPodState(
        ltk = ByteArray(16),
        uniqueId = 37879809L,
        activationProgress = ActivationProgress.COMPLETED,
        bluetoothAddress = "pod",
        podStatus = status(),
        reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        lastUpdatedSystemMs = NOW,
        podLifeInHours = 80
    )

    private fun buttons(state: DashPodState, queueEmpty: Boolean = true, inQueue: Set<KClass<out CustomCommand>> = emptySet()) =
        dashOverviewButtons(stateManagerWith(state), queueEmpty) { it in inQueue }.associateBy { it.action }

    @Test
    fun `with no pod only Activate shows and Refresh cannot be pressed`() {
        val b = buttons(DashPodState())

        assertTrue(b.getValue(DashOverviewAction.ACTIVATE_POD).visible)
        assertTrue(b.getValue(DashOverviewAction.REFRESH).visible)
        assertFalse(b.getValue(DashOverviewAction.REFRESH).enabled, "nothing to read before SET_UNIQUE_ID")
        listOf(
            DashOverviewAction.SILENCE_ALERTS, DashOverviewAction.RESUME_DELIVERY, DashOverviewAction.DEACTIVATE_POD,
            DashOverviewAction.PLAY_TEST_BEEP, DashOverviewAction.DISCARD_POD
        ).forEach { assertFalse(b.getValue(it).visible, "$it with no pod") }
    }

    @Test
    fun `a running pod can be refreshed deactivated and beeped but not activated again`() {
        val b = buttons(running)

        assertTrue(b.getValue(DashOverviewAction.REFRESH).enabled)
        assertFalse(b.getValue(DashOverviewAction.ACTIVATE_POD).visible)
        assertTrue(b.getValue(DashOverviewAction.DEACTIVATE_POD).visible)
        assertTrue(b.getValue(DashOverviewAction.PLAY_TEST_BEEP).visible)
        assertTrue(b.getValue(DashOverviewAction.PLAY_TEST_BEEP).enabled)
        assertFalse(b.getValue(DashOverviewAction.SILENCE_ALERTS).visible, "no alerts")
        assertFalse(b.getValue(DashOverviewAction.RESUME_DELIVERY).visible, "not suspended")
        assertFalse(b.getValue(DashOverviewAction.DISCARD_POD).visible)
    }

    @Test
    fun `a busy queue disables the pod commands`() {
        val b = buttons(running.copy(podStatus = status(delivery = DeliveryStatus.SUSPENDED, alerts = setOf(AlertType.LOW_RESERVOIR))), queueEmpty = false)

        assertFalse(b.getValue(DashOverviewAction.REFRESH).enabled)
        assertTrue(b.getValue(DashOverviewAction.SILENCE_ALERTS).visible)
        assertFalse(b.getValue(DashOverviewAction.SILENCE_ALERTS).enabled)
        assertTrue(b.getValue(DashOverviewAction.RESUME_DELIVERY).visible)
        assertFalse(b.getValue(DashOverviewAction.RESUME_DELIVERY).enabled)
    }

    @Test
    fun `a command waiting in the queue keeps its button and a waiting beep cannot be pressed again`() {
        val b = buttons(running, inQueue = setOf(CommandResumeDelivery::class, CommandPlayTestBeep::class))

        assertTrue(b.getValue(DashOverviewAction.RESUME_DELIVERY).visible)
        assertFalse(b.getValue(DashOverviewAction.PLAY_TEST_BEEP).enabled)
    }

    @Test
    fun `a pod paired but stopped before SET_UNIQUE_ID can be discarded and deactivated and activated again`() {
        val b = buttons(DashPodState(ltk = ByteArray(16), uniqueId = 37879809L, bluetoothAddress = "pod", activationProgress = ActivationProgress.GOT_POD_VERSION))

        assertTrue(b.getValue(DashOverviewAction.DISCARD_POD).visible)
        assertTrue(b.getValue(DashOverviewAction.DEACTIVATE_POD).visible)
        assertTrue(b.getValue(DashOverviewAction.ACTIVATE_POD).visible)
        assertFalse(b.getValue(DashOverviewAction.PLAY_TEST_BEEP).visible)
    }

    @Test
    fun `the pod status text follows the activation progress as Android`() {
        fun text(state: DashPodState) = dashPodStatusText(stateManagerWith(state), NameResolver)

        assertEquals("omnipod_dash_ios_no_active_pod", text(DashPodState()))
        assertEquals("dash_pod_status_waiting_for_activation", text(DashPodState(activationProgress = ActivationProgress.SET_UNIQUE_ID)))
        assertEquals("dash_pod_status_waiting_for_cannula_insertion", text(DashPodState(activationProgress = ActivationProgress.PHASE_1_COMPLETED)))
        assertEquals("dash_pod_status_running", text(running))
        assertEquals("dash_pod_status_suspended", text(running.copy(podStatus = status(delivery = DeliveryStatus.SUSPENDED))))
        assertEquals("ALARM", text(running.copy(reportedPodStatus = PodStatus.ALARM)))
    }

    @Test
    fun `a half activated or faulted pod is red and an open command is yellow`() {
        assertEquals(StatusLevel.CRITICAL, dashPodStatusLevel(stateManagerWith(DashPodState(activationProgress = ActivationProgress.PHASE_1_COMPLETED))))
        assertEquals(StatusLevel.CRITICAL, dashPodStatusLevel(stateManagerWith(running.copy(reportedPodStatus = PodStatus.ALARM))))
        assertEquals(StatusLevel.NORMAL, dashPodStatusLevel(stateManagerWith(running)))
        val open = DashActiveCommand(sequence = 3, kind = DashCommandKind.BOLUS, historyId = 1L, statusCountAtCreation = 0L, requestedBolusUnits = 1.0, createdAtMs = NOW)
        assertEquals(StatusLevel.WARNING, dashPodStatusLevel(stateManagerWith(running.copy(activeCommand = open))))
    }

    @Test
    fun `an unconfirmed bolus shows as uncertain and red and a fault shows in the errors`() {
        val open = DashActiveCommand(sequence = 3, kind = DashCommandKind.BOLUS, historyId = 1L, statusCountAtCreation = 0L, requestedBolusUnits = 1.0, createdAtMs = NOW)
        val rows = dashOverviewRows(stateManagerWith(running.copy(activeCommand = open, alarmType = AlarmType.ALARM_OCCLUDED)), NameResolver, PlainFormat, NOW)
            .associateBy { it.label }

        val bolus = rows.getValue("last_bolus_label")
        assertEquals(StatusLevel.CRITICAL, bolus.level)
        assertTrue("dash_overview_uncertain" in bolus.value, bolus.value)
        val errors = rows.getValue("errors")
        assertEquals(StatusLevel.CRITICAL, errors.level)
        assertTrue(errors.value.startsWith("omnipod_dash_ios_pod_fault"), errors.value)
    }

    @Test
    fun `with no pod the screen shows the address and the status only`() {
        val rows = dashOverviewRows(stateManagerWith(DashPodState()), NameResolver, PlainFormat, NOW)

        assertEquals(listOf("dash_overview_bluetooth_address", "dash_overview_pod_status"), rows.map { it.label })
    }

    /** Writes values plainly, so a test reads which value went where. */
    private object PlainFormat : DashOverviewFormat {

        override fun dateAndTime(ms: Long): String = "t$ms"
        override fun insulin(units: Double): String = "${units}U"
        override fun insulinAgo(units: Double, atMs: Long): String = "${units}U@$atMs"
        override fun basalRate(rate: Double): String = "${rate}U/h"
        override fun tempBasal(rate: Double, startMs: Long, minutes: Int): String = "${rate}U/h@$startMs/$minutes"
        override fun ago(ms: Long): String = "ago$ms"
        override fun timeRemaining(durationMs: Long): String = "left$durationMs"
    }

    private companion object {

        const val NOW = 1_700_000_000_000L
    }
}
