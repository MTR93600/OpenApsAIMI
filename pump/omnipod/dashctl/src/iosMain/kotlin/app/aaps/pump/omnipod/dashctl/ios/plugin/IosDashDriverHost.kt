package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.BolusProgressData
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.omnipod.dashctl.ios.ble.IosDashBleHost
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.ios.store.DashKeychainStateStore
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.runBlocking

/**
 * Builds the iOS Dash driver and owns the parts it needs, the counterpart of `IosMedtrumDriverHost`:
 * the pod state in the Keychain ([DashPodStateManager] over [DashKeychainStateStore]), the
 * CoreBluetooth side ([IosDashBleHost]), the driver ([IosDashPump]), the `PumpSync` recorder
 * ([DashPumpSyncRecorder]) and the [Pump][app.aaps.core.interfaces.pump.Pump] the plugin gives to
 * the app ([pump]).
 *
 * It exists so that `IosOmnipodDashPlugin` can delegate the whole `Pump` interface with one `by`
 * clause: a delegation target has to be ready when the plugin constructor runs.
 *
 * ## Nothing here touches the radio
 *
 * Building this object reads the Keychain and builds objects, nothing more. The CoreBluetooth
 * central makes its `CBCentralManager` only in [openRadio], which the plugin calls when it starts.
 * So a graph that builds the plugin does not start Bluetooth and does not make iOS ask for the
 * Bluetooth permission.
 *
 * ## What the driver reads from the app
 *
 * - every outcome goes to the recorder, which writes `PumpSync`;
 * - the beep settings come from the user settings with Android's keys and defaults ([DashBeepKey]),
 *   read for each command;
 * - whether AAPS expects a temp basal to run comes from `PumpSync.expectedPumpState()` (Android
 *   `cancelTempBasal`), read when a cancel is asked.
 */
@SingleIn(AppScope::class)
@Inject
class IosDashDriverHost(
    private val aapsLogger: AAPSLogger,
    private val pumpSync: PumpSync,
    private val preferences: Preferences,
    bolusProgressData: BolusProgressData,
) {

    /** The pod state, loaded from the Keychain now. */
    val stateManager = DashPodStateManager(DashKeychainStateStore())

    private val bleHost = IosDashBleHost(aapsLogger)

    val recorder = DashPumpSyncRecorder(
        pumpSync = pumpSync,
        aapsLogger = aapsLogger,
        serialNumber = { stateManager.uniqueId?.toString() ?: IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR },
    )

    val driver = IosDashPump(
        aapsLogger = aapsLogger,
        onCommandDecided = recorder::onOutcome,
        stateManager = stateManager,
        bolusProgressData = bolusProgressData,
        beeps = { DashBeepKey.beepSettings { key -> preferences.get(key) } },
        expectedTempBasalRunning = { expectedTempBasalRunning(pumpSync, aapsLogger) },
        connectorFactory = bleHost.connectorFactory(stateManager.bluetoothAddress),
    )

    /** What the plugin delegates `Pump` to. */
    val pump = IosDashPluginPump(driver = driver, stateManager = stateManager, recorder = recorder, aapsLogger = aapsLogger)

    /**
     * One run of the Android status checker part that is ported now: the zero temp basal while no
     * pod runs ([writeNoPodTempBasal]). An error is logged and the next run tries again, as Android.
     */
    suspend fun checkNoActivePod() {
        try {
            writeNoPodTempBasal(stateManager.isPodRunning, pumpSync, Clock.System.now().toEpochMilliseconds(), aapsLogger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.warn(LTag.PUMP, "Error on createFakeTBRWhenNoActivePod=$e")
        }
    }

    /** Starts CoreBluetooth. The first call makes iOS ask for the Bluetooth permission. */
    fun openRadio() {
        aapsLogger.debug(LTag.PUMP, "IosDashDriverHost: opening the radio")
        bleHost.open()
    }

    /**
     * The plugin stops: the held connection stops and the link is closed (Android `disconnect`).
     * The pod state stays in the Keychain, so starting the plugin again goes on with the same pod.
     * The `CBCentralManager` is not released (`CoreBluetoothCentral` has no close).
     */
    fun shutdown() {
        aapsLogger.debug(LTag.PUMP, "IosDashDriverHost: shutting down")
        driver.disconnect("plugin stopped")
    }
}

/**
 * Android `pumpSync.expectedPumpState().temporaryBasal != null`, for `IosDashPump.cancelTempBasal`.
 * When `PumpSync` cannot answer, true: the cancel is then sent (fail closed).
 */
internal fun expectedTempBasalRunning(pumpSync: PumpSync, aapsLogger: AAPSLogger): Boolean =
    try {
        runBlocking { pumpSync.expectedPumpState().temporaryBasal != null }
    } catch (e: Exception) {
        aapsLogger.error(LTag.PUMP, "Dash: expected pump state not read, the cancel is sent", e)
        true
    }

/**
 * Android `OmnipodDashPumpPlugin.createFakeTBRWhenNoActivePod`: with no running pod, a zero temp basal
 * of the PUMP_SUSPEND type for the pod lifetime (80 h), with the serial "4241", unless a zero one
 * already runs. So AAPS does not count the profile basal while no pod gives it. Android keeps "4241"
 * here even with a known pod: changing the serial would need `connectNewPump`, and then the temp
 * basal would be started by one serial and stopped by another.
 *
 * @return true when the temp basal was written
 */
internal suspend fun writeNoPodTempBasal(podRunning: Boolean, pumpSync: PumpSync, nowMs: Long, aapsLogger: AAPSLogger): Boolean {
    if (podRunning) return false
    val tbr = pumpSync.expectedPumpState().temporaryBasal
    if (tbr != null && tbr.rate == 0.0) return false
    aapsLogger.info(LTag.PUMP, "createFakeTBRWhenNoActivePod")
    return pumpSync.syncTemporaryBasalWithPumpId(
        timestamp = nowMs,
        rate = PumpRate(0.0),
        duration = PodConstants.MAX_POD_LIFETIME.inWholeMilliseconds,
        isAbsolute = true,
        type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
        // Android: not used, only unique.
        pumpId = nowMs,
        pumpType = PumpType.OMNIPOD_DASH,
        pumpSerial = IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR
    )
}
