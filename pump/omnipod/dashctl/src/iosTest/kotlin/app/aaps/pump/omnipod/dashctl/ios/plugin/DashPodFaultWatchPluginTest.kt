package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.pump.omnipod.dashctl.comm.connection.DashConnectResult
import app.aaps.pump.omnipod.dashctl.comm.connection.DashPodConnector
import app.aaps.pump.omnipod.dashctl.comm.io.SilentPodLogger
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.ios.pump.MemoryDashPodStateStore
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

/**
 * The fault listener path as `IosDashDriverHost` wires it: the connector (on the iPhone the fault
 * watch of `DashHeldConnection`) calls the listener the pump gave it ([IosDashPump.setPodFaultListener]),
 * and [DashPluginRules.onPodFaultAdvertised] queues a status read at once, as OmnipodKit (Trio)
 * `omnipodDidDetectAlert` reads the pod status.
 */
class DashPodFaultWatchPluginTest {

    /** The listener the pump gave to the connector. */
    private var faultListener: (() -> Unit)? = null

    private val connector = object : DashPodConnector {
        override val isConnecting: Boolean = false
        override val isHandshakeInProgress: Boolean = false
        override fun setPod(peripheralId: String) = Unit
        override fun hold(peripheralId: String, reason: String) = Unit
        override fun stopHolding(reason: String) = Unit
        override fun forget(reason: String) = Unit
        override fun awaitConnection(peripheralId: String, timeoutMs: Long): DashConnectResult = DashConnectResult.NotConnected("test")
        override fun awaitConnectionHoldingLock(peripheralId: String, timeoutMs: Long): DashConnectResult = DashConnectResult.NotConnected("test")
        override fun connectNewPod(timeoutMs: Long, onFound: (String) -> Unit): DashConnectResult = DashConnectResult.NotConnected("test")
        override fun setFaultListener(listener: () -> Unit) {
            faultListener = listener
        }
    }

    private val stateManager = DashPodStateManager(MemoryDashPodStateStore())
    private val pump = IosDashPump(aapsLogger = SilentPodLogger, onCommandDecided = {}, stateManager = stateManager, connectorFactory = { connector })
    private val queue = RecordingCommandQueue()
    private val launched = mutableListOf<suspend () -> Unit>()

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
        launch = { launched += it },
        now = { 0L },
    )

    @Test
    fun `a fault the connector sees reaches the plugin and queues a status read`() = runTest {
        stateManager.overwriteForTest { it.copy(activationProgress = ActivationProgress.COMPLETED) }
        pump.setPodFaultListener(rules::onPodFaultAdvertised)

        assertNotNull(faultListener, "the pump gave the listener to the connector").invoke()
        launched.toList().forEach { it() }

        assertEquals(listOf("readStatus(omnipod_dash_ios_pod_fault_seen)"), queue.queued)
    }
}
