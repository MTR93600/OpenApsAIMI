package app.aaps.plugins.source

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventMobileToWear
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.dexcomoneplus.OnePlusAndroidPlatform
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDriver
import app.aaps.plugins.dexcomoneplus.OnePlusCgmDrivers
import app.aaps.plugins.dexcomoneplus.OnePlusGlucoseWatcher
import app.aaps.plugins.dexcomoneplus.OnePlusInstalledApps
import app.aaps.plugins.dexcomoneplus.OnePlusInstalledAppsAndroid
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import app.aaps.plugins.dexcomoneplus.identity.OnePlusSensorStore
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScanner
import app.aaps.plugins.dexcomoneplus.scan.OnePlusBleScannerAndroid
import app.aaps.core.ui.compose.navigation.NavigationRequest
import app.aaps.plugins.source.activities.DexcomOnePlusStartActivity
import app.aaps.plugins.source.activities.DexcomOnePlusWarmupActivity
import app.aaps.plugins.source.logs.DriverLogFilter
import app.aaps.plugins.source.logs.DriverLogReader
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.withContext

/**
 * True unless the phone's Bluetooth is known to be switched off. A phone without Bluetooth, or one
 * that does not let the app ask, counts as on: then the driver's own message is shown as it is.
 */
internal fun phoneBluetoothOn(context: Context): Boolean =
    try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != false
    } catch (_: SecurityException) {
        true
    }

/**
 * [DexcomOnePlusHost] on Android: the driver registry, the start and warm-up screens, the driver
 * lines of `AndroidAPS.log`, the `connectedDevice` foreground service and the status-bar warm-up
 * notification.
 */
@ContributesBinding(AppScope::class, binding = binding<DexcomOnePlusHost>())
@SingleIn(AppScope::class)
@Inject
class DexcomOnePlusAndroidHost(
    private val context: Context,
    // A function, not the type: the visibility tests build this host around a bare Context, and
    // they never open the log screen.
    private val loggerUtils: () -> LoggerUtils,
) : DexcomOnePlusHost {

    /**
     * Resolved on first use, not in the constructor.
     *
     * Nothing here needs it until the plugin actually runs, and building the host must not depend
     * on a `Context` that can answer `getApplicationContext`. The visibility tests build the host
     * around a bare mock to read `showInList`, and they never reach a screen or a store.
     */
    private val appContext: Context by lazy { context.applicationContext }

    private val platform by lazy { OnePlusAndroidPlatform(appContext) }

    override fun attachPlatform(driver: OnePlusCgmDriver) = driver.attach(platform)

    override fun bluetoothOn(): Boolean = phoneBluetoothOn(appContext)

    override fun slotStore(namespace: String?): OnePlusSensorStore =
        OnePlusSensorStore(appContext, namespace)

    override val stagingNamespace: String = OnePlusCgmDrivers.STAGING_NAMESPACE

    /** Android can run the driver, so there is nothing to say and nothing to lock off. */
    override val notUsableReason: TextRef? = null

    /** Nor anything that works differently enough here to be worth a line in the description. */
    override val platformNote: TextRef? = null

    private val warmupNotification by lazy { DexcomOnePlusWarmupNotification(appContext) }

    override fun productionDriver(): OnePlusCgmDriver = OnePlusCgmDrivers.default()

    override fun stagingDriver(): OnePlusCgmDriver = OnePlusCgmDrivers.staging()
    override fun existingProductionDriver(): OnePlusCgmDriver? = OnePlusCgmDrivers.existingProduction()
    override fun existingStagingDriver(): OnePlusCgmDriver? = OnePlusCgmDrivers.existingStaging()

    override fun attachProductionDriver(watcher: OnePlusGlucoseWatcher): OnePlusCgmDriver =
        OnePlusCgmDrivers.production(watcher)

    override fun promoteStagingInstance(): OnePlusCgmDriver? = OnePlusCgmDrivers.promoteStagingInstance()

    override fun setSessionServiceWanted(wanted: Boolean) {
        if (wanted) DexcomOnePlusSessionService.start(appContext)
        else DexcomOnePlusSessionService.stop(appContext)
    }

    override fun showWarmup(state: OnePlusWarmupState) = warmupNotification.update(state)

    override fun cancelWarmupNotification() = warmupNotification.cancel()

    override fun alert(message: String) = warmupNotification.alert(message)

    /**
     * The same scanner the start screen built for itself before it became shared code: its own
     * instance, with no hint until the screen sets one.
     */
    override fun screenScanner(): OnePlusBleScanner =
        OnePlusBleScannerAndroid(appContext, sessionHint = null)

    /**
     * The Wear OS data layer. `EventMobileToWear` is picked up by the wear plugin, which sends it to
     * the connected watch; with no watch or the wear plugin off, the message simply goes nowhere,
     * and the Status screen says the watch has not answered.
     */
    override fun watchTransport(rxBus: RxBus): DexcomOnePlusWatchTransport =
        DexcomOnePlusWatchTransport { message -> rxBus.send(EventMobileToWear(message)) }

    /** Android can list the installed apps it declares in its manifest `<queries>`. */
    override val installedApps: OnePlusInstalledApps by lazy { OnePlusInstalledAppsAndroid(appContext) }

    /**
     * None on Android. Start, Warm-up and Status are buttons of the plugin's Status tab, and each
     * opens its own Activity through [openScreen].
     */
    override fun screenItems(plugin: DexcomOnePlusPlugin): List<PreferenceItem> = emptyList()

    override val supportsStaging: Boolean = true

    /** Activities on this phone, so [navigate] is not needed. */
    override fun openScreen(screen: DexcomOnePlusHost.Screen, navigate: (NavigationRequest) -> Unit) = when (screen) {
        DexcomOnePlusHost.Screen.START         -> appContext.startActivity(
            DexcomOnePlusStartActivity.intent(appContext, SensorSlot.PRODUCTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        DexcomOnePlusHost.Screen.START_PRESOAK -> appContext.startActivity(
            DexcomOnePlusStartActivity.intent(appContext, SensorSlot.STAGING).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        DexcomOnePlusHost.Screen.WARMUP        -> open(DexcomOnePlusWarmupActivity::class.java)
    }

    /** The ONE+ lines of `AndroidAPS.log`, as the driver log Activity reads them. */
    override suspend fun driverLog(): DexcomOnePlusHost.DriverLog {
        val lines = withContext(aapsIoDispatcher) {
            DriverLogReader.read(loggerUtils().logDirectory, DriverLogFilter.DEXCOM_ONE_PLUS)
        }
        return DexcomOnePlusHost.DriverLog(lines = lines, note = SourceStrings.dexcom_oneplus_log_note_file)
    }

    override val logSharer: ((String) -> Unit) = { text ->
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        appContext.startActivity(
            Intent.createChooser(send, appContext.getString(R.string.cgm_driver_log_share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun open(screen: Class<*>) {
        context.startActivity(
            Intent(context, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
