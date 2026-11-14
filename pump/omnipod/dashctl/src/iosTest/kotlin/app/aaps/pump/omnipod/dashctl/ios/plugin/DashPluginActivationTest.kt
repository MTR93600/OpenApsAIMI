package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashExpirationAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashLowReservoirAlertSettings
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What the Android activation wizard does around the two parts (`DashOmnipodWizardViewModel`
 * `doInitializePod` and `doInsertCannula`), through [DashPluginRules] on the real driver and the
 * simulated pod being activated: the alert settings of the user go into the parts, and after part 2
 * the zero temp basal of "no pod" ends, the new pod is registered in `PumpSync`, the cannula and insulin
 * changes are recorded, and a status is read.
 */
class DashPluginActivationTest {

    private val f = DashPumpOnFakePod()
    private val model = ActivatingPodModel()
    private val pumpSync = RecordingDashPumpSync()
    private val notices = RecordingNotificationManager(NameTextResolver)
    private val queue = RecordingCommandQueue()
    private val launched = mutableListOf<suspend () -> Unit>()

    private val rules = DashPluginRules(
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

    init {
        f.answerAsActivatingPod(model)
        f.nowMs = NOW
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
    }

    @Test
    fun `after part 2 PumpSync is told of the new pod as Android`() = runTest {
        pumpSync.expectedProfile = BasalOnlyProfile(0 to 1.0)

        val part1 = rules.activatePodPart1()
        assertTrue(part1.success, part1.comment)
        assertEquals(DashLowReservoirAlertSettings(enabled = true, units = 20), f.stateManager.lowReservoirAlertSettings, "Android defaults")
        assertTrue(pumpSync.calls.isEmpty(), "nothing is written after part 1")

        val part2 = rules.activatePodPart2()
        assertTrue(part2.success, part2.comment)
        assertEquals(ActivationProgress.COMPLETED, f.stateManager.activationProgress)
        assertEquals(DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 4, alarmEnabled = true, alarmHours = 8), f.stateManager.expirationAlertSettings)

        val serial = f.stateManager.uniqueId.toString()
        assertEquals(
            listOf(
                "stopTempBasal(timestamp=$NOW, endPumpId=$NOW, pumpType=OMNIPOD_DASH, serial=4241)",
                "connectNewPump",
                "therapyEvent(timestamp=$NOW, type=CANNULA_CHANGE, serial=$serial)",
                "therapyEvent(timestamp=$NOW, type=INSULIN_CHANGE, serial=$serial)",
            ),
            pumpSync.calls,
            "the events carry the serial of the new pod"
        )
        assertTrue("dismiss(OMNIPOD_POD_NOT_ATTACHED)" in notices.calls)
        launched.forEach { it() }
        assertEquals(listOf("readStatus(omnipod_dash_ios_pod_activated)"), queue.queued)
    }

    @Test
    fun `part 2 with no profile fails with nothing sent and nothing written`() = runTest {
        assertTrue(rules.activatePodPart1().success)
        val commands = f.pod.commands.size

        assertFalse(rules.activatePodPart2().success)

        assertEquals(commands, f.pod.commands.size)
        assertTrue(pumpSync.calls.isEmpty())
    }

    private companion object {

        const val NOW = 1_700_000_000_000L
    }
}
