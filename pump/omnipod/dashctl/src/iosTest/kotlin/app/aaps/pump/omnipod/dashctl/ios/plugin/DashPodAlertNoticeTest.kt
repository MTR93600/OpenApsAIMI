package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.comm.session.FakeDashPod.Packet
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.SilenceAlertsCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.util.AlertUtil
import app.aaps.pump.omnipod.dashctl.queue.command.CommandAcknowledgeAlert
import app.aaps.pump.omnipod.dashctl.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.dashctl.session.DashPodStateCodec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The pod alerts on the phone: one notice with the active alerts and a "Silence" button for each, one
 * alert silenced at a time, and a silence that did not reach the pod kept and sent again, on the real
 * driver against the simulated pod. Not in Android; from OmnipodKit (Trio) `alertsChanged`,
 * `issueAlert`, `acknowledgeAlert` and `silenceAcknowledgedAlerts` (see `DashPluginRules.updatePodAlerts`).
 *
 * Message numbers after a fresh pairing: 1-4 pairing, 5-6 session, 7 the first command and 8 its ACK.
 */
class DashPodAlertNoticeTest {

    private val f = DashPumpOnFakePod()
    private val pumpSync = RecordingDashPumpSync()
    private val notices = RecordingNotificationManager(NameTextResolver)
    private val queue = CommandKeepingQueue()

    /** Queue work the rules started on their own (Android `pluginScope.launch`); run by [runLaunched]. */
    private val launched = mutableListOf<suspend () -> Unit>()

    private var rules = newRules()
    private var pump = newPump(rules)

