package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.ios.pump.MemoryDashPodStateStore
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSThread

/**
 * The wizard model runs on the main thread (`IosDashDriverHost.wizardScope`). The pod work of an
 * activation part blocks its thread: the scan, the connect (up to 30 s), the pairing and each command.
 * Android subscribes it on `aapsSchedulers.io`. On iOS a blocked main thread freezes the app, and iOS
 * stops an app whose main thread does not answer, maybe between the pairing and the save of the key.
 * So [IosDashWizardPod] must run the pod work on another thread than the one that calls it.
 */
class DashWizardPodThreadTest {

    /** The thread the scan for a new pod ran on. */
    private var scanThread: NSThread? = null

    private val connector = object : DashPodConnector {
        override val isConnecting: Boolean = false
        override val isHandshakeInProgress: Boolean = false
        override fun setPod(peripheralId: String) = Unit
        override fun hold(peripheralId: String, reason: String) = Unit
        override fun stopHolding(reason: String) = Unit
        override fun forget(reason: String) = Unit
        override fun awaitConnection(peripheralId: String, timeoutMs: Long): DashConnectResult = DashConnectResult.NotConnected("test")
        override fun awaitConnectionHoldingLock(peripheralId: String, timeoutMs: Long): DashConnectResult = DashConnectResult.NotConnected("test")
        override fun connectNewPod(timeoutMs: Long, onFound: (String) -> Unit): DashConnectResult {
            scanThread = NSThread.currentThread
            return DashConnectResult.NotConnected("no pod in range")
        }

        override fun setFaultListener(listener: () -> Unit) = Unit
    }

    private val stateManager = DashPodStateManager(MemoryDashPodStateStore())
    private val pump = IosDashPump(aapsLogger = SilentPodLogger, onCommandDecided = {}, stateManager = stateManager, connectorFactory = { connector })
    private val queue = RecordingCommandQueue()

    private val rules = DashPluginRules(
        driver = pump,
        stateManager = stateManager,
        pumpSync = RecordingDashPumpSync(),
        notificationManager = RecordingNotificationManager(NameTextResolver),
        readBoolean = { key: BooleanNonPreferenceKey -> key.defaultValue },
        readInt = { key: IntNonPreferenceKey -> key.defaultValue },
        rh = NameTextResolver,
        aapsLogger = SilentPodLogger,
        commandQueue = { queue },
        rxBus = { RecordingRxBus() },
        uiInteraction = { RecordingUiInteraction() },
        serialNumber = { IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
        launch = {},
        now = { 0L },
    )

    /** With its default: the IO dispatcher, as `IosDashDriverHost` builds it. */
    private val wizardPod = IosDashWizardPod(rules = rules, stateManager = stateManager, commandQueue = { queue }, openRadio = {})

    @Test
    fun `the pod work of an activation part does not run on the thread of the wizard`() = runTest {
        val wizardThread = NSThread.currentThread

        val result = wizardPod.activatePart1()

        assertFalse(result.success, "no pod in range")
        val scan = assertNotNull(scanThread, "the scan ran")
        assertFalse(scan == wizardThread, "the scan blocked the thread of the wizard")
    }
}
