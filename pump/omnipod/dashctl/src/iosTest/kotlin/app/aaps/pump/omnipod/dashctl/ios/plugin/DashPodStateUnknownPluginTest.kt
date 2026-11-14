package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOnFakePod
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.session.DashPodStateRead
import app.aaps.pump.omnipod.dashctl.session.DashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashPodStateUnknownException
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The iPhone was restarted and iOS started the app for a BLE event before the first unlock: the
 * Keychain cannot be read (`errSecInteractionNotAllowed`), while a pod runs on the body. The plugin
 * must not take this for "no pod": no "no pod" zero temp basal in `PumpSync` (the pod still gives its
 * basal, so insulin on board would be counted too low), no pod command, no new activation and no
 * discard (both would delete the key of the pod on the body), and an urgent notice. Once the Keychain
 * can be read, everything goes on with the stored pod.
 */
class DashPodStateUnknownPluginTest {

    /** The Keychain of a phone with a running pod, locked until [unlock]. */
    private class LockedKeychain(val stored: DashPodState) : DashPodStateStore {

        var locked = true
        val writes = mutableListOf<String>()

        override fun load(): DashPodState? = if (locked) null else stored

        override fun read(): DashPodStateRead =
            if (locked) DashPodStateRead.Unreadable("Keychain read status -25308") else DashPodStateRead.Loaded(stored)

        override fun save(state: DashPodState) {
            writes += "save"
        }

        override fun clear() {
            writes += "clear"
        }
    }

    private val runningPod = DashPodState(
        ltk = ByteArray(DashPodState.LTK_SIZE) { 9 },
        uniqueId = 37879809L,
        activationProgress = ActivationProgress.COMPLETED,
        reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        bluetoothAddress = "the pod on the body",
    )

    private val keychain = LockedKeychain(runningPod)
    private val f = DashPumpOnFakePod(stateManager = DashPodStateManager(keychain))
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

    private val recorder = DashPumpSyncRecorder(pumpSync, SilentPodLogger, f.stateManager::countAsTakenWhileUnknown) { f.stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR }

    private val pump = IosDashPluginPump(f.pump, f.stateManager, recorder, rules, SilentPodLogger, now = { f.nowMs })

    init {
        pumpSync.expectedProfile = BasalOnlyProfile(0 to 1.0)
    }

    @AfterTest
    fun closePod() {
        f.pod.close()
    }

    @Test
    fun `a locked Keychain writes no no-pod temp basal and posts an urgent notice`() = runTest {
        rules.statusCheck()

        assertTrue(pumpSync.calls.none { it.startsWith("tempBasal(") }, "the pod on the body still gives its basal: ${pumpSync.calls}")
        assertEquals(listOf("post(PUMP_ERROR, omnipod_dash_ios_pod_state_unreadable, sound=null)"), notices.posts())
        assertTrue(notices.calls.none { it.contains("OMNIPOD_POD_NOT_ATTACHED") }, "not \"no active pod\"")
        assertTrue(keychain.writes.isEmpty())
    }

    @Test
    fun `every pod command is refused with nothing sent while the Keychain is locked`() = runTest {
        val bolus = pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 })
        val tbr = pump.setTempBasalAbsolute(0.5, 30, enforceNew = false, tbrType = PumpSync.TemporaryBasalType.NORMAL)
        val cancel = pump.cancelTempBasal(enforceNew = false)
        val profile = pump.setNewBasalProfile(BasalOnlyProfile(0 to 1.0))
        pump.getPumpStatus("test")

        listOf(bolus, tbr, cancel, profile).forEach { assertFalse(it.success, it.comment) }
        assertFalse(profile.enacted)
        assertTrue(f.pod.commands.isEmpty(), "nothing reached the pod")
        assertTrue(pumpSync.calls.isEmpty(), "nothing recorded: ${pumpSync.calls}")
        assertTrue(keychain.writes.isEmpty())
    }

    @Test
    fun `no new activation and no discard can delete the stored key while the Keychain is locked`() = runTest {
        val part1 = rules.activatePodPart1()

        assertFalse(part1.success, part1.comment)
        assertFailsWith<DashPodStateUnknownException> { rules.discardPod() }
        assertTrue(keychain.writes.isEmpty(), "the item with the key of the pod on the body is never replaced: ${keychain.writes}")
        assertTrue(f.pod.commands.isEmpty())
    }

    @Test
    fun `once the Keychain can be read the stored pod is used again and the notice goes`() = runTest {
        rules.statusCheck()
        assertFalse(pump.isInitialized())

        keychain.locked = false
        rules.statusCheck()

        assertTrue(f.stateManager.isStateKnown)
        assertEquals(37879809L, f.stateManager.uniqueId)
        assertEquals("the pod on the body", f.stateManager.bluetoothAddress)
        assertTrue(pump.isInitialized(), "the loop may use the pod again")
        assertTrue(pumpSync.calls.none { it.startsWith("tempBasal(") }, "a running pod: still no no-pod temp basal")
        assertTrue(notices.calls.contains("dismiss(PUMP_ERROR)"))
    }
}