    init {
        f.nowMs = SENT_AT
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    /** The plugin part, as `IosDashDriverHost` builds it; a new one is what an app restart gives. */
    private fun newRules() = DashPluginRules(
        driver = f.pump,
        stateManager = f.stateManager,
        pumpSync = pumpSync,
        notificationManager = notices,
        readBoolean = { key: BooleanNonPreferenceKey -> key.defaultValue },
        readInt = { key: IntNonPreferenceKey -> key.defaultValue },
        rh = NameTextResolver,
        aapsLogger = SilentPodLogger,
        commandQueue = { queue },
        rxBus = { RecordingRxBus() },
        uiInteraction = { RecordingUiInteraction() },
        serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
        launch = { launched += it },
        now = { f.nowMs },
    )

    private fun newPump(rules: DashPluginRules): IosDashPluginPump {
        val recorder = DashPumpSyncRecorder(
            pumpSync = pumpSync,
            aapsLogger = SilentPodLogger,
            countAsTakenWhileUnknown = f.stateManager::countAsTakenWhileUnknown,
            serialNumber = { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
        )
        f.onOutcome = {
            recorder.onOutcome(it)
            rules.onOutcome(it)
        }
        f.onSent = recorder::onCommandSent
        return IosDashPluginPump(f.pump, f.stateManager, recorder, rules, SilentPodLogger, now = { f.nowMs })
    }

    /** The app restarts: the plugin part is built again on the stored pod state, notices are gone. */
    private fun restartApp() {
        rules = newRules()
        pump = newPump(rules)
    }

    /** Runs the queue work the rules started, then the queue commands it queued, as the queue would. */
    private suspend fun runLaunched() {
        val work = launched.toList()
        launched.clear()
        work.forEach { it() }
        val commands = queue.commands.toList()
        queue.commands.clear()
        commands.forEach { pump.executeCustomCommand(it) }
    }

    private fun alertNotice(vararg names: String) =
        "post(OMNIPOD_POD_ALERTS, omnipod_dash_ios_pod_alerts[${names.joinToString("\n")}], sound=null)"

    /** The posts of the pod alert notice; the other notices of the status checker are not about this. */
    private fun alertPosts(): List<String> = notices.posts().filter { it.startsWith("post(OMNIPOD_POD_ALERTS") }

    private fun silenceLabel(name: String) = "omnipod_dash_ios_silence_alert[$name]"

    private fun buttons(): List<String> = notices.actions[NotificationId.OMNIPOD_POD_ALERTS].orEmpty().map { NameTextResolver.gs(it.buttonText) }

    /**
     * The user taps a button of the notice. As the shared UI (`NotificationBottomSheet`): the action
     * runs, then the whole card is closed through the notification manager, not through the plugin.
     */
    private fun pressButton(label: String) {
        notices.actions.getValue(NotificationId.OMNIPOD_POD_ALERTS).single { NameTextResolver.gs(it.buttonText) == label }.action()
        notices.actions.remove(NotificationId.OMNIPOD_POD_ALERTS)
        notices.calls += "closed by the UI(OMNIPOD_POD_ALERTS)"
    }

    /** Runs only the queue work the rules started (the queue calls), not the commands it queued. */
    private suspend fun runLaunchedWorkOnly() {
        val work = launched.toList()
        launched.clear()
        work.forEach { it() }
    }

    private fun silenceOf(sequence: Int, alerts: Set<AlertType>): String = SilenceAlertsCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setAlertTypes(alerts)
        .build().encoded.toParityHex()

    @Test
    fun `an alert at a status read is posted once with a silence button`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.pairAndConnect()

        pump.getPumpStatus("first")
        pump.getPumpStatus("second")

        assertEquals(listOf(alertNotice(LOW_RESERVOIR)), alertPosts(), "posted again only when the list changes")
        assertEquals(listOf(silenceLabel(LOW_RESERVOIR)), buttons())
    }

    @Test
    fun `silencing one alert from its notice silences only that slot and the notice keeps the other`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT or USER_EXPIRATION_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")
        assertEquals(listOf(alertNotice(EXPIRATION, LOW_RESERVOIR)), alertPosts(), "fixture")
        val start = f.pod.commands.size

        pressButton(silenceLabel(LOW_RESERVOIR))
        runLaunched()

        assertEquals(listOf(silenceOf(2, setOf(AlertType.LOW_RESERVOIR))), f.pod.commands.drop(start).map { it.toParityHex() })
        assertEquals(setOf(AlertType.USER_SET_EXPIRATION), f.stateManager.activeAlerts)
        assertEquals(alertNotice(EXPIRATION), alertPosts().last())
        assertEquals(listOf(silenceLabel(EXPIRATION)), buttons())
    }

    @Test
    fun `a silence that did not reach the pod is not shown again after a restart and is sent at the next status read`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")
        f.pod.dropLinkOnPhoneMessage = 9 to Packet.FIRST

        pressButton(silenceLabel(LOW_RESERVOIR))
        runLaunched()

        assertEquals(setOf(AlertType.LOW_RESERVOIR), f.stateManager.activeAlerts, "the pod did not get it")
        assertEquals(listOf(alertNotice(LOW_RESERVOIR)), alertPosts(), "the user silenced it: not posted again")
        assertTrue("dismiss(OMNIPOD_POD_ALERTS)" in notices.calls, "the notice is marked as gone: ${notices.calls}")
        // What the Keychain holds after the restart.
        assertEquals(setOf(AlertType.LOW_RESERVOIR), DashPodStateCodec.decode(DashPodStateCodec.encode(f.store.saved!!)).acknowledgedAlerts)

        restartApp()
        notices.calls.clear()
        rules.statusCheck()
        assertEquals(emptyList(), alertPosts(), "a silenced alert is not shown again after the restart")
        assertTrue(launched.isEmpty(), "the status checker sends nothing")

        f.pod.dropLinkOnPhoneMessage = null
        f.reconnect()
        val start = f.pod.commands.size
        pump.getPumpStatus("next read")
        runLaunched()

