package app.aaps.pump.medtrum.ios

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.pump.DetailedBolusInfoStorage
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.pump.ble.session.CoreBluetoothCentral
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.session.MedtrumSessionState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Builds the iOS Medtrum driver and owns the parts it needs: the radio, the clock, the stored
 * patch state and the coroutine scope.
 *
 * It exists so that `IosMedtrumPlugin` can delegate the whole [app.aaps.core.interfaces.pump.Pump]
 * interface to [driver] with one `by` clause. A Kotlin delegation target has to be ready when the
 * constructor runs, so it cannot be a property built inside the plugin body.
 *
 * ## Nothing here touches the radio
 *
 * Building this object is cheap and silent. [CoreBluetoothCentral] only creates its
 * `CBCentralManager` in `open()`, and `open()` is called from [openRadio] alone. So a graph that
 * builds the plugin does not start Bluetooth and does not make iOS ask the user for the Bluetooth
 * permission. The plugin calls [openRadio] when it starts and [shutdown] when it stops.
 */
@SingleIn(AppScope::class)
@Inject
class IosMedtrumDriverHost(
    private val aapsLogger: AAPSLogger,
    textResolver: TextResolver,
    pumpSync: PumpSync,
    detailedBolusInfoStorage: DetailedBolusInfoStorage,
    temporaryBasalStorage: TemporaryBasalStorage
) {

    private val logger: MedtrumLogger = AapsMedtrumLogger(aapsLogger)
    private val clock = systemMedtrumClock()
    private val central = CoreBluetoothCentral()
    private val transport = IosMedtrumBleTransport(central)

    /** Long lived patch state, read back from NSUserDefaults by the driver when it is built. */
    val state = MedtrumSessionState(logger = logger, clock = clock)

    /** The store the driver loads from and saves to. Also the only place the serial number lives today. */
    val store = IosMedtrumStateStore()

    /**
     * Work the driver starts by itself: a connect attempt and a bolus stop. Its own scope, not the
     * plugin scope, so [shutdown] ends it exactly when the plugin stops.
     */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    val driver = IosMedtrumPump(
        transport = transport,
        state = state,
        clock = clock,
        textResolver = textResolver,
        pumpSync = pumpSync,
        detailedBolusInfoStorage = detailedBolusInfoStorage,
        temporaryBasalStorage = temporaryBasalStorage,
        scope = scope,
        store = store,
        logger = logger
    )

    init {
        // The transport reads central events; the central has to know where to send them.
        central.events = transport::onEvent
    }

    /** Starts CoreBluetooth. The first call is what makes iOS ask for the Bluetooth permission. */
    fun openRadio() {
        aapsLogger.debug(LTag.PUMP, "IosMedtrumDriverHost: opening the radio")
        central.open()
    }

    /**
     * Ends the session and the driver's own work. After this the host must not be used again.
     *
     * The `CBCentralManager` itself is not released, because `CoreBluetoothCentral` has no close.
     * The link is dropped and the scan is stopped, so nothing is left running on the radio.
     */
    fun shutdown() {
        aapsLogger.debug(LTag.PUMP, "IosMedtrumDriverHost: shutting down")
        driver.disconnect("plugin stopped")
        driver.close()
        scope.cancel()
    }
}

/** Sends the Medtrum protocol log lines to the AAPS log, under [LTag.PUMPBTCOMM]. */
private class AapsMedtrumLogger(private val aapsLogger: AAPSLogger) : MedtrumLogger {

    override fun debug(tag: String, message: String) = aapsLogger.debug(LTag.PUMPBTCOMM, "$tag $message")
    override fun warn(tag: String, message: String) = aapsLogger.warn(LTag.PUMPBTCOMM, "$tag $message")
    override fun error(tag: String, message: String) = aapsLogger.error(LTag.PUMPBTCOMM, "$tag $message")
}