        val silences = f.pod.commands.drop(start).filter { it[6] == CommandType.SILENCE_ALERTS.value }
        assertEquals(listOf(AlertUtil.encodeAlertSet(setOf(AlertType.LOW_RESERVOIR))), silences.map { it[12] }, "only that slot")
        assertEquals(emptySet(), f.stateManager.activeAlerts)
        assertEquals(emptySet(), f.stateManager.acknowledgedAlerts)
        assertEquals(emptyList(), alertPosts())
    }

    @Test
    fun `a silence the pod refused after a tap on the notice shows the notice again`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT or USER_EXPIRATION_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")
        assertEquals(listOf(alertNotice(EXPIRATION, LOW_RESERVOIR)), alertPosts(), "fixture")
        val normal = f.pod.answerFor
        f.pod.answerFor = { command ->
            // Refused: the sequence of the last programming, the basal running and both alerts still on.
            if (command[6] == CommandType.SILENCE_ALERTS.value) {
                podDefaultStatus(
                    f.podStatusCode, f.lastProgrammingSequence,
                    deliveryStatus = ActivatingPodModel.BASAL_ACTIVE_DELIVERY, activeAlerts = f.activeAlerts,
                )
            } else normal(command)
        }

        pressButton(silenceLabel(LOW_RESERVOIR))
        runLaunched()

        assertEquals(setOf(AlertType.LOW_RESERVOIR, AlertType.USER_SET_EXPIRATION), f.stateManager.activeAlerts, "the pod refused")
        assertEquals(emptySet(), f.stateManager.acknowledgedAlerts)
        assertEquals(2, alertPosts().size, "the card the UI closed is posted again: ${notices.calls}")
        assertEquals(alertNotice(EXPIRATION, LOW_RESERVOIR), alertPosts().last())
        assertEquals(listOf(silenceLabel(EXPIRATION), silenceLabel(LOW_RESERVOIR)), buttons())
    }

    @Test
    fun `a tap on the notice while a single silence waits in the queue is not lost`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT or USER_EXPIRATION_BIT
        f.pairAndConnect()
        f.stateManager.addAcknowledgedAlert(AlertType.LOW_RESERVOIR)
        pump.getPumpStatus("status")
        assertEquals(listOf(alertNotice(EXPIRATION)), alertPosts(), "fixture: low reservoir is silenced already")
        runLaunchedWorkOnly()
        assertEquals(listOf(AlertType.LOW_RESERVOIR), queue.commands.map { (it as CommandAcknowledgeAlert).alert }, "fixture: queued again")

        pressButton(silenceLabel(EXPIRATION))
        runLaunchedWorkOnly()
        assertEquals(1, queue.commands.size, "the queue refuses a second single silence, as CommandQueueImplementation")
        val waiting = queue.commands.toList()
        queue.commands.clear()
        waiting.forEach { pump.executeCustomCommand(it) }

        assertEquals(setOf(AlertType.USER_SET_EXPIRATION), f.stateManager.activeAlerts, "fixture: low reservoir silenced")
        assertEquals(setOf(AlertType.USER_SET_EXPIRATION), f.stateManager.acknowledgedAlerts, "the tap is kept")
        assertEquals(emptyList(), alertPosts().drop(1), "the user silenced it: no notice")

        pump.getPumpStatus("next read")
        runLaunched()

        assertEquals(emptySet(), f.stateManager.activeAlerts, "sent at the next status read")
        assertEquals(emptySet(), f.stateManager.acknowledgedAlerts)
    }

    @Test
    fun `a silenced alert is sent again only while the pod answered just now`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")
        f.stateManager.addAcknowledgedAlert(AlertType.LOW_RESERVOIR)

        f.nowMs = SENT_AT + 10 * ONE_MINUTE
        rules.updatePodAlerts(silenceAcknowledged = true)
        assertTrue(launched.isEmpty(), "an old status: the pod may be out of reach")

        f.nowMs = SENT_AT + ONE_MINUTE
        rules.updatePodAlerts(silenceAcknowledged = true)
        launched.toList().forEach { it() }
        assertEquals(listOf(AlertType.LOW_RESERVOIR), queue.commands.map { (it as CommandAcknowledgeAlert).alert })
    }

    @Test
    fun `an alert the pod no longer reports is forgotten and the notice goes`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT or USER_EXPIRATION_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")
        f.stateManager.addAcknowledgedAlert(AlertType.LOW_RESERVOIR)

        f.activeAlerts = 0
        pump.getPumpStatus("cleared")

        assertEquals(emptySet(), f.stateManager.acknowledgedAlerts)
        assertEquals("dismiss(OMNIPOD_POD_ALERTS)", notices.calls.last())
    }

    @Test
    fun `silence all still silences every alert and the notice goes`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT or USER_EXPIRATION_BIT
        f.pairAndConnect()
        pump.getPumpStatus("status")

        assertTrue(pump.executeCustomCommand(CommandSilenceAlerts())!!.success)

        assertEquals(emptySet(), f.stateManager.activeAlerts)
        assertEquals("dismiss(OMNIPOD_POD_ALERTS)", notices.calls.last())
    }

    @Test
    fun `the status checker shows the alerts of the stored status after a restart`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.pairAndConnect()

        rules.statusCheck()

        assertTrue(alertNotice(LOW_RESERVOIR) in alertPosts(), notices.calls.toString())
        assertTrue(f.pod.commands.isEmpty(), "nothing sent")
    }

    @Test
    fun `the suspend ended alert has no silence button while delivery is suspended`() = runTest {
        f.activeAlerts = SUSPEND_ENDED_BIT or LOW_RESERVOIR_BIT
        f.pairAndConnect(basalOfThisSession = false)
        f.giveStatusOfThisSession(basal = false)

        rules.updatePodAlerts(silenceAcknowledged = false)

        assertEquals(listOf(alertNotice(LOW_RESERVOIR, SUSPENDED)), alertPosts())
        assertEquals(listOf(silenceLabel(LOW_RESERVOIR)), buttons())
    }

    @Test
    fun `no alert notice for a pod in alarm`() = runTest {
        f.activeAlerts = LOW_RESERVOIR_BIT
        f.podStatusCode = DashPumpOnFakePod.ALARM
        f.pairAndConnect()

        rules.updatePodAlerts(silenceAcknowledged = false)

        assertEquals(emptyList(), alertPosts())
    }

    /** A [RecordingCommandQueue] that also keeps each queued custom command, so the test can run it. */
    private class CommandKeepingQueue : RecordingCommandQueue() {

        val commands = mutableListOf<CustomCommand>()

        override fun isCustomCommandInQueue(customCommandType: KClass<out CustomCommand>): Boolean =
            commands.any { it::class == customCommandType } || super.isCustomCommandInQueue(customCommandType)

        /** As `CommandQueueImplementation.customCommand`: a command of a class already waiting is refused, not queued. */
        override suspend fun customCommand(customCommand: CustomCommand): PumpEnactResult {
            if (isCustomCommandInQueue(customCommand::class)) return DashPumpEnactResult(success = false, enacted = false, comment = "executing now")
            commands += customCommand
            return DashPumpEnactResult(success = true, enacted = false)
        }
    }

    private companion object {

        const val SENT_AT = 1_794_564_000_000L
        const val ONE_MINUTE = 60_000L

        /** Android `SetUniqueIdResponseTest`: unique id 37879809. */
        const val UNIQUE_ID = 37879809

        const val LOW_RESERVOIR_BIT = 0x10
        const val USER_EXPIRATION_BIT = 0x08
        const val SUSPEND_ENDED_BIT = 0x40

        const val LOW_RESERVOIR = "dash_alert_low_reservoir"
        const val EXPIRATION = "dash_alert_expiration"
        const val SUSPENDED = "dash_alert_delivery_suspended"
    }
}
